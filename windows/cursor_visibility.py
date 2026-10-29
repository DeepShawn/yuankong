"""Temporary cursor visibility, scoped to live remote input leases.

A separate pipe/heartbeat worker restores the current Windows cursor scheme even
if the receiver exits. No registry changes and no cursor files are replaced.
"""
import ctypes
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading
import time
from concurrent.futures import Future


class InputCursorMask:
    """Hide the cursor in its owning input queue, with balanced recovery.

    SetCursor only changes a shape; an application's WM_SETCURSOR can replace
    it immediately. ShowCursor's count belongs to an input queue. The recovery
    thread temporarily joins the queue under the pointer and contributes its
    own negative count, then balances exactly those calls before detaching.
    """
    def __init__(self, native):
        from ctypes import wintypes as w
        self.native, self.user = native, native.user
        self.kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        self.kernel.GetCurrentThreadId.restype = w.DWORD
        self.kernel.OpenThread.argtypes = [w.DWORD, w.BOOL, w.DWORD]
        self.kernel.OpenThread.restype = w.HANDLE
        self.kernel.WaitForSingleObject.argtypes = [w.HANDLE, w.DWORD]
        self.kernel.CloseHandle.argtypes = [w.HANDLE]
        self.user.AttachThreadInput.argtypes = [w.DWORD, w.DWORD, w.BOOL]
        self.user.AttachThreadInput.restype = w.BOOL
        self.user.ShowCursor.argtypes = [w.BOOL]
        self.user.ShowCursor.restype = ctypes.c_int
        self.user.WindowFromPoint.argtypes = [w.POINT]
        self.user.WindowFromPoint.restype = w.HWND
        self.user.GetWindowThreadProcessId.argtypes = [w.HWND, ctypes.POINTER(w.DWORD)]
        self.user.GetWindowThreadProcessId.restype = w.DWORD
        self.user.GetForegroundWindow.restype = w.HWND
        class GuiThreadInfo(ctypes.Structure):
            _fields_ = [('size', w.DWORD), ('flags', w.DWORD),
                        ('active', w.HWND), ('focus', w.HWND), ('capture', w.HWND),
                        ('menu', w.HWND), ('move', w.HWND), ('caret', w.HWND), ('rect', w.RECT)]
        self.GuiThreadInfo = GuiThreadInfo
        self.user.GetGUIThreadInfo.argtypes = [w.DWORD, ctypes.POINTER(GuiThreadInfo)]
        self.thread = self.kernel.GetCurrentThreadId()
        self.target, self.handle, self.calls = None, None, 0
        # AttachThreadInput requires a message queue on the recovery thread.
        message = w.MSG()
        self.user.PeekMessageW(ctypes.byref(message), None, 0, 0, 0)

    def suppress(self, point):
        info = self.GuiThreadInfo()
        info.size = ctypes.sizeof(info)
        capture = info.capture if self.user.GetGUIThreadInfo(0, ctypes.byref(info)) else None
        window = capture or self.user.WindowFromPoint(point) or self.user.GetForegroundWindow()
        target = self.user.GetWindowThreadProcessId(window, None) if window else 0
        if not target:
            raise RuntimeError('Windows 无法确认光标所属窗口，已停止本次控制')
        if target != self.target:
            if self.target is not None:
                # Keep the old location transparent during the short interval
                # between preparing a destination queue and SendInput moving
                # there. Otherwise balancing its count would expose its last
                # custom cursor before the move has happened.
                self.user.SetCursor(self.native.blank)
            self.release()
            handle = self.kernel.OpenThread(0x100000, False, target)  # SYNCHRONIZE; no injection.
            if not handle:
                raise RuntimeError('Windows 拒绝读取光标窗口线程，已停止本次控制')
            if target != self.thread and not self.user.AttachThreadInput(self.thread, target, True):
                self.kernel.CloseHandle(handle)
                raise RuntimeError('Windows 拒绝连接光标输入队列，已停止本次控制')
            self.target, self.handle = target, handle
        owned = self.calls
        count = self.user.ShowCursor(False)
        self.calls += 1
        if owned and count < -1:
            # Query the effective queue count without ever increasing it past
            # its previous value. Do not trust an already-hidden CI desktop's
            # CURSOR_SHOWING flag as proof that this queue is suppressed.
            count = self.user.ShowCursor(True)
            self.calls -= 1
        for _ in range(64):
            if count < 0:break
            count = self.user.ShowCursor(False)
            self.calls += 1
        else:
            raise RuntimeError('应用反复显示系统光标，Windows 未确认隐藏，已停止本次控制')

    def release(self):
        while self.calls:
            self.user.ShowCursor(True)
            self.calls -= 1
        if self.target is not None:
            if self.target != self.thread and not self.user.AttachThreadInput(self.thread, self.target, False):
                if self.kernel.WaitForSingleObject(self.handle, 0) != 0:
                    raise RuntimeError('Windows 光标输入队列尚未恢复')
            self.kernel.CloseHandle(self.handle)
            self.target, self.handle = None, None


