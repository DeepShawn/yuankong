"""Package validation and reversible installation using isolated synthetic releases."""
import json
from pathlib import Path
import os
import sys
import tempfile
import subprocess
import unittest
import zipfile
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"windows"))
from local_upgrade import REQUIRED,MANIFEST,LocalUpgrade,build_manifest,unpack,install,relative,digest
from wallpaper import Wallpaper


class UpgradeTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.package=self.root/"package";self.package.mkdir()
        for name in REQUIRED:
            path=self.package/name;path.parent.mkdir(exist_ok=True);path.write_bytes(b"new content: "+name.encode())
        self.manifest=build_manifest(self.package,"3.4.0")
        self.zip=self.root/"release.zip"
        self.archive()
    def archive(self):
        with zipfile.ZipFile(self.zip,"w") as archive:
            for path in self.package.rglob("*"):
                if path.is_file():archive.write(path,"LanCmd-Windows/"+path.relative_to(self.package).as_posix())
    def test_complete_release_is_verified_before_staging(self):
        result=unpack(self.zip,self.root/"stage","3.3.0")
        self.assertEqual("3.4.0",result["version"])
        for name,record in result["files"].items():self.assertEqual(record["sha256"],digest(self.root/"stage"/name))
    def test_unchanged_or_older_versions_are_rejected(self):
        for release in ("3.4.0","3.5.0"):
            with self.assertRaisesRegex(ValueError,"版本"):unpack(self.zip,self.root/"stage",release)
        self.assertFalse((self.root/"stage").exists())
    def test_changed_file_does_not_leave_staged_partial_release(self):
        (self.package/"lan_command_agent.py").write_bytes(b"tampered");self.archive()
        with self.assertRaises(ValueError):unpack(self.zip,self.root/"stage","3.3.0")
        self.assertFalse((self.root/"stage").exists())
    def test_extra_and_case_colliding_entries_are_rejected(self):
        for bad in ("../outside","python/../../outside","CON.txt","python\\evil.py","python/pythonw.exe:stream","START.EXE","unlisted.py"):
            self.archive()
            with zipfile.ZipFile(self.zip,"a") as archive:archive.writestr("LanCmd-Windows/"+bad,b"evil")
            with self.subTest(bad=bad),self.assertRaises(ValueError):unpack(self.zip,self.root/"stage","3.3.0")
        self.assertFalse((self.root/"outside").exists())
    def test_symlink_archive_entries_are_rejected(self):
        entry=zipfile.ZipInfo("LanCmd-Windows/link");entry.create_system=3;entry.external_attr=0o120777<<16
        with zipfile.ZipFile(self.zip,"a") as archive:archive.writestr(entry,"outside")
        with self.assertRaisesRegex(ValueError,"链接"):unpack(self.zip,self.root/"stage","3.3.0")
    def test_replacement_failure_restores_all_old_files_and_preserves_user_data(self):
        root=self.root/"install";root.mkdir();(root/"Start.exe").write_bytes(b"old exe")
        (root/"notes.txt").write_bytes(b"my file")
        stage=self.root/"stage";unpack(self.zip,stage,"3.3.0")
        calls=[]
        def fail(source,target):
            calls.append((source,target))
            if len(calls)==4:raise OSError("simulated sharing violation")
            return os.replace(source,target)
        with self.assertRaisesRegex(OSError,"simulated"):install(root,stage,self.root/"backup",list(self.manifest["files"]),fail)
        self.assertEqual(b"old exe",(root/"Start.exe").read_bytes());self.assertEqual(b"my file",(root/"notes.txt").read_bytes())
        self.assertEqual({"Start.exe","notes.txt"},{p.name for p in root.iterdir() if p.is_file()})
        self.assertTrue((self.root/"backup/journal.json").exists())
    def test_success_keeps_backup_and_does_not_delete_user_files_or_upgrade_zip(self):
        root=self.root/"install";root.mkdir();(root/"Start.exe").write_bytes(b"old exe")
        (root/"upgrade").mkdir();(root/"upgrade/release.zip").write_bytes(b"source")
        stage=self.root/"stage";unpack(self.zip,stage,"3.3.0")
        entries=install(root,stage,self.root/"backup",list(self.manifest["files"]))
        self.assertTrue(entries);self.assertEqual(b"old exe",(self.root/"backup/Start.exe").read_bytes())
        self.assertEqual(b"source",(root/"upgrade/release.zip").read_bytes())
        self.assertEqual("3.4.0",json.loads((root/MANIFEST).read_text())["version"])
    def test_drain_blocks_new_commands_and_always_releases_active_count(self):
        update=LocalUpgrade(self.root,self.root/"state","3.3.0")
        with update.operation("upgrade"):
            self.assertEqual(1,update.active);update.draining=True
            with self.assertRaisesRegex(ValueError,"升级"):
                with update.operation("put"):pass
            with update.operation("capabilities"):self.assertEqual(2,update.active)
        self.assertEqual(0,update.active)


