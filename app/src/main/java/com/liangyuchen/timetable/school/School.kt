package com.liangyuchen.timetable.school

import android.content.Context
import android.net.Uri
import com.liangyuchen.timetable.pdf.ParsedTimetable
import com.liangyuchen.timetable.pdf.PdfImport

/** 导入时支持的文件类型。 */
enum class SourceKind(val mimeTypes: Array<String>, val label: String) {
    PDF(
        arrayOf("application/pdf", "application/octet-stream", "*/*"),
        "PDF",
    ),
    EXCEL(
        arrayOf(
            "application/vnd.ms-excel",
            "application/excel",
            "application/x-msexcel",
            "application/octet-stream",
            "*/*",
        ),
        "Excel",
    ),
}

/**
 * 支持的学校。每所学校有自己的读表方案（文件类型 + 版式 + 作息来源）。
 */
enum class School(
    val id: String,
    val displayName: String,
    val sourceKind: SourceKind,
    val note: String,
) {
    SYNU(
        id = "synu",
        displayName = "沈阳师范大学",
        sourceKind = SourceKind.PDF,
        note = "教务 PDF 课表；节次时间不含在文件里，用 App 内置作息（可在设置里改）。",
    ),
    SAU(
        id = "sau",
        displayName = "沈阳航空航天大学",
        sourceKind = SourceKind.PDF,
        note = "教务 PDF 课表，版式与沈阳师范大学相同，用同一套解析。",
    ),
    DBUFE(
        id = "dbufe",
        displayName = "东北财经大学",
        sourceKind = SourceKind.PDF,
        note = "教务 PDF 课表；需要文字版 PDF（截图/扫描的读不出来）。",
    ),
    SIE(
        id = "sie",
        displayName = "沈阳工程学院",
        sourceKind = SourceKind.EXCEL,
        note = "教务导出的 .xls 课表；每行的「第 X 大节 08:20-10:00」会直接同步成作息时间。",
    ),
    ;

    companion object {
        fun fromId(id: String?): School = entries.firstOrNull { it.id == id } ?: SYNU
        val default: School get() = SYNU
    }
}

/** 按学校分派到对应的解析方案。 */
object SchoolImport {

    fun parse(context: Context, uri: Uri, school: School): ParsedTimetable = when (school) {
        // 沈师大与沈航的教务 PDF 版式一致，共用 PDF 解析
        School.SYNU, School.SAU, School.DBUFE -> PdfImport.parse(context, uri)
        School.SIE -> parseExcel(context, uri)
    }

    private fun parseExcel(context: Context, uri: Uri): ParsedTimetable {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
            ?: return ParsedTimetable(emptyList(), warnings = listOf("无法读取所选文件"))
        if (bytes.isEmpty()) {
            return ParsedTimetable(emptyList(), warnings = listOf("所选文件是空的"))
        }
        return try {
            SieImport.parse(bytes)
        } catch (t: Throwable) {
            ParsedTimetable(
                emptyList(),
                warnings = listOf(
                    "解析 .xls 失败：${t.message ?: t.javaClass.simpleName}。" +
                        "请确认是教务系统导出的、未加密的 Excel 97-2003 文件（.xls）；" +
                        "如果是 .xlsx，请先用 Excel/WPS 另存为 .xls。",
                ),
            )
        }
    }
}
