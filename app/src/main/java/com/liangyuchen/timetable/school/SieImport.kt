package com.liangyuchen.timetable.school

import com.liangyuchen.timetable.data.Period
import com.liangyuchen.timetable.pdf.ParsedCourse
import com.liangyuchen.timetable.pdf.ParsedTimetable
import com.liangyuchen.timetable.xls.XlsReader
import com.liangyuchen.timetable.xls.XlsSheet

/**
 * 沈阳工程学院的课表（教务系统导出的 .xls）。
 *
 * 版式（实例如下）：
 * ```
 *  A1 沈阳工程学院 电力B261 班级课表
 *  B3 星期一   C3 星期二  … F3 星期五
 *  A4 第一大节第0102节08:20-10:00
 *  B4 马克思主义基本原理
 *     (空行)
 *     [刘君]
 *     （6-17周[01-02节]）
 *     教学楼A座326电力
 * ```
 *
 * 要点：
 *  - 时间写在左列（每个"大节"一行，含 08:20-10:00 这样的起止），所以作息直接按文件来；
 *  - 每门课占**一行**（一个大节 = 两小节），所以课程的行号就是"第几大节"；
 *  - 周次写法很多：`6-17周`、`6-17单周`、`7,9,11,13,15,17周`、`15周`、`13-18周`；
 *  - 星期只到星期五，因此课表天数按文件里的列数决定（5 天）。
 */
object SieImport {

    private val WEEKDAY_WORDS = mapOf(
        "星期一" to 0, "星期一" to 0, "周一" to 0,
        "星期二" to 1, "周二" to 1,
        "星期三" to 2, "周三" to 2,
        "星期四" to 3, "周四" to 3,
        "星期五" to 4, "周五" to 4,
        "星期六" to 5, "周六" to 5,
        "星期日" to 6, "星期天" to 6, "周日" to 6, "周天" to 6,
    )

