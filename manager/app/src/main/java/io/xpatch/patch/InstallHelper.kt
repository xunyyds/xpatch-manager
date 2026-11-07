package io.xpatch.patch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

object InstallHelper {

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun signatureMatches(context: Context, packageName: String, certDer: ByteArray? = null): Boolean? {
        val installed = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }.getOrNull() ?: return null

        val installedSignatures = installed.signatures ?: return null
        if (installedSignatures.isEmpty()) return null

        val ours = certDer ?: return null

        val oursDigest = MessageDigest.getInstance("SHA-256").digest(ours)
        return installedSignatures.any { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .contentEquals(oursDigest)
        }
    }

    fun installWithSystem(context: Context, apk: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "io.xpatch.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK,
            )
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun openWith(context: Context, apk: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "io.xpatch.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK,
            )
        return runCatching {
            context.startActivity(
                Intent.createChooser(intent, "选择打开方式")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }

    fun shareApk(context: Context, apk: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "io.xpatch.files", apk)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(APK_MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK,
            )
        return runCatching {
            context.startActivity(
                Intent.createChooser(intent, "分享安装包")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }

    /**
     * 调用系统卸载界面（带结果回传），卸载后由调用方决定是否继续安装。
     * 数据会随卸载一并删除，调用前必须由用户二次确认。
     */
    fun uninstallIntent(packageName: String): Intent =
        Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$packageName"))
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)

    fun openDocument(context: Context, uri: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(android.net.Uri.parse(uri), APK_MIME)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK,
            )
        return runCatching {
            context.startActivity(
                Intent.createChooser(intent, "打开方式").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }

    fun hasInstaller(context: Context): Boolean {
        val probe = Intent(Intent.ACTION_VIEW)
            .setDataAndType(android.net.Uri.parse("file:///dummy.apk"), APK_MIME)
        return resolveInstaller(context, probe) != null
    }

    private fun resolveInstaller(context: Context, intent: Intent): ComponentName? {
        val candidates = context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val match = candidates.firstOrNull { activity ->
            activity.activityInfo.packageName.contains("packageinstaller", true) ||
                activity.activityInfo.name.contains("PackageInstaller", true) ||
                activity.activityInfo.packageName.contains("installer", true)
        } ?: candidates.firstOrNull()
        val info = match?.activityInfo ?: return null
        return ComponentName(info.packageName, info.name)
    }

    private const val APK_MIME = "application/vnd.android.package-archive"
}
