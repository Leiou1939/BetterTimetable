package com.liangyuchen.timetable.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 课表数据的读写：
 *  - 首次启动从 assets/course_data.json 读取内置课表；
 *  - 之后所有修改（开学日期、作息时间）写进 SharedPreferences。
 */
class CourseRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读取课表：优先用户数据，没有就用内置课表。 */
    fun load(): ScheduleData {
        prefs.getString(KEY_DATA, null)?.let { json ->
            parse(json)?.let { return it }
        }
        return parse(readAsset().toString(Charsets.UTF_8)) ?: ScheduleData()
    }

    fun save(data: ScheduleData) {
        prefs.edit().putString(KEY_DATA, toJson(data).toString()).apply()
    }

    /** 恢复内置课表。 */
    fun reset() {
        prefs.edit().remove(KEY_DATA).apply()
    }

    private fun readAsset(): ByteArray =
        context.assets.open(ASSET_NAME).use { it.readBytes() }

    companion object {
        private const val PREFS = "timetable_prefs"
        private const val KEY_DATA = "schedule_data"
        private const val ASSET_NAME = "course_data.json"

        fun parse(json: String): ScheduleData? = runCatching {
            val root = JSONObject(json)
            val studentJson = root.optJSONObject("student")
            val student = Student(
                name = studentJson?.optString("name", "") ?: "",
                studentId = studentJson?.optString("studentId", "") ?: "",
                term = studentJson?.optString("term", "") ?: "",
            )

            val periods = ArrayList<Period>()
            val periodsJson = root.optJSONArray("periods")
            if (periodsJson != null) {
                for (i in 0 until periodsJson.length()) {
                    val p = periodsJson.optJSONObject(i) ?: continue
                    val index = p.optInt("index", i + 1)
                    periods += Period(
                        index = index,
                        start = p.optString("start", "08:00"),
                        end = p.optString("end", "08:45"),
                    )
                }
            }

            val courses = ArrayList<Course>()
            val coursesJson = root.optJSONArray("courses")
            if (coursesJson != null) {
                for (i in 0 until coursesJson.length()) {
                    val c = coursesJson.optJSONObject(i) ?: continue
                    val weeks = ArrayList<Int>()
                    val weeksJson = c.optJSONArray("weeks")
                    if (weeksJson != null) {
                        for (w in 0 until weeksJson.length()) {
                            weeks += weeksJson.optInt(w, 0)
                        }
                    }
                    val name = c.optString("name", "")
                    if (name.isEmpty()) continue
                    courses += Course(
                        id = c.optString("id", "$i-$name"),
                        name = name,
                        teacher = c.optString("teacher", ""),
                        room = c.optString("room", ""),
                        campus = c.optString("campus", ""),
                        className = c.optString("className", ""),
                        kind = c.optString("kind", ""),
                        credits = c.optString("credits", ""),
                        hours = c.optString("hours", ""),
                        day = c.optInt("day", 0).coerceIn(0, 6),
                        periodFrom = c.optInt("periodFrom", 1).coerceAtLeast(1),
                        periodTo = c.optInt("periodTo", 1).coerceAtLeast(1),
                        weeks = weeks.filter { it > 0 }.distinct().sorted(),
                        weekSpec = c.optString("weekSpec", ""),
                    )
                }
            }

            if (courses.isEmpty()) return@runCatching null

            ScheduleData(
                student = student,
                termStartDate = root.optString("termStartDate", "2026-08-31"),
                totalWeeks = root.optInt("totalWeeks", 20).coerceIn(1, 30),
                periods = if (periods.isEmpty()) Period.DEFAULT else periods,
                courses = courses,
            )
        }.getOrNull()

        fun toJson(data: ScheduleData): JSONObject {
            val root = JSONObject()
            root.put("schemaVersion", 1)
            root.put(
                "student",
                JSONObject()
                    .put("name", data.student.name)
                    .put("studentId", data.student.studentId)
                    .put("term", data.student.term),
            )
            root.put("termStartDate", data.termStartDate)
            root.put("totalWeeks", data.totalWeeks)

            val periodsJson = JSONArray()
            for (p in data.periods) {
                periodsJson.put(
                    JSONObject()
                        .put("index", p.index)
                        .put("start", p.start)
                        .put("end", p.end),
                )
            }
            root.put("periods", periodsJson)

            val coursesJson = JSONArray()
            for (c in data.courses) {
                val weeksJson = JSONArray()
                for (w in c.weeks) weeksJson.put(w)
                coursesJson.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("name", c.name)
                        .put("teacher", c.teacher)
                        .put("room", c.room)
                        .put("campus", c.campus)
                        .put("className", c.className)
                        .put("kind", c.kind)
                        .put("credits", c.credits)
                        .put("hours", c.hours)
                        .put("day", c.day)
                        .put("periodFrom", c.periodFrom)
                        .put("periodTo", c.periodTo)
                        .put("weeks", weeksJson)
                        .put("weekSpec", c.weekSpec),
                )
            }
            root.put("courses", coursesJson)
            return root
        }
    }
}
