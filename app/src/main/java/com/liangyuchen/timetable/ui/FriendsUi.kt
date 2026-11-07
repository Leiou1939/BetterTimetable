package com.liangyuchen.timetable.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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

    // 每 30 秒刷新一次，状态跟着时间走
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

                if (adding) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    Spacer(Modifier.height(12.dp))
                    AddFriendForm(
                        context = context,
                        myData = myData,
                        onAdded = {
                            friends = store.load()
                            adding = false
                        },
                        onCancel = { adding = false },
                    )
                }
            }
        },
        confirmButton = {
            if (adding) {
                TextButton(onClick = { adding = false }) { Text("取消添加") }
            } else {
                Button(onClick = { adding = true }) { Text("添加同学") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )

    // 我的编辑窗
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

    // 同学的编辑窗
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
 * 长按进入编辑；上课中时头像和标签用课程色。
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun PersonRow(
    name: String,
    colorIndex: Int,
    school: String?,
    status: FreeStatus?,
    highlight: Boolean = false,
    onLongPress: () -> Unit,
) {
    val inClass = status?.inClass == true
    val courseColor = status?.let { colorFor(it.accentKey) }
    val meColor = avatarColor(colorIndex)
    val avatarBg = if (inClass && courseColor != null) {
        courseColor.copy(alpha = 0.18f)
    } else {
        meColor.copy(alpha = 0.16f)
    }
    val avatarFg = if (inClass && courseColor != null) courseColor else meColor
    val chipBg = if (inClass && courseColor != null) {
        courseColor.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val chipFg = if (inClass && courseColor != null) {
        courseColor
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

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
        Avatar(name = name, bg = avatarBg, fg = avatarFg)
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

/** 头像配色选择：一排小圆点。 */
@Composable
private fun ColorPicker(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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

@Composable
private fun SchoolDropdown(school: School, onSelect: (School) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .clickable { expanded = true }
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
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
                        onSelect(s)
                        expanded = false
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
}

/** 添加同学：昵称 + 学校 + 选文件。 */
@Composable
private fun AddFriendForm(
    context: Context,
    myData: ScheduleData,
    onAdded: () -> Unit,
    onCancel: () -> Unit,
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

    LabeledField("同学昵称") {
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it.take(12) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    LabeledField("头像") { ColorPicker(colorIndex) { colorIndex = it } }
    LabeledField("学校") { SchoolDropdown(school) { school = it } }

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
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text("取消添加", fontSize = 12.sp)
    }
}

@Composable
private fun LabeledField(label: String, content: @Composable () -> Unit) {
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
    )
    content()
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
                LabeledField("学校") { SchoolDropdown(school) { school = it } }
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
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
                ) {
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
