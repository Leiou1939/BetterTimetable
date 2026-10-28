package com.liangyuchen.timetable.xls

/**
 * 一个工作表。单元格按 (行 shl 16) or 列 存进 Map，行列都从 0 开始（和 Excel 一致）。
 */
class XlsSheet(
    val name: String,
    val cells: Map<Int, String>,
    /** 合并区域，每项为 [rowFirst, rowLast, colFirst, colLast]。 */
    val merged: List<IntArray> = emptyList(),
) {
    fun cell(row: Int, col: Int): String = cells[key(row, col)] ?: ""

    val maxRow: Int get() = cells.keys.maxOfOrNull { it ushr 16 } ?: 0
    val maxCol: Int get() = cells.keys.maxOfOrNull { it and 0xFFFF } ?: 0

    /** 某一行里第一段非空文本（课表左列的"第一大节第0102节08:20-10:00"）。 */
    fun rowText(row: Int): String {
        val parts = ArrayList<String>()
        for (c in 0..maxCol) {
            val v = cell(row, c)
            if (v.isNotBlank()) parts += v
        }
        return parts.joinToString(" ")
    }

    companion object {
        fun key(row: Int, col: Int): Int = (row shl 16) or (col and 0xFFFF)
    }
}

class XlsWorkbook(val sheets: List<XlsSheet>) {
    fun sheetContaining(needle: String): XlsSheet? =
        sheets.firstOrNull { s -> s.cells.values.any { it.contains(needle) } }
}

/**
 * 极简 BIFF8（Excel 97-2003 .xls）读取器，零依赖，只取"文本/数字/合并单元格"。
 *
 * 为什么要自己写：Android 上没有现成的 .xls 解析器，而引进 POI 体积大、在 Android
 * 上还依赖 java.awt 的部分类。课表只需要读格子里的字符串，所以按需实现：
 *   OLE2 复合文档 → 找到 "Workbook" 流 → 解析 BOUNDSHEET / SST / LABELSST /
 *   LABEL / NUMBER / RK / MULRK / FORMULA / MERGEDCELLS 这几种记录。
 */
object XlsReader {

    private const val FREESECT = -1

    /** 读取失败时返回 null（调用方好回退到"这个文件不是 xls"的提示）。 */
    fun readOrNull(bytes: ByteArray): XlsWorkbook? = runCatching { read(bytes) }.getOrNull()

    fun read(bytes: ByteArray): XlsWorkbook {
        val ole = Ole2(bytes)
        val stream = ole.readStream("Workbook")
            ?: ole.readStream("Book")
            ?: throw IllegalArgumentException("这不是 Excel 97-2003 工作簿（找不到 Workbook 流）")
        return Biff8.parse(stream)
    }

    // ------------------------------------------------------------------ OLE2

    private class Ole2(private val d: ByteArray) {

        private val sectorSize: Int
        private val miniSectorSize: Int
        private val miniCutoff: Int
        private val fat: IntArray
        private val miniFat: IntArray
        private val entries: List<DirEntry>
        private val miniStream: ByteArray

        init {
            require(d.size >= 512 && d[0] == 0xD0.toByte() && d[1] == 0xCF.toByte()) {
                "不是 OLE2 复合文档"
            }
            sectorSize = 1 shl u16(30)
            miniSectorSize = 1 shl u16(32)
            miniCutoff = u32(56)
            val numFatSectors = u32(44)
            val dirFirstSector = u32(48)
            val miniFatFirstSector = u32(60)
            val numMiniFatSectors = u32(64)
            val difatFirstSector = u32(68)

            // ---- DIFAT：头部 109 项 + 后续 DIFAT 扇区链
            val fatSectorIds = ArrayList<Int>()
            for (i in 0 until 109) {
                val s = u32(76 + i * 4)
                if (s >= 0 && s != FREESECT) fatSectorIds += s
            }
            var difat = difatFirstSector
            var guard = 0
            while (difat in 0 until 0xFFFFFFF0.toInt() && guard < 1024) {
                guard++
                val base = sectorOffset(difat)
                val perSector = sectorSize / 4 - 1
                for (i in 0 until perSector) {
                    val s = i32(base + i * 4)
                    if (s >= 0 && s != FREESECT) fatSectorIds += s
                }
                difat = i32(base + perSector * 4)
            }
            if (numFatSectors in 1..fatSectorIds.size) {
                // 以头部声明的数量为准（多余的可能是脏数据）
            }

            // ---- FAT
            val fatOut = ArrayList<Int>()
            for (sid in fatSectorIds) {
                val base = sectorOffset(sid)
                for (i in 0 until sectorSize / 4) fatOut += i32(base + i * 4)
            }
            fat = fatOut.toIntArray()

            // ---- 目录
            val dirBytes = readChain(dirFirstSector, -1)
            val list = ArrayList<DirEntry>()
            var p = 0
            while (p + 128 <= dirBytes.size) {
                val nameLen = u16(dirBytes, p + 64)
                val type = dirBytes[p + 66].toInt() and 0xFF
                val name = if (nameLen in 2..64) {
                    String(dirBytes, p, nameLen - 2, Charsets.UTF_16LE)
                } else {
                    ""
                }
                list += DirEntry(
                    name = name,
                    type = type,
                    start = i32(dirBytes, p + 116),
                    size = i32(dirBytes, p + 120),
                )
                p += 128
            }
            entries = list

            // ---- mini FAT + mini stream（Root Entry 的数据就是 mini stream）
            val root = entries.firstOrNull { it.type == 5 }
            miniStream = if (root != null && root.start >= 0 && root.size > 0) {
                readChain(root.start, root.size)
            } else {
                ByteArray(0)
            }

            val mfBytes = if (numMiniFatSectors > 0 && miniFatFirstSector >= 0) {
                readChain(miniFatFirstSector, numMiniFatSectors * sectorSize)
            } else {
                ByteArray(0)
            }
            val mf = ArrayList<Int>()
            var q = 0
            while (q + 4 <= mfBytes.size) {
                mf += i32(mfBytes, q)
                q += 4
            }
            miniFat = mf.toIntArray()
        }

