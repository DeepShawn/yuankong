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
from touch_injection import NativeTouch, TouchInfo, TouchInjection, TouchWorker, validate, recovery_worker, authorized_promotion, authorized_touch_mouse
from remote_features import RemoteFeatures


def point(ident=0, state='down', x=.25, y=.5):return dict(id=ident, state=state, x=x, y=y)


class TouchTest(unittest.TestCase):
    def test_controller_exposes_contact_state_without_worker_transport_ids(self):
        class Worker:
            def __init__(self):self.active=0;self.closed=False
            def call(self,action='ping',contacts=None):
                if action=='frame':self.active=sum(p['state']!='up' for p in contacts)
                if action=='cancel':self.active=0
                return dict(id=999,ok=True,active=self.active)
            def close(self):self.closed=True
        worker=Worker();control=TouchInjection(lambda:worker,interval=.01)
        try:
            ready=control.request('phone','start',None,lambda:True)
            self.assertEqual({'active','api','max_contacts'},set(ready))
            self.assertEqual({'active':1},control.request('phone','frame',[point()],lambda:True))
            self.assertEqual({'active':0},control.request('phone','cancel',None,lambda:True))
        finally:control.release('phone')
        self.assertTrue(worker.closed)

    def test_touch_promotion_does_not_unlock_unrelated_physical_or_software_input(self):
        with authorized_promotion([dict(x=100,y=200,state='down')]):
            self.assertTrue(authorized_touch_mouse(0x201,1,0xff51578c,100,200))
            self.assertFalse(authorized_touch_mouse(0x201,1,0xff51578c,100,200))
            self.assertFalse(authorized_touch_mouse(0x201,0,0xff51578c,100,200))
            self.assertFalse(authorized_touch_mouse(0x201,1,0,100,200))
            self.assertFalse(authorized_touch_mouse(0x201,1,0xff51578c,300,400))
            self.assertFalse(authorized_touch_mouse(0x202,1,0xff51578c,100,200))
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

    def test_fast_repeated_taps_always_update_before_up_and_can_reuse_android_id(self):
        native,user=self.native();frames=[]
        user.InjectTouchInput.side_effect=lambda count,array:frames.append([p.pointerInfo.pointerFlags for p in array]) or 1
        for _ in range(4):native.frame([point()]);native.frame([point(state='up')])
        self.assertEqual([[0x10006],[0x20006],[0x40000]]*4,frames)
        self.assertFalse(native.points)

    def test_stationary_hold_injects_updates_without_expiring_parent_watchdog(self):
        native,user=self.native();input_lines=queue.Queue();frames=[]
        class Stream:
            def __iter__(self):
                while True:
                    line=input_lines.get()
                    if line is None:return
                    yield line
        user.InjectTouchInput.side_effect=lambda count,array:frames.append([p.pointerInfo.pointerFlags for p in array]) or 1
        output=io.StringIO()
        thread=threading.Thread(target=recovery_worker,args=(native,Stream(),output,.18),daemon=True)
        input_lines.put(json.dumps(dict(id=1,action='frame',contacts=[point()]))+'\n');thread.start()
        thread.join(1)
        self.assertFalse(thread.is_alive(),'Synthetic stationary updates must not keep abandoned touches alive')
        self.assertGreaterEqual(sum(p==[0x20006] for p in frames),3)
        self.assertEqual([0x48000],frames[-1]);self.assertFalse(native.points)
        input_lines.put(None)

    def test_held_touch_promotion_stays_narrow_and_allows_only_context_click_at_that_point(self):
        with authorized_promotion([dict(x=777,y=888,state='hold')]):
            self.assertTrue(authorized_touch_mouse(0x204,1,0xff51578c,777,888))
            self.assertTrue(authorized_touch_mouse(0x205,1,0xff51578c,777,888))
            self.assertFalse(authorized_touch_mouse(0x201,1,0xff51578c,777,888))
            self.assertFalse(authorized_touch_mouse(0x204,0,0xff51578c,777,888))
            self.assertFalse(authorized_touch_mouse(0x204,1,0xff51578c,500,500))

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
    def test_real_worker_supports_double_taps_and_stationary_long_press_then_another_gesture(self):
        from cursor_window_fixture import CursorWindow
        with CursorWindow(touch=True) as app:
            worker=TouchWorker()
            try:
                x,y=app.initial['x'],app.initial['y']
                for _ in range(2):
                    worker.call('frame',[point(x=x,y=y)])
                    worker.call('frame',[point(state='up',x=x,y=y)])
                worker.call('frame',[point(x=x,y=y)])
                for _ in range(3):time.sleep(.4);self.assertEqual(1,worker.call()['active'])
                worker.call('frame',[point(state='up',x=x,y=y)])
                worker.call('frame',[point(x=x,y=y)])
                worker.call('frame',[point(state='move',x=x+.03,y=y)])
                worker.call('frame',[point(state='up',x=x+.03,y=y)])
                self.assertEqual(0,worker.call()['active'])
                deadline=time.monotonic()+3
                while True:
                    events=app.report('probe')['touch_events']
                    if sum(bool(p['flags']&4) for p in events)>=4:break
                    if time.monotonic()>=deadline:self.fail('Repeated taps or long press did not finish: '+str(events))
                    time.sleep(.02)
                self.assertGreaterEqual(sum(bool(p['flags']&1) for p in events),5)
            finally:worker.close()
    def test_touch_promotion_reaches_legacy_apps_while_local_mouse_hook_is_active(self):
        from cursor_window_fixture import CursorWindow
        from input_guard import InputGuard, NativeInputGuard
        import touch_injection as injection
        seen = []
        original = injection.authorized_touch_mouse
        def check(*args):
            result = original(*args)
            seen.append((args,result))
            return result
        class Guard(NativeInputGuard):
            def __init__(self):
                with mock.patch.object(injection,'authorized_touch_mouse',check):super().__init__()
                self.touch = SimpleNamespace(start=lambda:None,stop=lambda:None,ping=lambda:None)
        guard = InputGuard(Guard())
        with CursorWindow() as app:
            worker = None
            try:
                guard.pause('native-test',lambda:True)
                worker = TouchWorker()
                x,y = app.initial['x'], app.initial['y']
                for index in range(3):
                    tap_x = x + index*.02
                    worker.call('frame',[point(x=tap_x,y=y)])
                    worker.call('frame',[point(state='up',x=tap_x,y=y)])
                    time.sleep(.03)  # Separate OS gesture timestamps, unlike a synthetic burst in one tick.
                deadline = time.monotonic()+2
                while True:
                    events = app.report('probe')['mouse_taps']
                    if sum(p['message']==0x202 for p in events)>=3:break
                    if time.monotonic()>=deadline:self.fail('Native touch promotion was blocked by local-input hook: '+str((events,seen[-20:],list(injection._promotions)[-8:])))
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
                worker.call('frame', [point(state='move',x=x,y=y),point(1,x=x+.04,y=y)])
                worker.call('frame', [point(state='move',x=x+.02,y=y),point(1,'move',x=x+.06,y=y)])
                worker.process.stdin.close()  # Abrupt parent EOF, without an explicit cancel command.
                worker.close();worker = None
                deadline = time.monotonic() + 3
                while True:
                    events = app.report('probe')['touch_events']
                    if len({p['id'] for p in events if p['flags'] & 4})==2:break
                    if time.monotonic() >= deadline:self.fail('App did not receive native touch DOWN/UP: ' + str(events))
                    time.sleep(.02)
                self.assertTrue(any(p['flags'] & 2 for p in events), events)
                self.assertTrue(any(p['flags'] & 1 for p in events), events)
                self.assertEqual(2,len({p['id'] for p in events if p['flags'] & 2}),events)
            finally:
                if worker:worker.close()


if __name__ == '__main__':unittest.main()
