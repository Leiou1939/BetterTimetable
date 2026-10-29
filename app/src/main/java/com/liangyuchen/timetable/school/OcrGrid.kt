package com.liangyuchen.timetable.school

import com.liangyuchen.timetable.pdf.ParsedCourse
import com.liangyuchen.timetable.pdf.ParsedTimetable

/**
 * 一行 OCR 结果（坐标是像素，左上角为原点）。
 */
data class OcrLine(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f
}

/**
 * 把「截图型 PDF 经 OCR 出来的一堆文字块」还原成课程。
 *
 * 纯逻辑、不依赖 Android，方便在桌面 JVM 上单独验证：
 *   1. 找「星期一…星期日」表头 → 列
 *   2. 找左列的「第 N 节 / 08:00-08:45」→ 行
 *   3. 其余文字按中心点落进格子，一个格子里的多行合成一门课
 */
object OcrGrid {

    private val DAY_WORDS = mapOf(
        "星期一" to 0, "周一" to 0, "星期一" to 0,
        "星期二" to 1, "周二" to 1,
        "星期三" to 2, "周三" to 2,
        "星期四" to 3, "周四" to 3,
        "星期五" to 4, "周五" to 4,
        "星期六" to 5, "周六" to 5,
        "星期日" to 6, "星期天" to 6, "周日" to 6, "周天" to 6,
    )

    private val DAY_CHARS = charArrayOf('一', '二', '三', '四', '五', '六', '日', '天')

    private val PERIOD_LINE = Regex("第?\\s*(\\d{1,2})\\s*节")
    private val TIME_RANGE = Regex("(\\d{1,2}[:：]\\d{2})\\s*[-~—–]\\s*(\\d{1,2}[:：]\\d{2})")
    private val SLOT_RANGE = Regex("(\\d{1,2})\\s*[-~—–]\\s*(\\d{1,2})\\s*节")
    private val WEEK_RANGE = Regex("(\\d{1,2})\\s*[-~—–]\\s*(\\d{1,2})")
    private val WEEK_NUMS = Regex("\\d{1,2}")

    /** OCR 常见形近字纠正（表头/单位用）。 */
    private fun fix(text: String): String = text
        .replace('（', '(')
        .replace('）', ')')
        .replace('：', ':')
        .replace('，', ',')
        .replace('－', '-')
        .replace('—', '-')
        .replace('–', '-')
        .replace(" ", "")

    fun parse(lines: List<OcrLine>): ParsedTimetable {
        val warnings = ArrayList<String>()
        if (lines.isEmpty()) {
            return ParsedTimetable(emptyList(), warnings = listOf("没有识别到任何文字"))
        }

        // ---- 1. 表头：找带"星期/周"的那些块
        val headerLines = lines.filter { l ->
            val t = fix(l.text)
            val last = t.lastOrNull()
            DAY_WORDS.keys.any { t.contains(it) } ||
                (t.length <= 3 && last != null && last in DAY_CHARS && t.contains('周'))
        }
        if (headerLines.isEmpty()) {
            return ParsedTimetable(
                emptyList(),
                warnings = listOf("没有识别到「星期一…星期日」表头，识别结果可能太模糊"),
            )
        }

        // 每个星期的列中心
        val columns = LinkedHashMap<Int, Float>()
        for (l in headerLines.sortedBy { it.cx }) {
            val t = fix(l.text)
            val day = DAY_WORDS.entries.firstOrNull { t.contains(it.key) }?.value
                ?: DAY_CHARS.indexOfFirst { t.contains(it) }.takeIf { it >= 0 }
            if (day != null) columns.putIfAbsent(day, l.cx)
        }
        if (columns.size < 3) {
            return ParsedTimetable(
                emptyList(),
                warnings = listOf("只认出 ${columns.size} 个星期表头，无法确定列"),
            )
        }
        val headerBottom = headerLines.maxOf { it.bottom }

        // ---- 2. 行：左列里的"第 N 节"或时间
        val firstColLeft = columns.values.min() - 120f
        val periodAnchors = ArrayList<Pair<Int, Float>>()
        for (l in lines) {
            if (l.cy <= headerBottom) continue
            if (l.left > firstColLeft) continue
            val t = fix(l.text)
            val m = PERIOD_LINE.find(t)
            if (m != null) {
                val idx = m.groupValues[1].toIntOrNull() ?: continue
                periodAnchors += idx to l.cy
            }
        }
        val rows = periodAnchors.distinctBy { it.first }.sortedBy { it.first }

        // ---- 3. 分列、分行
        val colCenters = columns.entries.sortedBy { it.key }
        fun columnOf(x: Float): Int? {
            if (colCenters.isEmpty()) return null
            val idx = colCenters.indexOfLast { it.value <= x }
            if (idx < 0) return null
            // 距离最近的中心，避免把左侧节次列算进来
            val nearest = colCenters.minByOrNull { kotlin.math.abs(it.value - x) } ?: return null
            return nearest.key
        }

        fun rowOf(y: Float, periodFromSlot: Int, periodToSlot: Int): Pair<Int, Int> {
            if (rows.isEmpty()) return periodFromSlot to periodToSlot
            val nearest = rows.minByOrNull { kotlin.math.abs(it.second - y) }
            val big = nearest?.first ?: 1
            // 若识别到的行是"第 1..10 节"，直接用；若是"第 1..5 大节"，展开成两节
            val maxRow = rows.maxOf { it.first }
            val looksLikeSmall = maxRow > 5
            return if (looksLikeSmall) {
                if (periodFromSlot > 0) periodFromSlot to periodToSlot else big to big
            } else {
                (big * 2 - 1) to (big * 2)
            }
        }

        // ---- 3. 先把文字按列分组，再在列内按纵向空隙聚成"一个格子"
        //        （一门课的多行文字常常跨过两条横线，按行号切会切碎）
        val leftMost = colCenters.first().value
        val byColumn = HashMap<Int, MutableList<OcrLine>>()
        for (l in lines) {
            if (l.cy <= headerBottom) continue
            if (l.right < leftMost - 40f) continue      // 左列节次
            val col = columnOf(l.cx) ?: continue
            byColumn.getOrPut(col) { ArrayList() } += l
        }

        val lineHeight = lines
            .map { it.bottom - it.top }
            .filter { it > 0f }
            .sorted()
            .let { if (it.isEmpty()) 16f else it[it.size / 2] }
        val gapLimit = lineHeight * 1.9f

        val courses = ArrayList<ParsedCourse>()
        var cellsSeen = 0
        for ((day, colLines) in byColumn) {
            val sorted = colLines.sortedBy { it.cy }
            var cluster = ArrayList<OcrLine>()
            fun flush() {
                if (cluster.isEmpty()) return
                cellsSeen++
                val top = cluster.minOf { it.cy }
                val text = cluster.joinToString("\n") { fix(it.text) }
                val anchor = rows.minByOrNull { kotlin.math.abs(it.second - top) }
                val bigRow = anchor?.first ?: 1
                val looksLikeSmall = (rows.maxOfOrNull { it.first } ?: 0) > 5
                val from = if (looksLikeSmall) bigRow else bigRow * 2 - 1
                val to = if (looksLikeSmall) bigRow else bigRow * 2
                val parsed = parseCell(text, day, from, to)
                if (parsed != null) {
                    courses += parsed
                } else {
                    warnings += "星期${day + 1} 有一格没读懂：${text.replace('\n', ' ').take(24)}"
                }
                cluster = ArrayList()
            }
            for (l in sorted) {
                val prev = cluster.lastOrNull()
                if (prev != null && l.top - prev.bottom > gapLimit) flush()
                cluster += l
            }
            flush()
        }

        if (courses.isEmpty()) {
            warnings += "识别到文字，但没能还原出课程"
        }

        return ParsedTimetable(
            courses = courses.sortedWith(compareBy({ it.day }, { it.periodFrom }, { it.name })),
            maxPeriod = (courses.maxOfOrNull { it.periodTo } ?: 10),
            warnings = warnings,
            dayCount = 7,
            schoolLabel = "OCR 识别",
        )
    }

