package com.liangyuchen.timetable.data

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.TimeZone

/** 一次具体的上课（已换算到日历日期）。 */
data class ClassOccurrence(
    val course: Course,
    val week: Int,
    val date: LocalDate,
    val startMillis: Long,
    val endMillis: Long,
)

/**
 * 周次 ↔ 日期换算，以及“某一周要上哪些课”。
 *
 * 约定：第 1 周的周一是 [ScheduleData.termStartDate]（开学第一周）。
 */
object ScheduleCalc {

    private val isoDate: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val WEEKDAY_SHORT = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun parseDate(text: String): LocalDate =
        runCatching { LocalDate.parse(text.trim(), isoDate) }.getOrElse { LocalDate.now() }

    fun format(date: LocalDate): String = date.format(isoDate)

    /** 第 [week] 周的周一。 */
    fun mondayOfWeek(data: ScheduleData, week: Int): LocalDate =
        parseDate(data.termStartDate).plusDays(((week - 1) * 7).toLong())

    /** [date] 落在第几周（可能 <1 或 >totalWeeks）。 */
    fun weekOf(data: ScheduleData, date: LocalDate): Int {
        val start = parseDate(data.termStartDate)
        val days = ChronoUnit.DAYS.between(start, date)
        // 用 Math.floorDiv 而不是 Long.floorDiv，避开 Kotlin 标准库里的弃用扩展
        return Math.floorDiv(days, 7L).toInt() + 1
    }

    /** 今天是第几周（限制在 1..totalWeeks）。 */
    fun currentWeek(data: ScheduleData, today: LocalDate = LocalDate.now()): Int =
        weekOf(data, today).coerceIn(1, data.totalWeeks)

    /** 周一 = 0 … 周日 = 6 */
    fun dayIndexOf(date: LocalDate): Int = date.dayOfWeek.value - 1

    fun weekRangeLabel(data: ScheduleData, week: Int): String {
        val mon = mondayOfWeek(data, week)
        val sun = mon.plusDays(6)
        return "${mon.monthValue}月${mon.dayOfMonth}日 - ${sun.monthValue}月${sun.dayOfMonth}日"
    }

    /** 把日期 + "HH:mm" 换算成日历用的 epoch 毫秒（设备本地时区）。 */
    fun epochMillis(date: LocalDate, time: String): Long {
        val parsed = runCatching { LocalTime.parse(time.trim()) }.getOrElse { LocalTime.MIDNIGHT }
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(date.year, date.monthValue - 1, date.dayOfMonth, parsed.hour, parsed.minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** 设备时区名，如 "Asia/Shanghai"。 */
    fun deviceTimeZoneId(): String = TimeZone.getDefault().id

    /** 某个时段开始的 epoch 毫秒。 */
    fun startMillis(data: ScheduleData, course: Course, date: LocalDate): Long =
        epochMillis(date, data.periods.at(course.periodFrom).start)

    /** 结束时间：下课后 10 分钟，便于日历里连续两节不粘连。 */
    fun endMillis(data: ScheduleData, course: Course, date: LocalDate): Long {
        val base = epochMillis(date, data.periods.at(course.periodTo).end)
        return base + 10 * 60 * 1000L
    }

    /** 第 [week] 周某天（[day] 0=周一）的课程，按节次排序；不含无固定节次的课。 */
    fun coursesFor(data: ScheduleData, week: Int, day: Int): List<Course> =
        data.courses.filter { it.day == day && it.occursIn(week) && !it.isExtra }
            .sortedWith(compareBy({ it.periodFrom }, { it.name }))

    /** 无固定节次的课程（实践课等），按名称排序。 */
    fun extraCourses(data: ScheduleData): List<Course> =
        data.courses.filter { it.isExtra }.sortedBy { it.name }

    /** 把课程展开成具体日期（无固定节次的课程会被跳过）。 */
    fun occurrences(
        data: ScheduleData,
        courses: List<Course> = data.courses,
        from: LocalDate? = null,
        until: LocalDate? = null,
        limit: Int = 800,
    ): List<ClassOccurrence> {
        val result = ArrayList<ClassOccurrence>()
        for (course in courses) {
            if (course.isExtra) continue
            for (week in course.weeks.sorted()) {
                val date = mondayOfWeek(data, week).plusDays(course.day.toLong())
                if (from != null && date.isBefore(from)) continue
                if (until != null && date.isAfter(until)) continue
                result += ClassOccurrence(
                    course = course,
                    week = week,
                    date = date,
                    startMillis = startMillis(data, course, date),
                    endMillis = endMillis(data, course, date),
                )
                if (result.size >= limit) return result.sortedBy { it.startMillis }
            }
        }
        return result.sortedBy { it.startMillis }
    }

    /** “周一”…“周日” */
    fun weekdayShort(date: LocalDate): String = WEEKDAY_SHORT[dayIndexOf(date)]

    fun weekdayShortByIndex(day: Int): String = WEEKDAY_SHORT[day.coerceIn(0, 6)]

    fun fullWeekdayNames(): List<String> = listOf(
        "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日",
    )
}
