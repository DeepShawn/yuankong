package com.aicode.lancmd

import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.TCP_PORT
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64

/**
 * 分块传输与进程管理，走真实接收端。
 * 接收端未启动时自动跳过。
 */
class TransferIntegrationTest {

    private fun reachable(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", TCP_PORT), 800) }
        true
    } catch (e: Exception) {
        false
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun chunkedUploadAndDownloadRoundTrip() = runBlocking {
        assumeTrue("接收端未运行，跳过", reachable())
        val c = AgentClient("127.0.0.1", TCP_PORT)
        c.connect()
        try {
            val hs = c.handshake("jvm-transfer", "JUnit", "")
            assumeTrue("握手应成功", hs.optBoolean("ok"))

            val dir = System.getProperty("java.io.tmpdir") + "/lancmd-jvm-" + System.nanoTime()
            File(dir).mkdirs()
            val remote = "$dir/roundtrip.bin"

            // 1.2MB 随机数据，跨多个 512KB 分块
            val payload = ByteArray(1229 * 1024).also { java.util.Random(42).nextBytes(it) }
            val digest = sha256(payload)

            val begin = c.call(
                "upload",
                JSONObject().put("action", "begin").put("path", remote).put("size", payload.size)
            )
            assertTrue("上传应开始: ${begin.optString("error")}", begin.optBoolean("ok"))
            val sid = begin.optString("sid")
            val chunk = begin.optInt("chunk", 512 * 1024)

            var offset = 0
            var seq = 0
            while (offset < payload.size) {
                val endIdx = minOf(offset + chunk, payload.size)
                val piece = payload.copyOfRange(offset, endIdx)
                val r = c.call(
                    "upload",
                    JSONObject()
                        .put("action", "chunk")
                        .put("sid", sid)
                        .put("seq", seq)
                        .put("data", Base64.getEncoder().encodeToString(piece))
                )
                assertTrue("分块 $seq 应成功: ${r.optString("error")}", r.optBoolean("ok"))
                offset = endIdx
                seq++
            }
            assertTrue("应至少分成 2 块", seq >= 2)

            val finish = c.call(
                "upload",
                JSONObject().put("action", "end").put("sid", sid).put("sha256", digest)
            )
            assertTrue("收尾应成功: ${finish.optString("error")}", finish.optBoolean("ok"))
            assertTrue("落盘内容应一致", File(remote).readBytes().contentEquals(payload))

            // 2. 分块拉回
            val db = c.call("download", JSONObject().put("action", "begin").put("path", remote))
            assertTrue("下载应开始: ${db.optString("error")}", db.optBoolean("ok"))
            val dsid = db.optString("sid")
            val total = db.optLong("size", 0)
            val out = java.io.ByteArrayOutputStream()
            var rounds = 0
            while (true) {
                val r = c.call(
                    "download",
                    JSONObject()
                        .put("action", "chunk")
                        .put("sid", dsid)
                        .put("offset", out.size().toLong())
                        .put("len", chunk)
                )
                assertTrue("下载分块应成功: ${r.optString("error")}", r.optBoolean("ok"))
                val raw = Base64.getDecoder().decode(r.optString("data"))
                if (raw.isEmpty()) break
                out.write(raw)
                rounds++
                if (r.optBoolean("eof") || out.size().toLong() >= total) break
            }
            c.call("download", JSONObject().put("action", "end").put("sid", dsid))
            assertTrue("应至少拉回 2 块", rounds >= 2)
            val received = out.toByteArray()
            assertEquals("字节数应一致", payload.size, received.size)
            assertTrue("md5/sha 应一致", received.contentEquals(payload))

            c.call("rm", JSONObject().put("path", remote))
            File(dir).delete()
        } finally {
            c.close()
        }
        Unit
    }

    @Test
    fun processListAndGuard() = runBlocking {
        assumeTrue("接收端未运行，跳过", reachable())
        val c = AgentClient("127.0.0.1", TCP_PORT)
        c.connect()
        try {
            val hs = c.handshake("jvm-ps", "JUnit", "")
            assumeTrue(hs.optBoolean("ok"))

            val ps = c.call("ps")
            assertTrue("应返回进程列表: ${ps.optString("error")}", ps.optBoolean("ok"))
            val items = ps.optJSONArray("items")
            assertTrue("列表不应为空", items != null && items.length() > 0)
            val selfPid = ps.optInt("self_pid", -1)
            assertTrue("应回传接收端自身 PID", selfPid > 0)

            // 列表里确实有这个 PID
            var found = false
            for (i in 0 until (items?.length() ?: 0)) {
                if (items!!.getJSONObject(i).optInt("pid", -1) == selfPid) found = true
            }
            assertTrue("自身进程应在列表中", found)

            // 结束自身必须被拒
            val guard = c.call(
                "kill",
                JSONObject().put("pid", selfPid).put("force", true)
            )
            assertFalse("不应允许结束接收端自身", guard.optBoolean("ok"))

            // 不存在的 PID 应报错
            val ghost = c.call("kill", JSONObject().put("pid", 999999).put("force", true))
            assertFalse("不存在的进程应报错", ghost.optBoolean("ok"))

            // 连接仍然可用（说明保护生效）
            assertTrue("守护后连接应仍可用", c.call("ping").optBoolean("ok"))
        } finally {
            c.close()
        }
        Unit
    }
}
