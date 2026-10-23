"""Regression checks for upload integrity and overwrite protection."""

import base64
import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "windows"))
import lan_command_agent as agent


class FileOperationsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "file.bin"
        self.state = agent.ClientState(None, ("127.0.0.1", 12345))
        self.addCleanup(self.state.close_transfers)

    def begin(self, size=3, overwrite=True):
        return agent.cmd_upload({"action": "begin", "path": str(self.path),
                                 "size": size, "overwrite": overwrite}, self.state)["sid"]

    def chunk(self, sid, data=b"new", seq=0):
        return agent.cmd_upload({"action": "chunk", "sid": sid, "seq": seq,
                                 "data": base64.b64encode(data).decode("ascii")}, self.state)

    def end(self, sid):
        return agent.cmd_upload({"action": "end", "sid": sid}, self.state)

    def assert_clean(self):
        self.assertFalse(self.state.uploads)
        self.assertEqual([], list(Path(self.directory.name).glob("*.lancmd-part")))

    def test_no_overwrite_when_target_appears_during_upload(self):
        sid = self.begin(overwrite=False)
        self.chunk(sid)
        self.path.write_bytes(b"keep")
        with self.assertRaises(agent.RpcError):
            self.end(sid)
        self.assertEqual(b"keep", self.path.read_bytes())
        self.assert_clean()

    def test_two_non_overwriting_uploads_commit_only_once(self):
        first = self.begin(overwrite=False)
        second = self.begin(overwrite=False)
        self.chunk(first, b"one")
        self.chunk(second, b"two")
        self.end(first)
        with self.assertRaises(agent.RpcError):
            self.end(second)
        self.assertEqual(b"one", self.path.read_bytes())
        self.assert_clean()

    def test_explicit_overwrite_still_replaces_target(self):
        self.path.write_bytes(b"old")
        sid = self.begin(overwrite=True)
        self.chunk(sid)
        result = self.end(sid)
        self.assertEqual(b"new", self.path.read_bytes())
        self.assertEqual(hashlib.sha256(b"new").hexdigest(), result["sha256"])
        self.assert_clean()

    def test_commit_failure_cleans_closed_session_and_temporary_file(self):
        sid = self.begin()
        self.chunk(sid)
        with mock.patch.object(agent.os, "replace", side_effect=PermissionError("busy")):
            with self.assertRaises(OSError):
                self.end(sid)
        self.assertFalse(self.path.exists())
        self.assert_clean()

    def test_invalid_base64_does_not_silently_corrupt_small_upload(self):
        with self.assertRaises(agent.RpcError):
            agent.cmd_put(str(self.path), "bm!V3")
        self.assertFalse(self.path.exists())

    def test_invalid_base64_chunk_can_be_retried_without_progress(self):
        sid = self.begin()
        with self.assertRaises(agent.RpcError):
            agent.cmd_upload({"action": "chunk", "sid": sid,
                              "data": "bm!V3", "seq": 0}, self.state)
        self.chunk(sid)
        self.end(sid)
        self.assertEqual(b"new", self.path.read_bytes())
        self.assert_clean()

    def test_oversized_chunk_is_rejected_before_writing(self):
        sid = self.begin()
        with self.assertRaises(agent.RpcError):
            self.chunk(sid, b"toolarge")
        session = self.state.uploads[sid]
        self.assertEqual(0, session["received"])
        self.assertEqual(0, session["seq"])
        self.assertEqual(0, session["fh"].tell())
        self.chunk(sid)
        self.end(sid)
        self.assertEqual(b"new", self.path.read_bytes())
        self.assert_clean()

    def test_small_upload_no_overwrite_is_atomic(self):
        # Simulate the target being created after the existence check.
        self.path.write_bytes(b"keep")
        original_exists = agent.os.path.exists
        with mock.patch.object(agent.os.path, "exists", side_effect=lambda path:
                               False if str(path) == str(self.path) else original_exists(path)):
            with self.assertRaises(agent.RpcError):
                agent.cmd_put(str(self.path), "bmV3", overwrite=False)
        self.assertEqual(b"keep", self.path.read_bytes())


if __name__ == "__main__":
    unittest.main()
