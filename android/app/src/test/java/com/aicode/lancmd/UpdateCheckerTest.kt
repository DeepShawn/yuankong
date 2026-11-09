package com.aicode.lancmd

import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

/** 应用自更新：版本比较、GitHub Release 解析、下载与本地校验。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class UpdateCheckerTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @After
    fun cleanUp() {
        UpdateChecker.apiUrlOverride = null
        UpdateChecker.downloadUrlOverride = null
        File(context.cacheDir, "updates").deleteRecursively()
    }

    private fun releaseJson(
        tag: String,
        apkUrl: String = "https://github.com/DeepShawn/yuankong/releases/download/v3.20.0/LanCmd-3.20.0.apk",
        assetName: String = "LanCmd-3.20.0.apk",
        body: String = "更新内容"
    ): String {
        val asset = JSONObject()
            .put("name", assetName)
            .put("size", 123456)
            .put("browser_download_url", apkUrl)
        return JSONObject()
            .put("tag_name", tag)
            .put("body", body)
            .put("assets", JSONArray().put(asset))
            .toString()
    }

    @Test
    fun versionComparisonHandlesNumericSegments() {
        assertTrue(UpdateChecker.isNewer("3.20.0", "3.19.9"))
        assertTrue(UpdateChecker.isNewer("3.10.0", "3.9.9"))
        assertTrue(UpdateChecker.isNewer("v4.0.0", "3.99.99"))
        assertFalse(UpdateChecker.isNewer("3.20.0", "3.20.0"))
        assertFalse(UpdateChecker.isNewer("3.19.9", "3.20.0"))
        assertFalse(UpdateChecker.isNewer("3.20", "3.19.0"))
        assertFalse(UpdateChecker.isNewer("latest", "3.19.0"))
    }

    @Test
    fun latestReleaseIsParsedWithApkAsset() {
        val release = UpdateChecker.parseRelease(releaseJson("v3.20.0"), "3.19.1")
        assertNotNull(release)
        val parsed = requireNotNull(release)
        assertEquals("v3.20.0", parsed.tag)
        assertEquals("3.20.0", parsed.version)
        assertEquals(123456L, parsed.size)
        assertTrue(parsed.apkUrl.endsWith("LanCmd-3.20.0.apk"))
    }

    @Test
    fun sameOrOlderReleaseIsNotAnUpdate() {
        assertNull(UpdateChecker.parseRelease(releaseJson("v3.20.0"), "3.20.0"))
        assertNull(UpdateChecker.parseRelease(releaseJson("v3.19.0"), "3.20.0"))
    }

    @Test
    fun releaseWithoutUsableApkIsRejected() {
        assertNull(UpdateChecker.parseRelease(releaseJson("v3.20.0", apkUrl = "http://example.com/x.apk"), "3.19.0"))
        assertNull(UpdateChecker.parseRelease(releaseJson("v3.20.0", assetName = "notes.txt"), "3.19.0"))
        assertNull(UpdateChecker.parseRelease(releaseJson("v3.20"), "3.19.0"))
        assertNull(UpdateChecker.parseRelease("not json", "3.19.0"))
    }

    @Test
    fun releaseNotesAreBounded() {
        val release = UpdateChecker.parseRelease(releaseJson("v3.20.0", body = "x".repeat(9000)), "3.19.0")
        assertNotNull(release)
        assertTrue(requireNotNull(release).notes.length <= 8000)
    }

    @Test
    fun downloadWritesApkAndReportsProgress() = runBlocking {
        val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
        val served = AtomicBoolean(false)
        val server = ServerSocket(0)
        val thread = Thread {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val output = socket.getOutputStream()
                output.write(
                    (
                        "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                            "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                        ).toByteArray()
                )
                output.write(payload)
                output.flush()
                served.set(true)
            }
        }.apply { isDaemon = true; start() }
        try {
            UpdateChecker.downloadUrlOverride = { "http://127.0.0.1:${server.localPort}/LanCmd-3.20.0.apk" }
            val release = AppRelease("v3.20.0", "3.20.0", "", "https://example.com/x.apk", payload.size.toLong())
            val progress = ArrayList<Float>()
            val file = UpdateChecker.download(context, release) { progress.add(it) }
            assertTrue(file.isFile)
            assertEquals("LanCmd-3.20.0.apk", file.name)
            assertEquals(payload.size.toLong(), file.length())
            assertTrue(file.readBytes().contentEquals(payload))
            assertEquals(1f, progress.last())
            assertTrue(served.get())
        } finally {
            server.close()
            thread.join(2000)
        }
    }

    @Test
    fun nonApkDownloadIsRejectedByVerification() {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val fake = File(dir, "LanCmd-3.20.0.apk").apply { writeText("not an apk") }
        val problem = UpdateChecker.verifyApk(context, fake, "3.20.0")
        assertNotNull("非 APK 必须被拒绝", problem)
    }

    @Test
    fun pendingVersionComesFromDownloadedFilename() {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "LanCmd-3.20.0.apk").apply { writeText("x") }
        assertEquals("3.20.0", UpdateChecker.pendingVersion(file))
        assertNull(UpdateChecker.pendingVersion(File(dir, "LanCmd-latest.apk")))
        assertEquals(file, UpdateChecker.pendingUpdate(context))
    }

    @Test
    fun checkTimestampIsPersistedAndTestEnvironmentSkipsAutoCheck() {
        Prefs.markUpdateChecked(context)
        assertTrue(Prefs.lastUpdateCheck(context) > 0L)
        // Robolectric 下自动检查必须关闭，否则截图/界面测试会访问网络。
        assertTrue(UpdateChecker.isTestEnvironment)
        assertFalse(UpdateChecker.shouldAutoCheck(context))
    }

    @Test
    fun installIntentUsesFileProviderForCacheApk() {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "LanCmd-3.20.0.apk").apply { writeText("x") }
        val intent = UpdateChecker.installIntent(context, apk)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertNotNull(intent.data)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        if (Build.VERSION.SDK_INT >= 26) {
            // 测试环境没有授予「安装未知应用」权限时，安装入口应引导去设置页而不是崩溃。
            val settings = UpdateChecker.unknownSourcesIntent(context)
            assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.action)
            assertNotNull(settings.data)
        }
    }

    @Test
    fun formatSizeIsHumanReadable() {
        assertEquals("", UpdateChecker.formatSize(0))
        assertEquals("9.5 MB", UpdateChecker.formatSize(10_000_000L))
        assertEquals("512 KB", UpdateChecker.formatSize(512L * 1024L))
    }
}
