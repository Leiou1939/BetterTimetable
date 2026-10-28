package com.liangyuchen.timetable.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 备用联动方式：导出标准 .ics 日历文件，交给 vivo 日历 / 系统日历导入。
 * 当设备日历不读取第三方日历账户，或只想手动导入时使用。
 */
object IcsExport {

    private val UTC_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    private const val PROD_ID = "-//课程表 App//Timetable//CN"

    /** 生成 .ics 内容（每节课一个 VEVENT，带提前 10 分钟提醒）。 */
    fun build(data: ScheduleData, from: LocalDate = LocalDate.now()): String {
        val sb = StringBuilder()
        line(sb, "BEGIN:VCALENDAR")
        line(sb, "VERSION:2.0")
        line(sb, "PRODID:$PROD_ID")
        line(sb, "CALSCALE:GREGORIAN")
        line(sb, "METHOD:PUBLISH")
        line(sb, "X-WR-CALNAME:${data.student.term.ifEmpty { "我的课表" }}")
        line(sb, "X-WR-TIMEZONE:${ScheduleCalc.deviceTimeZoneId()}")

        for (occ in ScheduleCalc.occurrences(data, from = from, limit = 800)) {
            val stub = "T${occ.startMillis}"
            line(sb, "BEGIN:VEVENT")
            line(sb, "UID:$stub-${occ.course.id}@timetable.local")
            line(sb, "DTSTAMP:${occ.startMillis.toUtcStamp()}")
            line(sb, "DTSTART:${occ.startMillis.toUtcStamp()}")
            line(sb, "DTEND:${occ.endMillis.toUtcStamp()}")
            line(sb, "SUMMARY:${escape(CalendarSync.buildTitle(occ.course))}")
            line(sb, "LOCATION:${escape(CalendarSync.buildLocation(occ.course))}")
            line(sb, "DESCRIPTION:${escape(CalendarSync.buildDescription(occ.course, occ.week))}")
            line(sb, "BEGIN:VALARM")
            line(sb, "TRIGGER:-PT${CalendarSync.DEFAULT_REMIND_MINUTES}M")
            line(sb, "ACTION:DISPLAY")
            line(sb, "DESCRIPTION:${escape(occ.course.name)} 即将开始")
            line(sb, "END:VALARM")
            line(sb, "END:VEVENT")
        }
        line(sb, "END:VCALENDAR")
        return sb.toString()
    }

    /** 写出 .ics 并返回分享用的 Intent（可直接发给 vivo 日历导入）。 */
    fun exportIntent(context: Context, data: ScheduleData): Intent {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, "课程表.ics")
        file.writeText(build(data), StandardCharsets.UTF_8)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/calendar"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "课程表 ${data.student.term}".trim())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun line(sb: StringBuilder, raw: String) {
        // RFC 5545: 折行到 75 字节，续行以空格开头
        val bytes = raw.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= 73) {
            sb.append(raw).append("\r\n")
            return
        }
        var count = 0
        val chunk = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            val chars = Character.charCount(cp)
            val size = String(Character.toChars(cp)).toByteArray(StandardCharsets.UTF_8).size
            if (count + size > 73 && chunk.isNotEmpty()) {
                sb.append(chunk).append("\r\n ")
                chunk.setLength(0)
                count = 0
            }
            chunk.append(raw, i, i + chars)
            count += size
            i += chars
        }
        sb.append(chunk).append("\r\n")
    }

    private fun escape(text: String): String = text
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")

    private fun Long.toUtcStamp(): String =
        java.time.Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).format(UTC_STAMP)
}
