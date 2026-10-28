package com.liangyuchen.timetable.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liangyuchen.timetable.data.Course
import com.liangyuchen.timetable.data.ScheduleCalc
import com.liangyuchen.timetable.data.ScheduleData
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime

internal val ROW_HEIGHT = 51.dp
private val HEADER_HEIGHT = 62.dp
private val HEADER_HEIGHT_SMALL = 54.dp
private val HEADER_MAX = 132.dp
private val GRID_LINE_WIDTH = 1.dp

/** 行高的上下界：太矮文字挤、太高界面松散（屏幕很高时才用得着上界）。 */
private val ROW_MIN = 44.dp
private val ROW_MAX = 150.dp

/**
 * 按可用高度均分各节次的行高，使表格正好填满屏幕、底部不留空白。
 * 屏幕装不下时退回最小行高（此时外层可滚动）。
 */
internal fun rowHeightFor(availableHeight: Dp, periodCount: Int): Dp {
    if (periodCount <= 0) return ROW_HEIGHT
    if (availableHeight == Dp.Infinity) return ROW_HEIGHT          // 高度无界时按默认
    val headerGuess = if (availableHeight < 520.dp) HEADER_HEIGHT_SMALL else HEADER_HEIGHT
    val raw = (availableHeight - headerGuess) / periodCount
    return raw.coerceIn(ROW_MIN, ROW_MAX)
}

/** 当前时间线：线身高度、端点圆点直径，以及绘制容器高度（必须比圆点高，否则圆点被裁）。 */
private val STROKE_HEIGHT = 2.5.dp
private val DOT_SIZE = 6.dp
private val NOW_LAYER_HEIGHT = 12.dp

/** 时间列的宽度范围，以及 7 天必须一屏放下时的最小日列宽度。 */
private val TIME_COL_MIN = 26.dp
private val TIME_COL_MAX = 40.dp
private val DAY_COL_MIN = 34.dp

/** 每门课一个稳定配色（同名课程同色）。 */
private val COURSE_COLORS = listOf(
    Color(0xFF2C6BE4),
    Color(0xFF0E8E7A),
    Color(0xFFE07B39),
    Color(0xFF8B5CF6),
    Color(0xFFDB2777),
    Color(0xFF0891B2),
    Color(0xFF65A30D),
    Color(0xFFB45309),
    Color(0xFF7C3AED),
    Color(0xFFDC2626),
)

fun colorFor(key: String): Color {
    if (key.isEmpty()) return COURSE_COLORS[0]
    var hash = 7
    for (ch in key) hash = hash * 31 + ch.code
    return COURSE_COLORS[Math.floorMod(hash, COURSE_COLORS.size)]
}

/** 按可用宽度算出的列宽。抽成纯函数，方便单独验证不同分辨率。 */
internal data class GridMetrics(
    val timeCol: Dp,
    val dayCol: Dp,
) {
    /** 7 天是否一屏放下；放不下时退化为横向滚动。 */
    val fits: Boolean get() = dayCol >= DAY_COL_MIN
    val gridWidth: Dp get() = dayCol * 7
}

internal fun gridMetrics(available: Dp, days: Int = 7): GridMetrics {
    val timeCol = (available * 0.115f).coerceIn(TIME_COL_MIN, TIME_COL_MAX)
    val raw = (available - timeCol) / days.coerceAtLeast(1)
    return GridMetrics(
        timeCol = timeCol,
        // 屏幕极窄时不再压缩，改为横向滚动，避免文字完全不可读
        dayCol = if (raw < DAY_COL_MIN) DAY_COL_MIN else raw,
    )
}

/**
 * 表格式周视图：左列固定显示节次/时间，右侧 7 列（周一~周日）。
 *
 * 列宽按可用宽度自适应，保证手机上 7 天能一屏看全；只有屏幕特别窄
 * （7 列压到 [DAY_COL_MIN] 以下）时才退化为横向滚动。
 */
