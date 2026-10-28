package com.liangyuchen.timetable.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liangyuchen.timetable.pdf.ParsedCourse
import com.liangyuchen.timetable.pdf.ParsedTimetable

/** 解析中提示。 */
@Composable
fun ImportProgressDialog() {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text("正在解析课表 PDF…", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("首次解析包含全部课程与周次，请稍候。", fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
        },
    )
}

/** 解析失败提示。 */
@Composable
fun ImportErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = onDismiss) { Text("知道了") } },
        title = { Text("导入失败", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = { Text(message, fontSize = 13.sp) },
    )
}

/**
 * 导入预览：把解析出来的课程列出来让用户确认，再写进课表。
 * 这是「课表随 PDF 变化」的确认环节——不同学校的课表格式差别很大，
 * 让用户先看一眼比直接覆盖更安全。
 */
@Composable
fun ImportPreviewDialog(
    parsed: ParsedTimetable,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val weekday = "一二三四五六日"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("解析到 ${parsed.courses.size} 门课", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = buildString {
                        append("${parsed.sourcePages} 页")
                        if (parsed.studentName.isNotEmpty()) append(" · ${parsed.studentName}")
                        if (parsed.term.isNotEmpty()) append(" · ${parsed.term}")
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                parsed.warnings.forEach { w ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                    ) {
                        Row(modifier = Modifier.padding(8.dp)) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(w, fontSize = 11.sp)
                        }
                    }
                }

                parsed.courses
                    .sortedWith(compareBy({ it.day }, { it.periodFrom }, { it.name }))
                    .forEach { c -> CoursePreviewRow(c, weekday) }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("导入这张课表") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun CoursePreviewRow(course: ParsedCourse, weekday: String) {
    val accent = colorFor(course.name)
    Surface(
        color = accent.copy(alpha = 0.10f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = "周${weekday.getOrElse(course.day) { '?' }}\n" +
                    "${course.periodFrom}-${course.periodTo}",
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                modifier = Modifier.width(38.dp),
            )
            Column(modifier = Modifier.padding(start = 4.dp)) {
                Text(
                    text = course.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        course.weekSpec.takeIf { it.isNotEmpty() },
                        course.room.takeIf { it.isNotEmpty() },
                        course.teacher.takeIf { it.isNotEmpty() },
                    ).joinToString(" · "),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
