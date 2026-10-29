"""Microsoft touch injection, with contact ownership and EOF/watchdog cleanup.

Uses InitializeTouchInjection/InjectTouchInput, not a virtual HID driver and not
SendInput mouse movement. No cursor scheme or ShowCursor changes are made here.
"""
import ctypes
import json
import math
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading
import time
from collections import deque
from contextlib import contextmanager

MAX_CONTACTS = 10
INPUT_TAG = 0x4C434D44
DOWN, UPDATE, UP, CANCELED = 0x10000, 0x20000, 0x40000, 0x8000
_promotion_lock = threading.Lock()
_promotions = deque(maxlen=64)


@contextmanager
def authorized_promotion(points):
    """Allow only compatibility messages for the frame we're about to inject."""
    permit = {'points': [dict(p,delivered=set()) for p in points], 'until': time.monotonic() + 3}
    with _promotion_lock:_promotions.append(permit)
    try:yield
    finally:
        # Windows gesture recognition can defer compatibility mouse messages
        # until after InjectTouchInput has already acknowledged the touch frame.
        with _promotion_lock:permit['until'] = time.monotonic() + 2


def authorized_touch_mouse(message, flags, extra, x, y):
    if not flags & 1 or extra & 0xffffff00 != 0xff515700 or not extra & 0x80:return False
    now = time.monotonic()
    with _promotion_lock:
        while _promotions and _promotions[0]['until'] < now:_promotions.popleft()
        for permit in _promotions:
            if permit['until'] < now:continue
            for point in permit['points']:
                messages = {'down': (0x200,0x201,0x204), 'move': (0x200,), 'up': (0x200,0x202,0x205)}[point['state']]
                if message in messages and abs(point['x']-x)<=1 and abs(point['y']-y)<=1:
                    if message != 0x200:
                        if message in point['delivered']:continue
                        point['delivered'].add(message)
                    return True
    return False


class Point(ctypes.Structure):
    _fields_ = [('x', ctypes.c_int32), ('y', ctypes.c_int32)]


class Rect(ctypes.Structure):
    _fields_ = [(name, ctypes.c_int32) for name in ('left', 'top', 'right', 'bottom')]


class PointerInfo(ctypes.Structure):
    _fields_ = [(name, ctypes.c_uint32) for name in ('pointerType', 'pointerId', 'frameId', 'pointerFlags')] + [
        ('sourceDevice', ctypes.c_void_p), ('hwndTarget', ctypes.c_void_p),
        ('ptPixelLocation', Point), ('ptHimetricLocation', Point),
        ('ptPixelLocationRaw', Point), ('ptHimetricLocationRaw', Point),
        ('dwTime', ctypes.c_uint32), ('historyCount', ctypes.c_uint32),
        ('InputData', ctypes.c_int32), ('dwKeyStates', ctypes.c_uint32),
        ('PerformanceCount', ctypes.c_uint64), ('ButtonChangeType', ctypes.c_uint32)]


class TouchInfo(ctypes.Structure):
    _fields_ = [('pointerInfo', PointerInfo), ('touchFlags', ctypes.c_uint32), ('touchMask', ctypes.c_uint32),
        ('rcContact', Rect), ('rcContactRaw', Rect), ('orientation', ctypes.c_uint32), ('pressure', ctypes.c_uint32)]


def validate(contacts, active):
    if not isinstance(contacts, list) or not 1 <= len(contacts) <= MAX_CONTACTS:
        raise ValueError('触摸帧需包含 1–10 个触点')
    frame, ids = [], set()
    for item in contacts:
        if not isinstance(item, dict):raise ValueError('触点格式无效')
        ident, state = item.get('id'), item.get('state')
        x, y = item.get('x'), item.get('y')
        if type(ident) is not int or not 0 <= ident <= 31 or ident in ids:
            raise ValueError('触点编号无效或重复')
        if state not in ('down', 'move', 'up') or any(type(v) not in (int, float) or not math.isfinite(v) or not 0 <= v <= 1 for v in (x, y)):
            raise ValueError('触摸状态或坐标无效')
        if (state == 'down') == (ident in active):raise ValueError('触点生命周期不连续')
        ids.add(ident);frame.append(dict(id=ident, state=state, x=float(x), y=float(y)))
    if not set(active).issubset(ids):raise ValueError('触摸帧遗漏了未释放的触点')
    return frame


