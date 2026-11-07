package io.xpatch.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionCenter {

    enum class Kind { RUNTIME, SETTINGS_PAGE, SHIZUKU, DECLARED }

    enum class Id { STORAGE, ALL_FILES, INSTALL, APP_LIST, SHIZUKU }

    data class Item(
        val id: Id,
        val title: String,
        val reason: String,
        val granted: Boolean,
        val kind: Kind,
    )

    fun storageRuntimeNeeded(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    fun appListVisible(context: Context): Boolean = runCatching {
        context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        ).isNotEmpty()
    }.getOrDefault(true)

    fun required(
        context: Context,
        shizukuAvailable: Boolean = false,
        shizukuGranted: Boolean = false,
    ): List<Item> = buildList {
        if (storageRuntimeNeeded()) {
            add(
                Item(
                    id = Id.STORAGE,
                    title = "存储权限",
                    reason = "读取目标应用安装包、作用域文件与运行日志",
                    granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                    ) == PackageManager.PERMISSION_GRANTED,
                    kind = Kind.RUNTIME,
                ),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(
                Item(
                    id = Id.ALL_FILES,
                    title = "所有文件访问",
                    reason = "把模块作用域写入目标应用目录，并读取运行日志",
                    granted = Environment.isExternalStorageManager(),
                    kind = Kind.SETTINGS_PAGE,
                ),
            )
        }
        add(
            Item(
                id = Id.INSTALL,
                title = "安装未知应用",
                reason = "允许 XPatch 直接安装打包产物",
                granted = context.packageManager.canRequestPackageInstalls(),
                kind = Kind.SETTINGS_PAGE,
            ),
        )
        add(
            Item(
                id = Id.APP_LIST,
                title = "应用列表访问",
                reason = "读取已安装应用用于作用域配置；列表为空时请在该应用的权限管理中放开",
                granted = appListVisible(context),
                kind = Kind.DECLARED,
            ),
        )
        add(
            Item(
                id = Id.SHIZUKU,
                title = "Shizuku",
                reason = if (shizukuAvailable) {
                    "静默安装、启动与停止应用、执行 Dexopt；未授权时自动回退系统安装器"
                } else {
                    "未检测到 Shizuku；可跳过，功能会自动回退系统安装器"
                },
                granted = shizukuAvailable && shizukuGranted,
                kind = Kind.SHIZUKU,
            ),
        )
    }

    fun storageRuntimePermissions(): Array<String> = when {
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )

        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun openAppDetails(context: Context): Boolean {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + context.packageName),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun openAllFiles(context: Context): Boolean {
        val exact = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:" + context.packageName),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(exact) }.isSuccess) return true
        val generic = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(generic) }.isSuccess
    }

    fun openInstall(context: Context): Boolean {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
