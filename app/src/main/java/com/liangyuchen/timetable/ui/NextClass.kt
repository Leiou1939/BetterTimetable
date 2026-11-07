package com.liangyuchen.timetable.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liangyuchen.timetable.data.Course
import com.liangyuchen.timetable.data.ScheduleData
import com.liangyuchen.timetable.data.ScheduleCalc
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/** 提示条固定高度：常驻，所以高度不随内容变。 */
internal val NEXT_BANNER_HEIGHT = 34.dp

internal data class NextClass(
    val course: Course,
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
)

/**
 * 最近的一节课：从今天此刻开始往后找，最多看 7 天。
 *
 * 和"当前翻到第几周"无关 —— 翻页时提示条内容不会变。
 * 正在上的课也会优先返回（提示语会变成"正在上"）。
 */
internal fun nearestClass(
    data: ScheduleData,
    today: LocalDate,
    now: LocalTime,
): NextClass? {
    val byIndex = data.periods.associateBy { it.index }
    for (offset in 0..7) {
        val date = today.plusDays(offset.toLong())
        val week = ScheduleCalc.weekOf(data, date)
        if (week !in 1..data.totalWeeks.coerceAtLeast(1)) continue
        val dayIndex = ScheduleCalc.dayIndexOf(date)
        if (dayIndex !in 0..6) continue

        val found = data.coursesOn(dayIndex, week)
            .mapNotNull { course ->
                val start = byIndex[course.periodFrom]?.start?.let(::parseClock)
                    ?: return@mapNotNull null
                val endText = byIndex[course.periodTo]?.end
                    ?: byIndex[course.periodFrom]?.end
                    ?: return@mapNotNull null
                val end = parseClock(endText) ?: return@mapNotNull null
                // 今天的课要"还没下课"；以后的课全算
                if (offset == 0 && end <= now) return@mapNotNull null
                NextClass(course, date, start, end)
            }
            .minByOrNull { it.start }

        if (found != null) return found
    }
    return null
}

private fun parseClock(text: String): LocalTime? =
    runCatching { LocalTime.parse(text.trim()) }.getOrNull()

/** 「12 分钟后」这种说法：满一小时才说小时。 */
internal fun countdownText(start: LocalTime, now: LocalTime): String {
    val minutes = Duration.between(now, start).toMinutes()
    return when {
        minutes >= 60 -> "${minutes / 60} 小时 ${minutes % 60} 分后"
        minutes >= 1 -> "$minutes 分钟后"
        else -> "马上开始"
    }
}

/** 正在上课时：还有多久下课。 */
internal fun untilEndText(end: LocalTime, now: LocalTime): String {
    val minutes = Duration.between(now, end).toMinutes()
    return when {
        minutes >= 60 -> "${minutes / 60} 小时 ${minutes % 60} 分后下课"
        minutes >= 1 -> "$minutes 分钟后下课"
        else -> "马上下课"
    }
}

/** 课程在几天后：「今天」「明天」「周三」。 */
private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "今天"
    today.plusDays(1) -> "明天"
    else -> ScheduleCalc.weekdayShort(date)
}

/**
 * 「下一节」提示条：最近的一节课，与当前翻到第几周无关。
 *
 * 教室紧跟在科目后面，时间信息靠右。
 */
@Composable
fun NextClassBanner(
    data: ScheduleData,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    // 每 30 秒刷新一次倒计时
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
    val now = remember(tick) { LocalTime.now() }
    val next = remember(today, tick) { nearestClass(data, today, now) }

    val inProgress = next != null && next.date == today && now >= next.start && now < next.end
    val accent = next?.let { colorFor(it.course.colorKey) }
        ?: MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(NEXT_BANNER_HEIGHT)
            .background(accent.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accent),
        )
        Spacer(Modifier.width(6.dp))
        if (next == null) {
            Text(
                text = "最近一周没有课了",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            return@Row
        }
        Text(
            text = when {
                inProgress -> "正在上"
                next.date == today -> "下一节"
                else -> dayLabel(next.date, today)
            },
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = next.course.name,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 教室紧跟在科目后面
        if (next.course.room.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = "@" + next.course.room,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = when {
                inProgress -> untilEndText(next.end, now)
                next.date == today -> countdownText(next.start, now)
                else -> "%02d:%02d".format(next.start.hour, next.start.minute)
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1,
        )
    }
}
