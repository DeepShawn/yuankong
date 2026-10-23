"""Windows 原生功能测试。

重要：主机端已按要求取消所有授权确认与可见提示窗口，
所以这里不再测试“弹窗并点允许”，而是验证：
  * 源码中不存在任何会话窗口 / 确认弹窗
  * ConsentSession 创建即为已授权状态且无时长限制
  * request_consent 恒为放行，且不创建窗口

需要真实 Windows 桌面的用例标记为 skipUnless，在 Linux 容器里自动跳过。
"""
import ctypes
import inspect
import os
import sys
import time
import unittest
from types import SimpleNamespace

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "windows"))
from remote_features import ConsentSession, WindowsBackend, RemoteFeatures


class NoConsentUiTest(unittest.TestCase):
    """无论平台都能跑的静态/行为断言。"""

    def test_show_session_window_is_gone(self):
        import remote_features
        self.assertFalse(
            hasattr(remote_features, "show_session_window"),
            "可见授权窗口必须已被移除",
        )

    def test_source_has_no_session_window_creation(self):
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "windows", "remote_features.py")
        with open(path, encoding="utf-8") as f:
            src = f.read()
        for banned in ("RegisterClassW", "CreateWindowExW", "ShowWindow", "GetMessageW"):
            self.assertNotIn(banned, src, "不应再创建任何可见窗口：%s" % banned)

    def test_session_never_waits_for_approval(self):
        """创建会话必须立即返回，不存在“等待本机确认”的阻塞。"""
        seen = []

        class Backend(WindowsBackend):
            def __init__(self):
                super().__init__("unused.exe")

            def request_consent(self, session):
                seen.append(session)
                return super().request_consent(session)

        backend = Backend()
        remote = RemoteFeatures(Exception, __import__("logging").getLogger("t"), "unused.exe", backend)
        state = SimpleNamespace(peer="127.0.0.1", client_id="abcdef123456", remote_sessions={})
        t0 = time.monotonic()
        r = remote.session({"scope": "screen"}, state)
        elapsed = time.monotonic() - t0
        self.assertTrue(r["sid"])
        self.assertIsNone(r["expires_in"])
        self.assertLess(elapsed, 0.3, "不应有任何确认等待")
        # 更彻底：session() 现在根本不会再走 request_consent 这条路
        self.assertEqual(0, len(seen), "不应存在任何确认调用")
        sid = r["sid"]
        self.assertIn(sid, state.remote_sessions)
        self.assertTrue(state.remote_sessions[sid].alive())

    def test_request_consent_always_true(self):
        backend = WindowsBackend("unused.exe")
        s = ConsentSession("screen", "peer")
        self.assertTrue(backend.request_consent(s))
        # 调用后不应被标记停止
        self.assertTrue(s.alive())

    def test_consent_session_has_no_duration_cap(self):
        s = ConsentSession("screen", "peer")
        self.assertIsNone(s.duration)
        s.created -= 24 * 3600
        self.assertTrue(s.alive(), "不应再有 300 秒/任何时长上限")


@unittest.skipUnless(os.name == "nt", "Requires a Windows desktop")
class WindowsFeaturesTest(unittest.TestCase):
    """真实原生能力，仅在 Windows 桌面执行。"""

    def setUp(self):
        here = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "windows")
        self.capture = os.path.join(here, "LanCmdCapture.exe")
        self.backend = WindowsBackend(self.capture)

    def test_native_capture_jpeg_and_camera_enumeration(self):
        if not os.path.isfile(self.capture):
            self.skipTest("缺少 LanCmdCapture.exe")
        raw = self.backend.capture("screen")
        self.assertTrue(raw.startswith(b"\xff\xd8"), "屏幕截图应为 JPEG")
        self.backend.cameras()  # 不校验数量，仅要求不抛异常

    def test_sendinput_layout_unicode(self):
        session = ConsentSession("input", "peer")
        # 只验证调用路径可用，不实际输入中文到当前光标
        self.backend.keys(["ENTER"], "", allowed=session.alive)


if __name__ == "__main__":
    unittest.main()
