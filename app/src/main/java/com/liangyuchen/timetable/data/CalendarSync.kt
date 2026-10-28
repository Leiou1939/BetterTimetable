package com.liangyuchen.timetable.data

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.LocalDate

/** 手机上的一个日历。 */
data class CalendarAccount(
    val id: Long,
    val name: String,
    val accountName: String,
    val accountType: String = "",
    val writable: Boolean = true,
    /** SYNC_EVENTS：为 false 时系统日历不会显示这个日历里的日程。 */
    val visible: Boolean = true,
    val isLocal: Boolean = false,
) {
    val label: String
        get() = when {
            accountType.contains("local", true) -> "$name（本机日历）"
            accountName.isNotEmpty() -> "$name（$accountName）"
            else -> name
        }
}

/** 同步结果。 */
data class SyncResult(
    val inserted: Int = 0,
    val reminders: Int = 0,
    val deleted: Int = 0,
    val firstDate: LocalDate? = null,
    val lastDate: LocalDate? = null,
    val errors: Int = 0,
    /** 写入后再查回来的条数，用来确认真的落库了。 */
    val verified: Int = 0,
    val calendarName: String = "",
    val reminderMinutes: Int = 10,
) {
    val ok: Boolean get() = inserted > 0 && errors == 0
}

/**
 * 与手机系统日历（vivo 日历 / 日程）联动：把课表按周次展开成一条条具体日程
 * 写进系统日历，并挂上提前提醒。之后由系统日历负责到点弹提醒，App 无需常驻。
 *
 * 关键点（之前踩过的坑）：
 *  - 用 `CUSTOM_APP_PACKAGE` / `CUSTOM_APP_URI` 认领自己写入的日程，
 *    而不是 `EVENT_COLOR_KEY`（那是颜色表的外键，部分机型会拒绝或覆盖它，
 *    导致「下次同步删不掉旧日程 → 重复堆积」）；
 *  - 明确写 `STATUS_CONFIRMED`，否则默认是 TENTATIVE，某些日历会淡化/不显示；
 *  - 提醒插入失败时回退到 `METHOD_DEFAULT`（让日历用它的默认提醒方式），
 *    并把成功条数报出来，而不是静默吞掉；
 *  - 写完回查一次，确认真的进了库。
 */
object CalendarSync {

    /** 日程标题前缀，方便在日历里一眼认出。 */
    private const val TITLE_PREFIX = "[课程表]"

    /** 自定义 URI 前缀，用于识别与批量清理本 App 的日程。 */
    private const val APP_URI_PREFIX = "timetable://event/"

    /** 旧版本用过 EVENT_COLOR_KEY 做标记，清理时一并兼容。 */
    private const val LEGACY_TAG = "[课程表]"

    const val DEFAULT_REMIND_MINUTES = 10

    /** 一次同步最多写入的日程条数。 */
    private const val MAX_EVENTS = 800

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------- 日历列表

    /**
     * 列出设备上的日历。先按「可写 + 会显示」筛，筛不到就退化为全部日历，
     * 让界面能提示用户去系统日历里建/开启一个。
     */
    fun listCalendars(context: Context): List<CalendarAccount> {
        val full = queryCalendars(context, wide = true)
        if (full.isNotEmpty()) return full
        return queryCalendars(context, wide = false)
    }

