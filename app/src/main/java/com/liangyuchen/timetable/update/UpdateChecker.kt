package com.liangyuchen.timetable.update

import android.content.Context
import com.liangyuchen.timetable.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** GitHub 上最新的一个 Release。 */
data class UpdateInfo(
    val tag: String,
    val versionCode: Int,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkAssetId: Long,
    val apkSize: Long,
    val publishedAt: String,
    val htmlUrl: String,
)

/**
 * 通过 GitHub Releases 检查并下载更新。
 *
 * 约定：Release 的 tag 最后一段数字 == APK 的 versionCode。
 * CI 发布 `v1.0.1007` 时 versionCode 就是 1007，所以"数字大 = 版本新"。
 */
object UpdateChecker {

    private const val API = "https://api.github.com"
    private const val USER_AGENT = "TimetableApp-Updater"

    /** 本地记录的仓库地址优先，其次用构建时注入的。 */
    fun repo(context: Context): String {
        val saved = UpdateSettings.repo(context)
        if (saved.isNotBlank()) return saved.trim()
        return BuildConfig.UPDATE_REPO.trim()
    }

    fun localVersionName(): String = BuildConfig.VERSION_NAME
    fun localVersionCode(): Int = BuildConfig.VERSION_CODE

    /**
     * 查询最新 Release。
     * @return 有可更新版本时返回 [UpdateInfo]；已是最新返回 null。
     * @throws IllegalStateException 仓库没配置 / 网络或解析失败。
     */
    fun checkForUpdate(context: Context, repoOverride: String? = null): UpdateInfo? {
        val slug = (repoOverride ?: repo(context)).trim()
        if (!slug.contains('/')) {
            throw IllegalStateException("还没设置 GitHub 仓库地址（格式：用户名/仓库名）")
        }
        val json = httpGet("$API/repos/$slug/releases/latest")
        val obj = JSONObject(json)
        val tag = obj.optString("tag_name", "")
        val remoteCode = parseVersionCode(tag)
        if (remoteCode <= 0) throw IllegalStateException("发布信息里没有可用的版本号：$tag")
        if (remoteCode <= BuildConfig.VERSION_CODE) return null

        val assets = obj.optJSONArray("assets")
        var apkUrl = ""
        var apkAssetId = 0L
        var apkSize = 0L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url", "")
                    apkAssetId = a.optLong("id", 0L)
                    apkSize = a.optLong("size", 0L)
                    break
                }
            }
        }
        if (apkUrl.isEmpty() && apkAssetId <= 0L) {
            throw IllegalStateException("这个 Release 里没有 APK 附件")
        }

        return UpdateInfo(
            tag = tag,
            versionCode = remoteCode,
            title = obj.optString("name", tag),
            notes = obj.optString("body", "").take(1200),
            apkUrl = apkUrl,
            apkAssetId = apkAssetId,
            apkSize = apkSize,
            publishedAt = obj.optString("published_at", ""),
            htmlUrl = obj.optString("html_url", ""),
        )
    }

    /** 从 tag 里取版本号：`v1.0.12` → 12，`v1.2.0` → 1_002_000 之类的比较不便，故取最后一段。 */
    internal fun parseVersionCode(tag: String): Int {
        val tail = tag.trim().substringAfterLast('.').filter { it.isDigit() }
        return tail.toIntOrNull() ?: 0
    }

    /**
     * 下载 APK 到 cache 目录，返回文件。
     *
     * 两条路径依次尝试：
     *  1. `browser_download_url`（github.com/.../releases/download/...）；
     *  2. 失败则改走 **API 附件接口**（api.github.com/repos/../releases/assets/<id>），
     *     加 `Accept: application/octet-stream` 直接拿二进制。
     *
     * 国内网络里 github.com 经常连不上、而 api.github.com 可用，第 2 条是必要的兜底。
     */
    fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "timetable-${info.tag}.apk")

        val candidates = ArrayList<String>()
        // API 接口优先：国内网络里 github.com 常常整个连不上，
        // 而 api.github.com 会 302 到 release-assets.githubusercontent.com（可达）。
        val slug = repo(context)
        if (info.apkAssetId > 0L && slug.contains('/')) {
            candidates += "$API/repos/$slug/releases/assets/${info.apkAssetId}"
        }
        if (info.apkUrl.isNotEmpty()) candidates += info.apkUrl
        if (candidates.isEmpty()) throw IllegalStateException("没有可用的下载地址")

        var lastError: Throwable? = null
        for (url in candidates) {
            try {
                fetchTo(url, info.apkSize, target, onProgress)
                if (target.length() >= 100_000L) return target
                lastError = IllegalStateException("下载到的文件不完整")
            } catch (t: Throwable) {
                lastError = t
            }
        }
        target.delete()
        throw IllegalStateException(
            "下载失败：${lastError?.message ?: "网络不可用"}。" +
                "若一直失败，可稍后重试，或到 GitHub 页面手动下载安装。",
        )
    }

    private fun fetchTo(
        url: String,
        expectedSize: Long,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
            // API 附件接口靠这个头把 302 变成真正的字节流
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val total = conn.contentLengthLong.let { if (it > 0) it else expectedSize }
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = conn.responseCode
            if (code == 404) {
                throw IllegalStateException("仓库或 Release 不存在（404），请检查仓库地址是否正确、是否已发布过版本")
            }
            if (code !in 200..299) throw IllegalStateException("查询失败：HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
