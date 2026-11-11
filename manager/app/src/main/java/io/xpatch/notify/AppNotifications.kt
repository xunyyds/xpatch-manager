package io.xpatch.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import io.xpatch.MainActivity
import java.io.File

object AppNotifications {

    const val CHANNEL_RESTART = "xp_restart"
    const val CHANNEL_DOWNLOAD = "xp_download"
    const val CHANNEL_MODULE = "xp_module_v2"

    const val ACTION_RESTART_APP = "io.xpatch.action.RESTART_APP"
    const val ACTION_IGNORE_RESTART = "io.xpatch.action.IGNORE_RESTART"
    const val ACTION_DOWNLOAD_PAUSE = "io.xpatch.action.DOWNLOAD_PAUSE"
    const val ACTION_DOWNLOAD_RESUME = "io.xpatch.action.DOWNLOAD_RESUME"
    const val ACTION_DOWNLOAD_CANCEL = "io.xpatch.action.DOWNLOAD_CANCEL"

    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_PACKAGE = "package_name"
    const val EXTRA_LABEL = "label"
    const val EXTRA_REASON = "reason"

    object NotificationIds {
        const val RESTART_BASE = 2001
        const val RESTART_SPAN = 800
        const val MODULE_CRASH_BASE = 2901
        const val MODULE_CRASH_SPAN = 800
        const val DOWNLOAD_BASE = 4000
        const val DOWNLOAD_SPAN = 1000

        fun of(base: Int, span: Int, key: String): Int =
            base + (key.hashCode() and 0x7FFFFFFF) % span
    }

    const val ID_RESTART = NotificationIds.RESTART_BASE
    const val ID_MODULE_CRASH = NotificationIds.MODULE_CRASH_BASE

    @Volatile
    private var channelsReady = false

    fun ensureChannels(context: Context) {
        if (channelsReady) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            channelsReady = true
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager == null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESTART, "重启提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "模块作用域或 API 变更后提醒重启目标应用"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOAD, "下载", NotificationManager.IMPORTANCE_LOW).apply {
                description = "模块与版本更新的下载进度，可在通知内暂停或取消"
                setShowBadge(false)
            },
        )
        runCatching { manager.deleteNotificationChannel("xp_module") }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MODULE, "模块保护", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "模块导致应用频繁闪退时，自动移除并通知"
            },
        )
        channelsReady = true
    }

    fun enabled(context: Context): Boolean =
        runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(false)

    fun restart(context: Context, packageName: String, label: String, reason: String): Notification {
        val restartIntent = actionIntent(context, ACTION_RESTART_APP, packageName, label, reason)
        val ignoreIntent = actionIntent(context, ACTION_IGNORE_RESTART, packageName, label, reason)
        return NotificationCompat.Builder(context, CHANNEL_RESTART)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("需要重启 $label")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$reason\n重启后生效；也可以先忽略，之后在应用详情页手动重启。"))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "重启", restartIntent)
            .addAction(0, "忽略", ignoreIntent)
            .build()
    }

    fun download(
        context: Context,
        taskId: Long,
        title: String,
        text: String,
        percent: Int,
        paused: Boolean,
        indeterminate: Boolean,
    ): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(!paused)
            .setAutoCancel(false)
            .setContentIntent(openAppIntent(context))
            .setProgress(100, percent.coerceIn(0, 100), indeterminate)
        if (paused) {
            builder.addAction(0, "继续", taskAction(context, ACTION_DOWNLOAD_RESUME, taskId))
        } else {
            builder.addAction(0, "暂停", taskAction(context, ACTION_DOWNLOAD_PAUSE, taskId))
        }
        builder.addAction(0, "取消", taskAction(context, ACTION_DOWNLOAD_CANCEL, taskId))
        return builder.build()
    }

    fun installReady(context: Context, taskId: Long, title: String, apk: File): Notification {
        val uri = FileProvider.getUriForFile(context, "io.xpatch.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context,
            taskId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText("下载完成，点击安装")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
    }

    fun moduleCrash(
        context: Context,
        moduleLabel: String,
        appLabel: String,
        scopeWritten: Boolean = true,
    ): Notification {
        val text = if (scopeWritten) {
            "已自动移除模块「$moduleLabel」"
        } else {
            "未能写入作用域文件，请到作用域页手动移除「$moduleLabel」"
        }
        val detail = if (scopeWritten) {
            "发现模块「$moduleLabel」导致「$appLabel」频繁闪退，已自动将其从该应用的作用域移除，本次启动已跳过。" +
                "如需恢复，可在模块作用域页重新勾选。"
        } else {
            "发现模块「$moduleLabel」导致「$appLabel」频繁闪退，已把它从管理器的作用域设置中移除，" +
                "但作用域文件写入失败（通常是没给「所有文件访问」权限），下次启动仍可能加载该模块。" +
                "请到模块作用域页手动确认，或授权后重试。"
        }
        return NotificationCompat.Builder(context, CHANNEL_MODULE)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("发现模块导致「$appLabel」频繁闪退")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openAppIntent(context))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun moduleCrashId(appPackage: String): Int =
        NotificationIds.of(NotificationIds.MODULE_CRASH_BASE, NotificationIds.MODULE_CRASH_SPAN, appPackage)

    fun post(context: Context, id: Int, notification: Notification) {
        ensureChannels(context)
        if (!enabled(context)) return
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun cancel(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    fun downloadId(taskId: Long): Int =
        NotificationIds.DOWNLOAD_BASE + (taskId % NotificationIds.DOWNLOAD_SPAN).toInt()

    fun restartId(packageName: String): Int =
        NotificationIds.of(NotificationIds.RESTART_BASE, NotificationIds.RESTART_SPAN, packageName)

    private fun taskAction(context: Context, action: String, taskId: Long): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            setAction(action)
            putExtra(EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            (action.hashCode() * 31 + taskId.toInt()),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(
        context: Context,
        action: String,
        packageName: String,
        label: String,
        reason: String,
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            setAction(action)
            putExtra(EXTRA_PACKAGE, packageName)
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_REASON, reason)
        }
        return PendingIntent.getBroadcast(
            context,
            (action.hashCode() * 31 + packageName.hashCode()),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
