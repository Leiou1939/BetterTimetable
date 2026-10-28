package com.liangyuchen.timetable.pdf

/** A positioned piece of text on a page. */
internal class TextRun(
    val x: Double,
    val y: Double,
    val size: Double,
    val text: String,
)

/** One course found in the PDF. */
data class ParsedCourse(
    val name: String,
    val day: Int,
    val periodFrom: Int,
    val periodTo: Int,
    val weeks: List<Int>,
    val teacher: String = "",
    val room: String = "",
    val campus: String = "",
    val className: String = "",
    val kind: String = "",
    val credits: String = "",
    val hours: String = "",
    val weekSpec: String = "",
)

/** Everything the parser managed to understand about one PDF. */
data class ParsedTimetable(
    val courses: List<ParsedCourse>,
    val term: String = "",
    val studentId: String = "",
    val studentName: String = "",
    val maxPeriod: Int = 10,
    val sourcePages: Int = 0,
    val warnings: List<String> = emptyList(),
    /**
     * 文件里自带作息时间时（沈阳工程学院的 .xls 每行都写着 08:20-10:00），
     * 用它覆盖设置里的作息；为空表示沿用原来的。
     */
    val periods: List<com.liangyuchen.timetable.data.Period>? = null,
    /** 课表里有几天（沈阳工程学院只排周一到周五）。 */
    val dayCount: Int = 7,
    /** 来源学校的名字，导入预览里显示。 */
    val schoolLabel: String = "",
    /** 班级名，如"某班级"。 */
    val className: String = "",
)

/**
 * Turns a Chinese university timetable PDF into structured courses.
 *
 * The layout is inferred from the file itself — the weekday header row gives the
 * columns, the period numbers in the left strip give the rows, and cards are cut
 * by the blank space between them.  Text is decoded per font, so CID fonts
 * without a ToUnicode map (``UniGB-UCS2-H`` etc.) still come out as Chinese.
 */
object TimetablePdfParser {

    private val WEEKDAYS = listOf(
        "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日",
    )
    private val WEEKDAY_ALT = mapOf(
        "周一" to 0, "周二" to 1, "周三" to 2, "周四" to 3, "周五" to 4,
        "周六" to 5, "周日" to 6, "周天" to 6, "星期天" to 6,
    )
    private val WEEKDAY_SET = WEEKDAYS.toSet() + WEEKDAY_ALT.keys

    private val PERIOD_RE = Regex("\\((\\d{1,2})\\s*[-~－]\\s*(\\d{1,2})\\s*节\\)")
    private val PERIOD_LABEL_RE = Regex("^\\d{1,2}$")
    private val WEEK_SPEC_RE = Regex(
        "((?:\\d{1,2}\\s*[-~－]\\s*\\d{1,2}|\\d{1,2})" +
            "(?:\\s*[,，]\\s*(?:\\d{1,2}\\s*[-~－]\\s*\\d{1,2}|\\d{1,2}))*)" +
            "\\s*周\\s*(?:[（(]\\s*([单双])\\s*[)）])?",
    )
    private val LABEL_RE = Regex(
        "(校区|场地|地点|教室|任课教师|教师|老师|教学班组成|教学班|课程性质简称|" +
            "课程性质|考核方式|课程学时组成|周学时|总学时|学分|选课备注)\\s*[:：]\\s*([^:：/;；]*)",
    )

    /** Numeric fields must not swallow the next card's text after a page spill. */
    private val NUMERIC_LABEL_RE = Regex("(周学时|总学时|学分)\\s*[:：]\\s*([\\d.,]*)")
    private val IMAGE_RE = Regex("([-\\d.]+) ([-\\d.]+) ([-\\d.]+) ([-\\d.]+) re")

    private val INTERESTING = mapOf(
        "校区" to "campus", "场地" to "room", "地点" to "room", "教室" to "room",
        "任课教师" to "teacher", "教师" to "teacher", "老师" to "teacher",
        "教学班" to "className", "课程性质简称" to "kind", "课程性质" to "kind",
        "考核方式" to "exam", "课程学时组成" to "hourMix", "周学时" to "weeklyHours",
        "总学时" to "hours", "学分" to "credits", "选课备注" to "remark",
    )

    // ---------------------------------------------------------------- decoding

