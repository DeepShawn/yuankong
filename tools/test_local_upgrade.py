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
    def test_helper_installs_and_rolls_back_only_its_isolated_test_child(self):
        from local_upgrade import write_json
        for fails in (False,True):
            with self.subTest(startup_failure=fails),tempfile.TemporaryDirectory() as folder:
                base=Path(folder);root=base/"install";root.mkdir();work=base/"work";work.mkdir();stage=work/"stage";stage.mkdir()
                runtime=root/"python"
                junction=subprocess.run(["cmd","/c","mklink","/J",str(runtime),sys.base_prefix],capture_output=True)
                self.assertEqual(0,junction.returncode,junction.stderr)
                # Both old and new dummy receivers have a short, finite lifetime.
                source="import json,os,pathlib,time\np=pathlib.Path(os.environ['LANCMD_UPGRADE_READY']);t=p.with_suffix('.tmp');t.write_text(json.dumps(dict(version='3.4.0',pid=os.getpid())));os.replace(t,p)\ntime.sleep(2)\n"
                old=b"import time;time.sleep(2)\n";(root/"lan_command_agent.py").write_bytes(old);(root/"Start.exe").write_bytes(b"old")
                (stage/"lan_command_agent.py").write_text("raise RuntimeError('simulated startup failure')\n" if fails else source)
                (stage/"Start.exe").write_bytes(b"new");write_json(stage/MANIFEST,{})
                parent=subprocess.Popen([sys.executable,"-c","import time;time.sleep(3)"],creationflags=0x08000000)
                plan=dict(root=str(root),work=str(work),parent=parent.pid,state=str(base/"status.json"),version="3.4.0",files=["lan_command_agent.py","Start.exe"])
                write_json(work/"plan.json",plan);helper=None
                try:
                    helper=subprocess.Popen([sys.executable,str(Path(__file__).resolve().parents[1]/"windows/local_upgrade.py"),"--apply",str(work/"plan.json")],creationflags=0x08000000)
                    self.assertEqual(0,helper.wait(15));parent.wait(3)
                    report=json.loads((base/"status.json").read_text(encoding="utf-8"))
                    self.assertEqual("rolled_back" if fails else "completed",report["status"],report)
                    import ctypes
                    from ctypes import wintypes as w
                    kernel=ctypes.WinDLL("kernel32",use_last_error=True)
                    kernel.OpenProcess.argtypes=[w.DWORD,w.BOOL,w.DWORD];kernel.OpenProcess.restype=w.HANDLE
                    kernel.WaitForSingleObject.argtypes=[w.HANDLE,w.DWORD];kernel.CloseHandle.argtypes=[w.HANDLE]
                    child=kernel.OpenProcess(0x100000,False,report["pid"])
                    if child:
                        try:self.assertEqual(0,kernel.WaitForSingleObject(child,5000))
                        finally:kernel.CloseHandle(child)
                    self.assertEqual(b"old" if fails else b"new",(root/"Start.exe").read_bytes())
                    if fails:self.assertEqual(old,(root/"lan_command_agent.py").read_bytes())
                    else:self.assertEqual(b"old",(work/"backup/Start.exe").read_bytes())
                finally:
                    if parent.poll() is None:parent.terminate();parent.wait(3)
                    if helper is not None and helper.poll() is None:helper.terminate();helper.wait(3)
                    runtime.rmdir()


if __name__=="__main__":unittest.main()
