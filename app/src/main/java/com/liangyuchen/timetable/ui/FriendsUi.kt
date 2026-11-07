package com.liangyuchen.timetable.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liangyuchen.timetable.data.CourseRepository
import com.liangyuchen.timetable.data.FreeStatus
import com.liangyuchen.timetable.data.Friend
import com.liangyuchen.timetable.data.FriendStore
import com.liangyuchen.timetable.data.ScheduleData
import com.liangyuchen.timetable.data.statusOf
import com.liangyuchen.timetable.pdf.PdfImport
import com.liangyuchen.timetable.school.School
import com.liangyuchen.timetable.school.SchoolImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * 同学：加一个昵称 + 他/她的课表，一眼看出谁在上课、谁有空。
 *
 * 课表和昵称只存在本机，不上传任何服务器。
 */
@Composable
fun FriendsDialog(
    context: Context,
    myName: String,
    myData: ScheduleData,
    today: LocalDate,
    onDismiss: () -> Unit,
) {
    val store = remember { FriendStore(context) }
    var friends by remember { mutableStateOf(store.load()) }
    var adding by remember { mutableStateOf(false) }
    var nickname by remember { mutableStateOf("") }
    var school by remember { mutableStateOf(School.entries.first()) }
    var schoolMenu by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 每 30 秒刷新一次，状态跟着时间走
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
    val now = remember(tick) { LocalTime.now() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { SchoolImport.parse(context, uri, school) }
            }
            importing = false
            result.onSuccess { parsed ->
                if (parsed.courses.isEmpty()) {
                    error = parsed.warnings.firstOrNull() ?: "这份文件里没有解析出课程"
                } else {
                    val schedule = PdfImport.toScheduleData(parsed, myData, school.id)
                    val json = CourseRepository.toJson(schedule).toString()
                    store.add(
                        Friend(
                            id = UUID.randomUUID().toString(),
                            nickname = nickname.trim().ifEmpty { "同学" },
                            schoolId = school.id,
                            scheduleJson = json,
                        ),
                    )
                    friends = store.load()
                    adding = false
                    nickname = ""
                }
            }.onFailure {
                error = "读不了这个文件：${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("同学", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                // 现在几点：给"上课中 / 空闲"一个参照
                Text(
                    "%02d:%02d".format(now.hour, now.minute),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionLabel("我")
                Spacer(Modifier.height(6.dp))
                PersonRow(
                    name = myName,
                    school = null,
                    status = statusOf(myData, today, now),
                    highlight = true,
                )

                if (friends.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    SectionLabel("同学")
                    Spacer(Modifier.height(6.dp))
                    friends.forEach { friend ->
                        PersonRow(
                            name = friend.nickname,
                            school = friend.school.displayName,
                            status = friend.data?.let { statusOf(it, today, now) },
                            onDelete = {
                                store.remove(friend.id)
                                friends = store.load()
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }

                if (adding) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    Spacer(Modifier.height(12.dp))

                    Text(
                        "同学昵称",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it.take(12) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "学校",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clickable { schoolMenu = true }
                                .padding(horizontal = 12.dp),
                        ) {
                            Text(school.displayName, fontSize = 14.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                school.sourceKind.label,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.weight(1f))
                            Icon(
                                Icons.Filled.ArrowDropDown,
                                contentDescription = "选择学校",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(
                            expanded = schoolMenu,
                            onDismissRequest = { schoolMenu = false },
                        ) {
                            School.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(s.displayName, fontSize = 14.sp)
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                s.sourceKind.label,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    onClick = {
                                        school = s
                                        schoolMenu = false
                                    },
                                    trailingIcon = {
                                        if (s == school) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = "已选择",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { picker.launch(school.sourceKind.mimeTypes) },
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (importing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (importing) "正在读取…" else "选择他/她的课表文件", fontSize = 13.sp)
                    }
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            if (adding) {
                TextButton(onClick = {
                    adding = false
                    error = null
                }) { Text("取消添加") }
            } else {
                Button(onClick = {
                    adding = true
                    error = null
                }) { Text("添加同学") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 一个人一行：左边头像，中间名字 / 状态说明，右边状态标签。
 * 在上课的那一行用课程色，扫一眼就能分出谁在上课。
 */
@Composable
private fun PersonRow(
    name: String,
    school: String?,
    status: FreeStatus?,
    highlight: Boolean = false,
    onDelete: (() -> Unit)? = null,
) {
    val accent = status?.let { colorFor(it.accentKey) }
        ?: MaterialTheme.colorScheme.onSurfaceVariant
    val inClass = status?.inClass == true
    val chipBg = if (inClass) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant
    val chipFg = if (inClass) accent else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (highlight) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    Color.Transparent
                },
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        // 头像：昵称首字
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(
                    if (inClass) {
                        accent.copy(alpha = 0.18f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.take(1),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (inClass) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (school != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        school,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val detail = status?.detail.orEmpty()
            if (detail.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    detail,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(8.dp))
        Text(
            text = status?.label ?: "—",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = chipFg,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(chipBg)
                .padding(horizontal = 9.dp, vertical = 4.dp),
        )
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Text(
                    "✕",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
