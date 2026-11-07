package com.liangyuchen.timetable.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import com.liangyuchen.timetable.data.AVATAR_PALETTE
import com.liangyuchen.timetable.data.CourseRepository
import com.liangyuchen.timetable.data.FreeStatus
import com.liangyuchen.timetable.data.Friend
import com.liangyuchen.timetable.data.FriendStore
import com.liangyuchen.timetable.data.MeProfile
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

/** 头像色（index → Color）。 */
private fun avatarColor(index: Int): Color =
    Color(AVATAR_PALETTE[index.coerceIn(AVATAR_PALETTE.indices)])

/**
 * 同学：加一个昵称 + 他/她的课表，一眼看出谁在上课、谁有空。
 *
 * 长按任意一行可以改名字、头像色；同学的还能换学校/换课表、删除。
 * 添加同学走单独的窗口。
 */
@Composable
fun FriendsDialog(
    context: Context,
    myData: ScheduleData,
    today: LocalDate,
    onDismiss: () -> Unit,
) {
    val store = remember { FriendStore(context) }
    var friends by remember { mutableStateOf(store.load()) }
    var me by remember { mutableStateOf(store.loadMe()) }
    var editingFriend by remember { mutableStateOf<Friend?>(null) }
    var editingMe by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
    val now = remember(tick) { LocalTime.now() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("同学", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
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
                    name = me.name,
                    colorIndex = me.colorIndex,
                    school = null,
                    status = statusOf(myData, today, now),
                    highlight = true,
                    onLongPress = { editingMe = true },
                )

                if (friends.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    SectionLabel("同学")
                    Spacer(Modifier.height(6.dp))
                    friends.forEach { friend ->
                        PersonRow(
                            name = friend.nickname,
                            colorIndex = friend.colorIndex,
                            school = friend.school.displayName,
                            status = friend.data?.let { statusOf(it, today, now) },
                            onLongPress = { editingFriend = friend },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { adding = true }) { Text("添加同学") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )

    if (adding) {
        AddFriendDialog(
            context = context,
            myData = myData,
            onDismiss = { adding = false },
            onAdded = {
                friends = store.load()
                adding = false
            },
        )
    }

    if (editingMe) {
        MeEditorDialog(
            me = me,
            onDismiss = { editingMe = false },
            onSave = { updated ->
                me = updated
                store.saveMe(updated)
                editingMe = false
            },
        )
    }

    editingFriend?.let { friend ->
        FriendEditorDialog(
            context = context,
            friend = friend,
            myData = myData,
            onDismiss = { editingFriend = null },
            onSave = { updated ->
                store.update(updated)
                friends = store.load()
                editingFriend = null
            },
            onDelete = {
                store.remove(friend.id)
                friends = store.load()
                editingFriend = null
            },
        )
    }
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
 *
 * 颜色只用**头像色**，不跟着课程色变 —— 否则同一个人每节课都在变色。
 * 长按进入编辑。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonRow(
    name: String,
    colorIndex: Int,
    school: String?,
    status: FreeStatus?,
    highlight: Boolean = false,
    onLongPress: () -> Unit,
) {
    val mine = avatarColor(colorIndex)
    val inClass = status?.inClass == true

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
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Avatar(name = name, bg = mine.copy(alpha = 0.16f), fg = mine)
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
            color = mine,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(mine.copy(alpha = if (inClass) 0.16f else 0.08f))
                .padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Avatar(name: String, bg: Color, fg: Color, size: Int = 34) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1).ifEmpty { "?" },
            fontSize = (size / 2.4f).sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
        )
    }
}

/** 头像配色选择：一排色块，选中的打勾。 */
@Composable
private fun ColorPicker(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AVATAR_PALETTE.indices.forEach { index ->
            val color = avatarColor(index)
            val isSelected = index == selected
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(color)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** 学校选择：两列卡片，选中的描边 + 打勾。 */
@Composable
private fun SchoolPicker(current: School, onSelect: (School) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        School.entries.chunked(2).forEach { rowSchools ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowSchools.forEach { s ->
                    val selected = s == current
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                },
                            )
                            .then(
                                if (selected) {
                                    Modifier.border(
                                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                        MaterialTheme.shapes.small,
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .clickable { onSelect(s) }
                            .padding(horizontal = 8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                s.displayName,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                s.sourceKind.label,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "已选择",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                if (rowSchools.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LabeledField(label: String, content: @Composable () -> Unit) {
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
    content()
}

/** 添加同学：独立窗口。 */
@Composable
private fun AddFriendDialog(
    context: Context,
    myData: ScheduleData,
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
) {
    val store = remember { FriendStore(context) }
    var nickname by remember { mutableStateOf("") }
    var school by remember { mutableStateOf(School.entries.first()) }
    var colorIndex by remember { mutableStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
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
                    store.add(
                        Friend(
                            id = UUID.randomUUID().toString(),
                            nickname = nickname.trim().ifEmpty { "同学" },
                            schoolId = school.id,
                            scheduleJson = CourseRepository.toJson(schedule).toString(),
                            colorIndex = colorIndex,
                        ),
                    )
                    onAdded()
                }
            }.onFailure {
                error = "读不了这个文件：${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加同学", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(
                        name = nickname.ifBlank { "同" },
                        bg = avatarColor(colorIndex).copy(alpha = 0.16f),
                        fg = avatarColor(colorIndex),
                        size = 44,
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it.take(12) },
                        singleLine = true,
                        label = { Text("昵称", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                    )
                }
                LabeledField("头像色") { ColorPicker(colorIndex) { colorIndex = it } }
                LabeledField("学校 / 课表格式") { SchoolPicker(school) { school = it } }

                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = { picker.launch(school.sourceKind.mimeTypes) },
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (importing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (importing) "正在读取…" else "选择他/她的课表文件", fontSize = 13.sp)
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 编辑「我」：名字 + 头像色。 */
@Composable
private fun MeEditorDialog(
    me: MeProfile,
    onDismiss: () -> Unit,
    onSave: (MeProfile) -> Unit,
) {
    var name by remember { mutableStateOf(me.name) }
    var colorIndex by remember { mutableStateOf(me.colorIndex) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑我", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(
                        name = name.ifBlank { "我" },
                        bg = avatarColor(colorIndex).copy(alpha = 0.16f),
                        fg = avatarColor(colorIndex),
                        size = 44,
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(8) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                LabeledField("头像色") {
                    ColorPicker(colorIndex) { colorIndex = it }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(MeProfile(name.trim().ifEmpty { "我" }, colorIndex))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 编辑同学：昵称、头像色、学校、换课表、删除。 */
@Composable
private fun FriendEditorDialog(
    context: Context,
    friend: Friend,
    myData: ScheduleData,
    onDismiss: () -> Unit,
    onSave: (Friend) -> Unit,
    onDelete: () -> Unit,
) {
    var nickname by remember { mutableStateOf(friend.nickname) }
    var colorIndex by remember { mutableStateOf(friend.colorIndex) }
    var school by remember { mutableStateOf(friend.school) }
    var json by remember { mutableStateOf(friend.scheduleJson) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
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
                    json = CourseRepository.toJson(schedule).toString()
                }
            }.onFailure {
                error = "读不了这个文件：${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑同学", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(
                        name = nickname.ifBlank { "同" },
                        bg = avatarColor(colorIndex).copy(alpha = 0.16f),
                        fg = avatarColor(colorIndex),
                        size = 44,
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it.take(12) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                LabeledField("头像色") { ColorPicker(colorIndex) { colorIndex = it } }
                LabeledField("学校 / 课表格式") { SchoolPicker(school) { school = it } }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { picker.launch(school.sourceKind.mimeTypes) },
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (importing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (importing) "正在读取…" else "更换他/她的课表", fontSize = 13.sp)
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Text("删除这位同学", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    friend.copy(
                        nickname = nickname.trim().ifEmpty { "同学" },
                        colorIndex = colorIndex,
                        schoolId = school.id,
                        scheduleJson = json,
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
