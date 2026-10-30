package com.liangyuchen.timetable.update

import android.content.Context
import android.net.Uri
import android.os.Build
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 问题反馈：在 App 里填表 → 校验/限流/去重 → 跳到 GitHub 新建 Issue 并预填。
 *
 * 为什么不在 App 里直接调 GitHub API 建 Issue：
 * 那需要把「有写权限的令牌」打进 APK，任何人反编译就能拿到，会被拿去刷 issue 甚至改仓库。
 * 所以采用「预填 + 用户在浏览器里点提交」的方式——Issue 由用户自己的账号创建，
 * 我们这边不存任何密钥。
 */
object Feedback {

    private const val PREFS = "timetable_feedback"
    private const val KEY_LAST_TIME = "last_time"
    private const val KEY_DAY = "day"
    private const val KEY_DAY_COUNT = "day_count"
    private const val KEY_HASHES = "hashes"

    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 1200
    private const val COOLDOWN_MS = 60_000L          // 两次提交至少间隔 1 分钟
    private const val MAX_PER_DAY = 3                // 每天最多 3 条

    /** 校验结果：null 表示通过，否则是给用户看的提示。 */
    fun validate(context: Context, text: String, contact: String): String? {
        val body = text.trim()
        if (body.isEmpty()) return "请先描述一下遇到的问题"
        if (body.length < MIN_LENGTH) return "再多写几个字吧（至少 $MIN_LENGTH 个字）"
        if (body.length > MAX_LENGTH) return "太长了，请精简到 $MAX_LENGTH 字以内"

        // 全是同一个字符、或几乎都是标点 → 当成刷屏
        val meaningful = body.count { it.isLetterOrDigit() }
        if (meaningful < 3) return "内容看起来没有实质描述（像是符号或空白）"
        if (body.toSet().size < 3) return "内容重复太多，写点具体现象吧"

        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_TIME, 0L)
        if (now - last < COOLDOWN_MS) {
            val wait = (COOLDOWN_MS - (now - last)) / 1000
            return "刚提交过，请等 $wait 秒后再试"
        }

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        val day = prefs.getString(KEY_DAY, "") ?: ""
        val count = if (day == today) prefs.getInt(KEY_DAY_COUNT, 0) else 0
        if (count >= MAX_PER_DAY) return "今天已经反馈过 $MAX_PER_DAY 次了，明天再来吧"

        val hash = sha1(body)
        val hashes = (prefs.getString(KEY_HASHES, "") ?: "").split(',').filter { it.isNotEmpty() }
        if (hashes.contains(hash)) return "这条反馈好像已经提交过了"
        if (contact.length > 60) return "联系方式太长了"
        return null
    }

    /** 提交成功后记账（限流 + 去重）。 */
    fun remember(context: Context, text: String) {
        val now = System.currentTimeMillis()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = prefs.getString(KEY_DAY, "") ?: ""
        val count = if (day == today) prefs.getInt(KEY_DAY_COUNT, 0) else 0
        val hashes = (prefs.getString(KEY_HASHES, "") ?: "").split(',')
            .filter { it.isNotEmpty() }
            .takeLast(9) + sha1(text.trim())
        prefs.edit()
            .putLong(KEY_LAST_TIME, now)
            .putString(KEY_DAY, today)
            .putInt(KEY_DAY_COUNT, count + 1)
            .putString(KEY_HASHES, hashes.joinToString(","))
            .apply()
    }

    /** 拼出 GitHub 新建 Issue 的地址（标题 + 正文 + 标签）。 */
    fun issueUri(
        repo: String,
        title: String,
        body: String,
    ): Uri {
        val slug = repo.ifBlank { "Leiou1939/BetterTimetable" }
        return Uri.parse("https://github.com/$slug/issues/new")
            .buildUpon()
            .appendQueryParameter("labels", "bug")
            .appendQueryParameter("title", title)
            .appendQueryParameter("body", body)
            .build()
    }

    /** 反馈正文（带上环境信息，方便定位）。 */
    fun buildBody(
        description: String,
        contact: String,
        appVersion: String,
        versionCode: Int,
        school: String,
    ): String = buildString {
        appendLine("### 问题描述")
        appendLine(description.trim())
        appendLine()
        if (contact.isNotBlank()) {
            appendLine("### 联系方式")
            appendLine(contact.trim())
            appendLine()
        }
        appendLine("### 运行环境")
        appendLine("- App 版本：$appVersion（$versionCode）")
        if (school.isNotBlank()) appendLine("- 学校：$school")
        appendLine("- Android：${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
        appendLine("- 机型：${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine()
        appendLine("<!-- 由「课程表」App 的问题反馈生成 -->")
    }

    fun buildTitle(description: String): String {
        val first = description.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        val cut = if (first.length > 40) first.take(40) + "…" else first
        return if (cut.isEmpty()) "问题反馈" else "[反馈] $cut"
    }

    private fun sha1(text: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
