package io.xpatch.patch

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File

class PatchInstaller(private val context: Context) {

    fun install(apk: File, packageName: String): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("xpatch", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallReceiver::class.java)
                .setAction(InstallReceiver.ACTION_RESULT)
                .putExtra(InstallReceiver.EXTRA_PACKAGE, packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
        return sessionId
    }
}

class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        val result = InstallResults.consume(intent)
        InstallResults.lastMessage = buildString {
            append(if (result) "安装成功" else "安装失败")
            if (!packageName.isNullOrBlank()) append("：").append(packageName)
            if (!message.isNullOrBlank() && !result) append("（").append(message).append("）")
        }
    }

    companion object {
        const val ACTION_RESULT = "io.xpatch.action.INSTALL_RESULT"
        const val EXTRA_PACKAGE = "io.xpatch.extra.PACKAGE"
    }
}

object InstallResults {

    @Volatile
    var lastMessage: String? = null

    fun consume(intent: Intent): Boolean {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        return status == PackageInstaller.STATUS_SUCCESS
    }
}
