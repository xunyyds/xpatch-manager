package io.xpatch.data

import android.content.Context
import android.os.Environment
import io.xpatch.patch.ShizukuBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 作用域同步：只以 Android/media/<pkg>/<sha1> 为唯一来源。
 *
 * media 目录在「所有文件访问」权限下可直接读写，无需特权通道；
 * Android/data 为应用私有目录，历史版本可能遗留旧作用域文件，
 * 而运行时按 media 优先、data 兜底读取，因此关闭全部模块时
 * 若发现遗留 data 份会一并清理（直删失败则以空作用域覆盖），
 * 避免已取消的模块经 data 兜底"复活"。
 */
class ScopeFileWriter(private val context: Context) {

    data class Result(
        val appPackage: String,
        val hasScope: Boolean,
        val mediaReady: Boolean,
        val legacyDataCleaned: Boolean,
        val dataViaShizuku: Boolean,
        val errors: List<String>,
    ) {
        val ok: Boolean get() = mediaReady && legacyDataCleaned

        val failureText: String?
            get() {
                if (ok) return null
                return buildString {
                    append(appPackage).append("：")
                    if (!mediaReady) {
                        append(if (hasScope) "media 写入失败；" else "media 删除失败；")
                    }
                    if (!legacyDataCleaned) {
                        append("遗留 data 作用域清理失败")
                        if (!dataViaShizuku) append("（Shizuku 兜底不可用）")
                        append("；")
                    }
                    if (errors.isNotEmpty()) append(errors.joinToString("；"))
                }
            }
    }

    /**
     * [onEvent]：可选的进度/失败回调（由 UI 层传入，用于把结果写进操作日志）。
     * sweep 失败常因 Shizuku UserService 冷启动未就绪，此处重试等待。
     */
    fun syncAll(onEvent: (String) -> Unit = {}) {
        val scopeStore = ScopeStore(context)
        val targets = LinkedHashSet<String>()
        scopeStore.exportAll().values.forEach { targets.addAll(it) }
        val results = targets.map { target -> sync(target, scopeStore) }.toMutableList()
        // 遗留清理：旧版本可能在 Android/data 留下作用域文件，而对应应用已无任何作用域，
        // 不在 exportAll 的 targets 里会被跳过；这里对常见残留路径做一次全量清扫。
        runCatching { sweepLegacyScopes(results, onEvent) }
    }

    private fun sweepLegacyScopes(results: MutableList<Result>, onEvent: (String) -> Unit) {
        val dataRoot = File(Environment.getExternalStorageDirectory(), "Android/data")
        val files = dataRoot.listFiles() ?: return
        for (dir in files) {
            if (!dir.isDirectory) continue
            val legacy = File(dir, sha1(dir.name + ".scope"))
            if (!legacy.isFile) continue
            // 与当前作用域一致的残留由 targets 路径处理；这里只处理"已无作用域"的应用。
            val hasScope = results.any { it.appPackage == dir.name && it.hasScope }
            if (hasScope) continue
            val staging = File(
                File(Environment.getExternalStorageDirectory(), "Android/media/" + dir.name),
                legacy.name + ".empty",
            )
            var viaShizuku = false
            runCatching {
                staging.parentFile?.mkdirs()
                staging.writeText(JSONObject().put("scopes", JSONArray()).toString())
                // Shizuku UserService 冷启动可能落后于本线程，重试等待最多 ~8 秒。
                repeat(4) { attempt ->
                    if (!viaShizuku) {
                        viaShizuku = copyDataViaShizuku(staging, legacy)
                        if (!viaShizuku && attempt < 3) Thread.sleep(2000)
                    }
                }
            }
            runCatching { staging.delete() }
            val cleaned = !legacy.isFile || readScopeIsEmpty(legacy)
            if (!cleaned) {
                onEvent("遗留 data 作用域清理失败 " + dir.name + "（Shizuku 通道未生效）")
            } else if (viaShizuku) {
                onEvent("已通过 Shizuku 清理 " + dir.name + " 的遗留 data 作用域")
            }
            results.add(
                Result(
                    appPackage = dir.name,
                    hasScope = false,
                    mediaReady = true,
                    legacyDataCleaned = cleaned,
                    dataViaShizuku = viaShizuku,
                    errors = if (cleaned) emptyList() else listOf("遗留 data 清理失败（Shizuku 通道未生效）"),
                ),
            )
        }
    }

    fun sync(appPackage: String): Result = sync(appPackage, ScopeStore(context))

