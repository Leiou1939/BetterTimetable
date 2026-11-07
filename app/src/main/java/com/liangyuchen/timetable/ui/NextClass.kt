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
    val start: LocalTime,
    val end: LocalTime,
)

/**
 * 今天「还没下课」的第一门课。正在上的课也算（提示语会变成"正在上"）。
 * 当天没课、或者都上完了，返回 null。
 */
internal fun nextClassToday(
    data: ScheduleData,
    week: Int,
    today: LocalDate,
    now: LocalTime,
): NextClass? {
    if (week != ScheduleCalc.weekOf(data, today)) return null
    val dayIndex = ScheduleCalc.dayIndexOf(today)
    if (dayIndex !in 0..6) return null
    val byIndex = data.periods.associateBy { it.index }
    return data.coursesOn(dayIndex, week)
        .mapNotNull { course ->
            val start = byIndex[course.periodFrom]?.start?.let(::parseClock) ?: return@mapNotNull null
            val endText = byIndex[course.periodTo]?.end
                ?: byIndex[course.periodFrom]?.end
                ?: return@mapNotNull null
            val end = parseClock(endText) ?: return@mapNotNull null
            if (end <= now) return@mapNotNull null
            NextClass(course, start, end)
        }
        .minByOrNull { it.start }
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

/**
 * 「下一节」提示条：教室紧跟在科目后面，倒计时靠右。
 *
 * 放在 WeekGrid 外面（TimetableScreen 的列里），所以翻周时它固定不动。
 */
@Composable
fun NextClassBanner(
    data: ScheduleData,
    week: Int,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    // 每分钟刷新一次倒计时
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
    val now = remember(tick) { LocalTime.now() }
    val next = remember(week, today, tick) { nextClassToday(data, week, today, now) }

    val inProgress = next != null && now >= next.start && now < next.end
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
                text = "今天没有课了",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            return@Row
        }
        Text(
            text = if (inProgress) "正在上" else "下一节",
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
            text = if (inProgress) {
                "第 ${next.course.periodFrom}-${next.course.periodTo} 节"
            } else {
                countdownText(next.start, now)
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1,
        )
    }
}