        fun readStream(name: String): ByteArray? {
            val e = entries.firstOrNull { it.type == 2 && it.name == name } ?: return null
            if (e.size <= 0) return ByteArray(0)
            return if (e.size >= miniCutoff) {
                readChain(e.start, e.size)
            } else {
                readMiniChain(e.start, e.size)
            }
        }

        private fun readMiniChain(start: Int, size: Int): ByteArray {
            val out = ByteArray(size)
            var sec = start
            var pos = 0
            var guard = 0
            while (sec in 0 until miniFat.size && pos < size && guard < 1 shl 20) {
                guard++
                val base = sec * miniSectorSize
                val n = minOf(miniSectorSize, size - pos, (miniStream.size - base).coerceAtLeast(0))
                if (n <= 0) break
                System.arraycopy(miniStream, base, out, pos, n)
                pos += n
                sec = miniFat[sec]
            }
            return out
        }

        /** [size] < 0 表示读到链尾为止。 */
        private fun readChain(start: Int, size: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var sec = start
            var guard = 0
            while (sec in 0 until fat.size && guard < 1 shl 20) {
                guard++
                val base = sectorOffset(sec)
                val n = minOf(sectorSize, d.size - base)
                if (n <= 0) break
                out.write(d, base, n)
                if (size >= 0 && out.size() >= size) break
                sec = fat[sec]
            }
            val bytes = out.toByteArray()
            return if (size in 0..bytes.size) bytes.copyOf(size) else bytes
        }

        private fun sectorOffset(sector: Int): Int = 512 + sector * sectorSize

        private fun u16(off: Int): Int = u16(d, off)
        private fun u32(off: Int): Int = i32(d, off)

        private fun i32(off: Int): Int = i32(d, off)

        private class DirEntry(val name: String, val type: Int, val start: Int, val size: Int)
    }

