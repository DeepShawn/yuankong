import ctypes
import io
import json
import logging
import os
from pathlib import Path
import queue
import sys
import threading
import time
from types import SimpleNamespace
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'windows'))
from touch_injection import NativeTouch, TouchInfo, TouchInjection, TouchWorker, validate, recovery_worker
from remote_features import RemoteFeatures


def point(ident=0, state='down', x=.25, y=.5):return dict(id=ident, state=state, x=x, y=y)


class TouchTest(unittest.TestCase):
    def native(self):
        user = SimpleNamespace(**{name: mock.Mock() for name in
            ('SetProcessDPIAware', 'SetMessageExtraInfo', 'InitializeTouchInjection', 'InjectTouchInput', 'GetSystemMetrics')})
        user.InitializeTouchInjection.return_value = 1
        user.InjectTouchInput.return_value = 1
        user.SetMessageExtraInfo.return_value = 0
        user.GetSystemMetrics.side_effect = lambda code: {76: -1000, 77: 0, 78: 2000, 79: 1000}[code]
        native = NativeTouch(user)
        return native, user

    def test_windows_abi_real_touch_flags_multimonitor_and_final_up_coordinate_update(self):
        native, user = self.native()
        self.assertEqual(144, ctypes.sizeof(TouchInfo))
        user.InitializeTouchInjection.assert_called_once_with(10, 3)
        frames = []
        user.InjectTouchInput.side_effect = lambda count, array: frames.append([
            (p.pointerInfo.pointerType, p.pointerInfo.pointerFlags, p.pointerInfo.ptPixelLocation.x,
             p.pointerInfo.ptPixelLocation.y) for p in array]) or 1
        native.frame([point()]);native.frame([point(state='up', x=1)])
        self.assertEqual(3, len(frames))
        self.assertEqual((2, 0x10006, -500, 500), frames[0][0])
        self.assertEqual((2, 0x20006, 999, 500), frames[1][0])
        self.assertEqual((2, 0x40000, 999, 500), frames[2][0])
        self.assertFalse(native.points)

    def test_multitouch_frames_cannot_drop_reuse_or_invent_contacts(self):
        native, user = self.native()
        native.frame([point()]);native.frame([point(state='move'), point(1)])
        for frame in ([point(state='move')], [point(), point(1, 'move')],
                      [point(state='move'), point(1, 'move'), point(1)],
                      [point(state='move', x=True), point(1, 'move')]):
            with self.assertRaises(ValueError):native.frame(frame)
        native.frame([point(state='move'), point(1, 'up')])
        native.cancel();self.assertFalse(native.points)
        self.assertEqual(0x48000, user.InjectTouchInput.call_args.args[1][0].pointerInfo.pointerFlags)

    def test_initialization_failure_does_not_use_a_mouse_fallback(self):
        native, user = self.native()
        user.InitializeTouchInjection.return_value = 0
        with self.assertRaisesRegex(RuntimeError, '官方触控'):NativeTouch(user)
        user.InjectTouchInput.assert_not_called()

    def test_worker_eof_cancels_every_contact(self):
        native, user = self.native()
        source = io.StringIO(json.dumps(dict(id=1, action='frame', contacts=[point(), point(1)])) + '\n')
        output = io.StringIO()
        self.assertEqual(0, recovery_worker(native, source, output))
        self.assertFalse(native.points)
        count, frame = user.InjectTouchInput.call_args.args
        self.assertEqual(2, count)
        self.assertTrue(all(p.pointerInfo.pointerFlags == 0x48000 for p in frame))

    def test_session_scope_conflicts_and_disconnect_release(self):
        backend = SimpleNamespace(touch=mock.Mock(return_value={'active':1}), release_touch=mock.Mock())
        remote = RemoteFeatures(RuntimeError, logging.getLogger('touch'), '', backend)
        state = SimpleNamespace(client_id='phone', peer='127.0.0.1')
        sid = remote.session(dict(scope='input'), state)['sid']
        screen = remote.session(dict(scope='screen'), state)['sid']
        with self.assertRaises(RuntimeError):remote.touch(dict(sid=screen), state)
        remote.touch(dict(sid=sid, contacts=[point()]), state)
        self.assertEqual(sid, remote.touch_owner)
        with self.assertRaises(RuntimeError):remote.pointer(dict(sid=sid,x=.5,y=.5), state)
        remote.session(dict(action='end',sid=sid), state)
        backend.release_touch.assert_called_once_with(sid)
        self.assertIsNone(remote.touch_owner)


@unittest.skipUnless(os.name == 'nt', 'Requires Windows official touch injection and app WM_TOUCH')
class NativeTouchTest(unittest.TestCase):
    def test_touch_promotion_reaches_legacy_apps_while_local_mouse_hook_is_active(self):
        from cursor_window_fixture import CursorWindow
        from input_guard import InputGuard, NativeInputGuard
        class Guard(NativeInputGuard):
            def __init__(self):
                super().__init__()
                self.touch = SimpleNamespace(start=lambda:None,stop=lambda:None,ping=lambda:None)
        guard = InputGuard(Guard())
        with CursorWindow() as app:
            worker = None
            try:
                guard.pause('native-test',lambda:True)
                worker = TouchWorker()
                x,y = app.initial['x'], app.initial['y']
                worker.call('frame',[point(x=x,y=y)])
                worker.call('frame',[point(state='up',x=x,y=y)])
                deadline = time.monotonic()+2
                while True:
                    events = app.report('probe')['mouse_taps']
                    if any(p['message']==0x202 for p in events):break
                    if time.monotonic()>=deadline:self.fail('Native touch promotion was blocked by local-input hook: '+str(events))
                    time.sleep(.02)
            finally:
                if worker:worker.close()
                guard.close()

    def test_official_worker_delivers_app_touch_frames_and_eof_releases_contacts(self):
        from cursor_window_fixture import CursorWindow
        with CursorWindow(touch=True) as app:
            worker = TouchWorker()
            try:
                x, y = app.initial['x'], app.initial['y']
                worker.call('frame', [point(x=x,y=y)])
                worker.call('frame', [point(state='move',x=x+.02,y=y)])
                worker.close();worker = None
                deadline = time.monotonic() + 3
                while True:
                    events = app.report('probe')['touch_events']
                    if any(p['flags'] & 4 for p in events):break
                    if time.monotonic() >= deadline:self.fail('App did not receive native touch DOWN/UP: ' + str(events))
                    time.sleep(.02)
                self.assertTrue(any(p['flags'] & 2 for p in events), events)
                self.assertTrue(any(p['flags'] & 1 for p in events), events)
            finally:
                if worker:worker.close()


if __name__ == '__main__':unittest.main()
