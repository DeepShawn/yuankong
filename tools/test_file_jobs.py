"""Small, isolated file tasks: no overwrite, identity checks, cleanup and recovery."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest import mock

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"windows"))
from file_jobs import FileJobs,file_token,snapshot,publish_new
import lan_command_agent as agent


class Error(Exception):pass


class FileJobsTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve();self.source=self.root/"source";self.dest=self.root/"destination"
        self.source.mkdir();self.dest.mkdir();self.owner=SimpleNamespace(client_id="phone")
        self.manager=FileJobs(self.root/"file-jobs.json",allow_non_windows=True)
        self.addCleanup(self.manager.close)
    def tearDown(self):
        self.manager.close()
        for job in self.manager.jobs.values():self.assertTrue(job["done"].wait(10))
    def spec(self,path):return dict(path=str(path),file_id=file_token(path.lstat()))
    def call(self,**q):return self.manager.request(q,self.owner,Error)
    def run_job(self,operation,paths):
        job=self.call(action="create",operation=operation,items=[self.spec(p) for p in paths],destination=str(self.dest))["job"]
        self.assertTrue(self.manager.jobs[job["id"]]["done"].wait(10))
        return self.call()["jobs"][0]
    def test_copy_files_directories_and_empty_files_then_delete_only_selected(self):
        a=self.source/"中文 & a.txt";a.write_bytes(b"original")
        folder=self.source/"folder";folder.mkdir();(folder/"empty").touch();(folder/"data").write_bytes(b"contents")
        copied=self.run_job("copy",[a,folder])
        self.assertEqual(["completed","completed"],[i["status"] for i in copied["items"]],copied)
        self.assertEqual(b"original",(self.dest/a.name).read_bytes());self.assertEqual(b"contents",(self.dest/folder.name/"data").read_bytes())
        self.assertTrue(a.exists());self.assertFalse(list(self.dest.glob(".lancmd-filejob-*")))
        removed=self.run_job("delete",[folder]);self.assertEqual("completed",removed["items"][0]["status"])
        self.assertFalse(folder.exists());self.assertTrue(a.exists())
    def test_same_volume_move_does_not_copy_or_overwrite(self):
        path=self.source/"move.txt";path.write_text("move")
        with mock.patch.object(self.manager,"_copy",side_effect=AssertionError("same-volume move should rename")):
            result=self.run_job("move",[path])
        self.assertEqual("completed",result["items"][0]["status"],result)
        self.assertFalse(path.exists());self.assertEqual("move",(self.dest/path.name).read_text())
    def test_existing_target_does_not_prevent_other_selected_files_from_copying(self):
        a=self.source/"a";b=self.source/"b";a.write_text("a");b.write_text("b");(self.dest/"a").write_text("KEEP")
        result=self.run_job("copy",[a,b])
        self.assertEqual(["failed","completed"],[i["status"] for i in result["items"]])
        self.assertEqual("KEEP",(self.dest/"a").read_text());self.assertEqual("b",(self.dest/"b").read_text())
    def test_publish_rejects_existing_file_and_empty_directory_atomically(self):
        for folder in (False,True):
            src=self.root/("src"+str(folder));dst=self.root/("dst"+str(folder))
            if folder:src.mkdir();dst.mkdir()
            else:src.write_text("new");dst.write_text("old")
            with self.assertRaises(OSError):publish_new(src,dst)
            self.assertTrue(src.exists());self.assertTrue(dst.exists())
            if not folder:self.assertEqual("old",dst.read_text())
    def test_racing_destination_creation_is_not_overwritten_and_staging_is_cleaned(self):
        path=self.source/"a";path.write_text("new")
        real=publish_new
        def race(source,target):target.write_text("race winner");real(source,target)
        with mock.patch("file_jobs.publish_new",side_effect=race):result=self.run_job("copy",[path])
        self.assertEqual("failed",result["items"][0]["status"])
        self.assertEqual("race winner",(self.dest/"a").read_text());self.assertEqual([],list(self.dest.glob(".lancmd-filejob-*")))
    def test_stale_selection_does_not_delete_replaced_contents(self):
        path=self.source/"stale";path.write_text("old");spec=self.spec(path)
        with mock.patch("file_jobs.threading.Thread.start"):
            job=self.call(action="create",operation="delete",items=[spec])["job"]
        path.write_text("external update")
        self.manager._run(self.manager.jobs[job["id"]])
        self.assertEqual("failed",self.call()["jobs"][0]["items"][0]["status"])
        self.assertEqual("external update",path.read_text())
    def test_changed_source_tree_never_publishes_partial_snapshot(self):
        path=self.source/"folder";path.mkdir();(path/"a").write_text("a")
        scan=self.manager._scan;count=[0]
        def changed(root,job):
            count[0]+=1
            if count[0]==2:(root/"new").write_text("new external file")
            return scan(root,job)
        with mock.patch.object(self.manager,"_scan",side_effect=changed):result=self.run_job("copy",[path])
        self.assertEqual("failed",result["items"][0]["status"]);self.assertFalse((self.dest/"folder").exists())
        self.assertTrue((path/"new").exists());self.assertEqual([],list(self.dest.glob(".lancmd-filejob-*")))
    def test_cross_volume_move_keeps_copy_if_source_cleanup_fails(self):
        path=self.source/"a";path.write_text("data");real=snapshot
        def other_volume(value):
            result=real(value)
            if value==self.dest:return SimpleNamespace(st_mode=result.st_mode,st_dev=result.st_dev+1)
            return result
        with mock.patch("file_jobs.snapshot",side_effect=other_volume),mock.patch.object(self.manager,"_remove_plan",side_effect=PermissionError("source busy")):
            result=self.run_job("move",[path])
        item=result["items"][0]
        self.assertEqual("failed",item["status"]);self.assertTrue(item["copied"])
        self.assertIn("源未完全移除",item["error"]);self.assertEqual("data",(self.dest/"a").read_text());self.assertTrue(path.exists())
    def test_cancel_is_owner_scoped_and_cleans_uncommitted_payload(self):
        path=self.source/"a";path.write_bytes(b"x"*1024)
        entered=threading.Event();release=threading.Event();real=self.manager._copy
        def hold(*args):entered.set();release.wait(5);return real(*args)
        with mock.patch.object(self.manager,"_copy",side_effect=hold):
            job=self.call(action="create",operation="copy",items=[self.spec(path)],destination=str(self.dest))["job"]
            self.assertTrue(entered.wait(3));other=SimpleNamespace(client_id="other")
            self.assertEqual([],self.manager.request({},other,Error)["jobs"])
            with self.assertRaises(Error):self.manager.request(dict(action="cancel",job_id=job["id"]),other,Error)
            self.call(id=100,action="cancel",job_id=job["id"]);release.set()
            self.assertTrue(self.manager.jobs[job["id"]]["done"].wait(5))
        self.assertFalse((self.dest/"a").exists());self.assertTrue(path.exists());self.assertEqual([],list(self.dest.glob(".lancmd-filejob-*")))
    def test_recovery_removes_only_owned_staging_directories(self):
        stage=self.dest/".lancmd-filejob-recover";stage.mkdir();(stage/"partial").write_text("partial")
        info=stage.stat();self.manager.stages[str(stage)]=[info.st_dev,info.st_ino];self.manager._save_stages()
        restarted=FileJobs(self.manager.journal,allow_non_windows=True)
        restarted.request({},self.owner,Error);self.assertFalse(stage.exists());restarted.close()
        stage.mkdir();(stage/"keep").write_text("not ours")
        self.manager.stages={str(stage):[0,0]};self.manager._save_stages()
        restarted=FileJobs(self.manager.journal,allow_non_windows=True)
        response=restarted.request({},self.owner,Error);self.assertTrue(response["warning"]);self.assertTrue((stage/"keep").exists());restarted.close()
    def test_space_shortage_preserves_sources_and_does_not_publish_target(self):
        path=self.source/"a";path.write_text("a")
        with mock.patch("file_jobs.shutil.disk_usage",return_value=SimpleNamespace(free=999_999_999)):result=self.run_job("copy",[path])
        self.assertEqual("failed",result["items"][0]["status"]);self.assertTrue(path.exists());self.assertFalse((self.dest/"a").exists())
    def test_source_inside_receiver_and_dest_inside_source_are_rejected(self):
        path=self.source/"folder";path.mkdir();(path/"a").write_text("a")
        self.manager.protected=[path/"a"]
        result=self.run_job("delete",[path]);self.assertEqual("failed",result["items"][0]["status"])
        self.manager.protected=[]
        job=self.call(action="create",operation="copy",items=[self.spec(path)],destination=str(path))["job"]
        self.assertTrue(self.manager.jobs[job["id"]]["done"].wait(5));self.assertEqual("failed",self.call()["jobs"][0]["items"][0]["status"])
    def test_linked_content_is_not_followed(self):
        if os.name=="nt":
            folder=self.source/"alias"
            result=subprocess.run(["cmd","/c","mklink","/J",str(folder),str(self.dest)],capture_output=True)
            self.assertEqual(0,result.returncode,result.stderr)
            self.addCleanup(folder.rmdir)
        else:
            folder=self.source/"alias";folder.symlink_to(self.dest,target_is_directory=True)
        result=self.run_job("copy",[folder]);self.assertEqual("failed",result["items"][0]["status"])
    def test_protocol_bounds_and_file_versions_are_required(self):
        path=self.source/"a";path.write_text("a")
        for items in ([],[self.spec(path)]*101,[dict(path=str(path))],[self.spec(path),self.spec(path)]):
            with self.subTest(items=len(items)),self.assertRaises(Error):self.call(action="create",operation="copy",items=items,destination=str(self.dest))
    def test_download_begin_and_chunks_validate_selected_file_version(self):
        path=self.source/"a";path.write_text("original")
        state=SimpleNamespace(downloads={},uploads={})
        token=file_token(path.stat());path.write_text("new content")
        with self.assertRaises(agent.RpcError):agent.cmd_download(dict(path=str(path),file_id=token),state)
        self.assertEqual({},state.downloads)
        opened=agent.cmd_download(dict(path=str(path),file_id=file_token(path.stat())),state)
        path.write_text("changed during download")
        with self.assertRaises(agent.RpcError):agent.cmd_download(dict(action="chunk",sid=opened["sid"]),state)
        self.assertEqual({},state.downloads)


if __name__=="__main__":unittest.main()
