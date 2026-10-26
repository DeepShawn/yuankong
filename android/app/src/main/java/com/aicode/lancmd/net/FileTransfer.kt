package com.aicode.lancmd.net

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * 分块文件传输。单块默认 512KB，走同一行长连接，
 * 因此几十 MB 的文件也不会撑爆内存或单帧上限。
 */
object FileTransfer {

    data class Progress(val done: Long, val total: Long, val speedBps: Long) {
        val percent: Int
            get() = when {
                total <= 0 -> 0
                else -> ((done * 100) / total).toInt().coerceIn(0, 100)
            }
    }

    private const val NO_WRAP = Base64.NO_WRAP

    // ------------------------------------------------------------ 上传

    suspend fun upload(
        ctx: Context,
        uri: Uri,
        remotePath: String,
        onProgress: (Progress) -> Unit
    ): JSONObject = uploadTo(ctx, uri, remotePath, null, onProgress)

    suspend fun uploadTo(
        ctx: Context,
        uri: Uri,
        remotePath: String,
        expectedClient: AgentClient?,
        onProgress: (Progress) -> Unit
    ): JSONObject = withContext(Dispatchers.IO) {
        val resolver = ctx.contentResolver
        val knownSize = querySize(ctx, uri)
        var temp: File? = null
        var activeSid: String? = null
        var owner: AgentClient? = null
        suspend fun call(params: JSONObject, timeout: Long): JSONObject {
            val client = owner ?: throw IOException("未连接")
            if (ConnectionManager.sessionClient() !== client) throw IOException("连接已切换，已停止向原电脑发送文件")
            return client.call("upload", params, timeout)
        }
        try {
            val source: () -> InputStream
            val size: Long
            if (knownSize > 0) {
                size = knownSize
                source = { resolver.openInputStream(uri) ?: throw IOException("无法读取文件") }
            } else {
                // 临时文件从创建起就受 finally 保护，包括读取、连接和 begin 失败。
                val cached = File.createTempFile("upload-", ".bin", ctx.cacheDir)
                temp = cached
                resolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cached).use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                        }
                    }
                } ?: throw IOException("无法读取文件")
                size = cached.length()
                source = { cached.inputStream() }
            }
            coroutineContext.ensureActive()

            owner = expectedClient ?: ConnectionManager.sessionClient()
            val begin = call(
                JSONObject()
                    .put("action", "begin")
                    .put("path", remotePath)
                    .put("size", size),
                20_000
            )
            if (!begin.optBoolean("ok")) throw IOException(begin.optString("error", "无法开始上传"))
            val sid = begin.optString("sid")
            activeSid = sid
            val chunkSize = begin.optInt("chunk", 512 * 1024).coerceIn(16 * 1024, 4 * 1024 * 1024)

            var sent = 0L
            val started = SystemClock.elapsedRealtime()
            var lastEmit = 0L

            source().use { input ->
                val buf = ByteArray(chunkSize)
                var seq = 0
                while (true) {
                    coroutineContext.ensureActive()
                    val n = readChunk(input, buf)
                    if (n <= 0) break
                    val payload = Base64.encodeToString(buf, 0, n, NO_WRAP)
                    val r = call(
                        JSONObject()
                            .put("action", "chunk")
                            .put("sid", sid)
                            .put("seq", seq)
                            .put("data", payload),
                        120_000
                    )
                    if (!r.optBoolean("ok")) throw IOException(r.optString("error", "分块发送失败"))
                    seq++
                    sent += n
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastEmit >= 120 || sent >= size) {
                        lastEmit = now
                        onProgress(Progress(sent, size, speed(sent, started, now)))
                    }
                }
            }

            val end = call(
                JSONObject().put("action", "end").put("sid", sid),
                120_000
            )
            if (!end.optBoolean("ok")) throw IOException(end.optString("error", "上传收尾失败"))
            onProgress(Progress(end.optLong("size", size), size, speed(sent, started, SystemClock.elapsedRealtime())))
            end
        } catch (e: Throwable) {
            // 协程被取消也要通知电脑端删掉半截文件
            if (activeSid != null) {
                withContext(NonCancellable) {
                    runCatching {
                        owner?.call(
                            "upload",
                            JSONObject().put("action", "abort").put("sid", activeSid),
                            10_000
                        )
                    }
                }
            }
            throw if (e is CancellationException) e else IOException(e.message ?: "上传失败", e)
        } finally {
            temp?.delete()
        }
    }

    // ------------------------------------------------------------ 下载

    suspend fun download(
        remotePath: String,
        dest: File,
        onProgress: (Progress) -> Unit
    ): File = downloadFrom(remotePath, dest, null, onProgress)

    suspend fun downloadFrom(
        remotePath: String, dest: File, expectedClient: AgentClient?,
        onProgress: (Progress) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val owner = expectedClient ?: ConnectionManager.sessionClient()
        suspend fun send(params: JSONObject, timeout: Long): JSONObject {
            if (ConnectionManager.sessionClient() !== owner || owner.closed) throw IOException("连接已切换，下载已停止")
            return owner.call("download", params, timeout)
        }
        val begin = send(
            JSONObject().put("action", "begin").put("path", remotePath),
            20_000
        )
        if (!begin.optBoolean("ok")) throw IOException(begin.optString("error", "无法开始下载"))
        val sid = begin.optString("sid")
        val total = begin.optLong("size", 0)
        val chunkSize = begin.optInt("chunk", 512 * 1024).coerceIn(16 * 1024, 4 * 1024 * 1024)

        dest.parentFile?.mkdirs()
        var written = 0L
        val started = SystemClock.elapsedRealtime()
        var lastEmit = 0L

        try {
            FileOutputStream(dest).use { out ->
                var offset = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val r = send(
                        JSONObject()
                            .put("action", "chunk")
                            .put("sid", sid)
                            .put("offset", offset)
                            .put("len", chunkSize),
                        120_000
                    )
                    if (!r.optBoolean("ok")) throw IOException(r.optString("error", "分块接收失败"))
                    val raw = Base64.decode(r.optString("data"), Base64.DEFAULT)
                    if (raw.isEmpty()) break
                    out.write(raw)
                    offset += raw.size
                    written += raw.size
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastEmit >= 120) {
                        lastEmit = now
                        onProgress(Progress(written, total, speed(written, started, now)))
                    }
                    if (r.optBoolean("eof", false) || (total > 0 && offset >= total)) break
                }
                out.flush()
            }

            send(
                JSONObject().put("action", "end").put("sid", sid),
                20_000
            )
            if (total > 0 && written != total) {
                throw IOException("文件不完整：$written / $total 字节")
            }
            onProgress(Progress(written, total, speed(written, started, SystemClock.elapsedRealtime())))
            dest
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                runCatching {
                    owner.call("download",
                        JSONObject().put("action", "abort").put("sid", sid),
                        10_000
                    )
                }
            }
            dest.delete()
            throw if (e is CancellationException) e else IOException(e.message ?: "下载失败", e)
        }
    }

    // ------------------------------------------------------------ 工具

    /** 读取 SAF 返回的文件大小；读不到返回 -1。 */
    fun querySize(ctx: Context, uri: Uri): Long {
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) return c.getLong(idx)
            }
        } catch (_: Exception) {
        }
        return -1
    }

    /** 用于显示的文件名；取不到时用 URI 末段。 */
    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) {
                    val name = c.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun readChunk(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun speed(done: Long, started: Long, now: Long): Long {
        val ms = (now - started).coerceAtLeast(1)
        return done * 1000 / ms
    }

    fun humanSize(n: Long): String {
        if (n < 1024) return "$n B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = n.toDouble() / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return String.format(java.util.Locale.US, "%.1f %s", v, units[i])
    }
}
