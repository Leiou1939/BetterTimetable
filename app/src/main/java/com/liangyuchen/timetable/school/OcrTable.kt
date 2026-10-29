package com.liangyuchen.timetable.school

import com.liangyuchen.timetable.pdf.ParsedCourse
import com.liangyuchen.timetable.pdf.ParsedTimetable

/**
 * 「表格线 + 逐格 OCR」的结果还原：拿到每格的文字和行列位置，拼成课程。
 *
 * 与 [OcrGrid] 的区别：那边靠文字坐标猜网格，这边网格是图像里检测出来的，
 * 所以对密集课表稳得多。
 */
object OcrTable {

    private val DAY = mapOf(
        "星期一" to 0, "周一" to 0,
        "星期二" to 1, "周二" to 1,
        "星期三" to 2, "周三" to 2,
        "星期四" to 3, "周四" to 3,
        "星期五" to 4, "周五" to 4,
        "星期六" to 5, "周六" to 5,
        "星期日" to 6, "星期天" to 6, "周日" to 6, "周天" to 6,
    )

    /** OCR 常把 "-" 认成这些字符。 */
    private fun normalize(text: String): String = text
        .replace('．', '-')
        .replace('•', '-')
        .replace('·', '-')
        .replace('—', '-')
        .replace('–', '-')
        .replace('－', '-')
        .replace('（', '(')
        .replace('）', ')')
        .replace('：', ':')
        .replace('，', ',')
        .replace("\u3000", " ")
        .trim()

    /**
     * @param cells key = "行-列" → 该格 OCR 出来的文字（多行用 \n 连接）
     * @param rows 行数、[cols] 列数
     */
    fun build(cells: Map<Pair<Int, Int>, String>, rows: Int, cols: Int): ParsedTimetable {
        val warnings = ArrayList<String>()

        // ---- 表头：哪一列是星期几
        val dayCols = LinkedHashMap<Int, Int>()
        var headerRow = -1
        for (r in 0 until rows) {
            val found = LinkedHashMap<Int, Int>()
            for (c in 0 until cols) {
                val text = normalize(cells[r to c] ?: continue)
                DAY.entries.firstOrNull { text.contains(it.key) }?.let { found[c] = it.value }
            }
            if (found.size >= 3) {
                headerRow = r
                dayCols.putAll(found)
                break
            }
        }
        if (dayCols.isEmpty()) {
            // 表头没认出来：这类表格的第一个列是"节次/时间"，后面依次是周一到周日
            if (cols < 5) {
                return ParsedTimetable(
                    emptyList(),
                    warnings = listOf("没认出星期表头，而且表格只有 $cols 列，无法判断"),
                )
            }
            // 先认出哪些列是"节次/时间"（不是星期），剩下的按顺序当周一到周日
            val nonDay = ArrayList<Int>()
            for (c in 0 until minOf(3, cols)) {
                val sample = (0 until rows).mapNotNull { cells[it to c] }.joinToString(" ")
                if (Regex("第?\\s*\\d{1,2}\\s*节|\\d{1,2}[:：]\\d{2}").containsMatchIn(sample) ||
                    sample.contains("节次") || sample.contains("时间")
                ) {
                    nonDay += c
                }
            }
            val dayCandidates = (0 until cols).filter { it !in nonDay }
            if (dayCandidates.size < 5) {
                return ParsedTimetable(
                    emptyList(),
                    warnings = listOf("没认出星期表头，也没法确定哪几列是星期"),
                )
            }
            warnings += "星期表头没认出来，按列顺序当作周一…周日（识别结果请核对）"
            dayCandidates.take(7).forEachIndexed { i, c -> dayCols[c] = i }
            headerRow = 0
        }

        val courses = ArrayList<ParsedCourse>()
        val periods = ArrayList<com.liangyuchen.timetable.data.Period>()
        for (r in (headerRow + 1) until rows) {
            // 这一行是第几节
            var periodIndex = 0
            var timeStart = ""
            var timeEnd = ""
            for (c in 0 until cols) {
                if (dayCols.containsKey(c)) continue
                val text = normalize(cells[r to c] ?: continue)
                if (text.isEmpty()) continue
                val m = Regex("第?\\s*(\\d{1,2})\\s*节").find(text)
                if (m != null) periodIndex = m.groupValues[1].toIntOrNull() ?: periodIndex
                val t = Regex("(\\d{1,2})[:：](\\d{2})").findAll(text).map { it.value }.toList()
                if (t.size >= 2) {
                    timeStart = fmt(t[0])
                    timeEnd = fmt(t[1])
                }
                if (periodIndex > 0) break
            }
            if (periodIndex > 0 && timeStart.isNotEmpty() && timeEnd.isNotEmpty()) {
                if (periods.none { it.index == periodIndex }) {
                    periods += com.liangyuchen.timetable.data.Period(
                        periodIndex,
                        timeStart,
                        timeEnd,
                    )
                }
            }

            for ((c, day) in dayCols) {
                val raw = cells[r to c] ?: continue
                val text = raw.split('\n').joinToString("\n") { normalize(it) }
                    .split('\n').filter { it.isNotBlank() }
                if (text.size < 2) continue
                val parsed = parseCell(text, day, periodIndex)
                if (parsed != null) {
                    courses += parsed
                } else {
                    warnings += "第 ${periodIndex.coerceAtLeast(r)} 行 星期${day + 1} 没读懂：" +
                        text.joinToString(" ").take(20)
                }
            }
        }

        if (courses.isEmpty()) warnings += "识别到表格，但没能还原出课程"
        return ParsedTimetable(
            courses = courses.sortedWith(compareBy({ it.day }, { it.periodFrom }, { it.name })),
            maxPeriod = courses.maxOfOrNull { it.periodTo } ?: 10,
            warnings = warnings,
            periods = periods.sortedBy { it.index }.ifEmpty { null },
            dayCount = 7,
            schoolLabel = "OCR 识别（图片型课表）",
        )
    }

