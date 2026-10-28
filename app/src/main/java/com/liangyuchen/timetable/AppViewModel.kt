package com.liangyuchen.timetable

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liangyuchen.timetable.data.CourseRepository
import com.liangyuchen.timetable.data.Period
import com.liangyuchen.timetable.data.ScheduleCalc
import com.liangyuchen.timetable.data.ScheduleData
import com.liangyuchen.timetable.pdf.ParsedTimetable
import com.liangyuchen.timetable.pdf.PdfImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = CourseRepository(app)
    private val appContext = app.applicationContext

    var data: ScheduleData by mutableStateOf(repository.load())
        private set

    /** 当前显示的周次 */
    var week: Int by mutableStateOf(ScheduleCalc.currentWeek(data))
        private set

    /** 导入 PDF 的状态 */
    var importing: Boolean by mutableStateOf(false)
        private set
    var importResult: ParsedTimetable? by mutableStateOf(null)
        private set
    var importError: String? by mutableStateOf(null)
        private set

    val today: LocalDate = LocalDate.now()

    fun weekOfToday(): Int = ScheduleCalc.currentWeek(data, today)

    fun goToWeek(target: Int) {
        week = target.coerceIn(1, data.totalWeeks.coerceAtLeast(1))
    }

    fun previousWeek() = goToWeek(week - 1)

    fun nextWeek() = goToWeek(week + 1)

    fun goToToday() = goToWeek(weekOfToday())

    // ------------------------------------------------------------ PDF 导入

    /** 解析所选 PDF；结果放进 [importResult] 供界面预览确认。 */
    fun startImport(uri: Uri) {
        importing = true
        importError = null
        importResult = null
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO) { PdfImport.parse(appContext, uri) }
            importing = false
            if (parsed.courses.isEmpty()) {
                importError = parsed.warnings.firstOrNull() ?: "这个 PDF 里没有解析出课程"
            } else {
                importResult = parsed
            }
        }
    }

    fun cancelImport() {
        importResult = null
        importError = null
    }

    fun dismissImportError() {
        importError = null
    }

    /** 确认导入：用解析结果替换课表，但保留开学日期与作息时间设置。 */
    fun confirmImport() {
        val parsed = importResult ?: return
        update(PdfImport.toScheduleData(parsed, data))
        importResult = null
        week = weekOfToday()
    }

    // -------------------------------------------------------------- 设置

    /** 修改开学日期（第 1 周周一）。 */
    fun setTermStart(date: LocalDate) {
        update(data.copy(termStartDate = ScheduleCalc.format(date)))
    }

    fun setTotalWeeks(weeks: Int) {
        update(data.copy(totalWeeks = weeks.coerceIn(1, 30)))
    }

    /** 修改某个节次的上下课时间。 */
    fun setPeriodTime(index: Int, start: String, end: String) {
        val periods: List<Period> = data.periods.map { p ->
            if (p.index == index) p.copy(start = start.trim(), end = end.trim()) else p
        }
        update(data.copy(periods = periods))
    }

    /** 恢复内置课表（也就是恢复到最初那份 PDF 解析出来的课表）。 */
    fun resetToBundled() {
        repository.reset()
        data = repository.load()
        week = weekOfToday()
    }

    private fun update(next: ScheduleData) {
        data = next
        repository.save(next)
        if (week > next.totalWeeks) week = next.totalWeeks
    }
}
