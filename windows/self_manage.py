"""Phone-triggered maintenance of the receiver's own installation.

Two operations, both executed by a detached helper because Windows keeps the
running interpreter (and every module it has loaded) locked inside the install
folder:

* ``rename``  — stop the receiver, rename the folder in place, repoint the
  current user's ``Run`` value and start the receiver from the new path.
* ``delete``  — stop the receiver, remove the install folder and the ``Run``
  value, and optionally the ``%APPDATA%\\LanCmd`` configuration folder.

Nothing is decided here about *what* to remove: the plan is written to a
journal first, the helper re-validates the folder, and every step appends a
line to that journal so a half-finished run can be read afterwards.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import time
import uuid

RUN_VALUE = "LanCmdAgent"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
JOURNAL_DIR_NAME = "LanCmdMaintenance"
CONFIRM = {"rename": "RENAME", "delete": "DELETE"}
MAX_WAIT = 120


def local_app_data():
    return os.environ.get("LOCALAPPDATA") or os.environ.get("XDG_STATE_HOME") or os.path.expanduser("~")


def journal_dir():
    return Path(local_app_data()) / JOURNAL_DIR_NAME


def check_name(root, text):
    """Only a plain folder name in the same parent; never a path."""
    if (not isinstance(text, str) or not text.strip() or text != text.strip() or len(text) > 80 or
            re.search(r'[<>:"/\\|?*\x00-\x1f]', text) or text.endswith((".", " ")) or text in (".", "..") or
            re.match(r"(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)", text)):
        raise ValueError("请输入 1–80 字符的普通文件夹名，不能包含路径或 Windows 保留名称")
    root = Path(root).absolute()
    target = root.parent / text
    if str(target).casefold() == str(root).casefold():
        raise ValueError("新名称与原名称相同")
    if target.exists() or target.is_symlink():
        raise ValueError("目标名称已存在，请换一个名称")
    return target


def check_root(root):
    root = Path(root).absolute()
    if not root.is_dir():
        raise ValueError("接收端目录不存在：%s" % root)
    if root.parent == root:
        raise ValueError("安装目录不能位于磁盘根目录")
    # Refuse the Windows/user/program folders themselves, not every folder below
    # them: portable copies usually live in Downloads or on the desktop.
    for key in ("SystemRoot", "WINDIR", "USERPROFILE", "ProgramData", "ProgramFiles", "ProgramFiles(x86)"):
        value = os.environ.get(key)
        if not value:
            continue
        special = Path(value).absolute()
        if root == special or (key in ("SystemRoot", "WINDIR") and special in root.parents):
            raise ValueError("不能维护系统、用户主目录或程序目录：%s" % root)
    if root.name.startswith("~"):
        raise ValueError("目录无效：%s" % root)
    return root


HELPER = r'''#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Detached worker for one self-maintenance operation. Written by self_manage.py."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

OP_PATH = sys.argv[1]
OP = json.loads(Path(OP_PATH).read_text(encoding="utf-8"))
ROOT = Path(OP["root"])
JOURNAL = Path(OP["journal"])
IS_WIN = os.name == "nt"
DETACHED = 0x00000008 | 0x00000200 | 0x08000000 if IS_WIN else 0


def audit(event, **fields):
    try:
        JOURNAL.parent.mkdir(parents=True, exist_ok=True)
        with JOURNAL.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(dict(time=time.time(), event=event, **fields), ensure_ascii=False) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
    except OSError:
        pass


def alive(pid):
    if pid <= 0:
        return False
    if not IS_WIN:
        try:
            os.kill(pid, 0)
            return True
        except OSError:
            return False
    import ctypes
    from ctypes import wintypes as w
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
    kernel.OpenProcess.restype = w.HANDLE
    kernel.GetExitCodeProcess.argtypes = [w.HANDLE, ctypes.POINTER(w.DWORD)]
    kernel.CloseHandle.argtypes = [w.HANDLE]
    handle = kernel.OpenProcess(0x1000, False, pid)
    if not handle:
        return False
    try:
        code = w.DWORD()
        if not kernel.GetExitCodeProcess(handle, ctypes.byref(code)):
            return True
        return code.value == 259
    finally:
        kernel.CloseHandle(handle)


def wait_for(pid, seconds):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if not alive(pid):
            return True
        time.sleep(0.25)
    return not alive(pid)


def related_pids(root):
    """Every process whose image lives in the install folder (panel, worker, ...)."""
    if not IS_WIN:
        return []
    import ctypes
    from ctypes import wintypes as w
    psapi = ctypes.WinDLL("psapi", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
    kernel.OpenProcess.restype = w.HANDLE
    kernel.QueryFullProcessImageNameW.argtypes = [w.HANDLE, w.DWORD, w.LPWSTR, ctypes.POINTER(w.DWORD)]
    kernel.TerminateProcess.argtypes = [w.HANDLE, w.UINT]
    kernel.CloseHandle.argtypes = [w.HANDLE]
    psapi.EnumProcesses.argtypes = [ctypes.POINTER(w.DWORD), w.DWORD, ctypes.POINTER(w.DWORD)]
    found, buffer, needed = [], (w.DWORD * 4096)(), w.DWORD()
    if not psapi.EnumProcesses(buffer, ctypes.sizeof(buffer), ctypes.byref(needed)):
        return found
    prefix = str(root).casefold().rstrip("\\") + "\\"
    for index in range(needed.value // ctypes.sizeof(w.DWORD)):
        pid = buffer[index]
        if pid in (0, 4, os.getpid()):
            continue
        handle = kernel.OpenProcess(0x1001, False, pid)
        if not handle:
            continue
        try:
            image = ctypes.create_unicode_buffer(32768)
            size = w.DWORD(len(image))
            if kernel.QueryFullProcessImageNameW(handle, 0, image, ctypes.byref(size)):
                if image.value.casefold().startswith(prefix):
                    found.append(pid)
        finally:
            kernel.CloseHandle(handle)
    return found


def stop_related(root, deadline):
    for _ in range(40):
        pids = related_pids(root)
        if not pids:
            return True
        for pid in pids:
            try:
                subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"],
                               creationflags=DETACHED, capture_output=True, timeout=15)
                audit("terminated", pid=pid)
            except (OSError, subprocess.SubprocessError) as exc:
                audit("terminate_failed", pid=pid, error=str(exc))
        time.sleep(0.5)
        if time.time() > deadline:
            return False
    return not related_pids(root)


def startup_value(expected_roots):
    """Return (present, matches) for the current user's Run value."""
    if not IS_WIN:
        return False, False
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, OP["run_key"]) as key:
            value = winreg.QueryValueEx(key, OP["run_value"])[0]
    except OSError:
        return False, False
    text = str(value).strip().strip('"').casefold()
    return True, any(text.startswith(str(root).casefold().rstrip("\\") + "\\") for root in expected_roots)


