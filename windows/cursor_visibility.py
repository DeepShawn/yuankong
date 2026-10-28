"""Temporary cursor visibility, scoped to live remote input leases.

A separate pipe/heartbeat worker restores the current Windows cursor scheme even
if the receiver exits. No registry changes and no cursor files are replaced.
"""
import ctypes
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
from concurrent.futures import Future


class NativeCursors:
    IDS = (32512, 32513, 32514, 32515, 32516, 32642, 32643, 32644,
           32645, 32646, 32648, 32649, 32650, 32651)

    def __init__(self, user=None):
        from ctypes import wintypes as w
        self.user = user or ctypes.WinDLL('user32', use_last_error=True)
        signatures = {
            'CreateCursor': ([w.HINSTANCE, ctypes.c_int, ctypes.c_int, ctypes.c_int,
                              ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p], w.HANDLE),
            'SetSystemCursor': ([w.HANDLE, w.DWORD], w.BOOL),
            'DestroyCursor': ([w.HANDLE], w.BOOL),
            'SystemParametersInfoW': ([w.UINT, w.UINT, ctypes.c_void_p, w.UINT], w.BOOL),
        }
        for name, (args, result) in signatures.items():
            function = getattr(self.user, name)
            function.argtypes, function.restype = args, result
        self.changed = False

    def hide(self):
        and_mask = (ctypes.c_ubyte * 128)(*([255] * 128))
        xor_mask = (ctypes.c_ubyte * 128)()
        for resource in self.IDS:
            handle = self.user.CreateCursor(None, 0, 0, 32, 32, and_mask, xor_mask)
            if not handle:
                raise RuntimeError('无法创建临时鼠标指针')
            if not self.user.SetSystemCursor(handle, resource):
                self.user.DestroyCursor(handle)
                raise RuntimeError('Windows 拒绝临时隐藏鼠标指针')
            self.changed = True  # SetSystemCursor owns and destroys this handle.

    def restore(self):
        if self.changed:
            if not self.user.SystemParametersInfoW(0x57, 0, None, 0):  # SPI_SETCURSORS
                raise RuntimeError('Windows 鼠标指针恢复失败')
            self.changed = False


def recovery_worker(native, input_stream, output_stream, watchdog=4):
    stop = threading.Event()
    seen = [time.monotonic()]

    def read():
        try:
            for line in input_stream:
                if json.loads(line).get('action') != 'ping':
                    break
                seen[0] = time.monotonic()
        except Exception:
            pass
        finally:
            stop.set()

    threading.Thread(target=read, daemon=True).start()
    try:
        if stop.is_set():
            return 0
        native.hide()
        output_stream.write(json.dumps({'ok': True}) + '\n'); output_stream.flush()
        while not stop.wait(.1):
            if time.monotonic() - seen[0] > watchdog:
                break
    except Exception as exc:
        try:
            output_stream.write(json.dumps({'ok': False, 'error': str(exc)}) + '\n'); output_stream.flush()
        except OSError:
            pass
    finally:
        # A failed partial hide must be recovered too. Keep this worker alive
        # across receiver termination while retrying transient Windows failures.
        for _ in range(30):
            try:
                native.restore()
                return 0
            except Exception:
                time.sleep(.2)
    return 1


class CursorWorker:
    def __init__(self):
        if getattr(sys, 'frozen', False):
            command = [sys.executable, '--cursor-worker']
        else:
            executable = Path(sys.executable)
            if executable.name.lower() == 'pythonw.exe':
                executable = executable.with_name('python.exe')
            command = [str(executable), str(Path(__file__).resolve())]
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, text=True, encoding='utf-8', close_fds=True, creationflags=0x08000000)
        ready = Future()
        def read():
            try:
                ready.set_result(json.loads(self.process.stdout.readline()))
            except Exception as exc:
                ready.set_exception(exc)
        threading.Thread(target=read, daemon=True).start()
        try:
            result = ready.result(timeout=4)
            if not result.get('ok'):
                raise RuntimeError(result.get('error', '鼠标指针隐藏失败'))
        except Exception:
            self.close()
            raise

    def ping(self):
        if self.process.poll() is not None:
            raise RuntimeError('鼠标指针恢复进程已结束')
        self.process.stdin.write('{"action":"ping"}\n'); self.process.stdin.flush()

    def close(self):
        try:
            self.process.stdin.close()  # EOF is a restore request; never kill the recovery worker.
            # A new worker must not hide before this one reloads the old scheme.
            # Even on timeout, leave the recovery process alive and report it.
            self.process.wait(timeout=8)
        finally:
            self.process.stdout.close()


class CursorVisibility:
    def __init__(self, worker_factory=CursorWorker, interval=.5):
        self.factory, self.interval = worker_factory, interval
        self.lock = threading.RLock()
        self.owners = {}
        self.worker = None
        self.last_error = ''
        self.thread = None

    def set_hidden(self, owner, hidden, allowed):
        with self.lock:
            if hidden:
                if not allowed():
                    raise RuntimeError('远程输入会话已结束')
                if self.last_error:
                    error, self.last_error = self.last_error, ''
                    raise RuntimeError(error)
                if self.worker is None:
                    self.worker = self.factory()
                self.owners[owner] = allowed
                if self.thread is None or not self.thread.is_alive():
                    self.thread = threading.Thread(target=self._watch, name='lancmd-cursor', daemon=True)
                    self.thread.start()
            else:
                self.owners.pop(owner, None)
                self._restore_if_unused()
            return {'hidden': bool(self.worker), 'requested': hidden}

    def _restore_if_unused(self):
        if not self.owners and self.worker is not None:
            worker, self.worker = self.worker, None
            worker.close()

    def _watch(self):
        while True:
            time.sleep(self.interval)
            with self.lock:
                for owner, allowed in list(self.owners.items()):
                    try:
                        alive = allowed()
                    except Exception:
                        alive = False
                    if not alive:
                        self.owners.pop(owner, None)
                try:
                    self._restore_if_unused()
                    if self.worker is None:
                        self.thread = None
                        return
                    self.worker.ping()
                except Exception as exc:
                    self.last_error = str(exc)
                    self.owners.clear()
                    self._restore_if_unused()
                    self.thread = None
                    return


def worker_main():
    # GUI/frozen Python may have None stdio despite the inherited native pipes.
    if sys.stdin is None or sys.stdout is None:
        import msvcrt
        kernel = ctypes.WinDLL('kernel32')
        kernel.GetStdHandle.argtypes = [ctypes.c_ulong]
        kernel.GetStdHandle.restype = ctypes.c_void_p
        if sys.stdin is None:
            sys.stdin = os.fdopen(msvcrt.open_osfhandle(kernel.GetStdHandle(-10 & 0xffffffff), os.O_RDONLY), 'r', encoding='utf-8')
        if sys.stdout is None:
            sys.stdout = os.fdopen(msvcrt.open_osfhandle(kernel.GetStdHandle(-11 & 0xffffffff), os.O_WRONLY), 'w', encoding='utf-8')
    return recovery_worker(NativeCursors(), sys.stdin, sys.stdout)


if __name__ == '__main__':
    raise SystemExit(worker_main())