class WallpaperTest(unittest.TestCase):
    def test_image_is_copied_and_failed_apply_restores_exact_previous_state(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);image=root/"photo.png";image.write_bytes(b"\x89PNG\r\n\x1a\n"+b"payload")
            previous={"WallPaper":("old.bmp",1),"WallpaperStyle":None,"TileWallpaper":("0",1)}
            class Backend:
                def snapshot(self):return previous
                def apply(self,path,style):
                    self.copied=Path(path);self.style=style
                    self.payload=Path(path).read_bytes()
                    raise OSError("driver refused wallpaper")
                def restore(self,data):self.restored=data
            backend=Backend();wallpaper=Wallpaper(root/"saved",backend)
            with self.assertRaisesRegex(OSError,"driver refused"):wallpaper.request(dict(action="set",path=str(image),style="fit"),OSError)
            self.assertEqual(previous,backend.restored);self.assertEqual(image.read_bytes(),backend.payload)
            self.assertFalse(backend.copied.exists());self.assertTrue(image.exists())
    def test_non_image_never_reaches_windows_setting(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);image=root/"fake.png";image.write_text("not a picture")
            class Backend:
                def apply(self,*args):raise AssertionError("must not change desktop")
            with self.assertRaises(ValueError):Wallpaper(root/"saved",Backend()).request(dict(action="set",path=str(image)),ValueError)


