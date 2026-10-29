package com.liangyuchen.timetable.school

import com.liangyuchen.timetable.data.Period
import com.liangyuchen.timetable.pdf.ParsedCourse
import com.liangyuchen.timetable.pdf.ParsedTimetable
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 大连工业大学的课表（教务导出的 .docx）。
 *
 * 版式：一张表 —— 列是「节次 / 时间 / 星期一…星期日」，行是每节课；
 * 课程格里的文字按段落给出：
 * ```
 * 高等数学Ⅰ
 * 毕秀国【7-17周】
 * 综A227
 * 高等数学Ⅰ高阶班
 * ………………            ← 同一个格子里还有别的课
 * 人工智能概论A
 * 邵利【8-10周】
 * 线上自主学习
 * ```
 * 纵向合并（vMerge）表示一门课连上两节，所以「休息/时间」那几格会重复。
 */
object DocxTimetable {

    private val WEEKDAYS = mapOf(
        "星期一" to 0, "周一" to 0,
        "星期二" to 1, "周二" to 1,
        "星期三" to 2, "周三" to 2,
        "星期四" to 3, "周四" to 3,
        "星期五" to 4, "周五" to 4,
        "星期六" to 5, "周六" to 5,
        "星期日" to 6, "星期天" to 6, "周日" to 6, "周天" to 6,
    )

    private val WEEKS_BRACKET = Regex("【([^】]*)】")
    private val SLOT_IN_WEEKS = Regex("\\[\\s*(\\d{1,2})\\s*[-—~]\\s*(\\d{1,2})\\s*节\\s*]")

    fun parse(bytes: ByteArray): ParsedTimetable {
        val xml = unzipDocument(bytes)
            ?: return ParsedTimetable(emptyList(), warnings = listOf("这个 .docx 里找不到 word/document.xml"))
        val root = runCatching { parseXml(xml) }.getOrNull()
            ?: return ParsedTimetable(emptyList(), warnings = listOf("文档结构解析失败"))
        val table = firstTable(root)
            ?: return ParsedTimetable(emptyList(), warnings = listOf("文档里没有表格，不是课表"))

        val rows = children(table, "tr").map { row -> cellsOf(row) }
        if (rows.isEmpty()) {
            return ParsedTimetable(emptyList(), warnings = listOf("表格是空的"))
        }

        // ---- 表头：含"星期一…"的那一行
        var headerRow = -1
        val dayCols = LinkedHashMap<Int, Int>()     // 起始列 -> day
        for ((ri, row) in rows.withIndex()) {
            val found = LinkedHashMap<Int, Int>()
            for (cell in row) {
                val text = cell.texts.firstOrNull()?.trim() ?: ""
                val day = WEEKDAYS.entries.firstOrNull { text == it.key || text.startsWith(it.key) }?.value
                if (day != null) found[cell.col] = day
            }
            if (found.size >= 3) {
                headerRow = ri
                dayCols.putAll(found)
                break
            }
        }
        if (headerRow < 0) {
            return ParsedTimetable(
                emptyList(),
                warnings = listOf("表格里没找到「星期一…星期日」表头"),
            )
        }

        val warnings = ArrayList<String>()
        val periods = ArrayList<Period>()
        val courses = ArrayList<ParsedCourse>()
        // 纵向合并：记录每一天"还在延续"的那几门课（存 courses 里的下标）
        val running = HashMap<Int, MutableList<Int>>()
        val firstDayCol = dayCols.keys.minOrNull() ?: 0

        for (ri in headerRow + 1 until rows.size) {
            val row = rows[ri]

            // ---- 这一行是第几节 + 上下课时间（在左侧节次列里）
            var periodIndex = 0
            var start = ""
            var end = ""
            for (cell in row) {
                if (cell.col >= firstDayCol) break
                val first = cell.texts.firstOrNull()?.trim() ?: continue
                val num = first.toIntOrNull()
                if (num != null && num in 1..20) {
                    periodIndex = num
                    val timeText = row
                        .firstOrNull { it.col == cell.col + cell.span }
                        ?.texts?.joinToString(" ") ?: ""
                    val times = TIME_RE.findAll(timeText).map { it.value }.toList()
                    if (times.size >= 2) {
                        start = normalize(times[0])
                        end = normalize(times[1])
                    }
                    break
                }
            }
            if (periodIndex > 0 && start.isNotEmpty() && end.isNotEmpty()) {
                periods += Period(periodIndex, start, end)
            }

            // ---- 每一天的格子
            for (cell in row) {
                val day = dayCols[cell.col] ?: continue
                if (cell.vMerge == "continue") {
                    // 上一行那门课还在继续：把它的结束节次往后延
                    if (periodIndex > 0) {
                        running[day]?.forEach { idx ->
                            if (idx in courses.indices) {
                                courses[idx] = courses[idx].copy(periodTo = periodIndex)
                            }
                        }
                    }
                    continue
                }

                val texts = cell.texts.map { it.trim() }.filter { it.isNotEmpty() }
                val blocks = splitCourses(texts)
                if (blocks.isEmpty()) {
                    running.remove(day)
                    continue
                }

                val indices = ArrayList<Int>()
                for (block in blocks) {
                    val parsed = parseBlock(block, day, periodIndex)
                    if (parsed == null) {
                        warnings += "星期${day + 1} 第${periodIndex}节 有一格没读懂：" +
                            (block.firstOrNull()?.take(16) ?: "")
                    } else {
                        courses += parsed
                        indices += courses.size - 1
                    }
                }
                if (cell.vMerge == "restart" && indices.isNotEmpty()) {
                    running[day] = indices
                } else {
                    running.remove(day)
                }
            }
        }

        if (courses.isEmpty()) warnings += "没有解析出课程"
        return ParsedTimetable(
            courses = courses.sortedWith(compareBy({ it.day }, { it.periodFrom }, { it.name })),
            maxPeriod = periods.maxOfOrNull { it.index } ?: (courses.maxOfOrNull { it.periodTo } ?: 10),
            warnings = warnings,
            periods = periods.sortedBy { it.index }.ifEmpty { null },
            dayCount = 7,
            schoolLabel = "大连工业大学",
        )
    }

