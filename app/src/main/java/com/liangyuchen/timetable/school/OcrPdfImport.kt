package com.liangyuchen.timetable.school

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.liangyuchen.timetable.pdf.ParsedTimetable
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 截图型 PDF 的兜底：把每页渲染成位图，交给离线中文 OCR，
 * 再把识别出的文字按网格还原成课程（[OcrGrid]）。
 *
 * 只在「PDF 里没有文字层」时才会走到这里。
 */
object OcrPdfImport {

    /** 渲染宽度（越大越准，也越慢；1800 在识别率和内存之间比较平衡）。 */
    private const val TARGET_WIDTH = 1800f

    /** 最多识别几页（课表一般 1-2 页）。 */
    private const val MAX_PAGES = 2

    suspend fun parse(context: Context, bytes: ByteArray): ParsedTimetable {
        val file = File(context.cacheDir, "ocr-import.pdf")
        return try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                val renderer = PdfRenderer(pfd)
                try {
                    val lines = ArrayList<OcrLine>()
                    val tables = ArrayList<Map<Pair<Int, Int>, String>>()
                    val pages = minOf(renderer.pageCount, MAX_PAGES)
                    for (i in 0 until pages) {
                        val page = renderer.openPage(i)
                        try {
                                val scale = (TARGET_WIDTH / page.width).coerceIn(1f, 4f)
                            val w = (page.width * scale).toInt().coerceAtLeast(1)
                            val h = (page.height * scale).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            // 关键：PdfRenderer 默认按页面原始像素画在左上角，
                            // 必须给缩放矩阵，否则位图大片留白、OCR 什么都认不出。
                            val matrix = Matrix().apply { setScale(scale, scale) }
                            page.render(
                                bmp,
                                null,
                                matrix,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                            )
                            if (looksBlank(bmp)) {
                                throw IllegalStateException(
                                    "PDF 渲染出来是空白页（第 ${i + 1} 页），无法识别",
                                )
                            }
                            // 先按表格线切格子，逐格识别（密集课表这样准得多）
                            val table = readTable(bmp)
                            if (table != null) {
                                tables += table
                            } else {
                                lines += recognize(bmp)
                            }
                            bmp.recycle()
                        } finally {
                            page.close()
                        }
                    }
                    // 优先用"逐格识别"的结果；没有表格线时退回整页 OCR 的网格还原
                    val parsed = tables.firstOrNull()?.let { cells ->
                        OcrTable.build(cells, tableRowsHolder, tableColsHolder)
                    } ?: OcrGrid.parse(lines)
                    if (parsed.courses.isEmpty()) {
                        val sample = lines.take(12).joinToString(" / ") { it.text }
                        return ParsedTimetable(
                            emptyList(),
                            warnings = parsed.warnings + listOf(
                                "识别到 ${lines.size} 段文字，但没能组成课表。前几段是：$sample",
                            ),
                        )
                    }
                    val note = "这段课表是从图片里识别出来的，请对着原文件核对一遍再导入。"
                    parsed.copy(
                        warnings = parsed.warnings + note,
                        schoolLabel = "OCR 识别（截图版 PDF）",
                    )
                } finally {
                    renderer.close()
                }
            }
        } catch (t: Throwable) {
            ParsedTimetable(
                emptyList(),
                warnings = listOf(
                    "图片识别失败：${t.message ?: t.javaClass.simpleName}。" +
                        "请在教务系统里导出 Excel 或网页版课表再试。",
                ),
            )
        } finally {
            runCatching { file.delete() }
        }
    }

    /**
     * 检测表格线并按格子逐格 OCR。一张图最多识别 260 个格子（避免太慢）。
     * @return 单元格文字（key = 行-列）；没有表格线时返回 null。
     */
    private suspend fun readTable(bitmap: Bitmap): Map<Pair<Int, Int>, String>? {
        // 直接在原分辨率上找线（缩图会把浅灰色的表格线糊掉）
        val dw = bitmap.width
        val dh = bitmap.height
        val pixels = IntArray(dw * dh)
        bitmap.getPixels(pixels, 0, dw, 0, 0, dw, dh)
        val gray = IntArray(pixels.size) { i ->
            val c = pixels[i]
            (((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3)
        }
        val grid = TableGrid.detect(gray, dw, dh, 244) ?: return null

        val xs = grid.xs.toList()
        val ys = grid.ys.toList()
        if (xs.size < 3 || ys.size < 3) return null

        val cells = HashMap<Pair<Int, Int>, String>()
        var count = 0
        for (r in 0 until ys.size - 1) {
            for (c in 0 until xs.size - 1) {
                if (count >= 260) break
                val left = (xs[c] + 2).coerceIn(0, bitmap.width - 1)
                val top = (ys[r] + 2).coerceIn(0, bitmap.height - 1)
                val right = (xs[c + 1] - 2).coerceIn(left + 1, bitmap.width)
                val bottom = (ys[r + 1] - 2).coerceIn(top + 1, bitmap.height)
                val w = right - left
                val h = bottom - top
                if (w < 24 || h < 14) continue
                val crop = Bitmap.createBitmap(bitmap, left, top, w, h)
                // 放大一点，小字更容易识别
                val scaled = Bitmap.createScaledBitmap(crop, w * 2, h * 2, true)
                crop.recycle()
                val text = recognize(scaled).joinToString("\n") { it.text }
                scaled.recycle()
                count++
                if (text.isNotBlank()) cells[r to c] = text
            }
        }
        tableRowsHolder = ys.size - 1
        tableColsHolder = xs.size - 1
        return if (cells.isEmpty()) null else cells
    }

    /** 最近一次检测到的表格规模（给 OcrTable 用）。 */
    private var tableRowsHolder = 0
    private var tableColsHolder = 0

    /** 渲染结果是否几乎全白（说明渲染失败，继续 OCR 只会得到空白）。 */
    private fun looksBlank(bitmap: Bitmap): Boolean {
        var dark = 0
        var sampled = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val gray = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
                if (gray < 200) dark++
                sampled++
                x += 37
            }
            y += 37
        }
        return sampled == 0 || dark * 200 < sampled
    }

    private suspend fun recognize(bitmap: Bitmap): List<OcrLine> =
        suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build(),
            )
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val out = ArrayList<OcrLine>()
                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val box = line.boundingBox ?: continue
                            val t = line.text.trim()
                            if (t.isEmpty()) continue
                            out += OcrLine(
                                text = t,
                                left = box.left.toFloat(),
                                top = box.top.toFloat(),
                                right = box.right.toFloat(),
                                bottom = box.bottom.toFloat(),
                            )
                        }
                    }
                    recognizer.close()
                    cont.resume(out)
                }
                .addOnFailureListener { e ->
                    recognizer.close()
                    cont.resumeWithException(e)
                }
        }
}