    /**
     * 一格文字 → 课程。样例会带 OCR 噪声：
     * ```
     * 英语听说1一17      ← 课程名（后面粘了周次）
     * 王向红'           ← 教师
     * 5．18周           ← 周次
     * 3．4节            ← 节次
     * 校朩部骂行檯210    ← 教室
     * ```
     */
    internal fun parseCell(lines: List<String>, day: Int, fallbackPeriod: Int): ParsedCourse? {
        if (lines.isEmpty()) return null
        var weeks: List<Int> = emptyList()
        var weekSpec = ""
        var from = 0
        var to = 0
        var room = ""

        for (line in lines) {
            val slot = Regex("(\\d{1,2})\\s*-\\s*(\\d{1,2})\\s*节").find(line)
            if (slot != null) {
                from = slot.groupValues[1].toIntOrNull() ?: from
                to = slot.groupValues[2].toIntOrNull() ?: to
                continue
            }
            if (line.contains('周')) {
                val w = weeksOf(line)
                if (w.isNotEmpty()) {
                    weeks = w
                    weekSpec = line
                    continue
                }
            }
            // 教室：含"楼/馆/场/教/A座"之类
            if (room.isEmpty() && Regex("[楼馆场室A-Za-z]\\d{2,4}|[楼馆场室]").containsMatchIn(line) &&
                !line.contains('周')
            ) {
                room = line
            }
        }

        // 课程名：第一行；如果第一行里带着"周"，切掉
        var name = lines.first()
        Regex("\\d{1,2}\\s*-\\s*\\d{1,2}\\s*周.*$").find(name)?.let {
            if (it.range.first > 1) name = name.substring(0, it.range.first)
        }
        name = name.trim().trimEnd('-', '一', '～')
        if (name.length < 2) return null

        val teacher = lines.getOrNull(1)?.let { t ->
            if (!t.contains('周') && !t.contains('节') && t.length <= 8) t.trim('*', '\'', ' ') else ""
        } ?: ""

        if (weeks.isEmpty()) {
            // 有些格子把周次写在第一行（"英语听说1一17"）
            val m = Regex("(\\d{1,2})\\s*-\\s*(\\d{1,2})").find(lines.first())
            if (m != null) {
                val a = m.groupValues[1].toInt()
                val b = m.groupValues[2].toInt()
                if (a in 1..30 && b in 1..30) weeks = (minOf(a, b)..maxOf(a, b)).toList()
            }
        }
        if (weeks.isEmpty()) return null

        val start = if (from > 0) from else (fallbackPeriod.takeIf { it > 0 } ?: 1)
        val end = if (to >= start) to else start
        return ParsedCourse(
            name = name,
            day = day,
            periodFrom = start,
            periodTo = end,
            weeks = weeks,
            teacher = teacher,
            room = room,
            campus = "",
            className = "",
            kind = "",
            credits = "",
            hours = "",
            weekSpec = weekSpec,
        )
    }

    internal fun weeksOf(text: String): List<Int> {
        val odd = text.contains("单")
        val even = text.contains("双")
        val clean = text.replace("周", "").replace("(", " ").replace(")", " ")
        val out = ArrayList<Int>()
        val range = Regex("(\\d{1,2})\\s*-\\s*(\\d{1,2})").find(clean)
        if (range != null) {
            val a = range.groupValues[1].toInt()
            val b = range.groupValues[2].toInt()
            for (w in minOf(a, b)..maxOf(a, b)) out += w
        } else {
            Regex("\\d{1,2}").findAll(clean).forEach { out += it.value.toInt() }
        }
        val distinct = out.filter { it in 1..30 }.distinct().sorted()
        return when {
            odd -> distinct.filter { it % 2 == 1 }
            even -> distinct.filter { it % 2 == 0 }
            else -> distinct
        }
    }

    private fun fmt(t: String): String {
        val parts = t.replace('：', ':').split(':')
        if (parts.size != 2) return t
        val h = parts[0].toIntOrNull() ?: return t
        val m = parts[1].toIntOrNull() ?: return t
        return "%02d:%02d".format(h, m)
    }
}
