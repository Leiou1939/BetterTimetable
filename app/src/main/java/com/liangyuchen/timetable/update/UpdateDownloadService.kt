package com.liangyuchen.timetable.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.liangyuchen.timetable.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * 后台下载更新：前台服务 + 通知栏进度。
 *
 * 放到服务里做的原因：GitHub 下载可能要好几分钟，用户切到别的应用时
 * Activity 里的协程会被系统干掉；前台服务能保证下载继续，并通过通知栏
 * 显示进度，下载完成后点通知直接进安装流程。
 */
class UpdateDownloadService : Service() {

    private var scope: CoroutineScope? = null


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tag = intent?.getStringExtra(EXTRA_TAG) ?: return stopNow()
        // 已经在下载了：直接忽略这次请求，避免下出多个包
        if (running) return START_NOT_STICKY
        running = true
        val info = UpdateInfo(
            tag = tag,
            versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, 0),
            title = intent.getStringExtra(EXTRA_TITLE) ?: tag,
            notes = "",
            apkUrl = intent.getStringExtra(EXTRA_URL) ?: "",
            apkAssetId = intent.getLongExtra(EXTRA_ASSET_ID, 0L),
            apkSize = intent.getLongExtra(EXTRA_SIZE, 0L),
            publishedAt = "",
            htmlUrl = intent.getStringExtra(EXTRA_HTML) ?: "",
        )

        startForegroundCompat(progressNotification(0, 0, 0))

        scope?.cancel()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            var lastTime = 0L
            var lastBytes = 0L
            try {
                val apk = UpdateChecker.downloadApk(this@UpdateDownloadService, info) { done, total ->
                    val pct = if (total > 0) ((done * 100) / total).toInt() else 0
                    val now = System.currentTimeMillis()
                    if (now - lastTime > 800) {
                        val speed = if (lastTime > 0 && now > lastTime) {
                            ((done - lastBytes) * 1000 / (now - lastTime)).coerceAtLeast(0)
                        } else {
                            0L
                        }
                        lastTime = now
                        lastBytes = done
                        notify(progressNotification(pct, done, total, speed))
                    } else {
                        notify(progressNotification(pct, done, total))
                    }
                }
                onCompleted(apk, info)
            } catch (t: Throwable) {
                onFailed(t.message ?: t.javaClass.simpleName)
            } finally {
                running = false
                s.cancel()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        running = false
        super.onDestroy()
    }

    // ------------------------------------------------------------ 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "应用更新下载",
            NotificationManager.IMPORTANCE_LOW,   // 低优先级：不打扰，但有进度条
        ).apply {
            description = "下载新版本 APK 时显示进度"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun progressNotification(
        percent: Int,
        done: Long,
        total: Long,
        speed: Long = 0L,
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = buildString {
            if (total > 0) {
                append("${percent}%  ")
                append(String.format(java.util.Locale.US, "%.1f", done / 1048576.0))
                append(" MB / ")
                append(total / 1048576)
                append(" MB")
            } else {
                append(done / 1024).append(" KB")
            }
            if (speed > 0) {
                append(" · ")
                append(
                    if (speed > 1024 * 1024) "${speed / 1024 / 1024} MB/s"
                    else "${speed / 1024} KB/s",
                )
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载课程表更新")
            .setContentText(text)
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun onCompleted(apk: File, info: UpdateInfo) {
        val install = installPendingIntent(apk)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("课程表 ${info.tag} 下载完成")
            .setContentText("点这里安装更新")
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(install)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notify(n)
        stopForegroundCompat(keepNotification = true)
        stopSelf()
    }

    private fun onFailed(message: String) {
        val openApp = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("更新下载失败")
            .setContentText(message)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(openApp)
            .build()
        notify(n)
        stopForegroundCompat(keepNotification = true)
        stopSelf()
    }

    private fun installPendingIntent(apk: File): PendingIntent {
        val uri: Uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return PendingIntent.getActivity(
            this,
            3,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notify(n: Notification) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, n)
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun stopForegroundCompat(keepNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (keepNotification) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(keepNotification)
        }
    }

    private fun stopNow(): Int {
        running = false
        stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        /** 已经有一个下载在跑（防止连点/重进界面又下一遍）。 */
        @Volatile
        private var running = false

        private const val CHANNEL_ID = "update_download"
        private const val NOTIF_ID = 0x51ED
        private const val EXTRA_TAG = "tag"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_URL = "url"
        private const val EXTRA_ASSET_ID = "asset_id"
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_VERSION_CODE = "version_code"
        private const val EXTRA_HTML = "html"

        /** 是否已有下载在跑。 */
        fun isRunning(): Boolean = running

        /** 在后台开始下载（界面只负责调用这个）；已在下载时直接忽略。 */
        fun start(context: Context, info: UpdateInfo) {
            if (running) return
            val intent = Intent(context, UpdateDownloadService::class.java).apply {
                putExtra(EXTRA_TAG, info.tag)
                putExtra(EXTRA_TITLE, info.title)
                putExtra(EXTRA_URL, info.apkUrl)
                putExtra(EXTRA_ASSET_ID, info.apkAssetId)
                putExtra(EXTRA_SIZE, info.apkSize)
                putExtra(EXTRA_VERSION_CODE, info.versionCode)
                putExtra(EXTRA_HTML, info.htmlUrl)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
