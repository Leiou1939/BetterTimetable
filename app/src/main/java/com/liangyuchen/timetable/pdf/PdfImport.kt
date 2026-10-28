package com.liangyuchen.timetable.pdf

import android.content.Context
import android.net.Uri
import com.liangyuchen.timetable.data.Course
import com.liangyuchen.timetable.data.Period
import com.liangyuchen.timetable.data.ScheduleData
import com.liangyuchen.timetable.data.Student

/**
 * Turns a parsed PDF into the app's data model, keeping whatever the user has
 * already configured (term start date, period clock times, total weeks).
 */
object PdfImport {

    /** Read a PDF from a content Uri and parse it. */
    fun parse(context: Context, uri: Uri): ParsedTimetable {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return ParsedTimetable(emptyList(), warnings = listOf("无法读取所选文件"))
        if (bytes.isEmpty()) {
            return ParsedTimetable(emptyList(), warnings = listOf("所选文件是空的"))
        }
        return try {
            TimetablePdfParser.parse(bytes)
        } catch (t: Throwable) {
            ParsedTimetable(
                emptyList(),
                warnings = listOf("解析失败：${t.message ?: t.javaClass.simpleName}"),
            )
        }
    }

    /** Build the new schedule, keeping the user's date/period settings. */
    fun toScheduleData(
        parsed: ParsedTimetable,
        previous: ScheduleData,
        schoolId: String = previous.schoolId,
    ): ScheduleData {
        val courses = ArrayList<Course>()
        parsed.courses.forEach { c ->
            courses += Course(
                id = buildId(c.name, c.day, c.periodFrom),
                name = c.name,
                teacher = c.teacher,
                room = c.room,
                campus = c.campus,
                className = c.className,
                kind = c.kind,
                credits = c.credits,
                hours = c.hours,
                day = c.day,
                periodFrom = c.periodFrom,
                periodTo = c.periodTo,
                weeks = c.weeks,
                weekSpec = c.weekSpec,
            )
        }

        // 文件自带作息（沈阳工程学院 .xls 每行都写着 08:20-10:00）就直接用它，
        // 否则沿用用户设置并补齐到新用到的节次。
        val periods = parsed.periods?.takeIf { it.isNotEmpty() }
            ?: if (schoolId != previous.schoolId) {
                // 换学校了：旧学校的作息（比如沈阳工程学院的 5 大节）不能带过来
                Period.DEFAULT
            } else {
                ensurePeriods(previous.periods, parsed.maxPeriod)
            }

        val student = if (parsed.studentName.isNotEmpty() || parsed.studentId.isNotEmpty()) {
            Student(
                name = parsed.studentName.ifEmpty { previous.student.name },
                studentId = parsed.studentId.ifEmpty { previous.student.studentId },
                term = parsed.term.ifEmpty { previous.student.term },
            )
        } else {
            previous.student
        }

        val maxWeek = courses.flatMap { it.weeks }.maxOrNull() ?: previous.totalWeeks
        return previous.copy(
            student = student,
            totalWeeks = maxOf(previous.totalWeeks, maxWeek).coerceIn(1, 30),
            periods = periods,
            dayCount = parsed.dayCount.coerceIn(5, 7),
            schoolId = schoolId,
            courses = courses.sortedWith(
                compareBy({ it.day }, { it.periodFrom }, { it.name }),
            ),
        )
    }

    private fun buildId(name: String, day: Int, periodFrom: Int): String =
        "$day-$periodFrom-$name"

    /** Extend the period list (keeping the user's edited times) up to [count]. */
    private fun ensurePeriods(current: List<Period>, count: Int): List<Period> {
        val wanted = count.coerceIn(current.size, 14)
        if (wanted <= current.size) return current
        val out = ArrayList(current)
        val defaults = Period.DEFAULT
        for (index in current.size + 1..wanted) {
            out += defaults.firstOrNull { it.index == index } ?: Period(index, "08:00", "08:45")
        }
        return out
    }
}
