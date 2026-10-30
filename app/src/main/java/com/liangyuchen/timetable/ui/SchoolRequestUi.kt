package com.liangyuchen.timetable.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liangyuchen.timetable.update.Feedback
import com.liangyuchen.timetable.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「没有我的学校？」：说明情况 + 收集学校名称与课表文件。
 *
 * 文件不会被 App 上传（GitHub API 也不支持给 issue 加附件），
 * 所以流程是：填学校名 → 打开 GitHub 新建 Issue → 在浏览器里把文件拖进去。
 */
@Composable
fun SchoolRequestDialog(
    context: Context,
    repo: String,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var fileUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var doneUrl by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            fileUri = uri
            fileName = queryName(context, uri)
            error = null
        }
    }

    fun buildBody(): String = buildString {
        appendLine("### 申请的学校")
        appendLine(name.trim())
        appendLine()
        appendLine("### 课表文件")
        if (fileName.isNotBlank()) {
            appendLine("文件名：`$fileName`")
            appendLine("（在浏览器里把文件拖到这个 Issue 的输入框即可上传）")
        } else {
            appendLine("未选择文件（可以在这里直接拖入教务系统导出的课表）")
        }
        appendLine()
        appendLine("### 说明")
        appendLine("希望把上面这所学校加入 App 的导入列表。")
    }

    fun openBrowser() {
        val title = "[新增学校] ${name.trim()}"
        val uri = Feedback.issueUri(repo, title, buildBody())
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            onDismiss()
        }.onFailure { error = "打不开浏览器：${it.message}" }
    }

    fun submit() {
        if (name.trim().length < 2) {
            error = "请填写学校名称"
            return
        }
        // 选了文件就必须走浏览器（GitHub 只允许在网页里加附件）
        if (fileUri != null || !Feedback.canSubmitDirectly()) {
            openBrowser()
            return
        }
        sending = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Feedback.submitDirectly(repo, "[新增学校] ${name.trim()}", buildBody())
                }
            }
            sending = false
            result.onSuccess { doneUrl = it }.onFailure {
                error = "自动提交失败（${it.message ?: "未知原因"}），改用浏览器"
                openBrowser()
            }
        }
    }

    doneUrl?.let { url ->
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("已提交", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("已经创建 Issue，谢谢！我们会尽快适配这所学校。", fontSize = 13.sp)
                    if (url.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(url, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = { Button(onClick = onDismiss) { Text("好") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("没有你的学校？", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    "各校教务系统导出的格式差别很大，部分学校还没做适配",
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
                Text(
                    text = "由于课表材料收集、版式差异等原因，目前只支持列表里的几所学校。" +
                        "把你的学校名称和一份课表文件发给我们，适配好之后会加到列表里。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(30)
                        error = null
                    },
                    label = { Text("学校名称", fontSize = 12.sp) },
                    placeholder = { Text("例如：大连理工大学", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Text("选择课表文件", fontSize = 12.sp)
                    }
                    if (fileName.isNotBlank()) {
                        Text(
                            text = fileName,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .weight(1f, fill = false),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "⚠️ 文件上传请注意保护个人隐私（学号、姓名等可先涂掉）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = { submit() }, enabled = !sending) {
                Text(
                    when {
                        sending -> "提交中…"
                        fileUri != null || !Feedback.canSubmitDirectly() -> "去 GitHub 提交"
                        else -> "提交"
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 取文件的显示名（不带路径）。 */
private fun queryName(context: Context, uri: Uri): String {
    val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: "课表文件"
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) ?: fallback else fallback
        } ?: fallback
    }.getOrDefault(fallback)
}
