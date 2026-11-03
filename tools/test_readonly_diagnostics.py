import hashlib
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"windows"))
from file_properties import properties,NativeProperties
from file_jobs import file_token,snapshot
from maintenance_diagnostics import MaintenanceDiagnostics


class Flags:
    def __init__(self,flags=32):self.value=flags;self.closed=0
    def __call__(self,path,expected,write=False):return self
    def attributes(self):return self.value
    def readonly(self,value,expected):
        if expected!=self.value:raise ValueError("changed")
        self.value=self.value|1 if value else self.value&~1
        return self.value
    def close(self):self.closed+=1


class PropertiesTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.folder=Path(self.temp.name);self.file=self.folder/"report.txt";self.file.write_text("preserve")
    def test_readonly_changes_only_that_flag_and_keeps_file_data_and_times(self):
        backend=Flags(34)
        record=properties(dict(action="properties",path=str(self.file)),backend,protected=[])
        before=self.file.stat()
        result=properties(dict(action="readonly",path=str(self.file),file_id=record["file_id"],expected_attributes=34,readonly=True),backend,protected=[])
        self.assertTrue(result["readonly"]);self.assertTrue(result["hidden"]);self.assertFalse(result["system"])
        self.assertEqual(35,result["attributes"]);self.assertEqual(before.st_mtime_ns,self.file.stat().st_mtime_ns)
        self.assertEqual("preserve",self.file.read_text());self.assertEqual(2,backend.closed)
    def test_hidden_system_and_raw_masks_are_rejected(self):
        for key in ("hidden","system","attributes","mask"):
            with self.subTest(key=key),self.assertRaises(ValueError):
                properties(dict(action="readonly",path=str(self.file),**{key:True}),Flags(),protected=[])
    def test_system_flags_and_protected_roots_are_view_only(self):
        for roots,flags in (([self.folder],32),([],4)):
            backend=Flags(flags)
            record=properties(dict(action="properties",path=str(self.file)),backend,protected=roots)
            self.assertFalse(record["editable"])
            with self.assertRaises(ValueError):
                properties(dict(action="readonly",path=str(self.file),file_id=record["file_id"],readonly=True,expected_attributes=flags),backend,protected=roots)
            self.assertEqual(flags,backend.value)
    def test_stale_file_or_attribute_revision_is_not_applied(self):
        backend=Flags();record=properties(dict(action="properties",path=str(self.file)),backend,protected=[])
        query=dict(action="readonly",path=str(self.file),file_id=record["file_id"],readonly=True,expected_attributes=0)
        with self.assertRaises(ValueError):properties(query,backend,protected=[])
        self.file.write_text("different contents")
        query["expected_attributes"]=32
        with self.assertRaises(ValueError):properties(query,backend,protected=[])
        self.assertEqual(32,backend.value)
    def test_hardlinks_and_missing_explicit_readonly_choice_are_rejected(self):
        other=self.folder/"other.txt";os.link(self.file,other)
        with self.assertRaises(ValueError):properties(dict(action="properties",path=str(other)),Flags(),protected=[])
        other.unlink()
        for value in (1,"true",None):
            with self.subTest(value=value),self.assertRaises(ValueError):
                properties(dict(action="readonly",path=str(self.file),file_id=file_token(snapshot(self.file)),readonly=value,expected_attributes=32),Flags(),protected=[])
    def test_folder_readonly_is_not_reported_as_access_denial(self):
        record=properties(dict(action="properties",path=str(self.folder)),Flags(16),protected=[])
        self.assertTrue(record["dir"]);self.assertIn("不限制子项读写",record["notice"])


class DiagnosticTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.records=self.root/"dates";self.journals=self.root/"journals"
        self.records.mkdir();self.journals.mkdir()
        self.manager=MaintenanceDiagnostics(self.root,self.records,self.journals,123)
    def test_normal_summaries_leave_every_record_unchanged(self):
        log=self.journals/"remote-one.jsonl";log.write_text('{"event":"renamed"}\n')
        (self.journals/"plan-one.json").write_text(json.dumps(dict(operation="rename",journal=str(log))))
        (self.records/"one.json").write_text(json.dumps(dict(target_text="test",entries=[dict(status="applied"),dict(status="failed")])))
        before={str(p):p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        result=self.manager.scan()
        self.assertEqual([],result["pending"]);self.assertEqual(2,result["items"][0]["files"])
        self.assertEqual(1,result["items"][0]["failed"]);self.assertFalse(result["partial"])
        self.assertEqual(before,{str(p):p.read_bytes() for p in self.root.rglob("*") if p.is_file()})
    def test_large_logs_are_tailed_and_oversize_json_is_skipped(self):
        self.manager.MAX_FILE=1024
        log=self.journals/"remote-large.jsonl";log.write_text('{"event":"progress"}\n'*1000+'{"event":"renamed"}\n')
        (self.journals/"plan-large.json").write_text(json.dumps(dict(operation="rename",journal=str(log))))
        huge=self.records/"huge.json";huge.write_bytes(b"x"*2048)
        result=self.manager.scan()
        self.assertEqual([],result["pending"]);self.assertEqual([],result["items"])
        self.assertTrue(result["partial"]);self.assertTrue(result["warnings"])
        self.assertEqual(2048,huge.stat().st_size)
    def test_journal_paths_cannot_leave_the_diagnostic_directory(self):
        outside=self.root/"outside.jsonl";outside.write_text('{"event":"renamed"}\n')
        (self.journals/"plan-outside.json").write_text(json.dumps(dict(journal=str(outside))))
        result=self.manager.scan();self.assertTrue(result["partial"]);self.assertEqual([],result["pending"])
    def test_hung_scan_does_not_block_status_or_start_more_workers(self):
        started=threading.Event();release=threading.Event()
        def slow():
            started.set();release.wait(5)
            return dict(pending=[],items=[],warnings=[],partial=False)
        with mock.patch.object(self.manager,"scan",side_effect=slow) as scan:
            try:
                first=self.manager.request({},ValueError);self.assertTrue(first["loading"])
                self.assertTrue(started.wait(1))
                before=time.monotonic()
                for _ in range(10):self.assertTrue(self.manager.request(dict(refresh=True),ValueError)["loading"])
                self.assertLess(time.monotonic()-before,.25);self.assertEqual(1,scan.call_count)
            finally:release.set()
    def test_read_only_diagnostics_never_accept_operations(self):
        for action in ("delete","rename","apply","restore"):
            with self.subTest(action=action),self.assertRaises(ValueError):
                self.manager.request(dict(action=action),ValueError)
    def test_scan_entry_limit_reports_partial_instead_of_reading_everything(self):
        self.manager.MAX_SCAN=3
        for index in range(8):(self.records/(str(index)+".json")).write_text('{"entries":[]}')
        result=self.manager.scan();self.assertLessEqual(len(result["items"]),3);self.assertTrue(result["partial"])


@unittest.skipUnless(os.name=="nt","Windows file attributes")
class NativePropertiesTest(unittest.TestCase):
    def test_real_file_and_directory_readonly_roundtrip_preserves_other_flags(self):
        with tempfile.TemporaryDirectory() as folder:
            base=Path(folder);file=base/"document.txt";file.write_bytes(b"normal document")
            directory=base/"documents";directory.mkdir()
            for path in (file,directory):
                with self.subTest(path=path):
                    before=properties(dict(action="properties",path=str(path)),protected=[])
                    modified=path.stat().st_mtime_ns
                    try:
                        after=properties(dict(action="readonly",path=str(path),file_id=before["file_id"],expected_attributes=before["attributes"],readonly=True),protected=[])
                        self.assertTrue(after["readonly"])
                        self.assertEqual(before["attributes"]&~(1|128),after["attributes"]&~(1|128))
                        self.assertEqual(modified,path.stat().st_mtime_ns)
                    finally:
                        current=properties(dict(action="properties",path=str(path)),protected=[])
                        restored=properties(dict(action="readonly",path=str(path),file_id=current["file_id"],expected_attributes=current["attributes"],readonly=before["readonly"]),protected=[])
                    self.assertEqual(before["readonly"],restored["readonly"])
            self.assertEqual(b"normal document",file.read_bytes())


if __name__=="__main__":unittest.main()
