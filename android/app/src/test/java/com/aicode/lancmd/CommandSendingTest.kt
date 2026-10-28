package com.aicode.lancmd

import android.app.Application
import android.os.Looper
import android.os.NetworkOnMainThreadException
import com.aicode.lancmd.net.AgentClient
import com.aicode.lancmd.net.ConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CommandSendingTest {
    /** Real TCP frames; enforce Android's main-thread network restriction on the JVM. */
    private class Agent(private val responseGate: CountDownLatch? = null) : Closeable {
        private val server = ServerSocket(0)
        val received = CopyOnWriteArrayList<JSONObject>()
        val receivedRequest = CountDownLatch(1)
        val client = AgentClient("127.0.0.1", server.localPort)
        private val worker = Thread {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()
                while (true) {
                    val line = reader.readLine() ?: break
                    val req = JSONObject(line)
                    received.add(req)
                    receivedRequest.countDown()
                    if (req.optString("cmd") == "disconnect") break
                    responseGate?.await(5, TimeUnit.SECONDS)
                    writer.write(JSONObject().put("id", req.getInt("id"))
                        .put("ok", true).put("out", "sent-from-ui-ok").toString() + "\n")
                    writer.flush()
                }
            }
        }.apply { isDaemon = true; start() }

        init {
            client.connect()
            val field = AgentClient::class.java.getDeclaredField("writer").apply { isAccessible = true }
            val actual = field.get(client) as BufferedWriter
            field.set(client, object : BufferedWriter(actual) {
                override fun write(s: String, off: Int, len: Int) {
                    checkThread()
                    super.write(s, off, len)
                }
                override fun flush() {
                    checkThread()
                    super.flush()
                }
                private fun checkThread() {
                    if (Thread.currentThread() == Looper.getMainLooper().thread) {
                        throw NetworkOnMainThreadException()
                    }
                }
            })
        }

        override fun close() {
            responseGate?.countDown()
            client.close()
            server.close()
            worker.join(2000)
        }
    }

    @Test
    fun clickSendWritesCommandAndDisplaysResponse() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        ConnectionManager.stop()
        // 等旧重连任务退出，避免它在取消收尾时清空测试连接。
        val loopField = ConnectionManager::class.java.getDeclaredField("loopJob")
            .apply { isAccessible = true }
        runBlocking { (loopField.get(ConnectionManager) as? Job)?.join() }
        Agent().use { agent ->
            val clientField = ConnectionManager::class.java.getDeclaredField("client")
                .apply { isAccessible = true }
            clientField.set(ConnectionManager, agent.client)
            try {
                // 首页已迁移到 Compose（miuix）：控制台与命令输入是页面状态。
                val activity = controller.get()
                activity.commandLine = "echo sent-from-ui-ok"
                activity.sendTyped()
                val deadline = System.nanoTime() + 5_000_000_000L
                while (!activity.consoleText.contains("\nsent-from-ui-ok") && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(10)
                }
                val log = activity.consoleText
                assertTrue("点击发送应把 exec 帧发到主机: $log", agent.received.any {
                    it.optString("cmd") == "exec" && it.optString("line") == "echo sent-from-ui-ok" &&
                        it.optInt("timeout") == 300
                })
                assertTrue("主机的输出应显示在控制台: $log", log.contains("\nsent-from-ui-ok"))
                assertFalse(log.contains("NetworkOnMainThreadException"))
                assertTrue("同一连接应能继续请求", runBlocking {
                    withContext(Dispatchers.IO) { agent.client.call("ping").optBoolean("ok") }
                })
            } finally {
                ConnectionManager.stop()
                controller.pause().stop().destroy()
            }
        }
    }

    @Test
    fun onePendingCommandIsCountedAndCancellationCleansUp() = runBlocking {
        val gate = CountDownLatch(1)
        Agent(gate).use { agent ->
            val request = async(Dispatchers.IO) { agent.client.call("exec") }
            try {
                assertTrue(agent.receivedRequest.await(5, TimeUnit.SECONDS))
                assertEquals("只有一条命令时也必须被心跳识别为繁忙", 1, agent.client.pendingRequests())
                request.cancelAndJoin()
                assertEquals(0, agent.client.pendingRequests())
            } finally {
                request.cancelAndJoin()
                gate.countDown()
            }
            assertTrue("迟到的已取消响应不应影响下一次请求", agent.client.call("ping").optBoolean("ok"))
            assertEquals(0, agent.client.pendingRequests())
        }
    }

    @Test
    fun disconnectKeepsItsReasonInsteadOfReportingTimeout() {
        Agent().use { agent ->
            val error = assertThrows(IOException::class.java) {
                runBlocking { agent.client.call("disconnect") }
            }
            assertEquals("连接已断开", error.message)
            assertEquals(0, agent.client.pendingRequests())
        }
    }
}
