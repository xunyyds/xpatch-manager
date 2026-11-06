package io.xpatch.patch

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * 基于 Shizuku 用户服务的静默安装（默认安装路径）。
 * 返回明确结果，由调用方负责反馈并兜底到系统安装器，绝不静默。
 */
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

    /** shell 读取不到应用私有目录，必要时把安装包复制到外部 files 目录。 */
    private fun readableByShell(context: Context, apk: File): File? {
        if (!apk.isFile) return null
        val private = apk.absolutePath.startsWith(context.cacheDir.absolutePath) ||
            apk.absolutePath.startsWith(context.filesDir.absolutePath)
        if (!private) return apk
        val dir = File(context.getExternalFilesDir(null), "install").apply { mkdirs() }
        val target = File(dir, apk.name)
        return runCatching { apk.copyTo(target, overwrite = true) }.getOrNull()
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
