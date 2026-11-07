package io.xpatch.patch

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

object ShizukuInstaller {

    sealed interface Result {
        data class Success(val message: String) : Result
        data class Failure(val message: String) : Result
        data object Unavailable : Result
    }

    suspend fun install(context: Context, apk: File, packageName: String): Result =
        withContext(Dispatchers.IO) {
            if (!ShizukuBridge.isAvailable()) return@withContext Result.Unavailable
            if (!ShizukuBridge.hasPermission()) {
                val granted = requestPermission()
                if (!granted) return@withContext Result.Failure("Shizuku 未授权，已回退系统安装器")
            }
            val source = readableByShell(context, apk)
                ?: return@withContext Result.Failure("Shizuku 无法读取安装包，已回退系统安装器")
            val output = ShizukuBridge.exec("pm install -r -t -d " + shellQuote(source.absolutePath))
            when {
                output.isNullOrBlank() -> Result.Failure("Shizuku 安装无响应，已回退系统安装器")
                output.contains("Success") -> Result.Success("已通过 Shizuku 静默安装 $packageName")
                else ->
                    Result.Failure(
                        "Shizuku 安装失败：" +
                            output.trim().lineSequence().last { it.isNotBlank() },
                    )
            }
        }

    private suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { cont ->
        ShizukuBridge.requestPermission { granted -> cont.resume(granted) }
    }

    private fun readableByShell(context: Context, apk: File): File? {
        if (!apk.isFile) return null
        val private = apk.absolutePath.startsWith(context.cacheDir.absolutePath) ||
            apk.absolutePath.startsWith(context.filesDir.absolutePath)
        if (!private) return apk
        val dir = File(context.getExternalFilesDir(null), "install").apply { mkdirs() }
        val target = File(dir, apk.name)
        return runCatching { apk.copyTo(target, overwrite = true) }.getOrNull()
    }

    /**
     * 强行覆盖安装：签名不一致时先保留数据卸载旧包，再安装新包。
     * 依赖 Shizuku 的 shell 权限，仅在可用且已授权时执行。
     */
    suspend fun installForceOverwrite(
        context: Context,
        apk: File,
        packageName: String,
    ): Result = withContext(Dispatchers.IO) {
        if (!ShizukuBridge.isAvailable()) return@withContext Result.Unavailable
        if (!ShizukuBridge.hasPermission()) {
            val granted = requestPermission()
            if (!granted) return@withContext Result.Failure("Shizuku 未授权，无法强行覆盖安装")
        }
        val source = readableByShell(context, apk)
            ?: return@withContext Result.Failure("Shizuku 无法读取安装包")

        ShizukuBridge.exec("pm uninstall -k " + shellQuote(packageName))
        val output = ShizukuBridge.exec("pm install -r -t -d " + shellQuote(source.absolutePath))
        when {
            output.isNullOrBlank() -> Result.Failure("强行覆盖安装无响应")
            output.contains("Success") ->
                Result.Success("已强行覆盖安装 $packageName，应用数据已保留")

            else ->
                Result.Failure(
                    "强行覆盖安装失败：" +
                        output.trim().lineSequence().last { it.isNotBlank() },
                )
        }
    }

    fun forceOverwriteReady(): Boolean =
        ShizukuBridge.isAvailable() && ShizukuBridge.hasPermission()

    fun forceOverwriteBlockReason(): String = when {
        !ShizukuBridge.isAvailable() -> "未检测到 Shizuku，请先安装并启动 Shizuku"
        !ShizukuBridge.hasPermission() -> "Shizuku 未授权，请先在权限管理中授权"
        else -> ""
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
