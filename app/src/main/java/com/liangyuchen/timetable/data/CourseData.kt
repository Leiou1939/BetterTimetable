package com.liangyuchen.timetable.data

/**
 * 一节课的上下课时间（第 [index] 节）。
 */
data class Period(
    val index: Int,
    val start: String,
    val end: String,
) {
    companion object {
        /** 缺省作息时间；PDF 课表不含时间，可在设置里逐节改。 */
        val DEFAULT: List<Period> = listOf(
            Period(1, "08:20", "09:05"),
            Period(2, "09:10", "09:55"),
            Period(3, "10:10", "10:55"),
            Period(4, "11:00", "11:45"),
            Period(5, "13:15", "14:00"),
            Period(6, "14:05", "14:50"),
            Period(7, "15:00", "15:45"),
            Period(8, "15:50", "16:35"),
            Period(9, "17:30", "18:15"),
            Period(10, "18:25", "19:10"),
        )
    }
}

fun List<Period>.at(index: Int): Period =
    firstOrNull { it.index == index } ?: Period(index, "08:00", "09:00")

/**
 * 一条课程安排。[day] 为 0=周一 … 6=周日；[weeks] 是实际上课的周次（已展开单双周）。
 */
data class Course(
    val id: String,
    val name: String,
    val teacher: String = "",
    val room: String = "",
    val campus: String = "",
    val className: String = "",
    val kind: String = "",
    val credits: String = "",
    val hours: String = "",
    val day: Int = 0,
    val periodFrom: Int = 1,
    val periodTo: Int = 1,
    val weeks: List<Int> = emptyList(),
    val weekSpec: String = "",
) {
    fun occursIn(week: Int): Boolean = weeks.contains(week)

    /** true 表示这门课没有固定节次（课表 PDF 里列在表格外的实践课等）。 */
    val isExtra: Boolean get() = periodFrom <= 0 || periodTo <= 0

    /** 用于稳定配色的键（同名课程同色）。 */
    val colorKey: String get() = name

    val timeLabel: String
        get() = when {
            isExtra -> "无固定节次"
            periodFrom == periodTo -> "第 $periodFrom 节"
            else -> "第 $periodFrom-$periodTo 节"
        }
}

data class Student(
    val name: String = "",
    val studentId: String = "",
    val term: String = "",
)

/**
 * 整份课表。与 assets/course_data.json 一一对应，可直接序列化回 SharedPreferences。
 */
data class ScheduleData(
    val student: Student = Student(),
    val termStartDate: String = "2026-08-31",
    val totalWeeks: Int = 20,
    val periods: List<Period> = Period.DEFAULT,
    val courses: List<Course> = emptyList(),
) {
    fun course(id: String): Course? = courses.firstOrNull { it.id == id }

    /** 同一时段重叠的课程（同格多门课时依次排列）。 */
    fun coursesAt(day: Int, week: Int): List<Course> =
        courses.filter { it.day == day && it.occursIn(week) && !it.isExtra }
            .sortedBy { it.periodFrom }

    /** 某周某天的课程（按节次排序）。 */
    fun coursesOn(day: Int, week: Int): List<Course> =
        courses.filter { it.day == day && it.occursIn(week) && !it.isExtra }
            .sortedWith(compareBy({ it.periodFrom }, { it.name }))

    /** 无固定节次的课程（课表 PDF 里列在表格外的实践课等）。 */
    fun extraCourses(): List<Course> = courses.filter { it.isExtra }.sortedBy { it.name }

    fun distinctCourses(): List<Course> =
        courses.distinctBy { it.name }
}