    private fun queryCalendars(context: Context, wide: Boolean): List<CalendarAccount> {
        val projection = if (wide) {
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.SYNC_EVENTS,
            )
        } else {
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
            )
        }

        val out = ArrayList<CalendarAccount>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                val nameCol = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accountCol = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
                val typeCol = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_TYPE)
                val accessCol =
                    cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                val syncCol = cursor.getColumnIndex(CalendarContract.Calendars.SYNC_EVENTS)
                while (cursor.moveToNext()) {
                    val id = if (idCol >= 0) cursor.getLong(idCol) else -1L
                    if (id < 0) continue
                    val type = if (typeCol >= 0) cursor.getString(typeCol) ?: "" else ""
                    val access = if (accessCol >= 0) cursor.getInt(accessCol) else 700
                    out += CalendarAccount(
                        id = id,
                        name = (if (nameCol >= 0) cursor.getString(nameCol) else null)
                            ?: "未命名日历",
                        accountName = (if (accountCol >= 0) cursor.getString(accountCol) else null)
                            ?: "",
                        accountType = type,
                        // CAL_ACCESS_CONTRIBUTOR = 500 起才可写
                        writable = access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                        visible = if (syncCol >= 0) cursor.getInt(syncCol) != 0 else true,
                        isLocal = type.contains("LOCAL", true),
                    )
                }
            }
        }
        return out.sortedWith(
            compareByDescending<CalendarAccount> { it.writable }
                .thenByDescending { it.visible }
                .thenByDescending { it.isLocal },
        )
    }

    // --------------------------------------------------------------- 同步

    /** 按周次展开出的日程条数（同步前预览用）。 */
    fun countOccurrences(data: ScheduleData, from: LocalDate = LocalDate.now()): Int =
        collect(data, from).size

    private fun collect(data: ScheduleData, from: LocalDate): List<ClassOccurrence> =
        ScheduleCalc.occurrences(
            data = data,
            from = from,
            until = from.plusDays(400),
            limit = MAX_EVENTS,
        )

    fun sync(
        context: Context,
        data: ScheduleData,
        calendarId: Long,
        reminderMinutes: Int = DEFAULT_REMIND_MINUTES,
        from: LocalDate = LocalDate.now(),
    ): SyncResult {
        if (!hasPermission(context)) {
            throw SecurityException("没有日历读写权限，无法写入系统日历")
        }
        val resolver = context.contentResolver
        val packageName = context.packageName
        val timeZone = ScheduleCalc.deviceTimeZoneId()

        // 先清掉本 App 之前写入的日程，避免重复
        val deleted = deleteOwnEvents(resolver, packageName)

        var inserted = 0
        var reminders = 0
        var errors = 0
        var first: LocalDate? = null
        var last: LocalDate? = null

        for (occ in collect(data, from)) {
            val base = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, buildTitle(occ.course))
                put(CalendarContract.Events.DESCRIPTION, buildDescription(occ.course, occ.week))
                put(CalendarContract.Events.EVENT_LOCATION, buildLocation(occ.course))
                put(CalendarContract.Events.DTSTART, occ.startMillis)
                put(CalendarContract.Events.DTEND, occ.endMillis)
                put(CalendarContract.Events.ALL_DAY, 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, timeZone)
                put(CalendarContract.Events.HAS_ALARM, 1)
                // 不写 STATUS 时默认是 TENTATIVE，部分日历会淡化甚至不显示
                put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
                put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
                put(CalendarContract.Events.CUSTOM_APP_PACKAGE, packageName)
                put(CalendarContract.Events.CUSTOM_APP_URI, APP_URI_PREFIX + System.nanoTime())
            }
            val eventId = insertEvent(resolver, base)
            if (eventId <= 0L) {
                errors++
                continue
            }
            inserted++
            first = first ?: occ.date
            last = occ.date
            if (addReminder(resolver, eventId, reminderMinutes)) reminders++
        }

        // 回查确认真的落库
        val verified = countOwnEvents(resolver, packageName)

        val calendarName = listCalendars(context).firstOrNull { it.id == calendarId }?.name ?: ""
        return SyncResult(
            inserted = inserted,
            reminders = reminders,
            deleted = deleted,
            firstDate = first,
            lastDate = last,
            errors = errors,
            verified = verified,
            calendarName = calendarName,
            reminderMinutes = reminderMinutes,
        )
    }

    /** 清空本 App 写入的全部日程（设置里的「清空日历」用）。 */
    fun clearEvents(context: Context): Int {
        if (!hasPermission(context)) return 0
        return deleteOwnEvents(context.contentResolver, context.packageName)
    }

    /**
     * 插入一条日程。正常路径带完整标记；若机型不接受 CUSTOM_APP_* 字段，
     * 退化为不带标记的最小字段再试一次（宁可没有标记，也不能写不进去）。
     */
    private fun insertEvent(resolver: ContentResolver, values: ContentValues): Long {
        val uri = runCatching {
            resolver.insert(CalendarContract.Events.CONTENT_URI, values)
        }.getOrNull()
        if (uri != null) return ContentUris.parseId(uri)

        val minimal = ContentValues(values).apply {
            remove(CalendarContract.Events.CUSTOM_APP_PACKAGE)
            remove(CalendarContract.Events.CUSTOM_APP_URI)
        }
        val retry = runCatching {
            resolver.insert(CalendarContract.Events.CONTENT_URI, minimal)
        }.getOrNull()
        return if (retry != null) ContentUris.parseId(retry) else -1L
    }

    /**
     * 挂提醒。先试「通知提醒」；日历若只允许默认提醒方式，
     * 回退到 METHOD_DEFAULT（交给系统日历用它配置的方式提醒）。
     */
    private fun addReminder(resolver: ContentResolver, eventId: Long, minutes: Int): Boolean {
        for (method in intArrayOf(
            CalendarContract.Reminders.METHOD_ALERT,
            CalendarContract.Reminders.METHOD_DEFAULT,
        )) {
            val values = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, minutes)
                put(CalendarContract.Reminders.METHOD, method)
            }
            val uri = runCatching {
                resolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
            }.getOrNull()
            if (uri != null) return true
        }
        return false
    }

    /** 删除本 App 写入的全部日程（CUSTOM_APP_PACKAGE 认领，兼容旧的 COLOR_KEY 标记）。 */
    private fun deleteOwnEvents(resolver: ContentResolver, packageName: String): Int {
        var deleted = 0
        val selections = listOf(
            CalendarContract.Events.CUSTOM_APP_PACKAGE + " = ?" to arrayOf(packageName),
            CalendarContract.Events.EVENT_COLOR_KEY + " = ?" to arrayOf(LEGACY_TAG),
        )
        for ((selection, args) in selections) {
            // 先尝试一次性批量删除
            val bulk = runCatching {
                resolver.delete(
                    CalendarContract.Events.CONTENT_URI,
                    selection,
                    args,
                )
            }.getOrDefault(0)
            deleted += bulk
            if (bulk > 0) continue
            // 个别机型不支持带 selection 的删除，退回逐条删
            runCatching {
                resolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    arrayOf(CalendarContract.Events._ID),
                    selection,
                    args,
                    null,
                )?.use { c ->
                    val idCol = c.getColumnIndex(CalendarContract.Events._ID)
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        deleted += runCatching {
                            resolver.delete(
                                ContentUris.withAppendedId(
                                    CalendarContract.Events.CONTENT_URI,
                                    id,
                                ),
                                null,
                                null,
                            )
                        }.getOrDefault(0)
                    }
                }
            }
        }
        return deleted
    }

    private fun countOwnEvents(resolver: ContentResolver, packageName: String): Int {
        var count = 0
        runCatching {
            resolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID),
                CalendarContract.Events.CUSTOM_APP_PACKAGE + " = ?",
                arrayOf(packageName),
                null,
            )?.use { c -> count = c.count }
        }
        return count
    }

    // --------------------------------------------------------------- 文案

    fun buildTitle(course: Course): String =
        if (course.room.isEmpty()) {
            "$TITLE_PREFIX ${course.name}"
        } else {
            "$TITLE_PREFIX ${course.name} @${course.room}"
        }

    fun buildDescription(course: Course, week: Int): String = buildString {
        append("第 ").append(week).append(" 周  ")
        append(course.timeLabel).append('\n')
        if (course.teacher.isNotEmpty()) append("教师：").append(course.teacher).append('\n')
        if (course.room.isNotEmpty()) {
            append("场地：").append(course.campus).append(course.room).append('\n')
        }
        if (course.kind.isNotEmpty()) append("性质：").append(course.kind)
        if (course.credits.isNotEmpty()) append("  学分：").append(course.credits)
        if (course.className.isNotEmpty()) {
            append('\n').append("教学班：").append(course.className)
        }
        append('\n').append("（由「课程表」App 自动同步）")
    }

    fun buildLocation(course: Course): String = (course.campus + course.room).trim()
}
