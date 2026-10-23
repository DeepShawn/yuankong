"""Session isolation, local revocation, expiry, keyboard bounds and messaging."""
import base64
import logging
import os
import sys
import time
import unittest
from types import SimpleNamespace

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "windows"))
from remote_features import ConsentSession, RemoteFeatures


class RpcError(Exception):
    def __init__(self, message, code=1):
        super().__init__(message)
        self.code = code


class Backend:
    def __init__(self):
        self.allow = True
        self.sessions = []
        self.captured = []
        self.sent = []
        self.messages = []
        self.on_capture = None

    def request_consent(self, session):
        self.sessions.append(session)
        if self.allow:
            session.approve()
        return self.allow

    def capture(self, scope, device):
        self.captured.append((scope, device))
        if self.on_capture:
            self.on_capture()
        return b"\xff\xd8test-jpeg\xff\xd9"

    def cameras(self):
        return [{"id": 0, "name": "Test camera"}]

    def keys(self, keys, text, allowed):
        assert allowed()
        self.sent.append((keys, text))

    def message(self, title, text):
        self.messages.append((title, text))
        return True


class RemoteFeaturesTest(unittest.TestCase):
    def setUp(self):
        self.backend = Backend()
        self.remote = RemoteFeatures(RpcError, logging.getLogger("test"), "unused.exe", self.backend)
        self.state = self.state_for("owner")

    @staticmethod
    def state_for(client):
        return SimpleNamespace(authorized=True, peer="127.0.0.1", client_id=client)

    def begin(self, scope="screen"):
        return self.remote.session({"scope": scope}, self.state)["sid"]

    def test_unpaired_client_cannot_prompt_or_capture(self):
        self.state.authorized = False
        with self.assertRaises(RpcError) as e:
            self.begin()
        self.assertEqual(401, e.exception.code)
        self.assertFalse(self.backend.sessions)

    def test_auto_accept_cannot_bypass_local_media_consent(self):
        self.backend.allow = False
        self.state.auto_accept = True
        with self.assertRaises(RpcError) as e:
            self.remote.session({"scope": "camera", "auto_accept": True}, self.state)
        self.assertEqual(403, e.exception.code)
        self.assertFalse(self.state.remote_sessions)
        self.assertFalse(self.backend.captured)

    def test_screen_session_returns_only_approved_frame(self):
        sid = self.begin()
        result = self.remote.frame({"sid": sid}, self.state, "screen")
        self.assertEqual(b"\xff\xd8test-jpeg\xff\xd9", base64.b64decode(result["data"]))
        self.assertEqual([("screen", 0)], self.backend.captured)

    def test_approval_is_scoped_to_camera_or_screen(self):
        sid = self.begin("screen")
        with self.assertRaises(RpcError):
            self.remote.frame({"sid": sid}, self.state, "camera")
        self.assertFalse(self.backend.captured)

    def test_session_cannot_be_reused_by_another_connection(self):
        sid = self.begin()
        with self.assertRaises(RpcError):
            self.remote.frame({"sid": sid}, self.state_for("other"), "screen")
        self.assertFalse(self.backend.captured)

    def test_rehandshake_identity_change_invalidates_session(self):
        sid = self.begin()
        self.state.client_id = "other"
        with self.assertRaises(RpcError):
            self.remote.frame({"sid": sid}, self.state, "screen")

    def test_local_stop_blocks_subsequent_frames(self):
        sid = self.begin()
        self.backend.sessions[0].close()
        with self.assertRaises(RpcError):
            self.remote.frame({"sid": sid}, self.state, "screen")
        self.assertFalse(self.backend.captured)

    def test_frame_in_flight_is_discarded_after_local_stop(self):
        sid = self.begin()
        self.backend.on_capture = self.backend.sessions[0].close
        with self.assertRaises(RpcError):
            self.remote.frame({"sid": sid}, self.state, "screen")
        self.assertEqual(1, len(self.backend.captured))

    def test_five_minute_expiry_requires_new_local_consent(self):
        sid = self.begin()
        self.backend.sessions[0].expires = time.monotonic() - 1
        with self.assertRaises(RpcError):
            self.remote.frame({"sid": sid}, self.state, "screen")
        fresh = self.begin()
        self.assertNotEqual(sid, fresh)
        self.assertEqual(2, len(self.backend.sessions))

    def test_idle_phone_cannot_leave_capture_authorized(self):
        sid = self.begin()
        self.backend.sessions[0].last_active = time.monotonic() - 21
        with self.assertRaises(RpcError):
            self.remote.frame({"sid": sid}, self.state, "screen")

    def test_keepalive_does_not_extend_maximum_duration(self):
        sid = self.begin("input")
        session = self.backend.sessions[0]
        expires = session.expires
        self.remote.session({"action": "keepalive", "sid": sid}, self.state)
        session.approve()
        self.assertEqual(expires, session.expires)

    def test_cancelled_prompt_cannot_later_grant_permission(self):
        session = ConsentSession("screen", "local")
        session.close()
        session.approve()
        self.assertFalse(session.alive())
        self.assertFalse(session.approved.is_set())

    def test_closing_connection_closes_all_local_controls(self):
        self.begin("screen")
        self.begin("camera")
        self.begin("input")
        self.remote.close(self.state)
        self.assertFalse(self.state.remote_sessions)
        self.assertTrue(all(s.stopped.is_set() for s in self.backend.sessions))

    def test_remote_stop_is_idempotent(self):
        sid = self.begin()
        for _ in range(2):
            self.assertTrue(self.remote.session({"action": "end", "sid": sid}, self.state)["stopped"])
        self.assertTrue(self.backend.sessions[0].stopped.is_set())

    def test_camera_list_requires_camera_permission(self):
        with self.assertRaises(RpcError):
            self.remote.frame({"action": "list"}, self.state, "camera")
        sid = self.begin("camera")
        self.assertEqual(0, self.remote.frame({"sid": sid, "action": "list"}, self.state, "camera")["devices"][0]["id"])

    def test_keyboard_needs_its_own_approval(self):
        sid = self.begin("screen")
        with self.assertRaises(RpcError):
            self.remote.keyboard({"sid": sid, "keys": ["ENTER"]}, self.state)
        self.assertFalse(self.backend.sent)

    def test_unicode_text_and_key_chords(self):
        sid = self.begin("input")
        self.remote.keyboard({"sid": sid, "keys": ["ctrl", "V"]}, self.state)
        self.remote.keyboard({"sid": sid, "text": "你好 🌏"}, self.state)
        self.assertEqual([(["CTRL", "V"], ""), ([], "你好 🌏")], self.backend.sent)

    def test_bad_input_payloads_are_rejected_before_sending(self):
        sid = self.begin("input")
        for payload in [{"keys": "ENTER"}, {"keys": ["UNKNOWN"]}, {"keys": ["CTRL", "CTRL"]},
                        {"keys": ["ENTER"], "text": "both"}, {"text": "x" * 1001}, {"text": "\x00"}, {}]:
            with self.subTest(payload=payload), self.assertRaises(RpcError):
                self.remote.keyboard(dict(payload, sid=sid), self.state)
        self.assertFalse(self.backend.sent)

    def test_message_limits_and_rate_limit(self):
        for payload in [{"text": ""}, {"text": "x" * 2001}, {"text": "hello", "title": "x" * 61}, {"text": "a\x00b"}]:
            with self.assertRaises(RpcError):
                self.remote.message(payload, self.state)
        self.remote.message({"text": "Hello"}, self.state)
        with self.assertRaises(RpcError):
            self.remote.message({"text": "Too soon"}, self.state)
        self.assertEqual([("手机消息", "Hello")], self.backend.messages)


if __name__ == "__main__":
    unittest.main()
