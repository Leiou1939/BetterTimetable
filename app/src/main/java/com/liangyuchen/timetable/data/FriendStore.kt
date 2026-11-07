package com.liangyuchen.timetable.data

import android.content.Context
import android.content.SharedPreferences
import com.liangyuchen.timetable.school.School
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/**
 * 同学的课表：只存在本机，用来比对"谁在上课、谁有空"。
 *
 * 结构很简单：昵称 + 学校 + 一份课表的 JSON（和主课表同一套格式）。
 */
data class Friend(
    val id: String,
    val nickname: String,
    val schoolId: String,
    val scheduleJson: String,
) {
    val school: School get() = School.fromId(schoolId)
    val data: ScheduleData? get() = CourseRepository.parse(scheduleJson)
}

/** 某一刻的状态：在上课 / 空闲。 */
data class FreeStatus(
    val inClass: Boolean,
    val label: String,
    val detail: String,
    val accentKey: String,
)

class FriendStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<Friend> {
        val raw = prefs.getString(KEY_FRIENDS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifEmpty { return@mapNotNull null }
                val json = o.optString("schedule").ifEmpty { return@mapNotNull null }
                Friend(
                    id = id,
                    nickname = o.optString("nickname", "同学"),
                    schoolId = o.optString("school", ""),
                    scheduleJson = json,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(friends: List<Friend>) {
        val array = JSONArray()
        friends.forEach { f ->
            array.put(
                JSONObject()
                    .put("id", f.id)
                    .put("nickname", f.nickname)
                    .put("school", f.schoolId)
                    .put("schedule", f.scheduleJson),
            )
        }
        prefs.edit().putString(KEY_FRIENDS, array.toString()).apply()
    }

    fun add(friend: Friend) = save(load().filterNot { it.id == friend.id } + friend)

    fun remove(id: String) = save(load().filterNot { it.id == id })

    private companion object {
        const val PREFS = "timetable_friends"
        const val KEY_FRIENDS = "friends"
    }
}

/**
 * 算某人在「现在这一刻」的状态。
 *
 * 只看"现在"：在上课就报课程、教室和还有多久下课；不在上课就是空闲。
 */
fun statusOf(data: ScheduleData, today: LocalDate, now: LocalTime): FreeStatus {
    val week = ScheduleCalc.weekOf(data, today)
    val dayIndex = ScheduleCalc.dayIndexOf(today)
    val byIndex = data.periods.associateBy { it.index }

    if (dayIndex in 0..6) {
        val current = data.coursesOn(dayIndex, week).firstOrNull { course ->
            val start = byIndex[course.periodFrom]?.start?.toTime() ?: return@firstOrNull false
            val end = (byIndex[course.periodTo]?.end ?: byIndex[course.periodFrom]?.end)
                ?.toTime() ?: return@firstOrNull false
            now >= start && now < end
        }
        if (current != null) {
            val endText = byIndex[current.periodTo]?.end ?: byIndex[current.periodFrom]?.end
            val end = endText?.toTime()
            val left = if (end != null) java.time.Duration.between(now, end).toMinutes() else -1
            val place = if (current.room.isNotEmpty()) " @${current.room}" else ""
            val tail = when {
                left < 0 -> ""
                left >= 60 -> " · ${left / 60} 小时 ${left % 60} 分后下课"
                left >= 1 -> " · $left 分钟后下课"
                else -> " · 马上下课"
            }
            return FreeStatus(
                inClass = true,
                label = "上课中",
                detail = current.name + place + tail,
                accentKey = current.colorKey,
            )
        }
    }

    // 不在上课就是空闲；不再预告后面的课
    return FreeStatus(inClass = false, label = "空闲", detail = "", accentKey = "")
}

private fun String.toTime(): LocalTime? =
    runCatching { LocalTime.parse(trim()) }.getOrNull()

private fun LocalTime.hhmm(): String = "%02d:%02d".format(hour, minute)
