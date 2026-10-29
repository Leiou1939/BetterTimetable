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
    // ---- 差量更新（有就用，没有就整包下载）
    val apkSha256: String = "",
    val patchUrl: String = "",
    val patchAssetId: Long = 0L,
    val patchSize: Long = 0L,
    val patchFromSha256: String = "",
    val patchToSha256: String = "",
) {
    val hasPatch: Boolean get() = patchFromSha256.isNotEmpty() && (patchAssetId > 0L || patchUrl.isNotEmpty())
}

/**
 * 通过 GitHub Releases 检查并下载更新。
 *
 * 约定：Release 的 tag 就是版本号本身（`v1.1`、`v1.2`…），
 * 与 APK 的 versionName 逐段比较（1.2 > 1.1 > 1.0.9），
 * versionCode 只用于 Android 允许覆盖安装，不参与新旧判断。
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
        if (versionParts(tag).isEmpty()) {
            throw IllegalStateException("发布信息里没有可用的版本号：$tag")
        }
        // 已经是同一版或更新版本就不提示
        if (!isNewer(tag, BuildConfig.VERSION_NAME)) return null

        val assets = obj.optJSONArray("assets")
        var apkUrl = ""
        var apkAssetId = 0L
        var apkSize = 0L
        var manifestAssetId = 0L
        var manifestUrl = ""
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name", "")
                when {
                    name.endsWith(".apk", ignoreCase = true) && apkUrl.isEmpty() -> {
                        apkUrl = a.optString("browser_download_url", "")
                        apkAssetId = a.optLong("id", 0L)
                        apkSize = a.optLong("size", 0L)
                    }
                    name == "update.json" -> {
                        manifestAssetId = a.optLong("id", 0L)
                        manifestUrl = a.optString("browser_download_url", "")
                    }
                }
            }
        }
        if (apkUrl.isEmpty() && apkAssetId <= 0L) {
            throw IllegalStateException("这个 Release 里没有 APK 附件")
        }

        // 发布里如果带了 update.json，就按里面的哈希决定能不能用补丁
        var apkSha = ""
        var patchName = ""
        var patchSize = 0L
        var patchFrom = ""
        var patchTo = ""
        var patchAssetId = 0L
        if (manifestAssetId > 0L || manifestUrl.isNotEmpty()) {
            val slug = repo(context)
            val manifest = runCatching {
                val url = if (manifestAssetId > 0L && slug.contains('/')) {
                    "$API/repos/$slug/releases/assets/$manifestAssetId"
                } else {
                    manifestUrl
                }
                JSONObject(httpGet(url))
            }.getOrNull()
            if (manifest != null) {
                apkSha = manifest.optString("apkSha256", "")
                patchName = manifest.optString("patch", "")
                patchSize = manifest.optLong("patchSize", 0L)
                patchFrom = manifest.optString("patchFromSha256", "")
                patchTo = manifest.optString("patchToSha256", "")
            }
        }
        if (patchName.isNotEmpty() && assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name", "") == patchName) {
                    patchAssetId = a.optLong("id", 0L)
                    break
                }
            }
        }

        return UpdateInfo(
            tag = tag,
            versionCode = versionCodeOf(tag),
            title = obj.optString("name", tag),
            notes = obj.optString("body", "").take(1200),
            apkUrl = apkUrl,
            apkAssetId = apkAssetId,
            apkSize = apkSize,
            publishedAt = obj.optString("published_at", ""),
            htmlUrl = obj.optString("html_url", ""),
            apkSha256 = apkSha,
            patchUrl = "",
            patchAssetId = patchAssetId,
            patchSize = patchSize,
            patchFromSha256 = patchFrom,
            patchToSha256 = patchTo,
        )
    }

    /** 下载任意附件（APK 或补丁）；[assetId] 优先走 API，失败再回落网页地址。 */
    fun downloadAsset(
        context: Context,
        url: String,
        assetId: Long,
        expectedSize: Long,
        fileName: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val target = File(dir, fileName)
        if (target.exists() && target.length() >= 16L &&
            (expectedSize <= 0L || target.length() == expectedSize)
        ) {
            onProgress(target.length(), target.length())
            return target
        }
        val candidates = ArrayList<String>()
        val slug = repo(context)
        if (assetId > 0L && slug.contains('/')) {
            candidates += "$API/repos/$slug/releases/assets/$assetId"
        }
        if (url.isNotEmpty()) candidates += url
        if (candidates.isEmpty()) throw IllegalStateException("没有可用的下载地址")

        var lastError: Throwable? = null
        for (candidate in candidates) {
            try {
                fetchTo(candidate, expectedSize, target, onProgress)
                if (target.length() > 0) return target
                lastError = IllegalStateException("下载到的文件是空的")
            } catch (t: Throwable) {
                lastError = t
            }
        }
        target.delete()
        throw IllegalStateException("下载失败：${lastError?.message ?: "网络不可用"}")
    }

    /** "v1.2" / "1.2.3" → [1, 2] / [1, 2, 3]；解析不出来返回空列表。 */
    internal fun versionParts(text: String): List<Int> {
        val cleaned = text.trim().removePrefix("v").removePrefix("V")
        val parts = cleaned.split('.', '-', '+')
            .mapNotNull { seg -> Regex("^\\d+").find(seg)?.value?.toIntOrNull() }
        return parts
    }

    /** [remote] 是否比 [local] 新（逐段比较，缺的段按 0 算）。 */
    internal fun isNewer(remote: String, local: String): Boolean {
        val a = versionParts(remote)
        val b = versionParts(local)
        if (a.isEmpty()) return false
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Android 覆盖安装用的 versionCode：1.2 → 10200，保证单调递增。 */
    internal fun versionCodeOf(tag: String): Int {
        val p = versionParts(tag)
        if (p.isEmpty()) return 0
        val major = p.getOrElse(0) { 0 }
        val minor = p.getOrElse(1) { 0 }
        val patch = p.getOrElse(2) { 0 }
        return major * 10000 + minor * 100 + patch
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
        val target = File(dir, "timetable-${info.tag}.apk")
        // 这个版本已经下过就用本地的，不重复下载
        if (target.exists() && target.length() >= 100_000L &&
            (info.apkSize <= 0L || target.length() == info.apkSize)
        ) {
            onProgress(target.length(), target.length())
            return target
        }
        // 其它版本的旧包清掉，避免缓存里堆一堆
        dir.listFiles()?.forEach { if (it.absolutePath != target.absolutePath) it.delete() }

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
