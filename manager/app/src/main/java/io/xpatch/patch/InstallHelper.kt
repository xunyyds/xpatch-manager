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

    /** 是否已获得「安装未知应用」权限。 */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** 跳转到本应用的「安装未知应用」授权页。 */
    fun openInstallPermission(context: Context) {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** 已安装应用的签名是否与内置证书一致；未安装返回 null。 */
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

    /** 调起系统安装器安装，会弹出安装确认界面。 */
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

    /**
     * 调起系统「打开方式」选择器，交给用户选择用什么应用处理产物 APK。
     * 与分享面板一致：列出所有能处理该 APK 的应用，而不是直接进安装器。
     */
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

    /**
     * 以系统分享面板分享产物 APK，等价于「分享文件」。
     */
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

    /** 用"打开方式"选择器处理 SAF 文档。 */
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
