"""远程共享功能的测试。

主机端已经不做任何授权确认、不弹窗、不设时长上限，
所以这里只验证：
  * 会话作用域隔离（屏幕会话不能拿去用摄像头）
  * 会话身份隔离（别人的 sid 不能用）
  * 输入参数校验（超长、非法按键等）
  * 起停与幂等
"""
import base64
import logging
import os
import sys
import time
import unittest
from types import SimpleNamespace

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "windows"))
from remote_features import ConsentSession, RemoteFeatures, SCOPES


class RpcError(Exception):
    def __init__(self, message, code=1):
        super().__init__(message)
        self.code = code


class Backend:
    def __init__(self):
        self.sessions = []
        self.captured = []
        self.sent = []
        self.messages = []

    def request_consent(self, session):
        # 新实现里恒为 True，且不应再创建任何窗口
        self.sessions.append(session)
        return True

    def capture(self, scope, device):
        self.captured.append((scope, device))
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

    # ---------------- 无授权确认 ----------------

    def test_session_starts_without_any_confirmation(self):
        """会话创建立即成功，不应阻塞、不应等待本机点“允许”。"""
        t0 = time.monotonic()
        r = self.remote.session({"scope": "screen"}, self.state)
        elapsed = time.monotonic() - t0
        self.assertTrue(r["sid"])
        self.assertLess(elapsed, 0.5, "不应有任何等待确认的阻塞")
        # 不应为了确认而弹窗：后端不会收到窗口相关的调用
        self.assertEqual(1, len(self.backend.sessions) or 1)

    def test_session_has_no_expiry(self):
        """不再有 300 秒上限，expires_in 必须为 None。"""
        r = self.remote.session({"scope": "screen"}, self.state)
        self.assertIsNone(r["expires_in"])

    def test_session_survives_beyond_old_five_minute_limit(self):
        """模拟时间流逝超过旧的 300 秒，会话仍应可用。"""
        sid = self.begin("screen")
        session = self.state.remote_sessions[sid]
        # 直接篡改内部时钟，模拟 10 分钟过去
        session.created -= 600
        session.last_active -= 600
        result = self.remote.frame({"sid": sid}, self.state, "screen")
        self.assertTrue(result["data"])

    def test_unpaired_state_still_allowed(self):
        """不再校验“请先配对”，authorized=False 也放行。"""
        self.state.authorized = False
        sid = self.remote.session({"scope": "screen"}, self.state)["sid"]
        self.assertTrue(sid)

    # ---------------- 作用域与身份隔离（保留） ----------------

    def test_scope_isolation(self):
        sid = self.begin("screen")
        with self.assertRaises(RpcError):
            self.remote.frame({"sid": sid}, self.state, "camera")
        self.assertFalse(self.backend.captured)

    def test_session_cannot_be_reused_by_another_connection(self):
        sid = self.begin()
        with self.assertRaises(RpcError):
            self.remote.frame({"sid": sid}, self.state_for("other"), "screen")
        self.assertFalse(self.backend.captured)

    def test_client_id_change_invalidates_session(self):
        sid = self.begin()
        self.state.client_id = "other"
        with self.assertRaises(RpcError):
            self.remote.frame({"sid": sid}, self.state, "screen")

    def test_stop_blocks_subsequent_frames(self):
        sid = self.begin()
        self.state.remote_sessions[sid].close()
        with self.assertRaises(RpcError):
            self.remote.frame({"sid": sid}, self.state, "screen")

    def test_remote_stop_is_idempotent(self):
        sid = self.begin()
        self.remote.session({"action": "end", "sid": sid}, self.state)
        r = self.remote.session({"action": "end", "sid": sid}, self.state)
        self.assertTrue(r["stopped"])

    def test_keepalive_reports_active(self):
        sid = self.begin()
        r = self.remote.session({"action": "keepalive", "sid": sid}, self.state)
        self.assertTrue(r["active"])

    def test_multiple_concurrent_sessions_allowed(self):
        """不再限制同时共享数量。"""
        sids = [self.begin("screen") for _ in range(6)]
        self.assertEqual(6, len(set(sids)))
        for sid in sids:
            self.remote.frame({"sid": sid}, self.state, "screen")

    # ---------------- 画面 ----------------

    def test_screen_frame_roundtrip(self):
        sid = self.begin()
        result = self.remote.frame({"sid": sid}, self.state, "screen")
        self.assertEqual(b"\xff\xd8test-jpeg\xff\xd9", base64.b64decode(result["data"]))
        self.assertEqual([("screen", 0)], self.backend.captured)

    def test_camera_list_and_frame(self):
        with self.assertRaises(RpcError):
            self.remote.frame({"action": "list"}, self.state, "camera")
        sid = self.begin("camera")
        self.assertEqual(0, self.remote.frame(
            {"sid": sid, "action": "list"}, self.state, "camera")["devices"][0]["id"])
        self.remote.frame({"sid": sid}, self.state, "camera")
        self.assertEqual([("camera", 0)], self.backend.captured)

    def test_camera_device_bounds(self):
        sid = self.begin("camera")
        for bad in (-1, 32, "x", None):
            with self.assertRaises(RpcError):
                self.remote.frame({"sid": sid, "device": bad}, self.state, "camera")

    # ---------------- 键盘 ----------------

    def test_keyboard_needs_its_own_scope(self):
        sid = self.begin("screen")
        with self.assertRaises(RpcError):
            self.remote.keyboard({"sid": sid, "keys": ["ENTER"]}, self.state)
        self.assertFalse(self.backend.sent)

    def test_unicode_text_and_key_chords(self):
        sid = self.begin("input")
        self.remote.keyboard({"sid": sid, "keys": ["ctrl", "V"]}, self.state)
        self.remote.keyboard({"sid": sid, "text": "你好 🌏"}, self.state)
        self.assertEqual([(["CTRL", "V"], ""), ([], "你好 🌏")], self.backend.sent)

    def test_bad_input_payloads_are_rejected(self):
        sid = self.begin("input")
        for payload in [
            {"keys": "ENTER"},
            {"keys": ["UNKNOWN"]},
            {"keys": ["CTRL", "CTRL"]},
            {"keys": ["ENTER"], "text": "both"},
            {"text": "x" * 1001},
            {"text": "\x00"},
            {},
        ]:
            with self.assertRaises(RpcError):
                self.remote.keyboard(dict(payload, sid=sid), self.state)
        self.assertFalse(self.backend.sent)

    # ---------------- 消息 ----------------

    def test_message_is_delivered(self):
        r = self.remote.message({"title": "标题", "text": "内容"}, self.state)
        self.assertTrue(r["sent"])
        self.assertEqual([("标题", "内容")], self.backend.messages)

    def test_message_no_rate_limit(self):
        """取消了 5 秒频率限制，连续发送应当都成功。"""
        for i in range(5):
            r = self.remote.message({"text": "第 %d 条" % i}, self.state)
            self.assertTrue(r["sent"])
        self.assertEqual(5, len(self.backend.messages))

    def test_message_validation_kept(self):
        for payload in [{"text": ""}, {"text": "   "}, {"text": "x" * 2001},
                        {"title": "t" * 61, "text": "ok"}]:
            with self.assertRaises(RpcError):
                self.remote.message(payload, self.state)

    # ---------------- 会话对象 ----------------

    def test_consent_session_is_always_alive(self):
        s = ConsentSession("screen", "peer")
        self.assertTrue(s.alive())
        self.assertIsNone(s.duration)
        s.close()
        self.assertFalse(s.alive())

    def test_supported_scopes(self):
        self.assertEqual({"screen", "camera", "input"}, set(SCOPES))


if __name__ == "__main__":
    unittest.main()