    private fun u16(b: ByteArray, off: Int): Int =
        if (off + 2 > b.size) 0
        else (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun i32(b: ByteArray, off: Int): Int =
        if (off + 4 > b.size) -1
        else (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun f64(b: ByteArray, off: Int): Double =
        Double.fromBits(
            (i32(b, off).toLong() and 0xFFFFFFFFL) or (i32(b, off + 4).toLong() shl 32),
        )

    /** RK 编码的数值。 */
    private fun rk(v: Int): Double {
        val isInt = (v and 0x02) != 0
        var out: Double = if (isInt) {
            (v shr 2).toDouble()
        } else {
            Double.fromBits(((v shr 2).toLong() and 0x3FFFFFFFL) shl 32)
        }
        if ((v and 0x01) != 0) out /= 100.0
        return out
    }

    private fun numText(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) {
            v.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
        }

    // ----------------------------------------------------------------- BIFF8

    private class Biff8 {
        companion object {
            private const val BOF = 0x0809
            private const val EOF_REC = 0x000A
            private const val BOUNDSHEET = 0x0085
            private const val SST = 0x00FC
            private const val CONTINUE = 0x003C
            private const val LABELSST = 0x00FD
            private const val LABEL = 0x0204
            private const val RSTRING = 0x00D6
            private const val NUMBER = 0x0203
            private const val RK = 0x027E
            private const val MULRK = 0x00BD
            private const val FORMULA = 0x0006
            private const val STRING = 0x0207
            private const val MERGEDCELLS = 0x00E5

            fun parse(stream: ByteArray): XlsWorkbook {
                val sheets = ArrayList<XlsSheet>()
                var pos = 0
                var strings: List<String> = emptyList()
                var globals = true

                // ---- 第一遍：读 BOUNDSHEET 与 SST（都在工作簿全局区）
                val bounds = ArrayList<Triple<String, Int, Int>>()   // name, streamPos, order
                var firstSheetPos = -1
                while (pos + 4 <= stream.size) {
                    val type = u16(stream, pos)
                    val len = u16(stream, pos + 2)
                    val data = pos + 4
                    if (type == BOF) {
                        val dt = if (data + 4 <= stream.size) u16(stream, data + 2) else 0
                        if (dt == 0x0010 && firstSheetPos < 0) {
                            firstSheetPos = pos
                            globals = false
                        }
                    }
                    if (globals && type == BOUNDSHEET) {
                        val sheetPos = i32(stream, data)
                        val cch = stream[data + 6].toInt() and 0xFF
                        val flags = stream[data + 7].toInt() and 0xFF
                        val name = readSheetName(stream, data + 8, cch, (flags and 0x01) != 0)
                        bounds += Triple(name, sheetPos, bounds.size)
                    }
                    if (globals && type == SST) {
                        // SST 后面可能紧跟若干 CONTINUE，一起交给读取器
                        val chunks = ArrayList<Pair<Int, Int>>()   // (start, end) 在 stream 里的区间
                        chunks += (data to (data + len))
                        var p2 = data + len
                        while (p2 + 4 <= stream.size) {
                            val t2 = u16(stream, p2)
                            val l2 = u16(stream, p2 + 2)
                            if (t2 != CONTINUE) break
                            chunks += ((p2 + 4) to (p2 + 4 + l2))
                            p2 += 4 + l2
                        }
                        strings = SstChunks(stream, chunks).readAll()
                    }
                    pos = data + len
                    if (!globals && firstSheetPos >= 0) break
                }

                // ---- 第二遍：逐个工作表读单元格
                for ((name, sheetPos, _) in bounds) {
                    val sheet = readSheet(stream, sheetPos, name, strings)
                    if (sheet != null) sheets += sheet
                }
                if (sheets.isEmpty()) {
                    throw IllegalArgumentException("工作簿里没有可读的工作表")
                }
                return XlsWorkbook(sheets)
            }

            private fun readSheetName(b: ByteArray, off: Int, cch: Int, wide: Boolean): String =
                if (wide) {
                    String(b, off, cch * 2, Charsets.UTF_16LE)
                } else {
                    String(b, off, cch, Charsets.ISO_8859_1)
                }

            private fun readSheet(
                stream: ByteArray,
                start: Int,
                name: String,
                strings: List<String>,
            ): XlsSheet? {
                if (start < 0 || start + 4 > stream.size) return null
                val cells = HashMap<Int, String>()
                val merged = ArrayList<IntArray>()
                var pos = start
                var depth = 0
                var pendingStringCell: Int = -1

                while (pos + 4 <= stream.size) {
                    val type = u16(stream, pos)
                    val len = u16(stream, pos + 2)
                    val d = pos + 4
                    when (type) {
                        BOF -> depth++
                        EOF_REC -> {
                            depth--
                            if (depth <= 0) break
                        }

                        LABELSST -> {
                            val row = u16(stream, d)
                            val col = u16(stream, d + 2)
                            val idx = i32(stream, d + 6)
                            val s = strings.getOrNull(idx)
                            if (s != null && s.isNotEmpty()) {
                                cells[XlsSheet.key(row, col)] = s
                            }
                        }

                        LABEL, RSTRING -> {
                            val row = u16(stream, d)
                            val col = u16(stream, d + 2)
                            val cch = u16(stream, d + 6)
                            val flags = stream[d + 8].toInt() and 0xFF
                            val wide = (flags and 0x01) != 0
                            val s = if (wide) {
                                String(stream, d + 9, cch * 2, Charsets.UTF_16LE)
                            } else {
                                String(stream, d + 9, cch, Charsets.ISO_8859_1)
                            }
                            if (s.isNotEmpty()) cells[XlsSheet.key(row, col)] = s
                        }

                        NUMBER -> {
                            val row = u16(stream, d)
                            val col = u16(stream, d + 2)
                            cells[XlsSheet.key(row, col)] = numText(f64(stream, d + 6))
                        }

                        RK -> {
                            val row = u16(stream, d)
                            val col = u16(stream, d + 2)
                            cells[XlsSheet.key(row, col)] = numText(rk(i32(stream, d + 6)))
                        }

                        MULRK -> {
                            val row = u16(stream, d)
                            val colFirst = u16(stream, d + 2)
                            val colLast = u16(stream, d + len - 2)
                            var p = d + 4
                            var c = colFirst
                            while (c <= colLast && p + 6 <= d + len) {
                                cells[XlsSheet.key(row, c)] = numText(rk(i32(stream, p + 2)))
                                p += 6
                                c++
                            }
                        }

                        FORMULA -> {
                            val row = u16(stream, d)
                            val col = u16(stream, d + 2)
                            val b6 = stream[d + 6].toInt() and 0xFF
                            val b12 = stream[d + 12].toInt() and 0xFF
                            val b13 = stream[d + 13].toInt() and 0xFF
                            if (b6 == 0 && b12 == 0xFF && b13 == 0xFF) {
                                pendingStringCell = XlsSheet.key(row, col)    // 值在下一条 STRING 里
                            } else if (b6 == 0) {
                                cells[XlsSheet.key(row, col)] = numText(f64(stream, d + 6))
                            }
                        }

                        STRING -> {
                            if (pendingStringCell >= 0) {
                                val cch = u16(stream, d)
                                val flags = stream[d + 2].toInt() and 0xFF
                                val wide = (flags and 0x01) != 0
                                val s = if (wide) {
                                    String(stream, d + 3, cch * 2, Charsets.UTF_16LE)
                                } else {
                                    String(stream, d + 3, cch, Charsets.ISO_8859_1)
                                }
                                if (s.isNotEmpty()) cells[pendingStringCell] = s
                                pendingStringCell = -1
                            }
                        }

                        MERGEDCELLS -> {
                            val count = u16(stream, d)
                            var p = d + 2
                            for (i in 0 until count) {
                                if (p + 8 > d + len) break
                                merged += intArrayOf(
                                    u16(stream, p),
                                    u16(stream, p + 2),
                                    u16(stream, p + 4),
                                    u16(stream, p + 6),
                                )
                                p += 8
                            }
                        }
                    }
                    pos = d + len
                }
                return XlsSheet(name, cells, merged)
            }
        }
    }

    /**
     * SST（共享字符串表）读取：字符串可能被 CONTINUE 记录切成多段，
     * 每段开头会重新给一个"后面的字符是 8 位还是 16 位"的标志位。
     */
    private class SstChunks(
        private val b: ByteArray,
        private val chunks: List<Pair<Int, Int>>,
    ) {
        private var ci = 0
        private var p = chunks.firstOrNull()?.first ?: 0

        private fun atEnd(): Boolean = ci >= chunks.size

        private fun end(): Int = chunks.getOrElse(ci) { chunks.last() }.second

        private fun ensure() {
            while (!atEnd() && p >= end()) {
                ci++
                if (!atEnd()) p = chunks[ci].first
            }
        }

        private fun byte(): Int {
            ensure()
            if (atEnd()) return 0
            return b[p++].toInt() and 0xFF
        }

        private fun u16(): Int = byte() or (byte() shl 8)

        private fun u32(): Int = u16() or (u16() shl 16)

        private fun chars(count: Int, wide: Boolean): String {
            val sb = StringBuilder()
            var wideNow = wide
            var left = count
            while (left > 0) {
                ensure()
                if (atEnd()) break
                val chunkEnd = end()
                val bytesPer = if (wideNow) 2 else 1
                val canRead = ((chunkEnd - p) / bytesPer).coerceAtMost(left)
                if (canRead <= 0) {
                    // 本段用完了：跳到下一段，段首那个字节是新的宽度标志
                    ci++
                    if (atEnd()) break
                    p = chunks[ci].first
                    wideNow = (byte() and 0x01) != 0
                    continue
                }
                if (wideNow) {
                    for (i in 0 until canRead) {
                        val lo = b[p].toInt() and 0xFF
                        val hi = b[p + 1].toInt() and 0xFF
                        sb.append(((hi shl 8) or lo).toChar())
                        p += 2
                    }
                } else {
                    for (i in 0 until canRead) {
                        sb.append((b[p].toInt() and 0xFF).toChar())
                        p++
                    }
                }
                left -= canRead
            }
            return sb.toString()
        }

        fun readAll(): List<String> {
            if (chunks.isEmpty()) return emptyList()
            u32()   // 总条数
            val unique = u32()
            val out = ArrayList<String>(unique.coerceIn(0, 100_000))
            for (i in 0 until unique) {
                if (atEnd()) break
                ensure()
                if (atEnd()) break
                val cch = u16()
                val flags = byte()
                val wide = (flags and 0x01) != 0
                val runs = if ((flags and 0x08) != 0) u16() else 0
                val ext = if ((flags and 0x04) != 0) u32() else 0
                val s = chars(cch, wide)
                // 跳过富文本与扩展数据
                if (runs > 0) repeat(runs * 4) { byte() }
                if (ext > 0) repeat(ext) { byte() }
                out += s
            }
            return out
        }
    }
}
