import os
from pathlib import Path
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest import mock

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"windows"))
from file_search import FileSearch
from file_jobs import file_token


class Error(Exception):pass


class FileSearchTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve();self.source=self.root/"source";self.source.mkdir()
        self.manager=FileSearch(self.root/"index",allow_non_windows=True);self.owner=SimpleNamespace(client_id="phone")
        self.addCleanup(self.manager.close)
        folder=self.source/"Reports";folder.mkdir();(folder/"季度报告.xlsx").write_bytes(b"never read this content")
        (self.source/"report.docx").write_bytes(b"office");(self.source/"report-final.docx").write_bytes(b"document")
        (self.source/"literal_100%.txt").write_bytes(b"text")
    def tearDown(self):
        self.manager.close()
        if self.manager.job:self.assertTrue(self.manager.job["done"].wait(5))
    def call(self,**q):return self.manager.request(q,self.owner,Error)
    def build(self,roots=None):
        self.call(action="build",roots=roots or [str(self.source)])
        self.assertTrue(self.manager.job["done"].wait(5))
        self.assertEqual("completed",self.manager.job["data"]["status"],self.manager.job["data"])
    def test_index_searches_names_paths_wildcards_unicode_and_versions_without_reading_files(self):
        original=Path.open
        def no_content(path,*args,**kwargs):
            if self.source in path.parents:raise AssertionError("must not read contents")
            return original(path,*args,**kwargs)
        with mock.patch.object(Path,"open",no_content):self.build()
        self.assertEqual(2,self.call(action="query",query="REPORT*.docx")["total"])
        self.assertEqual(1,self.call(action="query",query="季度报告")["total"])
        self.assertEqual(1,self.call(action="query",query="100%")["total"])
        found=self.call(action="query",query="report final")["items"][0]
        self.assertEqual("report-final.docx",found["name"]);self.assertEqual(file_token(Path(found["path"]).lstat()),found["file_id"])
        self.assertEqual(1,self.call(action="query",query="Reports",scope="path",kind="files")["total"])
    def test_pagination_sorting_and_folder_filter_are_stable(self):
        self.build();first=self.call(action="query",query="report",limit=1);second=self.call(action="query",query="report",limit=1,offset=1)
        self.assertNotEqual(first["items"][0]["path"],second["items"][0]["path"])
        self.assertEqual(1,self.call(action="query",query="",kind="folders")["total"])
        self.assertEqual("季度报告.xlsx",self.call(action="query",query="",kind="files",sort="size")["items"][0]["name"])
    def test_old_index_remains_queryable_during_rebuild_and_cancel_is_owner_scoped(self):
        self.build();old_count=self.call(action="query")["total"]
        entered=threading.Event();release=threading.Event();enumerate_real=self.manager._enumerate
        def hold(roots,job):
            entered.set();release.wait(3);yield from enumerate_real(roots,job)
        with mock.patch.object(self.manager,"_enumerate",side_effect=hold):
            job=self.call(action="build",roots=[str(self.source)])["job"];self.assertTrue(entered.wait(1))
            self.assertEqual(old_count,self.call(action="query")["total"])
            with self.assertRaises(Error):self.manager.request(dict(action="cancel",job_id=job["id"]),SimpleNamespace(client_id="other"),Error)
            self.call(action="cancel",job_id=job["id"]);release.set();self.assertTrue(self.manager.job["done"].wait(5))
        self.assertEqual("cancelled",self.manager.job["data"]["status"])
        self.assertEqual(old_count,self.call(action="query")["total"]);self.assertFalse(list(self.manager.directory.glob("building-*")))
    def test_persistent_index_reopens_without_rescanning_and_refresh_replaces_snapshot(self):
        self.build();(self.source/"new-file.txt").write_bytes(b"new")
        reopened=FileSearch(self.manager.directory,allow_non_windows=True);self.addCleanup(reopened.close)
        self.assertEqual(0,reopened.request(dict(action="query",query="new-file"),self.owner,Error)["total"])
        self.build();self.assertEqual(1,reopened.request(dict(action="query",query="new-file"),self.owner,Error)["total"])
    def test_invalid_conditions_and_root_links_are_rejected(self):
        for q in [dict(action="build",roots=[]),dict(action="query",limit=101),dict(action="query",offset=-1),dict(action="query",sort="path;drop table files"),dict(action="query",query="x"*257)]:
            with self.assertRaises(Error):self.call(**q)
        self.assertEqual(0,self.call(action="query",query="any")["total"])
    def test_failed_rebuild_keeps_previous_index(self):
        self.build()
        with mock.patch("file_search.shutil.disk_usage",return_value=SimpleNamespace(free=0)):
            self.call(action="build",roots=[str(self.source)]);self.assertTrue(self.manager.job["done"].wait(5))
        self.assertEqual("failed",self.manager.job["data"]["status"]);self.assertEqual(5,self.call(action="query")["total"])
    def test_overlapping_roots_do_not_duplicate_results(self):
        self.build([str(self.source),str(self.source/"Reports")]);self.assertEqual(5,self.call(action="query")["total"])
    def test_symlinks_and_junctions_are_not_traversed(self):
        alias=self.source/"alias"
        if os.name=="nt":
            import subprocess
            result=subprocess.run(["cmd","/c","mklink","/J",str(alias),str(self.source/"Reports")],capture_output=True)
            self.assertEqual(0,result.returncode);self.addCleanup(alias.rmdir)
        else:alias.symlink_to(self.source/"Reports",target_is_directory=True)
        self.build();self.assertEqual(5,self.call(action="query")["total"])
        self.assertGreaterEqual(self.call()["index"]["skipped"],1)


if __name__=="__main__":unittest.main()
