package com.liangyuchen.timetable.school

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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

    /** 渲染宽度（越大越准，也越慢）。 */
    private const val TARGET_WIDTH = 2200f

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
                    val pages = minOf(renderer.pageCount, MAX_PAGES)
                    for (i in 0 until pages) {
                        val page = renderer.openPage(i)
                        try {
                            val scale = (TARGET_WIDTH / page.width).coerceIn(1f, 4f)
                            val w = (page.width * scale).toInt().coerceAtLeast(1)
                            val h = (page.height * scale).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            lines += recognize(bmp)
                            bmp.recycle()
                        } finally {
                            page.close()
                        }
                    }
                    val parsed = OcrGrid.parse(lines)
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