    private val CN_NUM = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5,
        "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10,
    )

    private val BIG_PERIOD_RE = Regex("第\\s*([一二三四五六七八九十]+|\\d{1,2})\\s*大节")
    private val TIME_RANGE_RE = Regex("(\\d{1,2}:\\d{2})\\s*[-—~～至]\\s*(\\d{1,2}:\\d{2})")
    private val SMALL_PERIODS_RE = Regex("第\\s*(\\d{2})\\s*(\\d{2})\\s*节")
    private val WEEKS_PAREN_RE = Regex("[（(]([^（）()]*周[^（）()]*)[)）]")
    private val SLOT_RE = Regex("\\[\\s*(\\d{1,2})\\s*[-—~～]\\s*(\\d{1,2})\\s*节\\s*]")

    fun parse(bytes: ByteArray): ParsedTimetable {
        val book = XlsReader.read(bytes)
        val sheet = book.sheetContaining("星期一")
            ?: book.sheetContaining("周一")
            ?: book.sheets.firstOrNull()
            ?: return ParsedTimetable(emptyList(), warnings = listOf("工作簿里没有工作表"))
        return parseSheet(sheet)
    }

    private fun parseSheet(sheet: XlsSheet): ParsedTimetable {
        val warnings = ArrayList<String>()

        // ---- 表头：找到"星期一"所在行，记下每个星期对应的列
        var headerRow = -1
        var dayCols = LinkedHashMap<Int, Int>()     // col -> day
        for (r in 0..sheet.maxRow) {
            val found = LinkedHashMap<Int, Int>()
            for (c in 0..sheet.maxCol) {
                val text = sheet.cell(r, c).trim()
                if (text.isEmpty()) continue
                val day = WEEKDAY_WORDS.entries
                    .firstOrNull { text == it.key || text.startsWith(it.key) }
                    ?.value
                if (day != null) found[c] = day
            }
            if (found.size >= 3) {
                headerRow = r
                dayCols = found
                break
            }
        }
        if (headerRow < 0) {
            return ParsedTimetable(
                emptyList(),
                warnings = listOf("没找到「星期一…星期日」表头，这个文件不像沈阳工程学院的课表"),
            )
        }
        val firstDayCol = dayCols.keys.minOrNull() ?: 0

        // ---- 逐行读：左列是"大节 + 时间"，右侧各列是课程
        val periods = ArrayList<Period>()
        val courses = ArrayList<ParsedCourse>()
        var termText = ""
        var className = ""

        for (r in 0..sheet.maxRow) {
            // 页眉里的学期/班级信息
            val whole = sheet.rowText(r)
            if (termText.isEmpty()) {
                Regex("学年学期[:：]\\s*([0-9\\-]+)").find(whole)?.let { termText = it.groupValues[1] }
            }
            if (className.isEmpty()) {
                Regex("([\\u4e00-\\u9fa5A-Za-z]{1,8}\\d{2,4})\\s*班级课表").find(whole)
                    ?.let { className = it.groupValues[1] }
            }

            if (r <= headerRow) continue

            // 左列：第一大节第0102节08:20-10:00
            var periodIndex = 0
            var start = ""
            var end = ""
            for (c in 0 until firstDayCol) {
                val text = sheet.cell(r, c)
                if (text.isBlank()) continue
                val m = BIG_PERIOD_RE.find(text)
                val t = TIME_RANGE_RE.find(text)
                if (m != null || t != null) {
                    periodIndex = m?.let { cnToInt(it.groupValues[1]) } ?: periodIndex
                    if (t != null) {
                        start = t.groupValues[1]
                        end = t.groupValues[2]
                    }
                    break
                }
            }
            if (periodIndex <= 0 && start.isEmpty()) continue
            if (periodIndex <= 0) periodIndex = periods.size + 1
            if (start.isNotEmpty() && end.isNotEmpty()) {
                periods += Period(periodIndex, normalize(start), normalize(end))
            }

            // 每一列：一门课
            for ((col, day) in dayCols) {
                val raw = sheet.cell(r, col)
                if (raw.isBlank()) continue
                val parsed = parseCourseCell(raw, day, periodIndex)
                if (parsed == null) {
                    warnings += "第 ${periodIndex} 大节 星期${day + 1} 的格子没读懂：${raw.take(24)}"
                } else {
                    courses += parsed
                }
            }
        }

        if (courses.isEmpty()) {
            warnings += "没有解析出任何课程"
        }

        // 工程学院只排到周五，但周视图保留周六周日两列
        val dayCount = 7
        return ParsedTimetable(
            courses = courses.sortedWith(compareBy({ it.day }, { it.periodFrom }, { it.name })),
            term = termText,
            studentName = "",
            maxPeriod = periods.maxOfOrNull { it.index } ?: periods.size,
            warnings = warnings,
            periods = periods.sortedBy { it.index }.ifEmpty { null },
            dayCount = dayCount,
            schoolLabel = "沈阳工程学院",
            className = className,
        )
    }

    /**
     * 解析一格课程文字：
     * ```
     * 马克思主义基本原理
     *
     * [刘君]
     * （6-17周[01-02节]）
     * 教学楼A座326电力
     * ```
     */
    private fun parseCourseCell(raw: String, day: Int, periodIndex: Int): ParsedCourse? {
        val lines = raw.split('\n', '\r')
            .map { it.replace('\u3000', ' ').trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val name = lines.first()
        if (name.isEmpty() || WEEKS_PAREN_RE.matches(name)) return null

        var teacher = ""
        var weeksLine = ""
        var weekSpec = ""
        var slotFrom = 0
        var slotTo = 0
        val others = ArrayList<String>()

        for (line in lines.drop(1)) {
            when {
                line.matches(Regex("^\\[[^\\]]*]$")) -> teacher = line.removeSurrounding("[", "]")
                WEEKS_PAREN_RE.matches(line) -> {
                    weeksLine = WEEKS_PAREN_RE.find(line)?.groupValues?.get(1)?.trim() ?: ""
                    weekSpec = line
                    SLOT_RE.find(line)?.let {
                        slotFrom = it.groupValues[1].toIntOrNull() ?: 0
                        slotTo = it.groupValues[2].toIntOrNull() ?: 0
                    }
                }

                else -> others += line
            }
        }
        val weeks = parseWeeks(weeksLine)
        if (weeks.isEmpty()) return null

        // 地点：普通情况下是最后一行的"教学楼A座326电力"
        val room = others.lastOrNull { it.isNotBlank() } ?: ""
        val campus = if (room.startsWith("教学楼")) "教学楼" else ""

        // 课表里的作息按"大节"排（每行一个大节，含起止时间），
        // 而格子里写的是小节 [01-02] / [03-04] …，所以要换算成大节序号：
        //   01-02 → 第 1 大节，03-04 → 第 2 大节，… 09-10 → 第 5 大节
        val big = if (slotFrom > 0) (slotFrom + 1) / 2 else periodIndex
        val bigTo = if (slotTo > 0) (slotTo + 1) / 2 else big
        val from = if (big > 0) big else periodIndex
        val to = if (bigTo >= from) bigTo else from

        return ParsedCourse(
            name = name,
            day = day,
            periodFrom = from,
            periodTo = to,
            weeks = weeks,
            teacher = teacher,
            room = room,
            campus = campus,
            className = "",
            kind = "",
            credits = "",
            hours = "",
            weekSpec = weekSpec,
        )
    }

    /** `6-17周` / `6-17单周` / `6-17双周` / `7,9,11周` / `15周` → 周次列表。 */
    internal fun parseWeeks(text: String): List<Int> {
        if (text.isBlank()) return emptyList()
        // 去掉 [01-02节] 这类节次括注
        val clean = SLOT_RE.replace(text, "").trim()
        val odd = clean.contains("单")
        val even = clean.contains("双")

        val result = ArrayList<Int>()
        // 处理 "6-17" 与 "7,9,11" 混写的情况：先按逗号切段
        for (part in clean.split(',', '，', '、', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
            val range = Regex("(\\d{1,2})\\s*[-—~～]\\s*(\\d{1,2})").find(part)
            if (range != null) {
                val a = range.groupValues[1].toInt()
                val b = range.groupValues[2].toInt()
                for (w in minOf(a, b)..maxOf(a, b)) result += w
            } else {
                Regex("\\d{1,2}").findAll(part).forEach { result += it.value.toInt() }
            }
        }
        val distinct = result.filter { it in 1..30 }.distinct().sorted()
        return when {
            odd -> distinct.filter { it % 2 == 1 }
            even -> distinct.filter { it % 2 == 0 }
            else -> distinct
        }
    }

    private fun cnToInt(text: String): Int =
        text.toIntOrNull() ?: CN_NUM[text] ?: 0

    /** "8:20" → "08:20" */
    private fun normalize(t: String): String {
        val parts = t.split(':')
        if (parts.size != 2) return t
        val h = parts[0].trim().toIntOrNull() ?: return t
        val m = parts[1].trim().toIntOrNull() ?: return t
        return "%02d:%02d".format(h, m)
    }
}