def repoint_startup(old, new):
    if not IS_WIN:
        return
    import winreg
    present, owned = startup_value([old])
    if not present or not owned:
        audit("startup_kept", reason="value not owned by this installation")
        return
    with winreg.CreateKeyEx(winreg.HKEY_CURRENT_USER, OP["run_key"], 0,
                            winreg.KEY_QUERY_VALUE | winreg.KEY_SET_VALUE) as key:
        winreg.SetValueEx(key, OP["run_value"], 0, winreg.REG_SZ, '"%s"' % (new / "LanCmdAgent.exe"))
    audit("startup_repointed", value=str(new / "LanCmdAgent.exe"))


def drop_startup(old):
    if not IS_WIN:
        return
    import winreg
    present, owned = startup_value([old])
    if not present:
        return
    if not owned:
        audit("startup_kept", reason="value does not belong to this install")
        return
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, OP["run_key"], 0, winreg.KEY_SET_VALUE) as key:
        winreg.DeleteValue(key, OP["run_value"])
    audit("startup_removed")


def launch(root):
    candidates = [[str(root / "LanCmdAgent.exe")],
                  [str(root / "python" / "pythonw.exe"), str(root / "lan_command_agent.py")],
                  [sys.executable, str(root / "lan_command_agent.py")]]
    for command in candidates:
        if not all(Path(part).exists() for part in command):
            continue
        try:
            process = subprocess.Popen(command, cwd=str(root), creationflags=DETACHED,
                                       close_fds=True, stdin=subprocess.DEVNULL,
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            audit("relaunched", command=command, pid=process.pid)
            return True
        except OSError as exc:
            audit("relaunch_failed", command=command, error=str(exc))
    return False


def rename(target, deadline):
    error = ""
    while time.time() < deadline:
        try:
            ROOT.rename(target)
            return True, ""
        except OSError as exc:
            error = str(exc)
            time.sleep(0.5)
    return False, error


def remove_tree(path, deadline):
    error = ""
    while time.time() < deadline:
        try:
            shutil.rmtree(path)
            return True, ""
        except FileNotFoundError:
            return True, ""
        except OSError as exc:
            error = str(exc)
            time.sleep(0.5)
    return False, error


def cleanup_self():
    for path in (OP_PATH, __file__):
        try:
            os.remove(path)
        except OSError:
            pass


def main():
    audit("started", operation=OP["operation"], root=str(ROOT), pid=os.getpid())
    deadline = time.time() + OP["max_wait"]
    if not wait_for(OP["pid"], min(60, OP["max_wait"])):
        audit("abort", reason="receiver did not exit in time")
        cleanup_self()
        return 1
    if not stop_related(ROOT, deadline):
        audit("abort", reason="other processes still hold the folder")
        cleanup_self()
        return 1
    if OP["operation"] == "rename":
        target = Path(OP["target"])
        ok, error = rename(target, deadline)
        if not ok:
            audit("failed", operation="rename", error=error)
            cleanup_self()
            return 1
        audit("renamed", target=str(target))
        repoint_startup(ROOT, target)
        time.sleep(0.5)
        if OP.get("restart"):
            launch(target)
    else:
        if OP.get("keep_config"):
            audit("config_kept", path=OP.get("config", ""))
        drop_startup(ROOT)
        ok, error = remove_tree(ROOT, deadline)
        if not ok:
            audit("failed", operation="delete", error=error)
            cleanup_self()
            return 1
        if not OP.get("keep_config") and OP.get("config"):
            ok, error = remove_tree(Path(OP["config"]), deadline)
            audit("config_removed" if ok else "config_remove_failed", error=error)
        audit("deleted", root=str(ROOT))
    cleanup_self()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # the window closes with this process: log, never raise
        try:
            audit("crash", error="%s: %s" % (type(exc).__name__, exc))
        finally:
            cleanup_self()
        sys.exit(1)
'''


class SelfManage:
    """Validate, journal and hand one operation to the detached helper."""

    def __init__(self, root, config_dir, pid, error, logger=None, stopper=None):
        self.root = Path(root).absolute()
        self.config = Path(config_dir).absolute() if config_dir else None
        self.pid = int(pid or 0)
        self.error = error
        self.log = logger
        self.stopper = stopper
        self.lock = threading.Lock()

    def request(self, q, state=None):
        action = q.get("action", "status")
        if action == "status":
            return self.status()
        if action not in ("rename", "delete"):
            raise self.error("无效的自我维护操作")
        if q.get("confirm") != CONFIRM[action]:
            raise self.error("需要确认字符串 %s" % CONFIRM[action])
        with self.lock:
            if self.pending():
                raise self.error("已有维护任务在执行")
            if action == "rename":
                plan = self.plan_rename(q.get("name", ""))
            else:
                plan = self.plan_delete(q)
            helper = self.spawn(plan)
        if self.log:
            self.log.warning("自我维护：%s 已交给助手 pid=%s", action, helper)
        self.after_spawn()
        note = ("接收端即将退出；目录改名完成后会从新路径重新启动，请等十秒左右重连" if action == "rename"
                else "接收端即将退出；删除完成后本机不再有接收端，需重新解压发布包才能使用")
        return dict(accepted=True, operation=action, helper_pid=helper,
                    journal=str(plan["journal"]), target=plan.get("target", ""),
                    note=note)

    # ------------------------------------------------------------- planning
    def plan_rename(self, name):
        root = check_root(self.root)
        target = check_name(root, name)
        return self.plan("rename", root=root, target=str(target), restart=True)

    def plan_delete(self, q):
        root = check_root(self.root)
        keep = bool(q.get("keep_config", False))
        return self.plan("delete", root=root, keep_config=keep, config=str(self.config or ""))

    def plan(self, operation, **fields):
        stamp = time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]
        folder = journal_dir()
        folder.mkdir(parents=True, exist_ok=True)
        data = dict(format=1, operation=operation, pid=self.pid, max_wait=MAX_WAIT,
                    run_key=RUN_KEY, run_value=RUN_VALUE,
                    journal=str(folder / ("remote-" + stamp + ".jsonl")),
                    created=time.time(), **fields)
        data["root"] = str(data["root"])
        path = folder / ("plan-" + stamp + ".json")
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        data["plan"] = str(path)
        return data

    def spawn(self, plan):
        folder = Path(os.environ.get("TEMP") or os.environ.get("TMP") or "/tmp") / "LanCmdSelf"
        folder.mkdir(parents=True, exist_ok=True)
        stamp = uuid.uuid4().hex[:10]
        script = folder / ("helper-" + stamp + ".py")
        payload = folder / ("plan-" + stamp + ".json")
        script.write_text(HELPER, encoding="utf-8")
        payload.write_text(json.dumps(plan, ensure_ascii=False), encoding="utf-8")
        interpreter = sys.executable or "pythonw.exe"
        if os.name == "nt":
            pythonw = Path(self.root) / "python" / "pythonw.exe"
            if pythonw.exists():
                interpreter = str(pythonw)
        flags = 0x00000008 | 0x00000200 | 0x08000000 if os.name == "nt" else 0
        process = subprocess.Popen([interpreter, str(script), str(payload)],
                                   cwd=str(folder), creationflags=flags, close_fds=True,
                                   stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL)
        return process.pid

    def prune(self, folder):
        """Keep the maintenance folder bounded: newest 200 plans and journals."""
        for pattern in ("plan-*.json", "remote-*.jsonl"):
            items = sorted(folder.glob(pattern), key=lambda p: p.stat().st_mtime, reverse=True)
            for stale in items[200:]:
                try:
                    stale.unlink()
                except OSError:
                    pass

    def pending(self):
        """A plan without a final journal line means a helper may still be running."""
        items = []
        plans = sorted(journal_dir().glob("plan-*.json")) if journal_dir().is_dir() else []
        for path in plans:
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            journal = Path(data.get("journal", ""))
            finished = False
            if journal.is_file():
                text = journal.read_text(encoding="utf-8", errors="replace")
                finished = any('"event": "deleted"' in line or '"event": "failed"' in line or
                               '"event": "abort"' in line or '"event": "renamed"' in line
                               for line in text.splitlines())
            if not finished:
                items.append(dict(operation=data.get("operation"), created=data.get("created"),
                                  journal=str(journal), plan=path.name))
        return items

    def status(self):
        try:
            root = str(self.root)
        except OSError:
            root = ""
        return dict(root=root, config=str(self.config or ""), pid=self.pid,
                    journal_dir=str(journal_dir()), pending=self.pending())

    def after_spawn(self):
        """Give the reply time to leave the socket, then stop this process."""
        if self.stopper is None:
            return
        threading.Timer(1.2, self.stopper).start()
