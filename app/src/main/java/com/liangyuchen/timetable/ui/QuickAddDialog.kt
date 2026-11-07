package com.liangyuchen.timetable.ui

import android.Manifest
import android.content.Context
import android.os.Build
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.liangyuchen.timetable.data.MeProfile
import com.liangyuchen.timetable.data.NearbyPayload
import com.liangyuchen.timetable.data.ScheduleData
import com.liangyuchen.timetable.nearby.NearbyReceiver
import com.liangyuchen.timetable.nearby.NearbySender
import java.util.UUID

/**
 * 快速添加：用蓝牙（BLE）和旁边的人换课表。
 *
 * 一方点「发送」（广播），另一方点「接收」（扫描 → 连接 → 读取），
 * 收到的课表直接存成一位同学。
 */
@Composable
fun QuickAddDialog(
    context: Context,
    myData: ScheduleData,
    myProfile: MeProfile,
    onDismiss: () -> Unit,
    onAdded: (Friend) -> Unit,
) {
    var mode by remember { mutableStateOf(Mode.CHOOSE) }
    var status by remember { mutableStateOf("") }
    var devices by remember { mutableStateOf(listOf<NearbyReceiver.DeviceItem>()) }
    var error by remember { mutableStateOf<String?>(null) }

    val sender = remember { NearbySender(context) }
    val receiver = remember { NearbyReceiver(context) }

    val store = remember { FriendStore(context) }

    fun save(payload: NearbyPayload) {
        val schedule = CourseRepository.parse(payload.scheduleJson)
        if (schedule == null) {
            error = "收到的课表读不出来"
            return
        }
        val friend = Friend(
            id = UUID.randomUUID().toString(),
            nickname = payload.name,
            schoolId = payload.schoolId,
            scheduleJson = payload.scheduleJson,
            colorIndex = payload.colorIndex,
        )
        store.add(friend)
        onAdded(friend)
    }

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    fun startSend() {
        mode = Mode.SEND
        status = "准备广播…"
        sender.start(
            NearbyPayload(
                name = myProfile.name,
                schoolId = myData.schoolId,
                colorIndex = myProfile.colorIndex,
                scheduleJson = CourseRepository.toJson(myData).toString(),
            ),
            object : NearbySender.Listener {
                override fun onStatus(text: String) { status = text }
                override fun onServed() { status = "已把课表发给对方 ✓" }
            },
        )
    }

    fun startReceive() {
        mode = Mode.RECEIVE
        devices = emptyList()
        status = "准备扫描…"
        receiver.scan(object : NearbyReceiver.Listener {
            override fun onStatus(text: String) { status = text }
            override fun onFound(device: NearbyReceiver.DeviceItem) {
                if (devices.none { it.address == device.address }) {
                    devices = devices + device
                }
            }
            override fun onPayload(payload: NearbyPayload) {
                status = "收到了 ${payload.name} 的课表"
                save(payload)
            }
        })
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            if (mode == Mode.SEND) startSend() else startReceive()
        } else {
            error = "需要蓝牙权限才能和附近的同学交换课表"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            sender.stop()
            receiver.stop()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("快速添加", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "两台手机都打开这个窗口，面对面：一台点「发送」，一台点「接收」。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                when (mode) {
                    Mode.CHOOSE -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { permissionLauncher.launch(permissions) },
                                modifier = Modifier.weight(1f),
                            ) { Text("发送我的课表", fontSize = 13.sp) }
                            OutlinedButton(
                                onClick = {
                                    mode = Mode.RECEIVE
                                    permissionLauncher.launch(permissions)
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("接收对方课表", fontSize = 13.sp) }
                        }
                    }

                    Mode.SEND -> {
                        StatusBox(status)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "广播中，保持窗口打开；对方收到后会自动停止。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Mode.RECEIVE -> {
                        StatusBox(status)
                        Spacer(Modifier.height(8.dp))
                        if (devices.isEmpty()) {
                            Text(
                                "还没有发现设备，让对方确认点的是「发送我的课表」。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            devices.forEach { device ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable { receiver.connect(device.address) }
                                        .padding(horizontal = 10.dp, vertical = 12.dp),
                                ) {
                                    Text(
                                        device.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "连接",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }

                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private enum class Mode { CHOOSE, SEND, RECEIVE }

@Composable
private fun StatusBox(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Text(text, fontSize = 12.sp)
    }
}