    // ------------------------------------------------------------ 单元格

    private class Cell(
        val col: Int,
        val span: Int,
        val vMerge: String,
        val texts: List<String>,
    )

    private fun cellsOf(row: Element): List<Cell> {
        val out = ArrayList<Cell>()
        var col = 0
        for (tc in children(row, "tc")) {
            val pr = children(tc, "tcPr").firstOrNull()
            val span = pr?.let { children(it, "gridSpan").firstOrNull() }
                ?.getAttributeNS(W_NS, "val")?.toIntOrNull() ?: 1
            val vMergeEl = pr?.let { children(it, "vMerge").firstOrNull() }
            val vMerge = when {
                vMergeEl == null -> ""
                vMergeEl.getAttributeNS(W_NS, "val") in listOf("restart") -> "restart"
                else -> "continue"
            }
            val texts = children(tc, "p").map { p ->
                p.textContent ?: ""
            }
            out += Cell(col, span, vMerge, texts)
            col += span
        }
        return out
    }

    /** 一个格子里可能叠了多门课，用"…………"分隔。 */
    private fun splitCourses(texts: List<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        for (t in texts) {
            val clean = t.trim()
            if (clean.isEmpty()) continue
            if (clean.length >= 4 && clean.all { it == '…' || it == '.' || it == '·' }) {
                if (cur.isNotEmpty()) out += cur
                cur = ArrayList()
                continue
            }
            cur += clean
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** 一个块：课程名 / 教师【周次】 / 教室 / 教学班信息 */
    private fun parseBlock(block: List<String>, day: Int, periodIndex: Int): ParsedCourse? {
        if (block.isEmpty()) return null
        val name = block[0]
        if (name.isEmpty() || name.length > 40) return null

        var teacher = ""
        var weeks: List<Int> = emptyList()
        var weekSpec = ""
        var room = ""
        var slotFrom = 0
        var slotTo = 0

        for (line in block.drop(1)) {
            val m = WEEKS_BRACKET.find(line)
            if (m != null) {
                teacher = line.substringBefore(m.value).trim().trimEnd('【').trim()
                weekSpec = m.value
                weeks = weeksOf(m.groupValues[1])
                SLOT_IN_WEEKS.find(m.value)?.let {
                    slotFrom = it.groupValues[1].toIntOrNull() ?: 0
                    slotTo = it.groupValues[2].toIntOrNull() ?: 0
                }
                continue
            }
            if (room.isEmpty()) room = line
        }
        if (weeks.isEmpty()) return null

        val from = if (slotFrom > 0) slotFrom else periodIndex
        val to = if (slotTo >= from) slotTo else from
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

    /** "9-10周" / "6,8-9周" / "7-15(单)周" / "8-14(双),16-17周" → 周次。 */
    internal fun weeksOf(text: String): List<Int> {
        val odd = text.contains("单")
        val even = text.contains("双")
        val clean = text.replace('（', '(').replace('）', ')').replace("周", "")
        val out = ArrayList<Int>()
        val parts = clean.split(',', '，', '、')
        for (part in parts) {
            val one = Regex("(\\d{1,2})\\s*[-—~]\\s*(\\d{1,2})").find(part)
            if (one != null) {
                val a = one.groupValues[1].toInt()
                val b = one.groupValues[2].toInt()
                for (w in minOf(a, b)..maxOf(a, b)) out += w
            } else {
                Regex("\\d{1,2}").findAll(part).forEach { out += it.value.toInt() }
            }
        }
        val distinct = out.filter { it in 1..30 }.distinct().sorted()
        return when {
            odd -> distinct.filter { it % 2 == 1 }
            even -> distinct.filter { it % 2 == 0 }
            else -> distinct
        }
    }

    private fun normalize(t: String): String {
        val parts = t.split(':')
        if (parts.size != 2) return t
        val h = parts[0].toIntOrNull() ?: return t
        val m = parts[1].toIntOrNull() ?: return t
        return "%02d:%02d".format(h, m)
    }

    // ------------------------------------------------------------ docx 底层

    private fun unzipDocument(bytes: ByteArray): ByteArray? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.equals("word/document.xml", ignoreCase = true)) {
                    return zip.readBytes()
                }
            }
        }
        return null
    }

    private fun parseXml(bytes: ByteArray): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true   // 必须开，否则按命名空间找 <w:tbl> 找不到
        // 关掉外部实体，避免解析被恶意文档利用
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.isExpandEntityReferences = false
        val db = factory.newDocumentBuilder()
        return db.parse(ByteArrayInputStream(bytes)).documentElement
    }

    private fun firstTable(root: Element): Element? {
        val nodes = root.getElementsByTagNameNS(W_NS, "tbl")
        return if (nodes.length > 0) nodes.item(0) as? Element else null
    }

    private fun children(el: Element, localName: String): List<Element> {
        val out = ArrayList<Element>()
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.localName == localName) out += node
        }
        return out
    }

    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val TIME_RE = Regex("\\d{1,2}:\\d{2}")
}