    private fun sync(appPackage: String, scopeStore: ScopeStore): Result {
        // 无感迁移：旧版把作用域写在 Android/media/<pkg>/ 根下，
        // 新版统一放到 <pkg>/XPatch/，这里先迁移再按新路径读写。
        runCatching { migrateLegacyMedia(appPackage) }
        val modules = scopeStore.modulesOf(appPackage)
        val mediaFile = mediaScopeFile(appPackage)
        val legacyDataFile = scopeFile(appPackage)
        val errors = mutableListOf<String>()

        if (modules.isEmpty()) {
            runCatching { mediaFile.delete() }
                .onFailure { errors.add("media 删除: " + (it.message ?: "未知错误")) }
            runCatching { legacyMediaScopeFile(appPackage).delete() }
            var dataViaShizuku = false
            if (legacyDataFile.isFile) {
                runCatching { legacyDataFile.delete() }
                if (legacyDataFile.isFile) {
                    // 直删失败（FUSE 权限限制），改走实测可靠的 cp 通道写入空作用域。
                    // 注意：cp 源必须放在 media 下（shell uid 可读），
                    // 应用私有 cacheDir 的文件 UserService 读不到，会导致整条命令失败。
                    // UserService 冷启动可能超过绑定超时，这里重试最多 3 次。
                    val staging = File(mediaFile.parentFile, mediaFile.name + ".empty")
                    runCatching {
                        staging.parentFile?.mkdirs()
                        staging.writeText(JSONObject().put("scopes", JSONArray()).toString())
                        repeat(3) { attempt ->
                            if (!dataViaShizuku) {
                                dataViaShizuku = copyDataViaShizuku(staging, legacyDataFile)
                                if (!dataViaShizuku && attempt < 2) Thread.sleep(2000)
                            }
                        }
                    }
                    runCatching { staging.delete() }
                }
            }
            return Result(
                appPackage = appPackage,
                hasScope = false,
                mediaReady = !mediaFile.isFile,
                legacyDataCleaned = !legacyDataFile.isFile || readScopeIsEmpty(legacyDataFile),
                dataViaShizuku = dataViaShizuku,
                errors = errors,
            )
        }

        val payload = payloadOf(appPackage, modules)
        val mediaOk = runCatching {
            mediaFile.parentFile?.mkdirs()
            mediaFile.writeText(payload)
        }.onFailure { errors.add("media: " + (it.message ?: "未知错误")) }.isSuccess
        return Result(
            appPackage = appPackage,
            hasScope = true,
            mediaReady = mediaOk,
            legacyDataCleaned = true,
            dataViaShizuku = false,
            errors = errors,
        )
    }

    private fun payloadOf(appPackage: String, modules: Set<String>): String {
        val apiStore = ModuleApiStore(context)
        val inspector = ModuleApiInspector(context)
        val scopes = JSONArray()
        modules.sorted().forEach { modulePackage ->
            val chosen = apiStore.apiOf(appPackage, modulePackage)
            val info = inspector.inspect(modulePackage)
            val level = ModuleApiStore.resolveApi(
                chosen,
                info?.availableLevels.orEmpty(),
                info?.resolvedLevel ?: ModuleApiInspector.API_100,
            )
            scopes.put(
                JSONObject()
                    .put("packageName", modulePackage)
                    .put("path", apkPathOf(modulePackage))
                    .put("globalEnable", true)
                    .put("useApiLevel", level),
            )
        }
        return JSONObject().put("scopes", scopes).toString()
    }

    private fun copyDataViaShizuku(source: File, target: File): Boolean {
        if (!ShizukuBridge.hasPermission()) return false
        if (!source.isFile) return false
        val command = "cp " + quote(source.absolutePath) + " " + quote(target.absolutePath) +
            " && chmod 644 " + quote(target.absolutePath) +
            " && echo __XP_SCOPE_OK__"
        return ShizukuBridge.exec(command)?.contains("__XP_SCOPE_OK__") == true
    }

    private fun readScopeIsEmpty(file: File): Boolean = runCatching {
        val scopes = JSONObject(file.readText()).optJSONArray("scopes")
        scopes == null || scopes.length() == 0
    }.getOrDefault(false)

    private fun quote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun apkPathOf(modulePackage: String): String = runCatching {
        context.packageManager.getApplicationInfo(modulePackage, 0).sourceDir.orEmpty()
    }.getOrDefault("")

    private fun scopeFile(appPackage: String): File {
        val dir = File(Environment.getExternalStorageDirectory(), "Android/data/$appPackage")
        return File(dir, sha1("$appPackage.scope"))
    }

    /** 新位置：<pkg>/XPatch/<sha1>，与管理器自己的配置同目录，避免与其他工具混放。 */
    private fun mediaScopeFile(appPackage: String): File {
        val dir = File(
            File(Environment.getExternalStorageDirectory(), "Android/media/$appPackage"),
            CONFIG_DIR,
        )
        return File(dir, sha1("$appPackage.scope"))
    }

    /**
     * 旧位置（历史版本写入）：Android/media/<pkg>/<sha1>。
     * 检测到就迁移到 XPatch/ 子目录，迁移后删除旧文件，实现无感升级。
     */
    private fun legacyMediaScopeFile(appPackage: String): File {
        val dir = File(Environment.getExternalStorageDirectory(), "Android/media/$appPackage")
        return File(dir, sha1("$appPackage.scope"))
    }

    private fun migrateLegacyMedia(appPackage: String) {
        val legacy = legacyMediaScopeFile(appPackage)
        if (!legacy.isFile) return
        val target = mediaScopeFile(appPackage)
        if (!target.isFile) {
            runCatching {
                target.parentFile?.mkdirs()
                legacy.copyTo(target, overwrite = true)
            }
        }
        // 迁移完成（或目标已存在）后清理旧文件，避免两份并存导致状态歧义。
        if (target.isFile) runCatching { legacy.delete() }
    }

    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return digest.joinToString("") { "%02X".format(it) }
    }

    private companion object {
        const val CONFIG_DIR = "XPatch"
    }
}