class NativeCursors:
    IDS = (32512, 32513, 32514, 32515, 32516, 32642, 32643, 32644,
           32645, 32646, 32648, 32649, 32650, 32651)
    OPTIONAL_IDS = (32671, 32672)  # Location/person cursors on newer Windows.

    def __init__(self, user=None, gdi=None):
        from ctypes import wintypes as w
        self.real = user is None
        self.user = user or ctypes.WinDLL('user32', use_last_error=True)
        self.gdi = gdi or (ctypes.WinDLL('gdi32', use_last_error=True) if self.real else None)
        class CursorInfo(ctypes.Structure):
            _fields_ = [('size', w.DWORD), ('flags', w.DWORD), ('cursor', w.HANDLE), ('position', w.POINT)]
        class IconInfo(ctypes.Structure):
            _fields_ = [('icon', w.BOOL), ('x', w.DWORD), ('y', w.DWORD), ('mask', w.HANDLE), ('color', w.HANDLE)]
        class Bitmap(ctypes.Structure):
            _fields_ = [('type', w.LONG), ('width', w.LONG), ('height', w.LONG), ('stride', w.LONG),
                        ('planes', w.WORD), ('depth', w.WORD), ('bits', ctypes.c_void_p)]
        self.CursorInfo, self.IconInfo, self.Bitmap = CursorInfo, IconInfo, Bitmap
        signatures = {
            'CreateCursor': ([w.HINSTANCE, ctypes.c_int, ctypes.c_int, ctypes.c_int,
                              ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p], w.HANDLE),
            'SetSystemCursor': ([w.HANDLE, w.DWORD], w.BOOL),
            'DestroyCursor': ([w.HANDLE], w.BOOL),
            'SystemParametersInfoW': ([w.UINT, w.UINT, ctypes.c_void_p, w.UINT], w.BOOL),
            'SetCursor': ([w.HANDLE], w.HANDLE),
            'LoadCursorW': ([w.HINSTANCE, w.LPCWSTR], w.HANDLE),
            'GetCursorInfo': ([ctypes.POINTER(CursorInfo)], w.BOOL),
            'GetIconInfo': ([w.HANDLE, ctypes.POINTER(IconInfo)], w.BOOL),
        }
        for name, (args, result) in signatures.items():
            function = getattr(self.user, name)
            function.argtypes, function.restype = args, result
        self.changed = False
        self.blank = None
        self.hook = None
        self.native_error = None
        self.reapply_at = 0
        self.replacing = False
        self.reference = None
        self.input_mask = None
        self.prepared = None
        self.refreshing = False
        if self.real:
            # Calibrate bitmap row ordering from a known transparent cursor,
            # without selecting it or changing any system cursor.
            probe = self._blank()
            try:self.reference = self._mask(probe)
            finally:self.user.DestroyCursor(probe)

    def _blank(self):
        and_mask = (ctypes.c_ubyte * 128)(*([255] * 128))
        xor_mask = (ctypes.c_ubyte * 128)()
        handle = self.user.CreateCursor(None, 0, 0, 32, 32, and_mask, xor_mask)
        if not handle:
            raise RuntimeError('无法创建临时鼠标指针')
        return handle

    def _load(self, resource):
        from ctypes import wintypes as w
        return self.user.LoadCursorW(None, ctypes.cast(ctypes.c_void_p(resource), w.LPCWSTR))

    def _replace(self):
        self.reapply_at = time.monotonic() + .5
        self.replacing = True
        try:
            for resource in self.IDS + self.OPTIONAL_IDS:
                if resource in self.OPTIONAL_IDS and not self._load(resource):
                    continue
                handle = self._blank()
                if not self.user.SetSystemCursor(handle, resource):
                    self.user.DestroyCursor(handle)
                    raise RuntimeError('Windows 拒绝临时隐藏鼠标指针')
                self.changed = True  # SetSystemCursor owns and destroys this handle.
        finally:
            self.replacing = False

    def _current(self):
        info = self.CursorInfo()
        info.size = ctypes.sizeof(info)
        if not self.user.GetCursorInfo(ctypes.byref(info)):
            raise RuntimeError('Windows 无法读取当前系统光标，未确认隐藏')
        return info

    def transparent(self, handle):
        if handle == self.blank and handle:
            return True
        return self.reference is not None and self._mask(handle) == self.reference

    def _mask(self, handle):
        if not handle or self.gdi is None:
            return None
        from ctypes import wintypes as w
        gdi = self.gdi
        gdi.GetObjectW.argtypes = [w.HANDLE, ctypes.c_int, ctypes.c_void_p]
        gdi.GetBitmapBits.argtypes = [w.HANDLE, w.LONG, ctypes.c_void_p]
        gdi.GetBitmapBits.restype = w.LONG
        gdi.DeleteObject.argtypes = [w.HANDLE]
        icon = self.IconInfo()
        if not self.user.GetIconInfo(handle, ctypes.byref(icon)):
            return None
        try:
            if icon.color or not icon.mask:
                return None
            bitmap = self.Bitmap()
            if not gdi.GetObjectW(icon.mask, ctypes.sizeof(bitmap), ctypes.byref(bitmap)):
                return None
            size = bitmap.stride * bitmap.height
            if bitmap.depth != 1 or bitmap.height % 2 or not 0 < size <= 1024 * 1024:
                return None
            payload = (ctypes.c_ubyte * size)()
            if gdi.GetBitmapBits(icon.mask, size, payload) != size:
                return None
            return bitmap.width, bitmap.height, bitmap.stride, bytes(payload)
        finally:
            if icon.mask:gdi.DeleteObject(icon.mask)
            if icon.color:gdi.DeleteObject(icon.color)

    def _install_hook(self):
        if not self.real:
            return
        from ctypes import wintypes as w
        callback_type = ctypes.WINFUNCTYPE(None, w.HANDLE, w.DWORD, w.HWND, w.LONG, w.LONG, w.DWORD, w.DWORD)
        def changed(hook, event, window, object_id, child, thread, tick):
            if object_id == -9 and not self.replacing:  # Out-of-context, no application injection.
                try:self.refresh()
                except Exception as exc:self.native_error = exc
        self.callback = callback_type(changed)
        self.user.SetWinEventHook.argtypes = [w.DWORD, w.DWORD, w.HMODULE, callback_type, w.DWORD, w.DWORD, w.DWORD]
        self.user.SetWinEventHook.restype = w.HANDLE
        self.user.UnhookWinEvent.argtypes = [w.HANDLE]
        self.user.PeekMessageW.argtypes = [ctypes.POINTER(w.MSG), w.HWND, w.UINT, w.UINT, w.UINT]
        self.user.TranslateMessage.argtypes = [ctypes.POINTER(w.MSG)]
        self.user.DispatchMessageW.argtypes = [ctypes.POINTER(w.MSG)]
        self.user.DispatchMessageW.restype = ctypes.c_ssize_t
        self.user.MsgWaitForMultipleObjectsEx.argtypes = [w.DWORD, ctypes.c_void_p, w.DWORD, w.DWORD, w.DWORD]
        self.MSG = w.MSG
        self.hook = self.user.SetWinEventHook(0x8002, 0x800C, None, self.callback, 0, 0, 2)

    def hide(self):
        self.blank = self._blank()
        self.reference = self._mask(self.blank)
        if self.real and self.reference is None:
            raise RuntimeError('Windows 无法校验透明光标位图')
        self._install_hook()
        if self.real:
            self.user.SetProcessDPIAware()
            self.input_mask = InputCursorMask(self)
        self._replace()
        self.refresh()

    def prepare(self, x, y):
        from ctypes import wintypes as w
        # The frame and SendInput both cover the physical virtual desktop.
        self.prepared = (w.POINT(self.user.GetSystemMetrics(76) + round(x * 65535) * self.user.GetSystemMetrics(78) // 65536,
                                 self.user.GetSystemMetrics(77) + round(y * 65535) * self.user.GetSystemMetrics(79) // 65536),
                         time.monotonic() + .25)

    def refresh(self):
        if self.refreshing:return True
        self.refreshing = True
        try:return self._refresh()
        finally:self.refreshing = False

    def _refresh(self):
        if self.native_error:
            raise self.native_error
        if time.monotonic() >= self.reapply_at:
            self._replace()  # Repair application/theme resets without registry edits.
        current = self._current()
        if self.input_mask:
            point = current.position
            if self.prepared:
                requested, deadline = self.prepared
                if time.monotonic() < deadline and (point.x, point.y) != (requested.x, requested.y):
                    point = requested
                else:self.prepared = None
            self.input_mask.suppress(point)
            current = self._current()
        if current.flags & 1 and not self.transparent(current.cursor):
            self.user.SetCursor(self.blank)
            current = self._current()
            if current.flags & 1 and not self.transparent(current.cursor):
                raise RuntimeError('当前应用重新设置了系统光标，Windows 未确认隐藏，已停止本次控制')
        return True

    def wait(self):
        if self.real:
            from ctypes import wintypes as w
            self.user.MsgWaitForMultipleObjectsEx(0, None, 16, 0x4FF, 4)
            message = w.MSG()
            while self.user.PeekMessageW(ctypes.byref(message), None, 0, 0, 1):
                self.user.TranslateMessage(ctypes.byref(message))
                self.user.DispatchMessageW(ctypes.byref(message))
        else:
            time.sleep(.016)

    def restore(self):
        if self.hook:
            self.user.UnhookWinEvent(self.hook)
            self.hook = None
        if self.input_mask:
            self.input_mask.release()
            self.input_mask = None
        if self.changed:
            if not self.user.SystemParametersInfoW(0x57, 0, None, 0):  # SPI_SETCURSORS
                raise RuntimeError('Windows 鼠标指针恢复失败')
            self.changed = False
        if self.blank:
            self.user.SetCursor(self._load(32512))
            self.user.DestroyCursor(self.blank)
            self.blank = None


def recovery_worker(native, input_stream, output_stream, watchdog=4):
    stop = threading.Event()
    seen = [time.monotonic()]
    pending = queue.SimpleQueue()

    def read():
        try:
            for line in input_stream:
                message = json.loads(line)
                if message.get('action') != 'ping':
                    break
                seen[0] = time.monotonic()
                pending.put(message)
        except Exception:
            pass
        finally:
            stop.set()

    threading.Thread(target=read, daemon=True).start()
    try:
        if stop.is_set():
            return 0
        native.hide()
        output_stream.write(json.dumps({'ok': True, 'verified': True, 'id': 0}) + '\n'); output_stream.flush()
        while not stop.is_set():
            acknowledgement = None
            position = None
            while not pending.empty():
                message = pending.get()
                acknowledgement = message.get('id', 0)
                if message.get('position') is not None:position = message['position']
            if position is not None:
                native.prepare(*position)
            refresh = getattr(native, 'refresh', None)
            if refresh:refresh()
            if time.monotonic() - seen[0] > watchdog:
                break
            if acknowledgement is not None:
                output_stream.write(json.dumps({'ok': True, 'verified': True, 'id': acknowledgement}) + '\n')
                output_stream.flush()
            wait = getattr(native, 'wait', None)
            if wait:wait()
            else:stop.wait(.016)
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
        self.condition = threading.Condition()
        self.sequence, self.acknowledged = 0, -1
        self.failure = ''
        self.eof = False
        ready = Future()
        def read():
            try:
                for line in self.process.stdout:
                    result = json.loads(line)
                    with self.condition:
                        if not result.get('ok') or not result.get('verified'):
                            self.failure = result.get('error', 'Windows 未确认系统光标已隐藏')
                        self.acknowledged = max(self.acknowledged, result.get('id', -1))
                        if not ready.done():ready.set_result(result)
                        self.condition.notify_all()
            except Exception as exc:
                with self.condition:
                    self.failure = str(exc)
                    if not ready.done():ready.set_exception(exc)
            finally:
                with self.condition:
                    self.eof = True
                    if not ready.done():ready.set_exception(RuntimeError('光标工作进程未报告启动结果'))
                    self.condition.notify_all()
        self.reader = threading.Thread(target=read, daemon=True)
        self.reader.start()
        try:
            result = ready.result(timeout=4)
            if not result.get('ok') or not result.get('verified'):
                raise RuntimeError(result.get('error', '鼠标指针隐藏失败'))
        except Exception:
            self.close()
            raise

    def ping(self, position=None):
        if self.process.poll() is not None:
            raise RuntimeError(self.failure or '鼠标指针恢复进程已结束')
        with self.condition:
            self.sequence += 1
            sequence = self.sequence
            message = {'action': 'ping', 'id': sequence}
            if position is not None:message['position'] = position
            self.process.stdin.write(json.dumps(message) + '\n')
            self.process.stdin.flush()
            if not self.condition.wait_for(lambda: self.acknowledged >= sequence or self.failure or self.eof, timeout=3):
                raise RuntimeError('Windows 光标隐藏状态校验超时')
            if self.failure or self.eof:
                raise RuntimeError(self.failure or '光标隐藏工作进程已结束')

    def close(self):
        try:
            self.process.stdin.close()  # EOF is a restore request; never kill the recovery worker.
            # A new worker must not hide before this one reloads the old scheme.
            # Even on timeout, leave the recovery process alive and report it.
            result = self.process.wait(timeout=8)
            if result:
                raise RuntimeError(self.failure or 'Windows 鼠标指针恢复失败')
        finally:
            self.process.stdout.close()
            self.reader.join(timeout=1)


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
                try:self.worker.ping()  # ACK follows native visibility verification.
                except Exception:
                    self._restore_if_unused()
                    raise
                self.owners[owner] = allowed
                if self.thread is None or not self.thread.is_alive():
                    self.thread = threading.Thread(target=self._watch, name='lancmd-cursor', daemon=True)
                    self.thread.start()
            else:
                self.owners.pop(owner, None)
                self._restore_if_unused()
            return {'hidden': bool(self.worker), 'requested': hidden, 'verified': bool(self.worker) and hidden}

    def _restore_if_unused(self):
        if not self.owners and self.worker is not None:
            worker = self.worker
            worker.close()
            self.worker = None  # Do not race a still-running restoration process.

    def prepare(self, x, y):
        with self.lock:
            if self.worker is not None and self.owners:
                try:self.worker.ping(position=(x, y))
                except Exception:
                    self.owners.clear()
                    self._restore_if_unused()
                    raise

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
