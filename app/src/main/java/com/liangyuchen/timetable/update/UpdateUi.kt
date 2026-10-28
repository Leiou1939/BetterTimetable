package com.liangyuchen.timetable.update

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 更新流程的状态机。 */
private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Latest(val version: String) : UpdateState
    data class Found(val info: UpdateInfo) : UpdateState
    data class Downloading(val percent: Int, val bytes: Long, val total: Long) : UpdateState
    data class Ready(val info: UpdateInfo, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * 检查更新对话框：查询 GitHub Releases → 下载 APK → 调起系统安装。
 *
 * 仓库地址可在对话框里直接改（写进偏好），所以即使 APK 里没预置地址也能用。
 */
@Composable
fun UpdateDialog(context: Context, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var repo by remember { mutableStateOf(UpdateChecker.repo(context)) }
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    /** 记住了上一次查到的 Release 页面，下载失败时可以手动去下。 */
    var latestHtmlUrl by remember { mutableStateOf<String?>(null) }

    val startCheck: () -> Unit = remember(context, repo) {
        {
            UpdateSettings.saveRepo(context, repo)
            state = UpdateState.Checking
            scope.launch {
                state = try {
                    val info = withContext(Dispatchers.IO) {
                        UpdateChecker.checkForUpdate(context, repo)
                    }
                    if (info == null) {
                        UpdateState.Latest(UpdateChecker.localVersionName())
                    } else {
                        latestHtmlUrl = info.htmlUrl
                        UpdateState.Found(info)
                    }
                } catch (t: Throwable) {
                    UpdateState.Failed(t.message ?: t.javaClass.simpleName)
                }
            }
        }
    }

    fun startDownload(info: UpdateInfo) {
        state = UpdateState.Downloading(0, 0, info.apkSize)
        scope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    UpdateChecker.downloadApk(context, info) { done, total ->
                        // 回调发生在 IO 线程，这里只写状态由 Compose 观察
                        val pct = if (total > 0) ((done * 100) / total).toInt() else 0
                        state = UpdateState.Downloading(pct, done, total)
                    }
                }
                state = UpdateState.Ready(info, apk)
            } catch (t: Throwable) {
                state = UpdateState.Failed("下载失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("检查更新", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    "当前版本 ${UpdateChecker.localVersionName()}" +
                        "（${UpdateChecker.localVersionCode()}）",
                    fontSize = 10.sp,
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
                    value = repo,
                    onValueChange = { repo = it.trim() },
                    label = { Text("GitHub 仓库（用户名/仓库名）", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))

                when (val s = state) {
                    is UpdateState.Idle -> Text(
                        text = "点下面的按钮查询 GitHub 上发布的最新版本。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    is UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在查询…", fontSize = 12.sp)
                    }

                    is UpdateState.Latest -> Text(
                        text = "已是最新版本（${s.version}）。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    is UpdateState.Failed -> {
                        Text(
                            text = s.message,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "也可以到 GitHub 页面手动下载安装包：",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                val url = latestHtmlUrl ?: "https://github.com/${UpdateChecker.repo(context)}/releases"
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(url),
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("在浏览器中打开发布页", fontSize = 12.sp) }
                    }

                    is UpdateState.Found -> {
                        Text(
                            text = "${s.info.title}（${s.info.tag}）" +
                                if (s.info.apkSize > 0) {
                                    "  ${s.info.apkSize / 1024 / 1024} MB"
                                } else {
                                    ""
                                },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (s.info.publishedAt.isNotEmpty()) {
                            Text(
                                text = "发布于 " + s.info.publishedAt.replace("T", " ").take(16),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = s.info.notes.ifEmpty { "（该版本没有写更新说明）" },
                            fontSize = 11.sp,
                            maxLines = 12,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    is UpdateState.Downloading -> {
                        Text("正在下载… ${s.percent}%", fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        if (s.total > 0) {
                            LinearProgressIndicator(
                                progress = { s.percent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${s.bytes / 1024} KB / ${if (s.total > 0) "${s.total / 1024} KB" else "未知"}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is UpdateState.Ready -> {
                        Text(
                            text = "已下载 ${s.info.tag}，准备安装。",
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        if (!ApkInstaller.canInstall(context)) {
                            Text(
                                text = "需要先允许「课程表」安装未知来源应用，否则无法安装更新。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { ApkInstaller.openInstallPermission(context) },
                                ) { Text("去开启权限", fontSize = 12.sp) }
                                Button(onClick = { ApkInstaller.install(context, s.apk) }) {
                                    Text("重新安装", fontSize = 12.sp)
                                }
                            }
                        } else {
                            Button(
                                onClick = { ApkInstaller.install(context, s.apk) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("安装更新") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val s = state
            when (s) {
                is UpdateState.Found -> Button(onClick = { startDownload(s.info) }) {
                    Text("下载并安装")
                }

                is UpdateState.Ready -> Button(onClick = { ApkInstaller.install(context, s.apk) }) {
                    Text("安装")
                }

                is UpdateState.Checking, is UpdateState.Downloading -> Button(
                    onClick = {},
                    enabled = false,
                ) { Text("请稍候") }

                else -> Button(onClick = { startCheck() }) { Text("检查更新") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/**
 * 静默检查：App 启动时如果允许自动检查且有仓库地址，后台查一次；
 * 有新版本时通过回调把结果交给界面去提示。
 */
@Composable
fun AutoUpdateCheck(
    context: Context,
    onUpdateFound: (UpdateInfo) -> Unit,
) {
    LaunchedEffect(Unit) {
        if (!UpdateSettings.autoCheck(context)) return@LaunchedEffect
        val repo = UpdateChecker.repo(context)
        if (!repo.contains('/')) return@LaunchedEffect
        val info = runCatching {
            withContext(Dispatchers.IO) { UpdateChecker.checkForUpdate(context) }
        }.getOrNull() ?: return@LaunchedEffect
        onUpdateFound(info)
    }
}