@Composable
fun WeekGrid(
    data: ScheduleData,
    week: Int,
    today: LocalDate,
    selectedDay: Int,
    onCourseClick: (Course) -> Unit,
    onSelectDay: (Int) -> Unit = {},
    onPreviousWeek: () -> Unit = {},
    onNextWeek: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val periods = data.periods
    val monday = ScheduleCalc.mondayOfWeek(data, week)
    val isCurrentWeek = week == ScheduleCalc.weekOf(data, today)
    val todayIndex = ScheduleCalc.dayIndexOf(today)

    // 每分钟刷新一次，线会自己往前走
    var clockTick by remember { mutableStateOf(0) }
    LaunchedEffect(week) {
        while (true) {
            delay(60_000)
            clockTick++
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val days = data.dayCount.coerceIn(1, 7)
        val metrics = gridMetrics(maxWidth, days)
        // 纵向也自适应：把可用高度均分给各节次，避免表格下方留出空白。
        // 屏幕比「62dp 表头 + 10×51dp」矮时保持最小行高并允许滚动。
        val rowHeight = rowHeightFor(maxHeight, periods.size)
        // 节次少（如沈阳工程学院只有 5 个大节）时，行高上限之外剩余的高度
        // 交给表头吸收，避免表格下方又空出一块。
        val minHeader = if (maxHeight != Dp.Infinity && maxHeight < 520.dp) {
            HEADER_HEIGHT_SMALL
        } else {
            HEADER_HEIGHT
        }
        val headerHeight = if (maxHeight == Dp.Infinity) {
            HEADER_HEIGHT
        } else {
            (maxHeight - rowHeight * periods.size).coerceIn(minHeader, HEADER_MAX)
        }
        val gridHeight = rowHeight * periods.size
        val nowTop = remember(week, today, clockTick, rowHeight) {
            nowFractionTop(data, rowHeight)
        }
        val showNowLine = nowTop != null && isCurrentWeek && todayIndex in 0..6

        // 列窄时同步收一点字号，避免文字被压成省略号
        val narrow = metrics.dayCol < 44.dp
        val nameSize = if (narrow) 9.sp else 10.sp
        val roomSize = if (narrow) 8.sp else 9.sp
        val dateSize = if (narrow) 14.sp else 16.sp
        val weekdaySize = if (narrow) 10.sp else 11.sp
        val countSize = if (narrow) 8.sp else 9.sp
        val timeSize = if (metrics.timeCol < 32.dp) 8.sp else 9.sp

        val vScroll = rememberScrollState()
        val hScroll = rememberScrollState()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 左右滑动切周（只在 7 天已经放得下时启用，避免和横向滚动打架）
                .verticalScroll(vScroll),
        ) {
            // 左列：节次 + 上下课时间（永远不横向滚动）
            Column(
                modifier = Modifier
                    .width(metrics.timeCol)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Spacer(Modifier.height(headerHeight))
                periods.forEach { period ->
                    Column(
                        modifier = Modifier
                            .height(rowHeight)
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = period.index.toString(),
                            fontSize = if (narrow) 10.sp else 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = period.start,
                            fontSize = timeSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Text(
                            text = period.end,
                            fontSize = timeSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                        )
                    }
                }
            }

            // 右侧：星期表头 + 课程网格（同一滚动容器）
            // 左右滑动切周只加在这一块上——左边的时间列保持不动。
            val weekSwipe = if (metrics.fits) {
                Modifier.pointerInput(week) {
                    val threshold = 64.dp.toPx()
                    var dragged = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = {
                            if (dragged > threshold) onPreviousWeek()
                            else if (dragged < -threshold) onNextWeek()
                            dragged = 0f
                        },
                        onHorizontalDrag = { change, amount ->
                            dragged += amount
                            change.consume()
                        },
                    )
                }
            } else {
                Modifier
            }
            Row(
                modifier = if (metrics.fits) {
                    weekSwipe
                } else {
                    Modifier.horizontalScroll(hScroll)
                },
            ) {
                Column {
                    DayHeaderRow(
                        data = data,
                        days = days,
                        week = week,
                        monday = monday,
                        today = today,
                        selectedDay = selectedDay,
                        dayCol = metrics.dayCol,
                        headerHeight = headerHeight,
                        dateSize = dateSize,
                        weekdaySize = weekdaySize,
                        countSize = countSize,
                        onSelectDay = onSelectDay,
                    )
                    Box(
                        modifier = Modifier
                            .width(metrics.gridWidth)
                            .height(gridHeight),
                    ) {
                        DrawGridLines(periods.size, gridHeight, metrics.gridWidth, rowHeight)
                        for (day in 0 until days) {
                            Box(
                                modifier = Modifier
                                    .offset(x = metrics.dayCol * day)
                                    .width(metrics.dayCol)
                                    .height(gridHeight)
                                    .background(
                                        if (selectedDay == day) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.04f)
                                        } else {
                                            Color.Transparent
                                        },
                                    ),
                            ) {
                                data.coursesOn(day, week).forEach { course ->
                                    CourseBlock(
                                        course = course,
                                        width = metrics.dayCol,
                                        rowHeight = rowHeight,
                                        nameSize = nameSize,
                                        roomSize = roomSize,
                                        onClick = { onCourseClick(course) },
                                    )
                                }
                            }
                        }
                        // 当前时间线：只画在「今天」那一列里
                        if (showNowLine && nowTop != null) {
                            NowLine(dayIndex = todayIndex, top = nowTop, dayCol = metrics.dayCol)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 当前时间线：只画在 [dayIndex] 那一天（也就是今天）那一列的宽度内。
 *
 * 线身与圆点画在同一张 Canvas 上、共用 midY 中线；容器高度 [NOW_LAYER_HEIGHT]
 * 必须大于圆点直径，否则圆点会被裁成小方块（看起来像「折线」）。
 */
@Composable
private fun NowLine(dayIndex: Int, top: Dp, dayCol: Dp) {
    val color = MaterialTheme.colorScheme.error

    Canvas(
        modifier = Modifier
            .offset(x = dayCol * dayIndex, y = top - NOW_LAYER_HEIGHT / 2)
            .width(dayCol)
            .height(NOW_LAYER_HEIGHT),
    ) {
        val midY = size.height / 2f
        val strokePx = STROKE_HEIGHT.toPx()
        val dotRadius = DOT_SIZE.toPx() / 2f
        // 线身：从圆点圆心开始，保证与圆点连成一条、中间没有缝
        drawRoundRect(
            color = color,
            topLeft = Offset(dotRadius, midY - strokePx / 2f),
            size = Size((size.width - dotRadius).coerceAtLeast(0f), strokePx),
            cornerRadius = CornerRadius(strokePx / 2f, strokePx / 2f),
        )
        // 端点圆点：整个圆都在本列内，圆心与线中线重合
        drawCircle(
            color = color,
            radius = dotRadius,
            center = Offset(dotRadius, midY),
        )
    }
}

@Composable
private fun DrawGridLines(periodCount: Int, gridHeight: Dp, gridWidth: Dp, rowHeight: Dp) {
    val thin = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val thick = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)
    Box(
        modifier = Modifier
            .width(gridWidth)
            .height(gridHeight),
    ) {
        for (p in 0 until periodCount) {
            val isSectionStart = p == 0 || p == 4 || p == 8
            Box(
                modifier = Modifier
                    .offset(y = rowHeight * p)
                    .width(gridWidth)
                    .height(if (isSectionStart) 2.dp else GRID_LINE_WIDTH)
                    .background(if (isSectionStart) thick else thin),
            )
        }
    }
}

@Composable
private fun DayHeaderRow(
    data: ScheduleData,
    days: Int,
    week: Int,
    monday: LocalDate,
    today: LocalDate,
    selectedDay: Int,
    dayCol: Dp,
    headerHeight: Dp,
    dateSize: androidx.compose.ui.unit.TextUnit,
    weekdaySize: androidx.compose.ui.unit.TextUnit,
    countSize: androidx.compose.ui.unit.TextUnit,
    onSelectDay: (Int) -> Unit,
) {
    Row(modifier = Modifier.height(headerHeight)) {
        for (day in 0 until days) {
            val date = monday.plusDays(day.toLong())
            val isToday = date == today
            val isSelected = day == selectedDay
            val bg = when {
                isSelected -> MaterialTheme.colorScheme.primary
                isToday -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surface
            }
            val fg = when {
                isSelected -> MaterialTheme.colorScheme.onPrimary
                isToday -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            }
            val count = data.coursesOn(day, week).size
            Column(
                modifier = Modifier
                    .width(dayCol)
                    .height(headerHeight)
                    .background(bg)
                    .clickable { onSelectDay(day) },   // 点表头即可切到日视图
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(if (headerHeight < HEADER_HEIGHT) 3.dp else 6.dp))
                Text(
                    text = ScheduleCalc.weekdayShortByIndex(day).removePrefix("周"),
                    fontSize = weekdaySize,
                    color = fg.copy(alpha = 0.85f),
                    maxLines = 1,
                )
                Text(
                    text = date.dayOfMonth.toString(),
                    fontSize = dateSize,
                    fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = fg,
                    maxLines = 1,
                )
                Text(
                    text = if (count == 0) " " else "$count 节",
                    fontSize = countSize,
                    color = fg.copy(alpha = 0.75f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CourseBlock(
    course: Course,
    width: Dp,
    rowHeight: Dp,
    nameSize: androidx.compose.ui.unit.TextUnit,
    roomSize: androidx.compose.ui.unit.TextUnit,
    onClick: () -> Unit,
) {
    val span = (course.periodTo - course.periodFrom + 1).coerceAtLeast(1)
    val accent = colorFor(course.colorKey)
    // 能放几行取决于格子的实际高度（而不是"跨几节"），这样大字块不会被省略号截掉
    val usableHeight = (rowHeight * span - 12.dp).coerceAtLeast(18.dp)
    val maxNameLines = (usableHeight / 13.dp).toInt().coerceIn(2, 8)
    val maxRoomLines = ((usableHeight / 13.dp).toInt() / 3).coerceIn(1, 3)
    Surface(
        modifier = Modifier
            .offset(y = rowHeight * (course.periodFrom - 1))
            .padding(1.dp)
            .width(width - 1.dp)
            .height(rowHeight * span - 2.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick),
        color = accent.copy(alpha = 0.16f),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)) {
            Text(
                text = course.name,
                fontSize = nameSize,
                lineHeight = nameSize * 1.2f,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = maxNameLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (course.room.isNotEmpty()) {
                Text(
                    text = "@" + course.room,
                    fontSize = roomSize,
                    lineHeight = roomSize * 1.2f,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = maxRoomLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 「现在」在网格中的纵向位置（相对网格顶部）。
 *
 * 上课时段内，线在第 i 节那一行里线性推进（上课在行顶、下课在行底）；
 * **课间则停在两节课的接缝上**——课表里课与课之间没有空隙，接缝正是
 * 两门课的分界，线停在这里不会压到任何一门课的内容里。
 *
 * 第一节课之前、最后一节下课之后都不画线。
 */
private fun nowFractionTop(data: ScheduleData, rowHeight: Dp = ROW_HEIGHT): Dp? {
    val now = LocalTime.now()
    val nowMinute = now.hour * 60 + now.minute
    val periods = data.periods.sortedBy { it.index }
    if (periods.isEmpty()) return null

    fun minutesOf(text: String): Int? =
        runCatching { LocalTime.parse(text.trim()) }
            .getOrNull()
            ?.let { it.hour * 60 + it.minute }

    val starts = periods.map { minutesOf(it.start) }
    val ends = periods.map { minutesOf(it.end) }

    val firstStart = starts.firstOrNull() ?: return null
    if (nowMinute < firstStart) return null
    val lastEnd = ends.lastOrNull() ?: return null
    if (nowMinute >= lastEnd) return null

    for (i in periods.indices) {
        val start = starts[i] ?: continue
        val end = ends[i] ?: continue
        // 正在上第 i 节：在本行内推进
        if (nowMinute in start until end) {
            val span = (end - start).coerceAtLeast(1)
            val passed = nowMinute - start
            return rowHeight * i + rowHeight * (passed.toFloat() / span.toFloat())
        }
        // 课间：停在第 i 节与第 i+1 节的接缝上
        val nextStart = starts.getOrNull(i + 1) ?: break
        if (nowMinute in end until nextStart) {
            return rowHeight * (i + 1)
        }
    }
    return null
}