class NativeTouch:
    def __init__(self, user=None):
        self.user = user or ctypes.WinDLL('user32', use_last_error=True)
        self.user.InitializeTouchInjection.argtypes = [ctypes.c_uint32, ctypes.c_uint32]
        self.user.InitializeTouchInjection.restype = ctypes.c_int
        self.user.InjectTouchInput.argtypes = [ctypes.c_uint32, ctypes.POINTER(TouchInfo)]
        self.user.InjectTouchInput.restype = ctypes.c_int
        self.user.SetMessageExtraInfo.argtypes = [ctypes.c_ssize_t]
        self.user.SetMessageExtraInfo.restype = ctypes.c_ssize_t
        self.user.SetProcessDPIAware()
        if not self.user.InitializeTouchInjection(MAX_CONTACTS, 3):  # TOUCH_FEEDBACK_NONE
            raise RuntimeError('Windows 无法初始化官方触控注入接口')
        self.points = {}
        self.prepared = None

    def pixel(self, item):
        width, height = self.user.GetSystemMetrics(78), self.user.GetSystemMetrics(79)
        if width <= 0 or height <= 0:raise RuntimeError('Windows 当前没有可用的交互桌面')
        return (self.user.GetSystemMetrics(76) + min(width - 1, round(item['x'] * (width - 1))),
                self.user.GetSystemMetrics(77) + min(height - 1, round(item['y'] * (height - 1))))

    def inject(self, contacts):
        array = (TouchInfo * len(contacts))()
        for output, (ident, state, x, y) in zip(array, contacts):
            output.pointerInfo.pointerType = 2  # PT_TOUCH
            output.pointerInfo.pointerId = ident + 1
            output.pointerInfo.pointerFlags = state
            output.pointerInfo.ptPixelLocation = Point(x, y)
            output.touchMask = 7  # Contact area, orientation, pressure.
            output.rcContact = Rect(x - 2, y - 2, x + 2, y + 2)
            output.orientation, output.pressure = 90, 512
        previous = self.user.SetMessageExtraInfo(INPUT_TAG)
        try:
            deadline = time.monotonic() + .1
            while not self.user.InjectTouchInput(len(array), array):
                error = ctypes.get_last_error()
                if error != 21 or time.monotonic() >= deadline:  # ERROR_NOT_READY: timestamp granularity.
                    if error == 87:self.points.clear()  # Windows cancels all contacts for an invalid frame.
                    raise RuntimeError('Windows 触控注入失败（错误 %s），未回退为鼠标模拟' % error)
                time.sleep(.001)
        finally:self.user.SetMessageExtraInfo(previous)

    def prepare(self, contacts):
        frame = validate(contacts, self.points)
        pixels = {p['id']: self.pixel(p) for p in frame}
        self.prepared = frame, pixels
        return [dict(x=pixels[p['id']][0],y=pixels[p['id']][1],state=p['state']) for p in frame]

    def frame(self, contacts):
        frame = validate(contacts, self.points)
        pixels = self.prepared[1] if self.prepared and self.prepared[0] == frame else {p['id']:self.pixel(p) for p in frame}
        self.prepared = None
        # Windows requires UP coordinates to match the preceding UPDATE.
        if any(p['state'] == 'up' and pixels[p['id']] != self.points[p['id']] for p in frame):
            update = [(p['id'], UPDATE | 6, *pixels[p['id']]) for p in frame if p['id'] in self.points]
            self.inject(update)
            self.points.update({ident: (x, y) for ident, _, x, y in update})
        self.inject([(p['id'], {'down': DOWN | 6, 'move': UPDATE | 6, 'up': UP}[p['state']],
                      *pixels[p['id']]) for p in frame])
        self.points = {p['id']: pixels[p['id']] for p in frame if p['state'] != 'up'}
        return len(self.points)

    def cancel(self):
        if self.points:
            self.inject([(ident, CANCELED | UP, *point) for ident, point in self.points.items()])
            self.points.clear()


def recovery_worker(native, input_stream, output_stream, watchdog=4):
    pending = queue.Queue()
    def read():
        try:
            for line in input_stream:pending.put(json.loads(line))
        except Exception:pass
        finally:pending.put(None)
    threading.Thread(target=read, daemon=True).start()
    def reply(sequence, **data):
        output_stream.write(json.dumps(dict(id=sequence, ok=True, **data)) + '\n');output_stream.flush()
    try:
        reply(0, api='InjectTouchInput', max_contacts=MAX_CONTACTS, active=0)
        while True:
            try:message = pending.get(timeout=watchdog)
            except queue.Empty:break
            if message is None:break
            action = message.get('action')
            details = {}
            if action == 'prepare':details['points'] = native.prepare(message.get('contacts'))
            elif action == 'prepare_cancel':details['points'] = [dict(x=x,y=y,state='up') for x,y in native.points.values()]
            elif action == 'frame':native.frame(message.get('contacts'))
            elif action == 'cancel':native.cancel()
            elif action != 'ping':raise ValueError('无效的触控工作进程请求')
            reply(message['id'], active=len(native.points), **details)
    except Exception as exc:
        try:
            output_stream.write(json.dumps(dict(ok=False, error=str(exc))) + '\n');output_stream.flush()
        except OSError:pass
    finally:
        for _ in range(20):
            try:native.cancel();return 0
            except Exception:time.sleep(.05)
    return 1


