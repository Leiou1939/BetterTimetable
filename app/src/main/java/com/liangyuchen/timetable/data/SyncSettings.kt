package com.liangyuchen.timetable.data

import android.content.Context

/**
 * 日历同步相关的偏好：上次选中的日历、提醒提前量、是否自动同步。
 *
 * 记住上次的日历很关键——不然每次同步都要重新点一遍；配合「自动同步」，
 * 导入新 PDF 或改完开学日期后可以自动把日程补进日历。
 */
data class SyncPrefs(
    val calendarId: Long = -1L,
    val calendarName: String = "",
    val reminderMinutes: Int = CalendarSync.DEFAULT_REMIND_MINUTES,
    val autoSync: Boolean = true,
) {
    val hasCalendar: Boolean get() = calendarId > 0L
}

object SyncSettings {

    private const val PREFS = "timetable_sync"
    private const val KEY_CALENDAR_ID = "calendar_id"
    private const val KEY_CALENDAR_NAME = "calendar_name"
    private const val KEY_REMIND_MINUTES = "reminder_minutes"
    private const val KEY_AUTO_SYNC = "auto_sync"

    /** 可选的提醒提前量（分钟）。 */
    val REMINDER_CHOICES = listOf(0, 5, 10, 15, 30, 60)

    fun load(context: Context): SyncPrefs {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SyncPrefs(
            calendarId = p.getLong(KEY_CALENDAR_ID, -1L),
            calendarName = p.getString(KEY_CALENDAR_NAME, "") ?: "",
            reminderMinutes = p.getInt(KEY_REMIND_MINUTES, CalendarSync.DEFAULT_REMIND_MINUTES),
            autoSync = p.getBoolean(KEY_AUTO_SYNC, true),
        )
    }

    fun saveCalendar(context: Context, id: Long, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_CALENDAR_ID, id)
            .putString(KEY_CALENDAR_NAME, name)
            .apply()
    }

    fun saveReminderMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_REMIND_MINUTES, minutes.coerceIn(0, 24 * 60))
            .apply()
    }

    fun setAutoSync(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_SYNC, enabled)
            .apply()
    }
}
