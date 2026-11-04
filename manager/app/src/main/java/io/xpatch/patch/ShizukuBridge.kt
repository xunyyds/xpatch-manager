package io.xpatch.patch

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Shizuku 能力封装：授权、强行停止、重启。
 *
 * 借助 Shizuku 的 shell 权限拿到 system_server 的 activity 服务绑定，
 * 调用 forceStopPackage 完成免 Root 的强制停止。
 */
object ShizukuBridge {

    private const val REQUEST_CODE = 1001

    fun isAvailable(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.getVersion() >= 11
    }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        if (!Shizuku.pingBinder()) return false
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (!isAvailable()) {
            onResult(false)
            return
        }
        if (hasPermission()) {
            onResult(true)
            return
        }

        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                runCatching { Shizuku.removeRequestPermissionResultListener(this) }
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure {
                runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
                onResult(false)
            }
    }

    /** 强行停止指定应用（需要 Shizuku 已授权）。 */
    fun forceStop(packageName: String): Boolean = runCatching {
        if (!hasPermission()) return false

        val binder = SystemServiceHelper.getSystemService("activity")
        val wrapped: IBinder = ShizukuBinderWrapper(binder)
        val stub = Class.forName("android.app.IActivityManager\$Stub")
        val manager = stub.getMethod("asInterface", IBinder::class.java).invoke(null, wrapped)
            ?: return false

        val userId = runCatching {
            Class.forName("android.os.UserHandle")
                .getMethod("getUserId", Int::class.javaPrimitiveType)
                .invoke(null, android.os.Process.myUid()) as Int
        }.getOrDefault(0)

        manager.javaClass
            .getMethod("forceStopPackage", String::class.java, Int::class.javaPrimitiveType)
            .invoke(manager, packageName, userId)
        true
    }.getOrDefault(false)

    /** 强制停止后重新拉起。 */
    fun restart(context: Context, packageName: String): Boolean {
        val stopped = forceStop(packageName)
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            runCatching { context.startActivity(intent) }
        }
        return stopped
    }

    /** 直接拉起应用主界面。 */
    fun launch(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** 打开系统应用详情页。 */
    fun openAppDetails(context: Context, packageName: String) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** 通过 Shizuku 触发 Dexopt 优化。 */
    fun dexopt(packageName: String): Boolean = runCatching {
        if (!hasPermission()) return false

        val binder = SystemServiceHelper.getSystemService("package")
        val manager = Class.forName("android.content.pm.IPackageManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(binder)) ?: return false

        val candidates = manager.javaClass.methods.filter { it.name == "performDexOptMode" }
        for (method in candidates) {
            val args = buildDexoptArgs(method.parameterTypes, packageName) ?: continue
            runCatching { method.invoke(manager, *args) }
        }
        true
    }.getOrDefault(false)

    private fun buildDexoptArgs(types: Array<Class<*>>, packageName: String): Array<Any?>? {
        if (types.isEmpty() || types[0] != String::class.java) return null
        val args = arrayOfNulls<Any>(types.size)
        args[0] = packageName
        for (i in 1 until types.size) {
            val type = types[i]
            args[i] = when {
                type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java -> true
                type == String::class.java -> "speed"
                type == java.lang.Integer::class.java || type == Int::class.javaPrimitiveType -> 0
                type.isArray -> null
                else -> null
            }
        }
        return args
    }
}
