package com.liangyuchen.timetable.school

/**
 * 从位图里找表格线（纯逻辑，输入是灰度像素数组，方便在桌面 JVM 上验证）。
 *
 * 教务系统导出的"图片型课表"通常有完整的表格线，先按行/列投影找出横竖线，
 * 再按格子逐格 OCR —— 比整页 OCR 准得多（实测整页连"星期一"都识别不出来）。
 */
object TableGrid {

    data class Grid(
        val xs: IntArray,
        val ys: IntArray,
    ) {
        val cols: Int get() = (xs.size - 1).coerceAtLeast(0)
        val rows: Int get() = (ys.size - 1).coerceAtLeast(0)
    }

    /**
     * @param gray 灰度像素（0..255），长度 = w*h
     * @param threshold 低于它算"有墨"
     */
    fun detect(gray: IntArray, w: Int, h: Int, threshold: Int = 240): Grid? {
        if (w < 40 || h < 40) return null

        // ---- 1. 横线：这一行里"最长连续暗像素"接近整行宽度
        val hCandidates = ArrayList<Int>()
        for (y in 0 until h) {
            var run = 0
            var best = 0
            for (x in 0 until w) {
                if (gray[y * w + x] < threshold) {
                    run++
                    if (run > best) best = run
                } else {
                    run = 0
                }
            }
            if (best * 100 >= w * 55) hCandidates += y
        }
        val allY = group(hCandidates)
        if (allY.size < 3) return null

        // ---- 2. 只看"密集"的那一段横线（页面底部常有无关的装饰线）
        val ys = densestRun(allY)
        if (ys.size < 3) return null

        // ---- 3. 竖线：在这一段的纵向范围内找，同样看最长连续段
        val top = ys.first()
        val bottom = ys.last()
        val span = bottom - top
        if (span < 20) return null
        val vCandidates = ArrayList<Int>()
        for (x in 0 until w) {
            var run = 0
            var best = 0
            for (y in top..bottom) {
                if (gray[y * w + x] < threshold) {
                    run++
                    if (run > best) best = run
                } else {
                    run = 0
                }
            }
            if (best * 100 >= span * 45) vCandidates += x
        }
        val xs = group(vCandidates)
        if (xs.size < 3) return null

        // 竖线的有效高度按表格行数比例判定，避免把"整页竖线"当表格线
        return Grid(xs.toIntArray(), ys.toIntArray())
    }

    /** 取横线里间距最密的一段（表格本体）。 */
    private fun densestRun(lines: List<Int>): List<Int> {
        if (lines.size < 3) return lines
        val gaps = (1 until lines.size).map { lines[it] - lines[it - 1] }
        val sorted = gaps.sorted()
        val median = sorted[sorted.size / 2]
        val limit = (median * 4).coerceAtLeast(24)
        var bestStart = 0
        var bestEnd = 0
        var start = 0
        for (i in gaps.indices) {
            if (gaps[i] > limit) {
                if (i - start > bestEnd - bestStart) {
                    bestStart = start
                    bestEnd = i
                }
                start = i + 1
            }
        }
        if (gaps.size - start > bestEnd - bestStart) {
            bestStart = start
            bestEnd = gaps.size
        }
        return lines.subList(bestStart, bestEnd + 1)
    }

    /** 把相邻的候选行合并成一条线。 */
    private fun group(values: List<Int>, gap: Int = 3): List<Int> {
        if (values.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var start = values[0]
        var prev = values[0]
        var sum = 0
        var count = 0
        for (v in values) {
            if (v - prev > gap) {
                out += sum / count
                sum = 0
                count = 0
                start = v
            }
            sum += v
            count++
            prev = v
        }
        if (count > 0) out += sum / count
        return out
    }
}
