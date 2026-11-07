package com.liangyuchen.timetable.ui

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import com.liangyuchen.timetable.pdf.PdfImport
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liangyuchen.timetable.data.CourseRepository
import com.liangyuchen.timetable.data.Friend
import com.liangyuchen.timetable.data.FriendStore
import com.liangyuchen.timetable.data.ScheduleData
import com.liangyuchen.timetable.data.statusOf
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
 * 同学：加一个昵称 + 他/她的课表，然后一眼看出谁在上课、谁有空。
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
    var schoolExpanded by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 每 30 秒刷新一次状态
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
                    // 复用导入流程里的转换：作息时间取文件自带的，开学日期沿用我的
                    val schedule = PdfImport.toScheduleData(parsed, myData, school.id)
                    val json = CourseRepository.toJson(schedule).toString()
                    val friend = Friend(
                        id = UUID.randomUUID().toString(),
                        nickname = nickname.trim().ifEmpty { "同学" },
                        schoolId = school.id,
                        scheduleJson = json,
                    )
                    store.add(friend)
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
            Column {
                Text("同学", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    "看看谁在上课、谁有空。课表只存在本机",
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
                // 自己
                PersonRow(
                    name = myName,
                    school = null,
                    status = statusOf(myData, today, now),
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

                if (friends.isNotEmpty()) {
                    friends.forEach { friend ->
                        val data = friend.data
                        Spacer(Modifier.height(10.dp))
                        SwipeToDelete(
                            onDelete = {
                                store.remove(friend.id)
                                friends = store.load()
                            },
                        ) {
                            PersonRow(
                                name = friend.nickname,
                                school = friend.school.displayName,
                                status = data?.let { statusOf(it, today, now) },
                            )
                        }
                    }
                }

                if (adding) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    Spacer(Modifier.height(10.dp))
                    Text("同学昵称", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it.take(12) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    // 学校：折叠式，点一行展开列表
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { schoolExpanded = !schoolExpanded }
                            .padding(vertical = 8.dp),
                    ) {
                        Text("他/她的学校", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text(
                            school.displayName,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Icon(
                            imageVector = if (schoolExpanded) {
                                Icons.Filled.KeyboardArrowUp
                            } else {
                                Icons.Filled.KeyboardArrowDown
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    if (schoolExpanded) {
                        Column {
                            School.entries.forEach { s ->
                                val selected = s == school
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.small)
                                        .background(
                                            if (selected) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                Color.Transparent
                                            },
                                        )
                                        .clickable {
                                            school = s
                                            schoolExpanded = false
                                        }
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        s.displayName,
                                        fontSize = 12.sp,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        s.sourceKind.label,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
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
private fun PersonRow(
    name: String,
    school: String?,
    status: com.liangyuchen.timetable.data.FreeStatus?,
) {
    val accent = status?.let { colorFor(it.accentKey) }
        ?: MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(34.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(accent),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    fontSize = 13.sp,
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
                    )
                }
            }
            val detail = status?.detail.orEmpty()
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
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
            color = if (status?.inClass == true) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(
                    if (status?.inClass == true) {
                        accent.copy(alpha = 0.14f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