@unittest.skipUnless(os.name=="nt","Windows process and replacement APIs")
class NativeUpgradeTest(unittest.TestCase):
    def test_batch_restart_cross_volume_status_and_rollback_only_touch_our_own_children(self):
        from local_upgrade import write_json
        import ctypes
        from ctypes import wintypes as w
        repository=Path(__file__).resolve().parents[1]
        module=repository/"windows/local_upgrade.py"
        batch=(repository/"windows/start.bat").read_bytes()
        for fails in (False,True):
            with self.subTest(startup_failure=fails),tempfile.TemporaryDirectory(dir=repository) as folder,tempfile.TemporaryDirectory() as status_dir:
                base=Path(folder);root=base/"LanCmd 中文 & ! %folder%";root.mkdir()
                work=root/".upgrade/run-test";work.mkdir(parents=True)
                runtime=root/"python"
                junction=subprocess.run(["cmd","/c","mklink","/J",str(runtime),sys.base_prefix],capture_output=True)
                self.assertEqual(0,junction.returncode,junction.stderr)
                def source(release,nonce):
                    return ("import json,os,pathlib,time\n"+
                        "data=dict(version="+repr(release)+",pid=os.getpid())\n"+
                        ("data['nonce']=os.environ['LANCMD_UPGRADE_NONCE']\n" if nonce else "")+
                        "p=pathlib.Path(os.environ['LANCMD_UPGRADE_READY']);t=p.with_suffix('.tmp');t.write_text(json.dumps(data));os.replace(t,p)\ntime.sleep(6)\n")
                old=source("3.4.1",False).encode()
                (root/"lan_command_agent.py").write_bytes(old);(root/"Start.exe").write_bytes(b"old")
                (root/"local_upgrade.py").write_bytes(module.read_bytes());(root/"start.bat").write_bytes(b"@echo off\r\nexit /b 0\r\n")
                (root/"my-notes.txt").write_text("preserve user data")
                payload=base/"payload";payload.mkdir()
                (payload/"lan_command_agent.py").write_text("raise RuntimeError('simulated startup failure')\n" if fails else source("3.4.2",True))
                (payload/"Start.exe").write_bytes(b"new");(payload/"local_upgrade.py").write_bytes(module.read_bytes());(payload/"start.bat").write_bytes(batch)
                # The real embedded runtime is unchanged in this isolated test;
                # only these four payload files participate in replacement.
                names={"lan_command_agent.py","local_upgrade.py","start.bat","Start.exe"}
                from unittest import mock
                with mock.patch("local_upgrade.REQUIRED",names):manifest=build_manifest(payload,"3.4.2")
                with zipfile.ZipFile(work/"package.zip","w") as archive:
                    for path in payload.iterdir():archive.write(path,"LanCmd-Windows/"+path.name)
                parent=subprocess.Popen([sys.executable,"-c","import time;time.sleep(3)"],creationflags=0x08000000)
                plan=dict(root=str(root),work=str(work),parent=parent.pid,state=str(Path(status_dir)/"status.json"),version="3.4.2",previous="3.4.1",request_id="a"*32,files=list(manifest["files"]))
                write_json(work/"plan.json",plan);helper=None
                bootstrap="import json,sys;sys.path.insert(0,sys.argv[1]);import local_upgrade as u;u.REQUIRED=set(json.loads(sys.argv[2]));u.helper(json.load(open(sys.argv[3])))"
                try:
                    helper=subprocess.Popen([sys.executable,"-c",bootstrap,str(module.parent),json.dumps(sorted(names)),str(work/"plan.json")],creationflags=0x08000000)
                    import time
                    deadline=time.monotonic()+2
                    while not (work/"ready").exists() and time.monotonic()<deadline:time.sleep(.02)
                    self.assertTrue((work/"ready").exists());self.assertIsNone(parent.poll())
                    self.assertFalse((work/"stage").exists(),"不得在旧接收端退出前解压")
                    self.assertEqual(b"old",(root/"Start.exe").read_bytes())
                    self.assertEqual(0,helper.wait(35));parent.wait(3)
                    report=json.loads((Path(status_dir)/"status.json").read_text(encoding="utf-8"))
                    self.assertEqual("rolled_back" if fails else "completed",report["status"],report)
                    self.assertEqual("a"*32,report["request_id"])
                    kernel=ctypes.WinDLL("kernel32",use_last_error=True)
                    kernel.OpenProcess.argtypes=[w.DWORD,w.BOOL,w.DWORD];kernel.OpenProcess.restype=w.HANDLE
                    kernel.WaitForSingleObject.argtypes=[w.HANDLE,w.DWORD];kernel.CloseHandle.argtypes=[w.HANDLE]
                    child=kernel.OpenProcess(0x100000,False,report["pid"])
                    if child:
                        try:self.assertEqual(0,kernel.WaitForSingleObject(child,10000))
                        finally:kernel.CloseHandle(child)
                    self.assertEqual(b"old" if fails else b"new",(root/"Start.exe").read_bytes())
                    self.assertEqual("preserve user data",(root/"my-notes.txt").read_text())
                    if fails:self.assertEqual(old,(root/"lan_command_agent.py").read_bytes())
                    else:
                        self.assertEqual(b"old",(work/"backup/Start.exe").read_bytes())
                        self.assertTrue(report["cmd_closed"]);self.assertEqual("start.bat",report["start_via"])
                finally:
                    if parent.poll() is None:parent.terminate();parent.wait(3)
                    if helper is not None and helper.poll() is None:helper.terminate();helper.wait(3)
                    runtime.rmdir()


if __name__=="__main__":unittest.main()