class TouchWorker:
    def __init__(self):
        executable = Path(sys.executable)
        if executable.name.lower() == 'pythonw.exe':executable = executable.with_name('python.exe')
        self.process = subprocess.Popen([str(executable), str(Path(__file__).resolve())],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, encoding='utf-8', creationflags=0x08000000, close_fds=True)
        self.lines = queue.Queue()
        self.sequence = 0
        self.closed = False
        self.failed = False
        def read():
            try:
                for line in self.process.stdout:self.lines.put(json.loads(line))
            except Exception:pass
            finally:self.lines.put(None)
        self.reader = threading.Thread(target=read, daemon=True);self.reader.start()
        try:self._receive(0)
        except Exception:
            try:self.close()
            except Exception:pass
            raise

    def _receive(self, sequence):
        try:result = self.lines.get(timeout=3)
        except queue.Empty:raise RuntimeError('Windows 官方触控注入响应超时')
        if result is None or not result.get('ok') or result.get('id') != sequence:
            self.failed = True
            raise RuntimeError((result or {}).get('error', '触控工作进程已结束'))
        return result

    def call(self, action='ping', contacts=None):
        if action in ('frame','cancel'):
            prepared = self._call('prepare' if action == 'frame' else 'prepare_cancel', contacts)
            with authorized_promotion(prepared['points']):return self._call(action, contacts)
        return self._call(action, contacts)

    def _call(self, action, contacts=None):
        self.sequence += 1
        message = dict(id=self.sequence, action=action)
        if contacts is not None:message['contacts'] = contacts
        self.process.stdin.write(json.dumps(message) + '\n');self.process.stdin.flush()
        return self._receive(self.sequence)

    def close(self):
        if not self.process.stdin.closed:
            try:
                if not self.failed:self.call('cancel')
            except Exception:pass
            finally:self.process.stdin.close()
        try:
            result = self.process.wait(5)
        except subprocess.TimeoutExpired:
            raise RuntimeError('Windows 触点仍在释放，恢复进程将继续执行')
        self.closed = True
        self.reader.join(1);self.process.stdout.close()
        if result:raise RuntimeError('Windows 触点释放失败，触控进程已退出')


class TouchInjection:
    def __init__(self, factory=TouchWorker, interval=.5):
        self.factory, self.interval = factory, interval
        self.lock = threading.RLock()
        self.worker, self.thread = None, None
        self.owners, self.active, self.points = {}, None, {}

    def request(self, owner, action, contacts, allowed):
        with self.lock:
            if not allowed():raise RuntimeError('原生触控会话已结束')
            if action == 'start':
                if self.worker is None:self.worker = self.factory()
                try:result = self.worker.call()
                except Exception:self._close();raise
                self.owners[owner] = allowed
                if self.thread is None:
                    self.thread = threading.Thread(target=self._watch, daemon=True, name='lancmd-native-touch')
                    self.thread.start()
                return dict(active=result['active'], api='InjectTouchInput', max_contacts=MAX_CONTACTS)
            if owner not in self.owners or self.worker is None:raise RuntimeError('请先开始 Windows 原生触控')
            if self.active is not None and self.active != owner:raise RuntimeError('其他连接正在进行触摸操作')
            if action == 'cancel':
                result = self.worker.call('cancel');self.points.clear();self.active = None;return {'active':result['active']}
            if action != 'frame':raise ValueError('无效的原生触控操作')
            frame = validate(contacts, self.points)
            try:result = self.worker.call('frame', frame)
            except Exception:
                self._close()
                raise
            self.points = {p['id']: p for p in frame if p['state'] != 'up'}
            self.active = owner if self.points else None
            return {'active':result['active']}

    def release(self, owner):
        with self.lock:
            if self.active == owner and self.worker is not None:
                try:self.worker.call('cancel')
                except Exception:self._close();raise
                self.points.clear();self.active = None
            self.owners.pop(owner, None)
            if not self.owners:self._close()

    def _close(self):
        worker = self.worker
        self.owners.clear();self.points.clear();self.active = None
        if worker is not None:
            try:worker.close()
            except Exception:
                if getattr(worker,'closed',False):self.worker = None
                raise
            else:self.worker = None

    def _watch(self):
        try:
            while True:
                time.sleep(self.interval)
                with self.lock:
                    for owner, allowed in list(self.owners.items()):
                        if not allowed():self.release(owner)
                    if self.worker is None:
                        self.thread = None
                        return
                    self.worker.call()
        except Exception:
            with self.lock:
                try:self._close()
                finally:
                    if self.thread is threading.current_thread():self.thread = None
        finally:
            with self.lock:
                if self.thread is threading.current_thread():self.thread = None


if __name__ == '__main__':
    try:raise SystemExit(recovery_worker(NativeTouch(), sys.stdin, sys.stdout))
    except Exception as exc:
        print(json.dumps(dict(ok=False, error=str(exc))), flush=True)
        raise SystemExit(1)
