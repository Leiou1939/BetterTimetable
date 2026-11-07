package com.liangyuchen.timetable.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liangyuchen.timetable.data.CourseRepository
import com.liangyuchen.timetable.data.Friend
import com.liangyuchen.timetable.data.FriendStore
import com.liangyuchen.timetable.data.MeProfile
import com.liangyuchen.timetable.data.NearbyPayload
import com.liangyuchen.timetable.data.ScheduleData
import com.liangyuchen.timetable.nearby.NearbyExchange
import java.util.UUID

/**
 * 快速添加：两台手机都点「开始交换」，就会互相把课表发过去。
 *
 * 一边广播自己的、一边去取对方的，所以不需要区分发送方 / 接收方。
 */
@Composable
fun QuickAddDialog(
    context: Context,
    myData: ScheduleData,
    myProfile: MeProfile,
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
) {
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("两台手机都点「开始交换」，保持这个窗口打开") }
    var peers by remember { mutableStateOf(listOf<String>()) }
    var received by remember { mutableStateOf(listOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }

    val exchange = remember { NearbyExchange(context) }
    val store = remember { FriendStore(context) }

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

    fun start() {
        error = null
        running = true
        peers = emptyList()
        received = emptyList()
        exchange.start(
            NearbyPayload(
                name = myProfile.name,
                schoolId = myData.schoolId,
                colorIndex = myProfile.colorIndex,
                scheduleJson = CourseRepository.toJson(myData).toString(),
            ),
            object : NearbyExchange.Listener {
                override fun onStatus(text: String) {
                    status = text
                }

                override fun onPeer(name: String, connected: Boolean) {
                    if (peers.none { it == name }) peers = peers + name
                }

                override fun onPayload(payload: NearbyPayload) {
                    if (CourseRepository.parse(payload.scheduleJson) == null) {
                        error = "收到的课表读不出来"
                        return
                    }
                    store.add(
                        Friend(
                            id = UUID.randomUUID().toString(),
                            nickname = payload.name,
                            schoolId = payload.schoolId,
                            scheduleJson = payload.scheduleJson,
                            colorIndex = payload.colorIndex,
                        ),
                    )
                    received = received + payload.name
                    status = "已和 ${payload.name} 交换课表"
                    onAdded()
                }
            },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            start()
        } else {
            running = false
            error = "需要蓝牙权限才能和附近的同学交换课表"
        }
    }

    DisposableEffect(Unit) {
        onDispose { exchange.stop() }
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
                    "两台手机都打开这个窗口、都点「开始交换」，面对面等几秒，互相就能拿到课表。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                if (!running) {
                    Button(
                        onClick = { permissionLauncher.launch(permissions) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("开始交换", fontSize = 14.sp) }
                } else {
                    OutlinedButton(
                        onClick = {
                            exchange.stop()
                            running = false
                            status = "已停止"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("停止", fontSize = 14.sp) }
                }

                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                ) {
                    Text(status, fontSize = 12.sp)
                }

                if (peers.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "附近的同学",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    peers.forEach { name ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                        ) {
                            Text(name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(
                                if (name in received) "已交换" else "已发现",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
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
