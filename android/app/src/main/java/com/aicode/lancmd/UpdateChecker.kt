package com.aicode.lancmd

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** GitHub Release 里的一个安卓更新包。 */
data class AppRelease(
    val tag: String,
    val version: String,
    val notes: String,
    val apkUrl: String,
    val size: Long
)

/**
 * 安卓应用自更新：检查 GitHub Release → 下载 APK → 校验签名 → 交给系统安装。
 *
 * 不依赖任何服务端，只访问公开的 GitHub Releases API；下载完成后必须与当前
 * 安装包使用同一张签名证书，否则拒绝安装，避免被替换成仿冒包。
 */
object UpdateChecker {
    /** 官方发布证书 SHA-256；与 CI 发布校验使用同一指纹。 */
    const val RELEASE_CERT_SHA256 =
        "0e3f1973d35093280b1bf018af86bafb957a1048b529cfe1954638f10f4a1fed"

    const val API_URL = "https://api.github.com/repos/DeepShawn/yuankong/releases/latest"
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** 自动检查只在真机上跑；Robolectric 截图/界面测试不访问网络。 */
    val isTestEnvironment: Boolean
        get() = Build.FINGERPRINT.contains("robolectric", ignoreCase = true)

    /** 测试可替换 API 与下载地址；正常运行时为空。 */
    internal var apiUrlOverride: String? = null
    internal var downloadUrlOverride: ((String) -> String)? = null

    fun currentVersion(): String = BuildConfig.VERSION_NAME

    fun currentVersionCode(): Long = BuildConfig.VERSION_CODE.toLong()

    /** 纯函数：latest 是否比 current 新（只比较 x.y.z 数字段）。 */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parseVersion(latest) ?: return false
        val b = parseVersion(current) ?: return false
        for (index in 0 until 3) if (a[index] != b[index]) return a[index] > b[index]
        return false
    }

    internal fun parseVersion(value: String): IntArray? =
        Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$").find(value.trim())?.let { match ->
            IntArray(3) { match.groupValues[it + 1].toInt() }
        }

    /**
     * 纯函数：解析 `releases/latest` 响应。
     *
     * 只接受 https 的 .apk 资产和合法的 x.y.z 标签；比当前版本旧或解析失败时返回 null。
     */
    fun parseRelease(json: String, currentVersion: String = BuildConfig.VERSION_NAME): AppRelease? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val tag = root.optString("tag_name").trim()
        if (tag.isBlank()) return null
        val version = tag.removePrefix("v")
        if (parseVersion(version) == null) return null
        if (!isNewer(version, currentVersion)) return null
        val assets = root.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                apk = asset
                break
            }
        }
        val asset = apk ?: return null
        val url = asset.optString("browser_download_url").trim()
        if (!url.startsWith("https://")) return null
        return AppRelease(
            tag = tag,
            version = version,
            notes = root.optString("body").take(8000),
            apkUrl = url,
            size = asset.optLong("size", 0L)
        )
    }

    /** 每日最多自动检查一次。 */
    fun shouldAutoCheck(context: Context): Boolean {
        if (isTestEnvironment) return false
        val last = Prefs.lastUpdateCheck(context)
        return System.currentTimeMillis() - last >= CHECK_INTERVAL_MS
    }

    fun markChecked(context: Context) = Prefs.markUpdateChecked(context)

    suspend fun fetchLatest(context: Context): AppRelease? = withContext(Dispatchers.IO) {
        val url = apiUrlOverride ?: API_URL
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "LanCmd-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("检查更新失败：HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseRelease(body, BuildConfig.VERSION_NAME)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 下载更新包到 cache/updates。缓存目录里的旧包先清掉，进度通过 [onProgress] 回调。
     */
    suspend fun download(
        context: Context,
        release: AppRelease,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "LanCmd-${release.version}.apk")
        val url = downloadUrlOverride?.invoke(release.apkUrl) ?: release.apkUrl
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LanCmd-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("下载失败：HTTP ${connection.responseCode}")
            }
            val total = release.size.takeIf { it > 0 } ?: connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count
                        if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            onProgress(1f)
            if (target.length() <= 0L) error("下载的安装包为空")
            target
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 校验下载的 APK：包名一致、版本比当前新、签名证书与官方发布证书一致。
     *
     * @return null 表示通过；否则返回可以直接展示给用户的原因。
     */
    fun verifyApk(context: Context, file: File, expectedVersion: String): String? {
        if (!file.isFile || file.length() <= 0L) return "安装包不存在或为空"
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        }.getOrNull() ?: return "无法读取安装包信息"
        if (info.packageName != context.packageName) return "安装包包名不匹配"
        @Suppress("DEPRECATION")
        val code = info.versionCode.toLong()
        if (code <= currentVersionCode()) return "安装包版本不比当前新"
        if (expectedVersion.isNotBlank()) {
            @Suppress("DEPRECATION")
            val versionName = info.versionName
            if (versionName != expectedVersion) return "安装包版本号与发布不一致"
        }
        val digest = signerDigest(info) ?: return "无法读取安装包签名"
        if (!digest.equals(RELEASE_CERT_SHA256, ignoreCase = true)) {
            return "安装包签名与官方发布不一致，已拒绝"
        }
        return null
    }

    private fun signerDigest(info: android.content.pm.PackageInfo): String? {
        val signature = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return null
            val signers = if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                signing.signingCertificateHistory
            }
            signers.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()
        } ?: return null
        return MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun installIntent(context: Context, file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun unknownSourcesIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 26) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }

    /** 已下载但还没安装的更新包（Activity 重建后仍能找到）。 */
    fun pendingUpdate(context: Context): File? =
        File(context.cacheDir, "updates").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".apk", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }

    /** 从缓存文件名 `LanCmd-<版本>.apk` 取回版本号；不符合格式返回 null。 */
    fun pendingVersion(file: File): String? {
        val name = file.name
        if (!name.startsWith("LanCmd-") || !name.endsWith(".apk")) return null
        val version = name.removePrefix("LanCmd-").removeSuffix(".apk")
        return version.takeIf { parseVersion(it) != null }
    }

    fun formatSize(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024f / 1024f)
        bytes >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024f)
        else -> "$bytes B"
    }
}
