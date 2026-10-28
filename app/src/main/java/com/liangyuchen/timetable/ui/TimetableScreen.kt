package com.liangyuchen.timetable.ui

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liangyuchen.timetable.AppViewModel
import com.liangyuchen.timetable.data.CalendarAccount
import com.liangyuchen.timetable.data.CalendarSync
import com.liangyuchen.timetable.data.Course
import com.liangyuchen.timetable.data.IcsExport
import com.liangyuchen.timetable.data.ScheduleCalc
import com.liangyuchen.timetable.data.ScheduleData
import com.liangyuchen.timetable.data.SyncSettings
import com.liangyuchen.timetable.data.SyncPrefs
import com.liangyuchen.timetable.data.at
import com.liangyuchen.timetable.update.UpdateInfo
import com.liangyuchen.timetable.update.UpdateDialog
import com.liangyuchen.timetable.update.UpdateChecker
import com.liangyuchen.timetable.update.AutoUpdateCheck
import com.liangyuchen.timetable.update.UpdateAvailableDialog
import com.liangyuchen.timetable.update.UpdateSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 权限通过后要继续执行的动作。 */
enum class PendingCalendarAction { SYNC, EXPORT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    viewModel: AppViewModel,
    context: Context,
    pendingCalendarAction: PendingCalendarAction?,
    onCalendarPermissionNeeded: (PendingCalendarAction) -> Unit,
    onCalendarActionFinished: () -> Unit,
) {
    val data = viewModel.data
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val today = viewModel.today
    // 极窄屏（<340dp，如 320dp 老机型）顶栏放不下 4 个图标 + 周次条，
    // 此时把「导入 PDF」收进设置里（设置页一直有这一项）。
    val compactBar = LocalConfiguration.current.screenWidthDp < 340

    var selectedDay by remember { mutableStateOf(ScheduleCalc.dayIndexOf(today)) }
    var dayMode by remember { mutableStateOf(false) }
    var detailCourse by remember { mutableStateOf<Course?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showCalendarPicker by remember { mutableStateOf(false) }
    var calendars by remember { mutableStateOf<List<CalendarAccount>>(emptyList()) }
    var syncPrefs by remember { mutableStateOf(SyncSettings.load(context)) }
    var syncing by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var pendingUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateToInstall by remember { mutableStateOf<UpdateInfo?>(null) }

    // 启动时自动检查：有新版本就弹窗（版本号 + 更新内容 + 立即更新/下次再说）
    AutoUpdateCheck(context = context) { info ->
        pendingUpdate = info
    }
    pendingUpdate?.let { info ->
        UpdateAvailableDialog(
            info = info,
            onUpdate = {
                pendingUpdate = null
                updateToInstall = info          // 进对话框后直接开始下载，不用再点一次
                showUpdateDialog = true
            },
            onLater = {
                UpdateSettings.markSkipped(context, info.versionCode)
                pendingUpdate = null
            },
        )
    }

    // 选择课表 PDF 并导入
    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.startImport(uri)
    }

    /**
     * 把当前课表写进系统日历。
     * 注意：这里始终用 `viewModel.data`（而不是组合时捕获的 data），
     * 否则「导入后自动同步」会把上一份课表写进日历。
     */
    val runSync: (CalendarAccount) -> Unit = remember(context, viewModel) {
        { account ->
            scope.launch {
                syncing = true
                val minutes = syncPrefs.reminderMinutes
                val result = withContext(Dispatchers.IO) {
                    runCatching { CalendarSync.sync(context, viewModel.data, account.id, minutes) }
                }
                syncing = false
                SyncSettings.saveCalendar(context, account.id, account.name)
                syncPrefs = SyncSettings.load(context)
                result.onSuccess { r ->
                    val text = buildString {
                        append("已写入 ").append(r.inserted).append(" 条日程")
                        if (r.reminders > 0) append("，").append(r.reminders).append(" 条带提醒")
                        if (r.reminderMinutes > 0) {
                            append("（提前 ").append(r.reminderMinutes).append(" 分钟）")
                        }
                        append(" → 「").append(account.name).append("」")
                        if (r.errors > 0) append("；失败 ").append(r.errors).append(" 条")
                        if (r.inserted > 0 && r.reminders == 0) {
                            append("。⚠️ 该日历不允许自定义提醒，已改为日历默认提醒")
                        }
                        if (r.firstDate != null) append("\n范围：").append(r.firstDate)
                            .append(" ~ ").append(r.lastDate)
                    }
                    val res = snackbar.showSnackbar(
                        message = text,
                        actionLabel = "换日历",
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                    if (res == SnackbarResult.ActionPerformed) {
                        calendars = CalendarSync.listCalendars(context)
                        showCalendarPicker = true
                    }
                }.onFailure {
                    snackbar.showSnackbar(
                        "同步失败：${it.message ?: it.javaClass.simpleName}。" +
                            "请在系统设置里确认已允许「课程表」访问日历。",
                        duration = SnackbarDuration.Long,
                    )
                }
            }
        }
    }

    // 权限授予后继续
    LaunchedEffect(pendingCalendarAction) {
        when (pendingCalendarAction) {
            PendingCalendarAction.SYNC -> {
                calendars = CalendarSync.listCalendars(context)
                showCalendarPicker = true
            }
            PendingCalendarAction.EXPORT -> shareIcs(context, viewModel.data)
            null -> Unit
        }
        if (pendingCalendarAction != null) onCalendarActionFinished()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                title = {
                    WeekIndicator(
                        week = viewModel.week,
                        isCurrentWeek = viewModel.week == viewModel.weekOfToday(),
                        compact = compactBar,
                        onPrevious = {
                            viewModel.previousWeek()
                            selectedDay = ScheduleCalc.dayIndexOf(today)
                        },
                        onNext = {
                            viewModel.nextWeek()
                            selectedDay = ScheduleCalc.dayIndexOf(today)
                        },
                        onBackToCurrentWeek = {
                            viewModel.goToToday()
                            selectedDay = ScheduleCalc.dayIndexOf(today)
                        },
                    )
                },
                actions = {
                    // 与左侧「下一周 ›」之间留出间距 + 一条竖分隔线，
                    // 让"翻周"和"功能按钮"在视觉与触控上分开，减少误点。
                    Box(
                        modifier = Modifier
                            .padding(start = 4.dp, end = 6.dp)
                            .width(1.dp)
                            .height(22.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                    )
                    IconButton(onClick = {
                        dayMode = !dayMode
                    }) {
                        Icon(
                            if (dayMode) Icons.Filled.CalendarMonth else Icons.Filled.DateRange,
                            contentDescription = if (dayMode) "周视图" else "日视图",
                        )
                    }
                    // 窄屏时省掉「导入」（设置页里有同一入口），保证周次条不被挤
                    if (!compactBar) {
                        IconButton(onClick = {
                            pdfPicker.launch(
                                arrayOf("application/pdf", "application/octet-stream", "*/*"),
                            )
                        }) {
                            Icon(Icons.Filled.FileOpen, contentDescription = "导入课表 PDF")
                        }
                    }
                    IconButton(onClick = {
                        if (CalendarSync.hasPermission(context)) {
                            // 记住过日历就一键同步；没记住过才弹选择框
                            val saved = syncPrefs
                            if (saved.hasCalendar) {
                                val account = CalendarSync.listCalendars(context)
                                    .firstOrNull { it.id == saved.calendarId }
                                if (account != null) {
                                    runSync(account)
                                } else {
                                    calendars = CalendarSync.listCalendars(context)
                                    showCalendarPicker = true
                                }
                            } else {
                                calendars = CalendarSync.listCalendars(context)
                                showCalendarPicker = true
                            }
                        } else {
                            onCalendarPermissionNeeded(PendingCalendarAction.SYNC)
                        }
                    }, enabled = !syncing) {
                        if (syncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Filled.Sync, contentDescription = "同步到系统日历")
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            // The grid draws its own weekday header, so there is no second week
            // strip here — that row used to repeat the same dates twice.
            if (dayMode) {
                DayList(
                    data = data,
                    week = viewModel.week,
                    day = selectedDay,
                    date = ScheduleCalc.mondayOfWeek(data, viewModel.week)
                        .plusDays(selectedDay.toLong()),
                    onCourseClick = { detailCourse = it },
                    modifier = Modifier.weight(1f),
                )
            } else {
                WeekGrid(
                    data = data,
                    week = viewModel.week,
                    today = today,
                    selectedDay = selectedDay,
                    onCourseClick = { detailCourse = it },
                    onSelectDay = {
                        selectedDay = it
                        dayMode = true
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    detailCourse?.let { course ->
        CourseDetailDialog(
            course = course,
            data = data,
            onDismiss = { detailCourse = null },
            onSyncToCalendar = {
                detailCourse = null
                if (!CalendarSync.hasPermission(context)) {
                    onCalendarPermissionNeeded(PendingCalendarAction.SYNC)
                } else {
                    val saved = syncPrefs
                    val account = if (saved.hasCalendar) {
                        CalendarSync.listCalendars(context)
                            .firstOrNull { it.id == saved.calendarId }
                    } else {
                        null
                    }
                    if (account != null) {
                        runSync(account)
                    } else {
                        calendars = CalendarSync.listCalendars(context)
                        showCalendarPicker = true
                    }
                }
            },
            onShareIcs = {
                detailCourse = null
                if (CalendarSync.hasPermission(context)) {
                    shareIcs(context, data)
                } else {
                    onCalendarPermissionNeeded(PendingCalendarAction.EXPORT)
                }
            },
        )
    }

    if (showSettings) {
        SettingsDialog(
            data = data,
            currentWeek = viewModel.week,
            sync = syncPrefs,
            onDismiss = { showSettings = false },
            onTermStartChange = { viewModel.setTermStart(it) },
            onTotalWeeksChange = { viewModel.setTotalWeeks(it) },
            onPeriodChange = { index, start, end -> viewModel.setPeriodTime(index, start, end) },
            onReset = {
                viewModel.resetToBundled()
                showSettings = false
            },
            onImportPdf = {
                showSettings = false
                pdfPicker.launch(
                    arrayOf("application/pdf", "application/octet-stream", "*/*"),
                )
            },
            onGoToCurrentWeek = {
                viewModel.goToToday()
                selectedDay = ScheduleCalc.dayIndexOf(today)
                showSettings = false
            },
            onReminderMinutesChange = { minutes ->
                SyncSettings.saveReminderMinutes(context, minutes)
                syncPrefs = SyncSettings.load(context)
            },
            onAutoSyncChange = { enabled ->
                SyncSettings.setAutoSync(context, enabled)
                syncPrefs = SyncSettings.load(context)
            },
            onClearCalendarEvents = {
                scope.launch {
                    val removed = withContext(Dispatchers.IO) {
                        runCatching { CalendarSync.clearEvents(context) }.getOrDefault(0)
                    }
                    snackbar.showSnackbar("已从日历删除 $removed 条本 App 写入的日程")
                }
            },
            onCheckUpdate = {
                showSettings = false
                showUpdateDialog = true
            },
        )
    }

    if (showUpdateDialog) {
        UpdateDialog(
            context = context,
            autoStartInfo = updateToInstall,
            onDismiss = {
                showUpdateDialog = false
                updateToInstall = null
            },
        )
    }

    // 导入 PDF 的状态提示与预览
    viewModel.importResult?.let { parsed ->
        ImportPreviewDialog(
            parsed = parsed,
            onConfirm = {
                viewModel.confirmImport()
                // 默认「自动同步」：导入新课后立刻把日程补进日历，
                // 免得用户以为同步过就不用再点一次。
                val prefs = SyncSettings.load(context)
                val savedAccount = if (prefs.autoSync && prefs.hasCalendar &&
                    CalendarSync.hasPermission(context)
                ) {
                    CalendarSync.listCalendars(context)
                        .firstOrNull { it.id == prefs.calendarId }
                } else {
                    null
                }
                if (savedAccount != null) {
                    runSync(savedAccount)
                } else {
                    scope.launch {
                        snackbar.showSnackbar(
                            "已导入 ${parsed.courses.size} 门课程。" +
                                "记得核对开学第一周日期与作息时间。",
                        )
                    }
                }
            },
            onDismiss = viewModel::cancelImport,
        )
    }

    viewModel.importError?.let { message ->
        ImportErrorDialog(message = message, onDismiss = viewModel::dismissImportError)
    }

    if (viewModel.importing) {
        ImportProgressDialog()
    }

    if (showCalendarPicker) {
        CalendarPickerDialog(
            calendars = calendars,
            savedCalendarId = syncPrefs.calendarId,
            onDismiss = { showCalendarPicker = false },
            onOpenSystemCalendar = {
                showCalendarPicker = false
                openSystemCalendar(context)
            },
            onPick = { account -> runSync(account) },
        )
    }
}

/**
 * 顶栏左侧的周次切换：`‹ 第 N 周 ›`。
 *
 * 两点改动都是为了不误触：
 *  1. 「回到本周」不再是右端的一个日历图标（它紧挨着「下一周 ›」），
 *     而是**点周次文字本身**即可回到本周——不额外占位，也不与箭头相邻；
 *     不在本周时文字变成主题色，暗示可以点。
 *  2. 两个箭头各占 30dp 且与右侧图标之间有分隔条（见 actions），
 *     避免"想点下一周却点到别的按钮"。
 */
@Composable
private fun WeekIndicator(
    week: Int,
    isCurrentWeek: Boolean,
    compact: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBackToCurrentWeek: () -> Unit,
) {
    val chevron = if (compact) 26.dp else 30.dp
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, modifier = Modifier.size(chevron)) {
            Icon(
                Icons.Filled.ChevronLeft,
                contentDescription = "上一周",
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(3.dp))
        Text(
            text = if (compact) "第${week}周" else "第 $week 周",
            fontSize = if (compact) 13.sp else 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            color = if (isCurrentWeek) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(
                    enabled = !isCurrentWeek,
                    onClickLabel = "回到本周",
                    onClick = onBackToCurrentWeek,
                )
                .padding(horizontal = if (compact) 3.dp else 6.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(3.dp))
        IconButton(onClick = onNext, modifier = Modifier.size(chevron)) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = "下一周",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 日视图：按节次列出当天的课程。 */
@Composable
private fun DayList(
    data: ScheduleData,
    week: Int,
    day: Int,
    date: LocalDate,
    onCourseClick: (Course) -> Unit,
    modifier: Modifier = Modifier,
) {
    val courses = data.coursesOn(day, week)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            text = "${date.monthValue} 月 ${date.dayOfMonth} 日 ${ScheduleCalc.weekdayShort(date)} · 第 $week 周",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        if (courses.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "今天没有课 🎉",
                    modifier = Modifier.padding(20.dp),
                    fontSize = 14.sp,
                )
            }
        }
        courses.forEach { course ->
            val accent = colorFor(course.colorKey)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clickable { onCourseClick(course) },
                color = accent.copy(alpha = 0.12f),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
            ) {
                Row(modifier = Modifier.padding(12.dp)) {
                    Column(
                        modifier = Modifier.width(64.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "${course.periodFrom}-${course.periodTo}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                        )
                        Text(
                            text = data.periods.at(course.periodFrom).start,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = data.periods.at(course.periodTo).end,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            text = course.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = listOfNotNull(
                                course.room.takeIf { it.isNotEmpty() },
                                course.teacher.takeIf { it.isNotEmpty() },
                            ).joinToString(" · "),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 课程详情：完整信息 + 单双周标识 + 一键同步。 */
@Composable
private fun CourseDetailDialog(
    course: Course,
    data: ScheduleData,
    onDismiss: () -> Unit,
    onSyncToCalendar: () -> Unit,
    onShareIcs: () -> Unit,
) {
    val weeks = course.weeks
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(course.name, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = listOfNotNull(
                        course.kind.takeIf { it.isNotEmpty() },
                        course.credits.takeIf { it.isNotEmpty() }?.let { "$it 学分" },
                        course.hours.takeIf { it.isNotEmpty() }?.let { "$it 学时" },
                    ).joinToString(" · "),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column {
                if (course.isExtra) {
                    InfoLine("时间", "无固定节次（课表未安排具体时间）")
                } else {
                    InfoLine("时间", buildString {
                        append(ScheduleCalc.weekdayShortByIndex(course.day))
                        append("  ")
                        append(data.periods.at(course.periodFrom).start)
                        append("-")
                        append(data.periods.at(course.periodTo).end)
                        append("（")
                        append(course.timeLabel)
                        append("）")
                    })
                }
                InfoLine("周次", weeksLabel(weeks))
                if (course.room.isNotEmpty()) InfoLine("场地", course.campus + course.room)
                if (course.teacher.isNotEmpty()) InfoLine("教师", course.teacher)
                if (course.className.isNotEmpty()) InfoLine("教学班", course.className)

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "周次分布",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    for (w in 1..data.totalWeeks) {
                        val on = weeks.contains(w)
                        Box(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .width(26.dp)
                                .height(24.dp)
                                .background(
                                    when {
                                        on -> colorFor(course.colorKey)
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    MaterialTheme.shapes.extraSmall,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = w.toString(),
                                fontSize = 11.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onSyncToCalendar) {
                Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("同步到日历")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onShareIcs) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("导出")
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
        Text(text = value, fontSize = 12.sp)
    }
}

/** 选择写入哪个日历（vivo 手机通常有 vivo 账号日历、本地日历）。 */
@Composable
private fun CalendarPickerDialog(
    calendars: List<CalendarAccount>,
    savedCalendarId: Long,
    onDismiss: () -> Unit,
    onOpenSystemCalendar: () -> Unit,
    onPick: (CalendarAccount) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择要写入的日历", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "每节课会写成一条日程，并带上提前提醒；之后由系统日历负责到点通知。" +
                        "选一个你在日历 App 里能看到的日历。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                if (calendars.isEmpty()) {
                    Text(
                        text = "没有找到任何日历。请先打开一次 vivo 日历（或登录账号）让它建立本地日历，" +
                            "再回来重试。",
                        fontSize = 12.sp,
                    )
                }
                calendars.forEach { account ->
                    val usable = account.writable && account.visible
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable(enabled = account.writable) { onPick(account) },
                        color = if (usable) {
                            colorFor(account.name).copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = account.label,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                val notes = buildList {
                                    if (account.id == savedCalendarId) add("上次使用")
                                    if (!account.writable) add("只读")
                                    if (account.writable && !account.visible) add("未在日历中显示")
                                }
                                if (notes.isNotEmpty()) {
                                    Text(
                                        text = notes.joinToString(" · "),
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (account.id == savedCalendarId) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSystemCalendar) { Text("打开系统日历") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 设置：开学第一周周一、总周数、作息时间。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialog(
    data: ScheduleData,
    currentWeek: Int,
    sync: SyncPrefs,
    onDismiss: () -> Unit,
    onTermStartChange: (LocalDate) -> Unit,
    onTotalWeeksChange: (Int) -> Unit,
    onPeriodChange: (Int, String, String) -> Unit,
    onReset: () -> Unit,
    onImportPdf: () -> Unit,
    onGoToCurrentWeek: () -> Unit,
    onReminderMinutesChange: (Int) -> Unit,
    onAutoSyncChange: (Boolean) -> Unit,
    onClearCalendarEvents: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = ScheduleCalc.parseDate(data.termStartDate)
            .atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onTermStartChange(
                            Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate(),
                        )
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedButton(
                    onClick = onImportPdf,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("导入课表 PDF（替换当前课表）", fontSize = 13.sp)
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onGoToCurrentWeek,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Today, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "回到本周（当前第 $currentWeek 周）",
                        fontSize = 13.sp,
                    )
                }
                Text(
                    text = "选一份课表 PDF，程序会自动识别星期、节次、周次、场地与教师，" +
                        "确认后课表随新的 PDF 变化；开学日期与作息时间保持不变。",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                Text(
                    text = "开学第一周（周一）",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { showDatePicker = true }) {
                    Text(
                        text = data.termStartDate,
                        fontSize = 14.sp,
                    )
                }
                Text(
                    text = "当前策略：第 1 周周一 = 上面这一天。" +
                        "第 5 周周一 = ${ScheduleCalc.mondayOfWeek(data, 5)}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                Text("学期总周数：${data.totalWeeks}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    listOf(16, 18, 20, 22, 24).forEach { n ->
                        OutlinedButton(onClick = { onTotalWeeksChange(n) }) {
                            Text(n.toString(), fontSize = 12.sp)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Spacer(Modifier.height(10.dp))
                Text("关于与更新", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "版本 ${UpdateChecker.localVersionName()}" +
                        "（${UpdateChecker.localVersionCode()}）· 更新来自 GitHub Releases",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onCheckUpdate, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("检查更新", fontSize = 13.sp)
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Spacer(Modifier.height(10.dp))
                Text("日历提醒", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (sync.hasCalendar) {
                        "写入日历：${sync.calendarName}（同步时会先清掉上次写入的日程，不会重复）"
                    } else {
                        "还没有选择日历。点右上角的「同步」图标选一次即可，之后会记住。"
                    },
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "提前提醒：" + if (sync.reminderMinutes == 0) "不提醒" else "${sync.reminderMinutes} 分钟",
                    fontSize = 11.sp,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    SyncSettings.REMINDER_CHOICES.forEach { minutes ->
                        val selected = sync.reminderMinutes == minutes
                        if (selected) {
                            Button(onClick = { onReminderMinutesChange(minutes) }) {
                                Text(if (minutes == 0) "不提醒" else "${minutes}分", fontSize = 11.sp)
                            }
                        } else {
                            OutlinedButton(onClick = { onReminderMinutesChange(minutes) }) {
                                Text(if (minutes == 0) "不提醒" else "${minutes}分", fontSize = 11.sp)
                            }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Checkbox(checked = sync.autoSync, onCheckedChange = onAutoSyncChange)
                    Text("导入课表后自动同步到日历", fontSize = 11.sp)
                }
                TextButton(onClick = onClearCalendarEvents) {
                    Text("清空本 App 写入的日程", fontSize = 11.sp)
                }

                Spacer(Modifier.height(12.dp))
                Text("作息时间（可在下方逐节修改）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                data.periods.sortedBy { it.index }.forEach { period ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp),
                    ) {
                        Text("第 ${period.index} 节", fontSize = 11.sp, modifier = Modifier.width(58.dp))
                        TimeField(
                            value = period.start,
                            onValueChange = { onPeriodChange(period.index, it, period.end) },
                            modifier = Modifier.width(96.dp),
                        )
                        Text(" - ", fontSize = 11.sp)
                        TimeField(
                            value = period.end,
                            onValueChange = { onPeriodChange(period.index, period.start, it) },
                            modifier = Modifier.width(96.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = {
            TextButton(onClick = onReset) { Text("恢复内置课表") }
        },
    )
}

@Composable
private fun TimeField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            if (Regex("^\\d{1,2}:\\d{2}$").matches(input.trim())) onValueChange(input.trim())
        },
        singleLine = true,
        textStyle = TextStyle(fontSize = 12.sp),
        modifier = modifier.height(52.dp),
    )
}

internal fun weeksLabel(weeks: List<Int>): String {
    if (weeks.isEmpty()) return "未设置"
    val sorted = weeks.distinct().sorted()
    val parts = ArrayList<String>()
    var start = sorted.first()
    var prev = start
    for (w in sorted.drop(1)) {
        if (w == prev + 1) {
            prev = w
            continue
        }
        parts += if (start == prev) "$start" else "$start-$prev"
        start = w
        prev = w
    }
    parts += if (start == prev) "$start" else "$start-$prev"
    return "第 " + parts.joinToString("、") + " 周（共 ${sorted.size} 次）"
}

internal fun shareIcs(context: Context, data: ScheduleData) {
    runCatching {
        val intent = Intent.createChooser(IcsExport.exportIntent(context, data), "导出课表")
        context.startActivity(intent)
    }
}

internal fun openSystemCalendar(context: Context) {
    val millis = System.currentTimeMillis()
    val candidates = listOf(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(
                CalendarContract.CONTENT_URI.buildUpon()
                    .appendPath("time").appendPath(millis.toString()).build(),
                "vnd.android.cursor.item/event",
            )
            .putExtra("beginTime", millis),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR),
        Intent(Intent.ACTION_VIEW).setData(CalendarContract.CONTENT_URI),
    )
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (context.packageManager.resolveActivity(intent, 0) != null) {
            runCatching { context.startActivity(intent) }
            return
        }
    }
}
