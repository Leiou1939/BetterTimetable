package com.liangyuchen.timetable.pdf

/**
 * Minimal PDF reader: enough to pull positioned text out of a timetable PDF.
 *
 * Deliberately does not use the cross-reference table — objects are located by
 * scanning for ``N 0 obj``, which also works for the slightly broken files that
 * campus systems emit.  FlateDecode streams are inflated with [java.util.zip].
 */
internal class PdfDocument(val bytes: ByteArray) {

    /** object number -> raw object body bytes */
    private val objects = HashMap<Int, ByteArray>()

    /** object number -> inflated stream body */
    private val streams = HashMap<Int, ByteArray>()

    init {
        scanObjects()
    }

    private fun scanObjects() {
        var i = 0
        val n = bytes.size
        while (i < n) {
            val at = indexOf(bytes, OBJ, i)
            if (at < 0) break
            i = at + OBJ.size
            // "N G obj": skip the generation number, then take the object number
            var k = at - 1
            while (k >= 0 && isPdfSpace(bytes[k])) k--
            while (k >= 0 && bytes[k] in '0'.code..'9'.code) k--   // generation
            while (k >= 0 && isPdfSpace(bytes[k])) k--
            val digitsEnd = k + 1
            while (k >= 0 && bytes[k] in '0'.code..'9'.code) k--
            val digitsStart = k + 1
            if (digitsStart >= digitsEnd) continue
            val number = String(
                bytes, digitsStart, digitsEnd - digitsStart, Charsets.ISO_8859_1,
            ).toIntOrNull() ?: continue
            val end = indexOf(bytes, ENDOBJ, at)
            val bodyEnd = if (end < 0) n else end
            objects[number] = bytes.copyOfRange(at + OBJ.size, bodyEnd)
        }
    }

    private fun isPdfSpace(b: Byte): Boolean {
        val v = b.toInt()
        return v == 32 || v == 9 || v == 10 || v == 13 || v == 12 || v == 0
    }

    fun rawObject(number: Int): ByteArray = objects[number] ?: ByteArray(0)

    /** Stream body of [number], inflated when it is FlateDecode. */
    fun stream(number: Int): ByteArray {
        streams[number]?.let { return it }
        val body = rawObject(number)
        val streamAt = indexOf(body, STREAM)
        if (streamAt < 0) return ByteArray(0)
        val header = String(body, 0, streamAt, Charsets.ISO_8859_1)
        var start = streamAt + STREAM.size
        while (start < body.size &&
            (body[start] == CR.toByte() || body[start] == LF.toByte())
        ) start++
        var end = lastIndexOf(body, ENDSTREAM)
        if (end < start) end = body.size
        var raw = body.copyOfRange(start, end)
        if (header.contains("FlateDecode")) {
            raw = inflate(raw)
        }
        streams[number] = raw
        return raw
    }

