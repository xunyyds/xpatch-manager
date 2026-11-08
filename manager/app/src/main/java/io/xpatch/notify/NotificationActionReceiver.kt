package io.xpatch.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.xpatch.data.DownloadCenter
import io.xpatch.data.RestartCenter

class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            AppNotifications.ACTION_RESTART_APP -> {
                val packageName = intent.getStringExtra(AppNotifications.EXTRA_PACKAGE).orEmpty()
                if (packageName.isNotBlank()) {
                    RestartCenter.restartNow(app, packageName)
                }
            }

            AppNotifications.ACTION_IGNORE_RESTART -> {
                val packageName = intent.getStringExtra(AppNotifications.EXTRA_PACKAGE).orEmpty()
                if (packageName.isNotBlank()) {
                    RestartCenter.ignore(app, packageName)
                } else {
                    RestartCenter.ignoreAll(app)
                }
            }

            AppNotifications.ACTION_DOWNLOAD_PAUSE ->
                DownloadCenter.pause(intent.getLongExtra(AppNotifications.EXTRA_TASK_ID, -1L))

            AppNotifications.ACTION_DOWNLOAD_RESUME ->
                DownloadCenter.resume(intent.getLongExtra(AppNotifications.EXTRA_TASK_ID, -1L))

            AppNotifications.ACTION_DOWNLOAD_CANCEL ->
                DownloadCenter.cancel(intent.getLongExtra(AppNotifications.EXTRA_TASK_ID, -1L))
        }
    }
}
