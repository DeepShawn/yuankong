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
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 与本机运行中的接收端做真实握手/执行/断线重连验证。
 * 接收端未启动时自动跳过（不影响常规单测）。
 */
class AgentClientIntegrationTest {

    private fun reachable(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", TCP_PORT), 800) }
        true
    } catch (e: Exception) {
        false
    }

    private fun connect(token: String = ""): AgentClient {
        val c = AgentClient("127.0.0.1", TCP_PORT)
        c.connect()
        return c
    }

    @Test
    fun handshakeAndExec() = runBlocking {
        assumeTrue("接收端未运行，跳过集成测试", reachable())
        val client = connect()
        try {
            val hs = client.handshake("jvm-test-client", "JUnit", "")
            assertTrue("握手应成功", hs.optBoolean("ok"))
            assertTrue("应拿到 token", hs.optString("token").isNotEmpty())
            assertTrue("应返回 agent_id", hs.optString("agent_id").isNotEmpty())

            val info = client.call("info")
            assertTrue(info.optBoolean("ok"))
            assertTrue(info.optString("host").isNotEmpty())

            val shell = if (info.optString("os").startsWith("Windows")) "cmd" else "sh"
            val exec = client.call(
                "exec",
                JSONObject().put("shell", shell).put("line", "echo kotlin-client-ok")
            )
            assertTrue(exec.optBoolean("ok"))
            assertEquals("kotlin-client-ok", exec.optString("out").trim())

            val bad = client.call("no_such_command")
            assertFalse("未知命令应返回失败", bad.optBoolean("ok"))
        } finally {
            client.close()
        }
    }

    @Test
    fun tokenReconnectsWithoutApproval() = runBlocking {
        assumeTrue("接收端未运行，跳过集成测试", reachable())
        val first = connect()
        val hs = first.handshake("jvm-reconnect", "JUnit", "")
        assumeTrue("首次握手需成功", hs.optBoolean("ok"))
        val token = hs.optString("token")
        val agentId = hs.optString("agent_id")
        first.close()

        val second = connect()
        try {
            val hs2 = second.handshake("jvm-reconnect", "JUnit", token)
            assertTrue("带 token 应免确认重连", hs2.optBoolean("ok"))
            assertFalse("重连不应走首次配对", hs2.optBoolean("fresh"))
            assertEquals("仍是同一台电脑", agentId, hs2.optString("agent_id"))
            assertTrue("重连后命令可用", second.call("ping").optBoolean("ok"))
        } finally {
            second.close()
        }
    }

    @Test
    fun pingKeepsConnectionAlive() = runBlocking {
        assumeTrue("接收端未运行，跳过集成测试", reachable())
        val client = connect()
        try {
            val hs = client.handshake("jvm-ping", "JUnit", "")
            assumeTrue(hs.optBoolean("ok"))
            repeat(5) {
                val r = client.call("ping")
                assertTrue("心跳应持续成功", r.optBoolean("ok"))
                assertTrue("应回传时间戳", r.optLong("ts", 0) > 0)
            }
            assertFalse("连接应仍然打开", client.closed)
        } finally {
            client.close()
        }
    }
}
