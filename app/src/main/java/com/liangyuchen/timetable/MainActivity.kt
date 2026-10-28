package com.liangyuchen.timetable

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.liangyuchen.timetable.ui.PendingCalendarAction
import com.liangyuchen.timetable.ui.TimetableScreen
import com.liangyuchen.timetable.ui.theme.TimetableTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TimetableTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val context = LocalContext.current

                    // 权限被授予后要继续的动作（同步 / 导出）
                    val pending = remember { mutableStateOf<PendingCalendarAction?>(null) }

                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions(),
                    ) { grants ->
                        val granted = grants[Manifest.permission.WRITE_CALENDAR] == true &&
                            grants[Manifest.permission.READ_CALENDAR] == true
                        if (!granted) pending.value = null
                    }

                    val notificationLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { /* 通知权限被拒也不影响写入日历 */ }

                    LaunchedEffect(Unit) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

                    TimetableScreen(
                        viewModel = viewModel,
                        context = context,
                        pendingCalendarAction = pending.value,
                        onCalendarPermissionNeeded = { action ->
                            pending.value = action
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.WRITE_CALENDAR,
                                    Manifest.permission.READ_CALENDAR,
                                ),
                            )
                        },
                        onCalendarActionFinished = { pending.value = null },
                    )
                }
            }
        }
    }
}
