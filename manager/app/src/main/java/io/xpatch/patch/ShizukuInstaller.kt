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
            val output = ShizukuBridge.exec("pm install -r -t -d --user current " + shellQuote(source.absolutePath))
            cleanupStaged(source)
            when {
                output.isNullOrBlank() -> Result.Failure("Shizuku 安装无响应，已回退系统安装器")
                output.contains("Success") -> Result.Success("已通过 Shizuku 静默安装 $packageName")
                else ->
                    Result.Failure(
                        "Shizuku 安装失败：" + failureDetail(output),
                    )
            }
        }

    private suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { cont ->
        ShizukuBridge.requestPermission { granted -> cont.resume(granted) }
    }

    /**
     * 选一个 system_server 读得到的路径再交给 pm install。
     * /sdcard/Android/data 属于 FUSE 挂载，system_server 读取会被 SELinux 拒绝
     * （avc denied ... fuse），必须复制到 /data/local/tmp 再安装。
     */
    private fun readableByShell(context: Context, apk: File): File? {
        if (!apk.isFile) return null
        // 应用私有目录 shell 读不到，外部目录 system_server 又因 FUSE 读不到，
        // 所以先落到外部目录，再由 shell 复制到 /data/local/tmp 供 pm install 使用。
        val dir = File(context.getExternalFilesDir(null), "install-staging").apply { mkdirs() }
        val staged = File(dir, PREFIX + System.currentTimeMillis() + ".apk")
        runCatching { apk.copyTo(staged, overwrite = true) }.getOrNull() ?: return null

        val tmpPath = "/data/local/tmp/" + staged.name
        ShizukuBridge.exec("cp " + shellQuote(staged.absolutePath) + " " + shellQuote(tmpPath))
        val verified = ShizukuBridge.exec("test -f " + shellQuote(tmpPath) + " && echo OK")
        staged.delete()
        return if (verified?.contains("OK") == true) File(tmpPath) else null
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

        // 按当前用户处理：设备上可能存在应用分身/隐私空间里的同名安装，
        // 全用户卸载会因权限不足失败，全用户安装又会被别的用户的旧签名挡住。
        val wasInstalled = installedForCurrentUser(packageName)
        if (wasInstalled != false) {
            val uninstallOutput = ShizukuBridge.exec(
                "pm uninstall -k --user current " + shellQuote(packageName),
            )
            if (installedForCurrentUser(packageName) == true) {
                return@withContext Result.Failure(
                    "无法卸载 $packageName，强行覆盖安装已中止：" + failureDetail(uninstallOutput),
                )
            }
        }

        val output = ShizukuBridge.exec(
            "pm install -r -t -d --user current " + shellQuote(source.absolutePath),
        )
        cleanupStaged(source)
        when {
            output.isNullOrBlank() -> Result.Failure("强行覆盖安装无响应")
            output.contains("Success") ->
                Result.Success("已强行覆盖安装 $packageName，应用数据已保留")

            else -> Result.Failure(
                if (wasInstalled == true) {
                    "原应用已卸载（数据保留），但重装失败：" + failureDetail(output)
                } else {
                    "强行覆盖安装失败：" + failureDetail(output)
                },
            )
        }
    }

    /** 安装结束后清理 /data/local/tmp 下的临时副本。 */
    private fun cleanupStaged(source: File) {
        val path = source.absolutePath
        if (!path.startsWith("/data/local/tmp/")) return
        ShizukuBridge.exec("rm -f " + shellQuote(path))
    }

    /** 当前用户下是否已安装；执行失败返回 null，交由调用方按未知处理。 */
    private fun installedForCurrentUser(packageName: String): Boolean? {
        val listed = ShizukuBridge.exec("pm list packages --user current " + shellQuote(packageName))
            ?: return null
        return listed.contains(packageName)
    }

    /** pm 的失败输出混着调用栈，取 Failure / Exception 行才有参考价值。 */
    private fun failureDetail(output: String?): String {
        val lines = output.orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        return lines.firstOrNull { it.startsWith("Failure") }
            ?: lines.firstOrNull { it.contains("Exception", true) || it.contains("Error", true) }
            ?: lines.firstOrNull()
            ?: "未知错误"
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

    private const val PREFIX = "xpinstall-"
}
