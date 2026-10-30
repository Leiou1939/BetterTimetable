package com.liangyuchen.timetable.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.liangyuchen.timetable.update.Feedback
import com.liangyuchen.timetable.update.UpdateChecker
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 问题反馈对话框：校验 → 跳到 GitHub 新建 Issue（预填内容）。
 */
@Composable
fun FeedbackDialog(
    context: Context,
    repo: String,
    schoolName: String,
    onDismiss: () -> Unit,
) {
    var description by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val body = Feedback.buildBody(
        description = description,
        contact = contact,
        appVersion = UpdateChecker.localVersionName(),
        versionCode = UpdateChecker.localVersionCode(),
        school = schoolName,
    )

    fun submit() {
        val problem = Feedback.validate(context, description, contact.trim())
        error = problem
        if (problem != null) return
        runCatching {
            val uri = Feedback.issueUri(repo, Feedback.buildTitle(description), body)
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Feedback.remember(context, description)
            onDismiss()
        }.onFailure {
            error = "打不开浏览器：${it.message ?: it.javaClass.simpleName}"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("反馈问题", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    "会在浏览器里打开 GitHub 的新建 Issue 页面，内容已填好，确认后提交",
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
                OutlinedTextField(
                    value = description,
                    onValueChange = {
                        description = it.take(Feedback.MAX_LENGTH)
                        error = null
                    },
                    label = { Text("遇到的问题", fontSize = 12.sp) },
                    placeholder = { Text("比如：导入沈阳工程学院课表后，周二第 3 节显示成周一", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 8,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = contact,
                    onValueChange = { contact = it.take(60) },
                    label = { Text("联系方式（可选）", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "会自动附带：App 版本 ${UpdateChecker.localVersionName()}" +
                        "（${UpdateChecker.localVersionCode()}）· Android ${android.os.Build.VERSION.RELEASE}" +
                        " · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = { submit() }) { Text("去 GitHub 提交") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("feedback", body))
                }) { Text("复制内容") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
