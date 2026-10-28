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
import androidx.compose.material3.HorizontalDivider
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

/** 更新流程的状态机。 */
private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Latest(val version: String) : UpdateState
    data class Found(val info: UpdateInfo) : UpdateState
    /** 已交给前台服务在后台下载，进度看通知栏。 */
    data object InBackground : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * 「发现新版本」弹窗：写清版本号与更新内容，让用户选「立即更新」或「下次再说」。
 *
 * 启动时自动弹出，也可以从设置里手动打开。
 */
@Composable
fun UpdateAvailableDialog(
    info: UpdateInfo,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = {
            Column {
                Text("发现新版本", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "${info.title.ifBlank { info.tag }}（${info.tag}）" +
                        if (info.apkSize > 0) " · ${info.apkSize / 1024 / 1024} MB" else "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "当前版本 ${UpdateChecker.localVersionName()}" +
                        "（${UpdateChecker.localVersionCode()}）",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text("更新内容", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = info.notes.ifBlank { "本次没有填写更新说明。" },
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        },
        confirmButton = {
            Button(onClick = onUpdate) { Text("立即更新") }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text("下次再说") }
        },
    )
}

/**
 * 检查更新对话框：查 GitHub Releases → 下载 APK → 调起系统安装。
 *
 * - 打开即开始检查（仓库地址已在 APK 里写好，不需要用户输入）；
 * - 只有在**没有内置地址**时，才显示输入框让用户手填；
 * - [autoStartInfo] 不为空时（用户点了「立即更新」），直接开始下载。
 */
@Composable
fun UpdateDialog(
    context: Context,
    autoStartInfo: UpdateInfo? = null,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val bakedRepo = remember { UpdateChecker.repo(context) }
    var repo by remember { mutableStateOf(bakedRepo) }
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    /** 记住最近的 Release 页面，下载失败时可以手动去下。 */
    var latestHtmlUrl by remember { mutableStateOf(autoStartInfo?.htmlUrl) }
    var started by remember { mutableStateOf(false) }

    fun startDownload(info: UpdateInfo) {
        runCatching { UpdateDownloadService.start(context, info) }
            .onSuccess { state = UpdateState.InBackground }
            .onFailure {
                state = UpdateState.Failed(
                    "无法启动后台下载：${it.message ?: it.javaClass.simpleName}",
                )
            }
    }

    fun startCheck() {
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

    // 打开就查（或直接下载），不用再点一次按钮
    LaunchedEffect(Unit) {
        if (started) return@LaunchedEffect
        started = true
        if (autoStartInfo != null) startDownload(autoStartInfo) else startCheck()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("软件更新", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                // 只有没内置仓库地址时才让用户填
                if (bakedRepo.isBlank()) {
                    OutlinedTextField(
                        value = repo,
                        onValueChange = { repo = it.trim() },
                        label = { Text("GitHub 仓库（用户名/仓库名）", fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                } else {
                    Text(
                        text = "更新源：$bakedRepo",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    )
                }

                when (val s = state) {
                    is UpdateState.Idle, is UpdateState.Checking ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在检查更新…", fontSize = 12.sp)
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
                        OutlinedButton(
                            onClick = {
                                val url = latestHtmlUrl
                                    ?: "https://github.com/$bakedRepo/releases"
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
                            text = "${s.info.title.ifBlank { s.info.tag }}（${s.info.tag}）" +
                                if (s.info.apkSize > 0) "  ${s.info.apkSize / 1024 / 1024} MB" else "",
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
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "GitHub 在国内下载可能较慢，建议点「下载并安装」后转到后台，看通知栏进度。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("更新内容", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = s.info.notes.ifBlank { "本次没有填写更新说明。" },
                            fontSize = 11.sp,
                            lineHeight = 17.sp,
                            maxLines = 16,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    is UpdateState.InBackground -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        Text("已转入后台下载", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "进度看通知栏；可以退出 App，下载完点通知即可安装。",
                            fontSize = 12.sp,
                        )
                        if (!ApkInstaller.canInstall(context)) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "手机还没允许「课程表」安装应用，装之前要先打开这个开关。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                            OutlinedButton(
                                onClick = { ApkInstaller.openInstallPermission(context) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("去开启安装权限") }
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

                is UpdateState.Checking -> Button(
                    onClick = {},
                    enabled = false,
                ) { Text("请稍候") }

                is UpdateState.InBackground -> Button(onClick = onDismiss) { Text("知道了") }

                is UpdateState.Failed -> Button(onClick = { startCheck() }) { Text("重试") }

                else -> Button(onClick = { startCheck() }) { Text("检查更新") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/**
 * 启动时的静默检查：有新版本就把结果交给界面弹窗。
 * 「下次再说」过的版本不会再提示（除非出了更新的版本）。
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
        if (info.versionCode <= UpdateSettings.skippedVersionCode(context)) return@LaunchedEffect
        onUpdateFound(info)
    }
}
