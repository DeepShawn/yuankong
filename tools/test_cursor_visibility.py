"""Cursor ownership and restoration; never change the test machine's cursors."""
import io
import logging
from pathlib import Path
import queue
import sys
import threading
import time
from types import SimpleNamespace
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'windows'))
from cursor_visibility import CursorVisibility, NativeCursors, recovery_worker
from remote_features import RemoteFeatures


class Stream:
    def __init__(self): self.lines = queue.Queue()
    def __iter__(self): return self
    def __next__(self):
        line = self.lines.get()
        if line is None: raise StopIteration
        return line


class Native:
    def __init__(self): self.hidden = threading.Event(); self.restored = threading.Event(); self.fail = False
    def hide(self):
        self.hidden.set()
        if self.fail: raise RuntimeError('partial change')
    def restore(self): self.restored.set()


class FakeWorker:
    def __init__(self): self.closed=False; self.pings=0
    def ping(self): self.pings+=1
    def close(self): self.closed=True


class CursorTest(unittest.TestCase):
    def wait(self, check):
        end=time.monotonic()+2
        while time.monotonic()<end:
            if check():return
            time.sleep(.01)
        self.fail('Cursor was not recovered')

    def test_worker_eof_and_heartbeat_timeout_restore_cursors(self):
        for timeout in (False, True):
            with self.subTest(timeout=timeout):
                stream=Stream();native=Native();output=io.StringIO()
                thread=threading.Thread(target=recovery_worker,args=(native,stream,output,.15),daemon=True);thread.start()
                self.assertTrue(native.hidden.wait(1))
                if not timeout:stream.lines.put(None)
                thread.join(2)
                self.assertFalse(thread.is_alive());self.assertTrue(native.restored.is_set())
                stream.lines.put(None)

    def test_partial_hide_failure_restores_and_reports_failure(self):
        stream=Stream();native=Native();native.fail=True;output=io.StringIO()
        self.assertEqual(0,recovery_worker(native,stream,output))
        self.assertTrue(native.restored.is_set());self.assertIn('partial change',output.getvalue())
        stream.lines.put(None)

    def test_one_session_releasing_cannot_restore_another_and_stale_owner_expires(self):
        workers=[]
        def create():workers.append(FakeWorker());return workers[-1]
        control=CursorVisibility(create,interval=.01);alive=[True]
        control.set_hidden('a',True,lambda:True)
        control.set_hidden('b',True,lambda:alive[0])
        control.set_hidden('a',False,lambda:False)
        self.assertFalse(workers[0].closed)
        alive[0]=False;self.wait(lambda:workers[0].closed)
        self.assertIsNone(control.worker)

    def test_scope_boolean_validation_and_disconnect_cleanup(self):
        class Error(Exception):pass
        backend=SimpleNamespace(cursor=mock.Mock(return_value={'hidden':True}),release_cursor=mock.Mock())
        remote=RemoteFeatures(Error,logging.getLogger('cursor-test'),'unused',backend)
        state=SimpleNamespace(client_id='phone',peer='127.0.0.1')
        sid=remote.session({'scope':'input'},state)['sid'];screen=remote.session({'scope':'screen'},state)['sid']
        for q in ({'sid':screen,'hidden':True},{'sid':sid,'hidden':1},{'sid':sid,'hidden':'true'}):
            with self.assertRaises(Error):remote.cursor(q,state)
        backend.cursor.assert_not_called()
        remote.cursor({'sid':sid,'hidden':True},state)
        owner,hidden,allowed=backend.cursor.call_args.args
        self.assertEqual(sid,owner);self.assertTrue(hidden);self.assertTrue(allowed())
        remote.session({'action':'end','sid':sid},state)
        self.assertFalse(allowed());backend.release_cursor.assert_called_once_with(sid)
        remote.close(state)

    def test_native_handles_are_not_truncated_and_partial_changes_reload_current_scheme(self):
        user=SimpleNamespace(**{name:mock.Mock() for name in ('CreateCursor','SetSystemCursor','DestroyCursor','SystemParametersInfoW')})
        user.CreateCursor.return_value=0x1234567887654321
        user.SetSystemCursor.side_effect=[True,False]
        user.SystemParametersInfoW.return_value=True
        native=NativeCursors(user)
        with self.assertRaisesRegex(RuntimeError,'Windows'):native.hide()
        user.DestroyCursor.assert_called_once_with(0x1234567887654321)
        native.restore();user.SystemParametersInfoW.assert_called_once_with(0x57,0,None,0)
        native.restore();self.assertEqual(1,user.SystemParametersInfoW.call_count)
        import ctypes
        self.assertEqual(ctypes.c_void_p,user.CreateCursor.restype)


if __name__=='__main__':unittest.main()