    private fun inflate(data: ByteArray): ByteArray {
        return try {
            val inflater = java.util.zip.Inflater()
            inflater.setInput(data)
            val out = java.io.ByteArrayOutputStream(data.size * 4)
            val buffer = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
            inflater.end()
            out.toByteArray()
        } catch (t: Throwable) {
            try {
                val inflater = java.util.zip.Inflater(true)
                inflater.setInput(data)
                val out = java.io.ByteArrayOutputStream(data.size * 4)
                val buffer = ByteArray(64 * 1024)
                while (!inflater.finished()) {
                    val read = inflater.inflate(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
                inflater.end()
                out.toByteArray()
            } catch (t2: Throwable) {
                ByteArray(0)
            }
        }
    }

    /** Page objects in document order: (pageObjectNumber, contentObjectNumber). */
    fun pages(): List<Pair<Int, Int>> {
        val found = ArrayList<Pair<Int, Int>>()
        for ((number, body) in objects) {
            val text = String(body, 0, minOf(body.size, 1024), Charsets.ISO_8859_1)
            if (!text.contains("/Type") || !text.contains("/Page")) continue
            if (!text.contains("/Contents")) continue
            val m = CONTENTS_RE.find(text) ?: continue
            found += number to m.groupValues[1].toInt()
        }
        // page objects normally come out of the scan in ascending order, which is
        // document order; keep that but make it deterministic
        return found.sortedBy { it.first }
    }

    /** First CMap-ish encoding name declared by any font in the file. */
    fun defaultCmap(): String? {
        for ((_, body) in objects) {
            val text = String(body, 0, body.size, Charsets.ISO_8859_1)
            if (!text.contains("/BaseFont") && !text.contains("/Type0")) continue
            val m = UNI_CMAP_RE.find(text) ?: continue
            return m.groupValues[1]
        }
        return null
    }

    /** Font object number -> CMap name, for per-font decoding. */
    fun fontCmaps(): Map<Int, String> {
        val out = HashMap<Int, String>()
        for ((number, body) in objects) {
            val text = String(body, 0, minOf(body.size, 2048), Charsets.ISO_8859_1)
            if (!text.contains("/BaseFont")) continue
            val m = ENCODING_RE.find(text) ?: continue
            out[number] = m.groupValues[1]
        }
        return out
    }

    /**
     * Resource name -> CMap name, e.g. ``F1`` -> ``UniGB-UCS2-H``.
     *
     * Every page's ``/Resources /Font`` dictionary maps the names used by the
     * content stream to font objects, so a file with several fonts (some CID,
     * some simple) is decoded correctly instead of falling back to one default.
     */
    /** 每个字体资源名 → 字体对象号，例如 F1 → 12。 */
    fun fontObjects(): Map<String, Int> {
        val out = HashMap<String, Int>()
        for ((_, body) in objects) {
            val text = String(body, 0, minOf(body.size, 4096), Charsets.ISO_8859_1)
            val fontAt = text.indexOf("/Font")
            if (fontAt < 0) continue
            val dictStart = text.indexOf("<<", fontAt)
            if (dictStart < 0) continue
            val dictEnd = text.indexOf(">>", dictStart)
            val dict = if (dictEnd > 0) text.substring(dictStart, dictEnd) else text.substring(dictStart)
            for (m in FONT_ENTRY_RE.findAll(dict)) {
                val obj = m.groupValues[2].toIntOrNull() ?: continue
                out.putIfAbsent(m.groupValues[1], obj)
            }
        }
        return out
    }

    /**
     * 解析每个字体的 /ToUnicode CMap：CID → Unicode 文本。
     *
     * Type0 字体（Identity-H + ToUnicode）在教务导出的 PDF 里很常见，
     * 文本是 `<0001>` 这样的字形编号，必须查这张表才有汉字。
     */
    fun fontToUnicode(): Map<String, Map<Int, String>> {
        val out = HashMap<String, Map<Int, String>>()
        for ((name, objNum) in fontObjects()) {
            val body = objects[objNum] ?: continue
            val head = String(body, 0, minOf(body.size, 2048), Charsets.ISO_8859_1)
            val ref = TO_UNICODE_RE.find(head)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val cmapBytes = stream(ref)
            if (cmapBytes.isEmpty()) continue
            val table = parseToUnicodeCmap(String(cmapBytes, Charsets.ISO_8859_1))
            if (table.isNotEmpty()) out[name] = table
        }
        return out
    }

    /** 解析 ToUnicode CMap 里的 beginbfchar / beginbfrange。 */
    private fun parseToUnicodeCmap(text: String): Map<Int, String> {
        val table = HashMap<Int, String>()
        BFCHAR_BLOCK.findAll(text).forEach { block ->
            for (m in HEX_PAIR.findAll(block.groupValues[1])) {
                val code = m.groupValues[1].toIntOrNull(16) ?: continue
                val value = hexToUnicode(m.groupValues[2])
                if (value.isNotEmpty()) table[code] = value
            }
        }
        BFRANGE_BLOCK.findAll(text).forEach { block ->
            val inner = block.groupValues[1]
            val m = BFRANGE_HEAD.find(inner) ?: return@forEach
            val lo = m.groupValues[1].toIntOrNull(16) ?: return@forEach
            val hi = m.groupValues[2].toIntOrNull(16) ?: return@forEach
            @Suppress("NAME_SHADOWING")
            val dst = m.groupValues[3]
            if (dst.trimStart().startsWith("[")) {
                val items = HEX_TOKEN.findAll(dst).map { it.value }.toList()
                items.forEachIndexed { i, hex ->
                    val code = lo + i
                    if (code <= hi) table.putIfAbsent(code, hexToUnicode(hex))
                }
            } else {
                val start = dst.trim().toIntOrNull(16) ?: return@forEach
                for (code in lo..hi) {
                    val v = start + (code - lo)
                    table.putIfAbsent(code, String(Character.toChars(v.coerceIn(0, 0x10FFFF))))
                }
            }
        }
        return table
    }

    private fun hexToUnicode(hex: String): String {
        val clean = hex.filter { it.isLetterOrDigit() }
        if (clean.isEmpty() || clean.length % 2 != 0) return ""
        val bytes = ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toIntOrNull(16)?.toByte() ?: 0
        }
        return String(bytes, Charsets.UTF_16BE)
    }

    fun resourceCmaps(): Map<String, String> {
        val byNumber = fontCmaps()
        val out = HashMap<String, String>()
        for ((_, body) in objects) {
            val text = String(body, 0, minOf(body.size, 4096), Charsets.ISO_8859_1)
            val fontAt = text.indexOf("/Font")
            if (fontAt < 0) continue
            val dictStart = text.indexOf("<<", fontAt)
            if (dictStart < 0) continue
            val dictEnd = text.indexOf(">>", dictStart)
            val dict = if (dictEnd > 0) text.substring(dictStart, dictEnd) else text.substring(dictStart)
            for (m in FONT_ENTRY_RE.findAll(dict)) {
                val name = m.groupValues[1]
                val obj = m.groupValues[2].toIntOrNull() ?: continue
                byNumber[obj]?.let { out.putIfAbsent(name, it) }
            }
        }
        return out
    }

    companion object {
        private val OBJ = " obj".toByteArray(Charsets.ISO_8859_1)
        private val ENDOBJ = "endobj".toByteArray(Charsets.ISO_8859_1)
        private val STREAM = "stream".toByteArray(Charsets.ISO_8859_1)
        private val ENDSTREAM = "endstream".toByteArray(Charsets.ISO_8859_1)
        private const val CR = 13
        private const val LF = 10

        private val CONTENTS_RE = Regex("/Contents\\s+(\\d+)\\s+0\\s+R")
        private val UNI_CMAP_RE = Regex("/Encoding\\s*/(Uni[A-Za-z0-9-]+)")
        private val ENCODING_RE = Regex("/Encoding\\s*/([#A-Za-z0-9_.-]+)")
        private val FONT_ENTRY_RE = Regex("/([A-Za-z0-9]+)\\s+(\\d+)\\s+0\\s+R")
        private val TO_UNICODE_RE = Regex("/ToUnicode\\s+(\\d+)\\s+0\\s+R")
        private val BFCHAR_BLOCK = Regex("beginbfchar(.*?)endbfchar", RegexOption.DOT_MATCHES_ALL)
        private val BFRANGE_BLOCK = Regex("beginbfrange(.*?)endbfrange", RegexOption.DOT_MATCHES_ALL)
        private val BFRANGE_HEAD =
            Regex("<\\s*([0-9A-Fa-f]+)\\s*>\\s*<\\s*([0-9A-Fa-f]+)\\s*>\\s*(\\[[^\\]]*]|<[0-9A-Fa-f]+>)")
        private val HEX_PAIR = Regex("<\\s*([0-9A-Fa-f]+)\\s*>\\s*<\\s*([0-9A-Fa-f]+)\\s*>")
        private val HEX_TOKEN = Regex("<([0-9A-Fa-f]+)>")

        internal fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
            if (needle.isEmpty() || haystack.size < needle.size) return -1
            outer@ for (i in from..haystack.size - needle.size) {
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return -1
        }

        internal fun lastIndexOf(haystack: ByteArray, needle: ByteArray): Int {
            if (needle.isEmpty() || haystack.size < needle.size) return -1
            outer@ for (i in haystack.size - needle.size downTo 0) {
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return -1
        }
    }
}
