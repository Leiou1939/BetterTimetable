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

/** 某一刻的状态：在上课 / 空闲 / 没课。 */
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
 * 上课中 → 显示还有多久下课；空闲 → 显示下一节几点；今天没课 → 显示明天有没有。
 * 和提示条同一套判断，保证两个入口说法一致。
 */
fun statusOf(data: ScheduleData, today: LocalDate, now: LocalTime): FreeStatus {
    val week = ScheduleCalc.weekOf(data, today)
    val dayIndex = ScheduleCalc.dayIndexOf(today)
    val byIndex = data.periods.associateBy { it.index }

    data class Slot(val course: Course, val start: LocalTime, val end: LocalTime)

    fun slotsOf(date: LocalDate, weekNumber: Int): List<Slot> {
        val day = ScheduleCalc.dayIndexOf(date)
        if (day !in 0..6) return emptyList()
        return data.coursesOn(day, weekNumber).mapNotNull { c ->
            val start = byIndex[c.periodFrom]?.start?.toTime() ?: return@mapNotNull null
            val end = (byIndex[c.periodTo]?.end ?: byIndex[c.periodFrom]?.end)?.toTime()
                ?: return@mapNotNull null
            Slot(c, start, end)
        }
    }

    val todaySlots = slotsOf(today, week).sortedBy { it.start }
    todaySlots.firstOrNull { now >= it.start && now < it.end }?.let { slot ->
        val left = java.time.Duration.between(now, slot.end).toMinutes()
        return FreeStatus(
            inClass = true,
            label = "上课中",
            detail = "${slot.course.name} · " + when {
                left >= 60 -> "${left / 60} 小时 ${left % 60} 分后下课"
                left >= 1 -> "$left 分钟后下课"
                else -> "马上下课"
            },
            accentKey = slot.course.colorKey,
        )
    }

    todaySlots.firstOrNull { it.start > now }?.let { slot ->
        val mins = java.time.Duration.between(now, slot.start).toMinutes()
        return FreeStatus(
            inClass = false,
            label = "空闲",
            detail = "${slot.start.hhmm()} 上课 · " + if (mins >= 60) {
                "${mins / 60} 小时后"
            } else {
                "$mins 分钟后"
            },
            accentKey = slot.course.colorKey,
        )
    }

    // 今天没课了，看后面几天
    for (offset in 1..7) {
        val date = today.plusDays(offset.toLong())
        val w = ScheduleCalc.weekOf(data, date)
        if (w !in 1..data.totalWeeks.coerceAtLeast(1)) continue
        val next = slotsOf(date, w).minByOrNull { it.start } ?: continue
        val dayLabel = if (offset == 1) "明天" else ScheduleCalc.weekdayShort(date)
        return FreeStatus(
            inClass = false,
            label = "没课",
            detail = "$dayLabel ${next.start.hhmm()} 有课",
            accentKey = next.course.colorKey,
        )
    }

    return FreeStatus(inClass = false, label = "没课", detail = "最近一周没有课", accentKey = "")
}

private fun String.toTime(): LocalTime? =
    runCatching { LocalTime.parse(trim()) }.getOrNull()

private fun LocalTime.hhmm(): String = "%02d:%02d".format(hour, minute)
