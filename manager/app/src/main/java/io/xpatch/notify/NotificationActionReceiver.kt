package io.xpatch.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.xpatch.data.DownloadCenter
import io.xpatch.data.RestartCenter
import io.xpatch.data.UserDirectory

class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            AppNotifications.ACTION_RESTART_APP -> {
                val packageName = intent.getStringExtra(AppNotifications.EXTRA_PACKAGE).orEmpty()
                val userId = intent.getIntExtra(AppNotifications.EXTRA_USER_ID, -1)
                if (packageName.isNotBlank()) {
                    RestartCenter.restartNow(
                        app,
                        packageName,
                        if (userId >= 0) userId else UserDirectory.currentUserId(),
                    )
                }
            }

            AppNotifications.ACTION_IGNORE_RESTART -> {
                val packageName = intent.getStringExtra(AppNotifications.EXTRA_PACKAGE).orEmpty()
                val userId = intent.getIntExtra(AppNotifications.EXTRA_USER_ID, -1)
                if (packageName.isNotBlank()) {
                    RestartCenter.ignore(
                        app,
                        packageName,
                        if (userId >= 0) userId else UserDirectory.currentUserId(),
                    )
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
