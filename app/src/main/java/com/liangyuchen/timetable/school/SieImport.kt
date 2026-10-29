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
 *  A1 沈阳工程学院 示例班级 班级课表
 *  B3 星期一   C3 星期二  … F3 星期五
 *  A4 第一大节第0102节08:20-10:00
 *  B4 某课程
 *     (空行)
 *     [某老师]
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
                // 一个"大节"= 两个小节：把时间窗对半分，落成表格的两行
                val s1 = normalize(start)
                val e2 = normalize(end)
                val mid = midpoint(s1, e2)
                periods += Period(periodIndex * 2 - 1, s1, mid)
                periods += Period(periodIndex * 2, mid, e2)
            }

            // 每一列：可能有多门课（同一格子里叠着写）
            for ((col, day) in dayCols) {
                val raw = sheet.cell(r, col)
                if (raw.isBlank()) continue
                val parsed = parseCourseCells(raw, day, periodIndex)
                if (parsed.isEmpty()) {
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
     * 某课程
     *
     * [某老师]
     * （6-17周[01-02节]）
     * 教学楼A座326电力
     * ```
     */
    private fun parseCourseCells(raw: String, day: Int, periodIndex: Int): List<ParsedCourse> {
        val lines = raw.split('\n', '\r')
            .map { it.replace('\u3000', ' ').trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()

        // 一格多课时的版式：
        //   课程名 / [教师] / （周次[节次]） / 地点
        //   课程名 / [教师] / （周次[节次]） / 地点   ← 再来一门
        // 以"（…周…）"这一行为界切开。
        val out = ArrayList<ParsedCourse>()
        var head = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (WEEKS_PAREN_RE.matches(line)) {
                val name = head.firstOrNull { it.isNotBlank() } ?: ""
                val teacher = head.drop(1).firstOrNull { BRACKET_RE.matches(it) }
                    ?.removeSurrounding("[", "]") ?: ""
                val weeksText = WEEKS_PAREN_RE.find(line)?.groupValues?.get(1)?.trim() ?: ""
                var slotFrom = 0
                var slotTo = 0
                SLOT_RE.find(line)?.let {
                    slotFrom = it.groupValues[1].toIntOrNull() ?: 0
                    slotTo = it.groupValues[2].toIntOrNull() ?: 0
                }
                // 下一行是地点（除非已经是下一门课的开始）
                var room = ""
                if (i + 1 < lines.size && !WEEKS_PAREN_RE.matches(lines[i + 1]) &&
                    !BRACKET_RE.matches(lines[i + 1])
                ) {
                    room = lines[i + 1]
                    i++
                }
                val weeks = parseWeeks(weeksText)
                if (name.isNotEmpty() && weeks.isNotEmpty()) {
                    val from = when {
                        slotFrom > 0 -> slotFrom
                        periodIndex > 0 -> periodIndex * 2 - 1
                        else -> 1
                    }
                    val to = when {
                        slotTo > 0 && slotTo >= from -> slotTo
                        from % 2 == 1 -> from + 1
                        else -> from
                    }
                    out += ParsedCourse(
                        name = name,
                        day = day,
                        periodFrom = from,
                        periodTo = to,
                        weeks = weeks,
                        teacher = teacher,
                        room = room,
                        campus = if (room.startsWith("教学楼")) "教学楼" else "",
                        className = "",
                        kind = "",
                        credits = "",
                        hours = "",
                        weekSpec = line,
                    )
                }
                head = ArrayList()
            } else {
                head += line
            }
            i++
        }
        // 同一个格子里"同名 + 同周次"的重复块，通常是可选教学班（体育1 会列二十多个），
        // 只保留第一个，避免互相重叠把格子塞爆。
        val unique = ArrayList<ParsedCourse>()
        val seen = HashSet<String>()
        for (c in out) {
            val key = "${c.name}|${c.weeks}|${c.periodFrom}-${c.periodTo}"
            if (seen.add(key)) unique += c
        }
        return unique
    }

    private val BRACKET_RE = Regex("^\\[[^\\]]*]$")

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

    /** 两个时刻的中点，如 08:20 与 10:00 → 09:10。 */
    private fun midpoint(start: String, end: String): String {
        fun minutes(t: String): Int? {
            val p = t.split(':')
            if (p.size != 2) return null
            val h = p[0].toIntOrNull() ?: return null
            val m = p[1].toIntOrNull() ?: return null
            return h * 60 + m
        }
        val a = minutes(start) ?: return start
        val b = minutes(end) ?: return end
        val mid = (a + b) / 2
        return "%02d:%02d".format(mid / 60, mid % 60)
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