    private fun decodeEscaped(raw: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\') {
                out.write(c.code and 0xFF)
                i++
                continue
            }
            if (i + 1 >= raw.length) break
            val n = raw[i + 1]
            when (n) {
                'n' -> { out.write(10); i += 2 }
                'r' -> { out.write(13); i += 2 }
                't' -> { out.write(9); i += 2 }
                'b' -> { out.write(8); i += 2 }
                'f' -> { out.write(12); i += 2 }
                '(', ')' , '\\' -> { out.write(n.code and 0xFF); i += 2 }
                '\n' -> i += 2
                '\r' -> {
                    i += 2
                    if (i < raw.length && raw[i] == '\n') i++
                }
                in '0'..'7' -> {
                    var j = i + 1
                    val digits = StringBuilder()
                    while (j < raw.length && digits.length < 3 && raw[j] in '0'..'7') {
                        digits.append(raw[j]); j++
                    }
                    out.write(digits.toString().toInt(8) and 0xFF)
                    i = j
                }
                else -> { out.write(n.code and 0xFF); i += 2 }
            }
        }
        return out.toByteArray()
    }

    private fun decodeText(raw: ByteArray, cmap: String): String {
        val twoByte = cmap.contains("UCS2", true) || cmap.contains("UTF16", true) ||
            cmap.startsWith("Identity") || cmap.startsWith("Uni") ||
            cmap.contains("GBK", true) || cmap.contains("GB-EUC", true)
        return if (twoByte) {
            val even = if (raw.size % 2 == 0) raw else raw.copyOf(raw.size - 1)
            String(even, Charsets.UTF_16BE)
        } else {
            String(raw, Charsets.ISO_8859_1)
        }
    }

    // ------------------------------------------------------------- text runs

    private fun extractRuns(
        content: ByteArray,
        defaultCmap: String,
        fontCmaps: Map<String, String>,
    ): List<TextRun> {
        val text = String(content, Charsets.ISO_8859_1)
        val runs = ArrayList<TextRun>(256)
        var x = 0.0
        var y = 0.0
        var size = 12.0
        var cmap = defaultCmap

        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            TM_RE.find(trimmed)?.let { m ->
                x = m.groupValues[1].toDoubleOrNull() ?: x
                y = m.groupValues[2].toDoubleOrNull() ?: y
                return@let
            }
            if (TM_RE.containsMatchIn(trimmed)) continue

            TF_RE.find(trimmed)?.let { m ->
                size = m.groupValues[2].toDoubleOrNull() ?: size
                cmap = fontCmaps[m.groupValues[1]] ?: defaultCmap
                return@let
            }
            if (TF_RE.containsMatchIn(trimmed)) continue

            if (trimmed.endsWith("Tj")) {
                val open = trimmed.indexOf('(')
                val close = trimmed.lastIndexOf(')')
                if (open >= 0 && close > open) {
                    val raw = decodeEscaped(trimmed.substring(open + 1, close))
                    runs += TextRun(x, y, size, decodeText(raw, cmap))
                }
                continue
            }
            if (trimmed.endsWith("TJ")) {
                val open = trimmed.indexOf('[')
                val close = trimmed.lastIndexOf(']')
                if (open >= 0 && close > open) {
                    val sb = StringBuilder()
                    for (sm in ARRAY_STRING_RE.findAll(trimmed.substring(open + 1, close))) {
                        sb.append(decodeText(decodeEscaped(sm.groupValues[1]), cmap))
                    }
                    if (sb.isNotEmpty()) runs += TextRun(x, y, size, sb.toString())
                }
            }
        }
        return runs
    }

    private val TM_RE = Regex("^1 0 0 1 (-?[\\d.]+) (-?[\\d.]+) Tm$")
    private val TF_RE = Regex("^/([A-Za-z0-9]+)\\s+([\\d.]+)\\s+Tf$")
    private val ARRAY_STRING_RE = Regex("\\(((?:[^()\\\\]|\\\\.)*)\\)")

    /**
     * Line spacing inside a card is 12pt.  Two stacked cards are separated by
     * more — but on a continuation page that gap shrinks to ~15pt, so anything
     * above ~13 must still belong to the same card.
     */
    private const val MAX_LINE_GAP = 13.5

    /**
     * A page-2 spill fragment consists only of ``字段:值`` pieces and slashes —
     * no course title of its own.  Anything containing other wording is treated
     * as a real card, so a page header cannot be swallowed.
     */
    /**
     * A page-2 spill fragment consists only of ``字段:值`` pieces separated by
     * slashes — it never contains a course title.  Anything else is a real card,
     * so a page header cannot be swallowed.
     */
    private val SPILL_SEGMENT_RE = Regex("^\\s*[\\u4e00-\\u9fff]{1,8}\\s*[:：]\\s*[^:：/;；]*\\s*$")

    internal fun isSpillFragment(blob: String): Boolean {
        val text = blob.trim()
        if (text.isEmpty()) return false
        val segments = text.split('/')
        if (segments.isEmpty()) return false
        return segments.all { it.isBlank() || SPILL_SEGMENT_RE.matches(it) }
    }

    // --------------------------------------------------------------- parsing

    fun parse(bytes: ByteArray): ParsedTimetable {
        val doc = PdfDocument(bytes)
        val defaultCmap = doc.defaultCmap() ?: "UniGB-UCS2-H"
        val fontCmaps = doc.resourceCmaps().ifEmpty {
            // some files name their fonts nowhere useful; fall back to F1
            mapOf("F1" to defaultCmap)
        }

        val warnings = ArrayList<String>()
        val pages = ArrayList<PageData>()
        val allRuns = ArrayList<TextRun>()

        for ((_, contentObj) in doc.pages()) {
            val content = doc.stream(contentObj)
            if (content.isEmpty()) continue
            val runs = extractRuns(content, defaultCmap, fontCmaps)
            val rects = IMAGE_RE.findAll(String(content, Charsets.ISO_8859_1))
                .map { m ->
                    Rect(
                        m.groupValues[1].toDouble(),
                        m.groupValues[2].toDouble(),
                        m.groupValues[3].toDouble(),
                        m.groupValues[4].toDouble(),
                    )
                }
                .toList()
            pages += PageData(runs, rects)
            allRuns += runs
        }
        if (pages.isEmpty()) {
            return ParsedTimetable(emptyList(), warnings = listOf("这个 PDF 没有可读的页面内容"))
        }

        // ---- columns from the weekday header ----
        val header = allRuns.filter { it.text.trim() in WEEKDAY_SET }
        if (header.isEmpty()) {
            return ParsedTimetable(
                emptyList(),
                sourcePages = pages.size,
                warnings = listOf("没有找到“星期一…星期日”表头，这似乎不是课表 PDF"),
            )
        }
        val columns = LinkedHashMap<Int, Double>()   // day -> center x
        for (r in header.sortedBy { it.x }) {
            val label = r.text.trim()
            val day = WEEKDAYS.indexOf(label).takeIf { it >= 0 } ?: WEEKDAY_ALT[label]
            if (day != null && !columns.containsKey(day)) columns[day] = r.x
        }

        val xEdges = sortedSetOf<Double>()
        for (p in pages) for (r in p.rects) {
            xEdges.add(round2(r.x))
            xEdges.add(round2(r.x + r.w))
        }

        fun columnBounds(center: Double): Pair<Double, Double> {
            val left = xEdges.filter { it <= center - 1 }.maxOrNull() ?: (center - 50)
            val right = xEdges.filter { it >= center + 1 }.minOrNull() ?: (center + 50)
            return left to right
        }

        val bounds = columns.mapValues { (_, center) -> columnBounds(center) }
        val gridX0 = bounds.values.minOf { it.first }

        // ---- sanity check: the left strip must contain period numbers ----
        // Each card carries its own "(x-y节)" marker, so the row number is read
        // from the card text rather than derived from these labels; they only
        // prove that we are looking at a timetable at all.
        val labelHi = gridX0
        val labelLo = labelHi * 0.35
        var periodLabels = 0
        for (p in pages) {
            for (r in p.runs) {
                val t = r.text.trim()
                if (!PERIOD_LABEL_RE.matches(t)) continue
                if (!(r.x > labelLo && r.x < labelHi)) continue
                if (r.y <= 150) continue
                val idx = t.toIntOrNull() ?: continue
                if (idx in 1..20) periodLabels++
            }
        }
        if (periodLabels == 0) {
            return ParsedTimetable(
                emptyList(),
                sourcePages = pages.size,
                warnings = listOf("没有找到节次编号，无法确定课程时间"),
            )
        }

        // ---- cards ---------------------------------------------------------
        // Cluster each weekday column by vertical gap *within one page*, parse
        // every cluster, then glue back the fragments a continuation page
        // repeats.  Page 2 of a two-page timetable redraws the grid, so the tail
        // of a card whose detail wrapped over reappears there as a small cluster
        // with no period marker of its own — that cluster is not a course.
        val cards = ArrayList<ParsedCourse>()
        for ((day, _) in columns) {
            val (lo, hi) = bounds[day]!!
            val picked = ArrayList<Pair<Int, TextRun>>()
            for ((pageIndex, p) in pages.withIndex()) {
                for (r in p.runs) {
                    if (r.text.isBlank()) continue      // blanks must not bridge cards
                    if (r.x >= lo - 2 && r.x < hi + 2) picked += pageIndex to r
                }
            }
            picked.sortWith(compareBy({ it.first }, { -it.second.y }))

            val groups: MutableList<List<Pair<Int, TextRun>>?> =
                clusterCards(picked).flatMap { splitAtMarkers(it) }.toMutableList()

            // A continuation page redraws the grid, and the tail of a card whose
            // detail wrapped over reappears there as a small cluster sitting
            // above the page's topmost text, holding only "字段:值" pieces and no
            // period marker.  Append it to the card it belongs to instead of
            // inventing a course from it.
            val pageTopText = HashMap<Int, Double>()
            for ((index, p) in pages.withIndex()) {
                pageTopText[index] = p.runs.maxOfOrNull { it.y } ?: Double.MAX_VALUE
            }

            for (i in 1 until groups.size) {
                val fragment = groups[i]
                val owner = groups[i - 1]
                if (owner == null || fragment == null) continue
                if (fragment.first().first == owner.first().first) continue
                if (fragment.first().second.y <
                    pageTopText.getOrDefault(fragment.first().first, 0.0) - 1.0
                ) {
                    continue                                 // not at the page top
                }
                val fragmentBlob = fragment.joinToString("") { it.second.text }
                if (!isSpillFragment(fragmentBlob)) continue
                groups[i - 1] = owner + fragment
                groups[i] = null                             // consumed
            }

            for (group in groups) {
                if (group == null) continue
                val blob = group.joinToString("") { it.second.text }
                val parsed = parseCard(blob, day) ?: continue
                cards += parsed
            }
        }

        // Merge a card whose detail text spilled onto a later page into the
        // copy that already carries the full card.  A spill fragment has no
        // title of its own, so it is dropped rather than merged.
        val merged = ArrayList<ParsedCourse>()
        for (c in cards.sortedWith(
            compareBy({ it.day }, { it.periodFrom }, { it.periodTo }, { it.name }),
        )) {
            val prev = merged.lastOrNull()
            if (prev != null && prev.day == c.day && prev.name == c.name &&
                prev.periodFrom == c.periodFrom && prev.periodTo == c.periodTo
            ) {
                merged[merged.size - 1] = prev.copy(
                    teacher = prev.teacher.ifEmpty { c.teacher },
                    room = prev.room.ifEmpty { c.room },
                    campus = prev.campus.ifEmpty { c.campus },
                    className = prev.className.ifEmpty { c.className },
                    kind = prev.kind.ifEmpty { c.kind },
                    credits = prev.credits.ifEmpty { c.credits },
                    hours = prev.hours.ifEmpty { c.hours },
                    weeks = if (prev.weeks.isEmpty()) c.weeks else prev.weeks,
                    weekSpec = if (prev.weeks.isEmpty()) c.weekSpec else prev.weekSpec,
                )
                continue
            }
            merged += c
        }

        // ---- header / footer information ----
        var term = ""
        var studentId = ""
        var studentName = ""
        for (r in allRuns) {
            val t = r.text.trim()
            if (term.isEmpty()) {
                Regex("\\d{4}\\s*[-－]\\s*\\d{4}\\s*学年\\s*第?\\s*\\d\\s*学期")
                    .find(t)?.let { term = it.value.replace(" ", "") }
            }
            if (studentId.isEmpty()) {
                Regex("学号\\s*[:：]\\s*(\\w+)").find(t)?.let { studentId = it.groupValues[1] }
            }
            if (studentName.isEmpty()) {
                Regex("([\\u4e00-\\u9fff]{2,4})\\s*课表").find(t)?.let {
                    studentName = it.groupValues[1]
                }
            }
        }

        val maxPeriod = merged.maxOfOrNull { it.periodTo }
            ?.coerceAtLeast(10) ?: 10

        val extraWarnings = ArrayList<String>(warnings)
        if (merged.isEmpty()) {
            extraWarnings += "这张 PDF 里没有解析出任何课程，可能不是课表格式"
        }

        return ParsedTimetable(
            courses = merged,
            term = term,
            studentId = studentId,
            studentName = studentName,
            maxPeriod = maxPeriod,
            sourcePages = pages.size,
            warnings = extraWarnings,
        )
    }

    // ----------------------------------------------------------- card parsing

    internal fun parseCard(blob: String, day: Int): ParsedCourse? {
        // A slow row band can drag the next card's first line into this blob.
        // Everything from the second period marker on belongs to the next card.
        val second = PERIOD_RE.findAll(blob).drop(1).firstOrNull()
        val body = if (second != null) blob.substring(0, second.range.first) else blob
        val m = PERIOD_RE.find(body) ?: PERIOD_RE.find(blob) ?: return null
        val title = titleFrom(body)
        val fields = fieldsFrom(body)
        if (title.isEmpty() || title.length > 40) return null

        var weeksText = ""
        val withoutPeriods = PERIOD_RE.replace(body, " ")
        // 一门课可能写在多段里：「10-11周,13-15周」。正则一次只吃一段（不能把两个
        // 数字段并成一个可重复分组，否则只有最后一次捕获有效），所以这里把紧邻的
        // 匹配拼起来——中间只允许出现空白或逗号。中间夹着别的字符说明那是另一张
        // 卡片，绝不能拼（否则会吃进相邻课程的周次）。
        val weekMatches = WEEK_SPEC_RE.findAll(withoutPeriods).toList()
        val specs = ArrayList<String>()
        var prevEnd = -1
        for (wm in weekMatches) {
            val gap = if (prevEnd < 0) "" else withoutPeriods.substring(prevEnd, wm.range.first)
            if (prevEnd >= 0 && !gap.all { it.isWhitespace() || it == ',' || it == '，' }) break
            val text = wm.groupValues[1] + "周" +
                (if (wm.groupValues[2].isNotEmpty()) "(${wm.groupValues[2]})" else "")
            if (parseWeeks(text).isEmpty()) break
            specs += text
            prevEnd = wm.range.last + 1
        }
        if (specs.isNotEmpty()) weeksText = specs.joinToString(",")

        return ParsedCourse(
            name = title,
            day = day,
            periodFrom = m.groupValues[1].toIntOrNull() ?: 1,
            periodTo = m.groupValues[2].toIntOrNull() ?: 1,
            weeks = parseWeeks(weeksText),
            weekSpec = weeksText,
            teacher = fields["teacher"].orEmpty(),
            room = fields["room"].orEmpty(),
            campus = fields["campus"].orEmpty(),
            className = fields["className"].orEmpty(),
            kind = fields["kind"].orEmpty(),
            credits = fields["credits"].orEmpty(),
            hours = fields["hours"].orEmpty(),
        )
    }

    private fun fieldsFrom(blob: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (m in LABEL_RE.findAll(blob)) {
            val key = INTERESTING[m.groupValues[1]] ?: continue
            val value = m.groupValues[2].trim()
            if (value.isNotEmpty() && !out.containsKey(key)) out[key] = value
        }
        // Numeric fields use the *last* match: a page spill puts the previous
        // card's "学分:3.0" in front of this card's own "学分:4.0".
        for (m in NUMERIC_LABEL_RE.findAll(blob)) {
            val key = INTERESTING[m.groupValues[1]] ?: continue
            val value = m.groupValues[2].trim().trimEnd('.', ',')
            if (value.isNotEmpty()) out[key] = value
        }
        return out
    }

    internal fun titleFrom(blob: String): String {
        val m = PERIOD_RE.find(blob)
        var head = if (m != null) blob.substring(0, m.range.first) else blob
        for (sep in listOf("/", ";", "；")) {
            val at = head.lastIndexOf(sep)
            if (at >= 0) head = head.substring(at + 1)
        }
        head = stripLeadingSpill(head)
        head = PERIOD_RE.replace(head, "")
        head = Regex(
            "\\d{1,2}\\s*[-~－]\\s*\\d{1,2}\\s*(?:[,，]\\s*\\d{1,2}" +
                "(?:\\s*[-~－]\\s*\\d{1,2})?)*\\s*周(?:\\s*[（(]\\s*[单双]\\s*[)）])?",
        ).replace(head, "")
        head = Regex("\\d{1,2}\\s*周(?:\\s*[（(]\\s*[单双]\\s*[)）])?").replace(head, "")
        for (w in WEEKDAYS + listOf("课表", "某同学")) head = head.replace(w, "")
        head = Regex("^[^A-Za-z\\u4e00-\\u9fff(（]*").replace(head, "")
        head = Regex("[\\s/;；,，\\-－]+$").replace(head, "")
        return head.trim()
    }

    private fun stripLeadingSpill(head: String): String {
        var h = head
        for (i in 0 until 8) {
            val m = Regex("^\\s*/?\\s*([\\u4e00-\\u9fff]{1,8})\\s*[:：]").find(h) ?: break
            if (m.groupValues[1] in WEEKDAY_SET) break
            h = h.substring(m.range.last + 1)
            val nxt = Regex("[\\u4e00-\\u9fff]{1,8}\\s*[:：]").find(h)
            if (nxt != null) {
                h = h.substring(nxt.range.first)
            } else {
                h = Regex("^[^A-Za-z\\u4e00-\\u9fff(（]*").replace(h, "")
                break
            }
        }
        return Regex("^\\s*[^A-Za-z\\u4e00-\\u9fff(（]+").replace(h, "")
    }

    /** "5-18周" / "6-16周(双)" / "5-17周(单)" / "10-11周,13-15周" -> week list */
    internal fun parseWeeks(spec: String): List<Int> {
        if (spec.isBlank()) return emptyList()
        val parity = when {
            spec.contains("(单)") || spec.contains("（单）") -> "odd"
            spec.contains("(双)") || spec.contains("（双）") -> "even"
            else -> null
        }
        var body = spec
        for (token in listOf("(单)", "（单）", "(双)", "（双）", "周")) {
            body = body.replace(token, "")
        }
        body = Regex("^[（(]\\s*\\d+\\s*[-~－]\\s*\\d+\\s*节\\s*[)）]").replace(body, "")
        val weeks = sortedSetOf<Int>()
        for (part in body.split(',', '，', ';', '；')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val m = Regex("^(\\d+)\\s*[-~－]\\s*(\\d+)$").find(p)
            if (m != null) {
                val a = m.groupValues[1].toInt()
                val b = m.groupValues[2].toInt()
                if (a <= b && b - a < 40) for (w in a..b) weeks += w
            } else if (p.all { it.isDigit() }) {
                p.toIntOrNull()?.let { weeks += it }
            }
        }
        val filtered = weeks.filter {
            it > 0 && it <= 40 &&
                (parity == null || (parity == "odd" && it % 2 == 1) ||
                    (parity == "even" && it % 2 == 0))
        }
        return filtered
    }

    // ------------------------------------------------------------------ misc

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    /**
     * Split one weekday column into cards.
     *
     * Clustering happens *within a page* by vertical gap: line spacing inside a
     * card is 12pt while two stacked cards are separated by more — even on a
     * continuation page, where that gap shrinks to ~15pt.  y values must not be
     * compared across pages, because a two-page timetable repeats the grid on
     * page 2 and the two pages cover different period ranges.
     */
    private fun clusterCards(
        picked: List<Pair<Int, TextRun>>,
    ): List<List<Pair<Int, TextRun>>> {
        val groups = ArrayList<MutableList<Pair<Int, TextRun>>>()
        for (item in picked) {
            val last = groups.lastOrNull()?.lastOrNull()
            val samePage = last != null && last.first == item.first
            if (last != null && samePage && (last.second.y - item.second.y) <= MAX_LINE_GAP) {
                groups.last().add(item)
            } else {
                groups.add(mutableListOf(item))
            }
        }
        return groups
    }

    /**
     * One course occupies exactly one ``(x-y节)`` marker line, and the card's
     * detail runs sit *after* it while its title sits just before it.  When the
     * gap between two stacked cards is small (it shrinks to ~15pt on a
     * continuation page) the gap test can merge them, so split again at every
     * marker after the first: the marker then starts the next card, and the runs
     * above it are that card's title — never the previous card's field values.
     */
    private fun splitAtMarkers(
        group: List<Pair<Int, TextRun>>,
    ): List<List<Pair<Int, TextRun>>> {
        val markerIndexes = group.indices.filter {
            PERIOD_RE.containsMatchIn(group[it].second.text)
        }
        if (markerIndexes.size <= 1) return listOf(group)

        val out = ArrayList<List<Pair<Int, TextRun>>>()
        var start = 0
        for (k in 1 until markerIndexes.size) {
            // the title of the next card sits between the two markers
            val boundary = maxOf(start, markerIndexes[k] - 1)
            out += group.subList(start, boundary)
            start = boundary
        }
        out += group.subList(start, group.size)
        return out
    }

    /** 调试用：每张卡片解析出的课程 + 它在页面上的纵向位置。 */
    internal data class DebugCard(
        val day: Int,
        val periodFrom: Int,
        val periodTo: Int,
        val yTop: Double,
        val yBottom: Double,
        val name: String,
    )

    internal fun debugCards(bytes: ByteArray): List<DebugCard> {
        val doc = PdfDocument(bytes)
        val defaultCmap = doc.defaultCmap() ?: "UniGB-UCS2-H"
        val fontMaps = doc.resourceCmaps().ifEmpty { mapOf("F1" to defaultCmap) }
        val pages = ArrayList<Pair<Int, List<TextRun>>>()
        for ((_, contentObj) in doc.pages()) {
            val content = doc.stream(contentObj)
            if (content.isEmpty()) continue
            pages += contentObj to extractRuns(content, defaultCmap, fontMaps)
        }
        val all = pages.flatMap { it.second }
        val header = all.filter { it.text.trim() in WEEKDAY_SET }
        val columns = LinkedHashMap<Int, Double>()
        for (r in header.sortedBy { it.x }) {
            val label = r.text.trim()
            val day = WEEKDAYS.indexOf(label).takeIf { it >= 0 } ?: WEEKDAY_ALT[label]
            if (day != null) columns.putIfAbsent(day, r.x)
        }
        val xEdges = sortedSetOf<Double>()
        for ((_, runs) in pages) {
            for (r in runs) { /* nothing */ }
        }
        // reuse the same column bounds as parse(): rebuild from the rects
        val rectEdges = sortedSetOf<Double>()
        for ((_, contentObj) in doc.pages()) {
            val content = doc.stream(contentObj)
            for (m in IMAGE_RE.findAll(String(content, Charsets.ISO_8859_1))) {
                val x = m.groupValues[1].toDouble()
                val w = m.groupValues[3].toDouble()
                rectEdges.add(round2(x))
                rectEdges.add(round2(x + w))
            }
        }
        xEdges.addAll(rectEdges)

        val out = ArrayList<DebugCard>()
        for ((day, center) in columns) {
            val left = xEdges.filter { it <= center - 1 }.maxOrNull() ?: (center - 50)
            val right = xEdges.filter { it >= center + 1 }.minOrNull() ?: (center + 50)
            val picked = ArrayList<Triple<Int, Int, TextRun>>()   // page, pageObj, run
            for ((pi, entry) in pages.withIndex()) {
                for (r in entry.second) {
                    if (r.text.isBlank()) continue
                    if (r.x >= left - 2 && r.x < right + 2) picked += Triple(pi, entry.first, r)
                }
            }
            picked.sortWith(compareBy({ it.first }, { -it.third.y }))
            for (g in clusterCards(picked.map { it.first to it.third }).flatMap { splitAtMarkers(it) }) {
                val blob = g.joinToString("") { it.second.text }
                val parsed = parseCard(blob, day) ?: continue
                out += DebugCard(
                    day = day,
                    periodFrom = parsed.periodFrom,
                    periodTo = parsed.periodTo,
                    yTop = g.first().second.y,
                    yBottom = g.last().second.y,
                    name = parsed.name + "  [page ${g.first().first + 1}]",
                )
            }
        }
        return out.sortedWith(compareBy({ it.day }, { -it.yTop }))
    }

    private class Rect(val x: Double, val y: Double, val w: Double, val h: Double)
    private class PageData(val runs: List<TextRun>, val rects: List<Rect>)
    private class RowBand(val period: Int, val lower: Double, val upper: Double)
}
