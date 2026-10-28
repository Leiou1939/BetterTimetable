package com.liangyuchen.timetable.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** 更新相关的偏好：GitHub 仓库地址、是否自动检查、被跳过的版本。 */
object UpdateSettings {

    private const val PREFS = "timetable_update"
    private const val KEY_REPO = "repo"
    private const val KEY_AUTO_CHECK = "auto_check"
    private const val KEY_SKIPPED = "skipped_version_code"

    fun repo(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REPO, "") ?: ""

    fun saveRepo(context: Context, repo: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REPO, repo.trim())
            .apply()
    }

    fun autoCheck(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CHECK, true)

    fun setAutoCheck(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_CHECK, enabled)
            .apply()
    }

    /** 用户点过「下次再说」的版本号：这个版本不再打扰，更新的版本仍会提示。 */
    fun skippedVersionCode(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SKIPPED, 0)

    fun markSkipped(context: Context, versionCode: Int) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (versionCode > p.getInt(KEY_SKIPPED, 0)) {
            p.edit().putInt(KEY_SKIPPED, versionCode).apply()
        }
    }
}

/** 安装下载好的 APK；必要时先引导用户打开「安装未知应用」。 */
object ApkInstaller {

    /** 当前 App 是否被允许安装未知来源应用（Android 8+）。 */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** 跳转到「安装未知应用」开关页。 */
    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }

    /** 调起系统安装器。 */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // 部分 ROM 需要显式给安装器授权
                clipData = ClipData.newRawUri("apk", uri)
            }
        }
        context.startActivity(intent)
    }
}