    /**
     * 一格文字 → 一门课。OCR 出来的典型内容：
     * ```
     * 高等数学(上)_08
     * 宋雯彦*
     * 5-18周
     * 1-2节
     * 校本部播慧楼201
     * ```
     */
    internal fun parseCell(
        text: String,
        day: Int,
        fallbackFrom: Int,
        fallbackTo: Int,
    ): ParsedCourse? {
        val rows = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return null

        var from = fallbackFrom
        var to = fallbackTo
        var weeks: List<Int> = emptyList()
        var weekSpec = ""
        val rest = ArrayList<String>()

        for (r in rows) {
            val slot = SLOT_RANGE.find(r)
            if (slot != null && r.length <= 12) {
                from = slot.groupValues[1].toIntOrNull() ?: from
                to = slot.groupValues[2].toIntOrNull() ?: to
                continue
            }
            if (r.contains('周') && r.length <= 24) {
                val parsed = weeksOf(r)
                if (parsed.isNotEmpty()) {
                    weeks = parsed
                    weekSpec = r
                    continue
                }
            }
            if (TIME_RANGE.matches(r)) continue
            rest += r
        }
        if (weeks.isEmpty() || rest.isEmpty()) return null

        // 课程名：第一行，去掉末尾的 _08 之类编号
        var name = rest.first().replace(Regex("[_＿]\\d+$"), "").trim()
        if (name.isEmpty()) return null
        // 教师：第二行（短、无数字、没有"楼/室/馆"）
        var teacher = ""
        if (rest.size >= 2) {
            val cand = rest[1]
            if (cand.length <= 8 && cand.none { it.isDigit() } &&
                listOf('楼', '室', '馆', '场', '教', '学').none { cand.contains(it) }
            ) {
                teacher = cand.removeSuffix("*").trim()
            }
        }
        val room = rest.reversed().firstOrNull {
            it != name && it != teacher && it.length >= 2
        } ?: ""

        return ParsedCourse(
            name = name,
            day = day,
            periodFrom = from,
            periodTo = to,
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

    /** "5-18周" / "5-18周(单)" / "6,8,10周" → 周次列表。 */
    internal fun weeksOf(text: String): List<Int> {
        val clean = text.replace("周", "").replace("(", " ").replace(")", " ")
        val odd = clean.contains("单")
        val even = clean.contains("双")
        val out = ArrayList<Int>()
        val range = WEEK_RANGE.find(clean)
        if (range != null) {
            val a = range.groupValues[1].toInt()
            val b = range.groupValues[2].toInt()
            for (w in minOf(a, b)..maxOf(a, b)) out += w
        } else {
            WEEK_NUMS.findAll(clean).forEach { out += it.value.toInt() }
        }
        val distinct = out.filter { it in 1..30 }.distinct().sorted()
        return when {
            odd -> distinct.filter { it % 2 == 1 }
            even -> distinct.filter { it % 2 == 0 }
            else -> distinct
        }
    }
}
