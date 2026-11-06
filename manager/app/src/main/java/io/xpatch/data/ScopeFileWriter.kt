package io.xpatch.data

import android.content.Context
import android.os.Environment
import io.xpatch.patch.ShizukuBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class ScopeFileWriter(private val context: Context) {

    data class Result(
        val appPackage: String,
        val hasScope: Boolean,
        val mediaWritten: Boolean,
        val dataWritten: Boolean,
        val dataViaShizuku: Boolean,
        val errors: List<String>,
    ) {
        val ok: Boolean get() = !hasScope || (mediaWritten && dataWritten)

        val failureText: String?
            get() {
                if (ok) return null
                return buildString {
                    append(appPackage).append("：")
                    if (!mediaWritten) append("media 写入失败；")
                    if (!dataWritten) {
                        append("data 写入失败")
                        if (!dataViaShizuku) append("（Shizuku 兜底不可用，旧版运行时将读不到模块）")
                        append("；")
                    }
                    if (errors.isNotEmpty()) append(errors.joinToString("；"))
                }
            }
    }

    fun syncAll(): List<Result> {
        val scopeStore = ScopeStore(context)
        val targets = LinkedHashSet<String>()
        scopeStore.exportAll().values.forEach { targets.addAll(it) }
        return targets.map { target -> sync(target, scopeStore) }
    }

    fun sync(appPackage: String): Result = sync(appPackage, ScopeStore(context))

    private fun sync(appPackage: String, scopeStore: ScopeStore): Result {
        val modules = scopeStore.modulesOf(appPackage)
        if (modules.isEmpty()) {
            val errors = mutableListOf<String>()
            val mediaFile = mediaScopeFile(appPackage)
            val dataFile = scopeFile(appPackage)
            var mediaOk = runCatching { mediaFile.delete() }
                .onFailure { errors.add("media 删除: " + (it.message ?: "未知错误")) }
                .isSuccess
            var dataOk = runCatching { dataFile.delete() }
                .onFailure { errors.add("data 删除: " + (it.message ?: "未知错误")) }
                .isSuccess
            var dataViaShizuku = false
            // data 目录属应用私有，直删失败时改走 Shizuku 特权删除，
            // 否则运行时会按 media 优先、data 兜底读到残留文件，已取消的模块"复活"。
            if (!dataOk && dataFile.isFile) {
                dataViaShizuku = deleteDataViaShizuku(dataFile)
                if (dataViaShizuku) {
                    dataOk = true
                    errors.removeAll { it.startsWith("data 删除: ") }
                }
            }
            // 文件本来就不存在也算删除成功。
            if (!mediaOk && !mediaFile.isFile) mediaOk = true
            if (!dataOk && !dataFile.isFile) dataOk = true
            return Result(
                appPackage = appPackage,
                hasScope = false,
                mediaWritten = mediaOk,
                dataWritten = dataOk,
                dataViaShizuku = dataViaShizuku,
                errors = errors,
            )
        }
        val payload = payloadOf(appPackage, modules)
        val errors = mutableListOf<String>()
        val mirror = mediaScopeFile(appPackage)
        val mediaOk = runCatching {
            mirror.parentFile?.mkdirs()
            mirror.writeText(payload)
        }.onFailure { errors.add("media: " + (it.message ?: "未知错误")) }.isSuccess
        val file = scopeFile(appPackage)
        var dataOk = runCatching {
            file.parentFile?.mkdirs()
            file.writeText(payload)
        }.onFailure { errors.add("data: " + (it.message ?: "未知错误")) }.isSuccess
        var dataViaShizuku = false
        if (!dataOk) {
            dataViaShizuku = copyDataViaShizuku(mirror, file)
            if (dataViaShizuku) {
                dataOk = true
                errors.removeAll { it.startsWith("data: ") }
            }
        }
        return Result(
            appPackage = appPackage,
            hasScope = true,
            mediaWritten = mediaOk,
            dataWritten = dataOk,
            dataViaShizuku = dataViaShizuku,
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

    private fun copyDataViaShizuku(mirror: File, data: File): Boolean {
        if (!ShizukuBridge.hasPermission()) return false
        if (!mirror.isFile) return false
        val command = "cp " + quote(mirror.absolutePath) + " " + quote(data.absolutePath) +
            " && chmod 644 " + quote(data.absolutePath) +
            " && echo __XP_SCOPE_OK__"
        return ShizukuBridge.exec(command)?.contains("__XP_SCOPE_OK__") == true
    }

    private fun deleteDataViaShizuku(data: File): Boolean {
        if (!ShizukuBridge.hasPermission()) return false
        val command = "rm -f " + quote(data.absolutePath) + " && echo __XP_SCOPE_OK__"
        return ShizukuBridge.exec(command)?.contains("__XP_SCOPE_OK__") == true
    }

    private fun quote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun apkPathOf(modulePackage: String): String = runCatching {
        context.packageManager.getApplicationInfo(modulePackage, 0).sourceDir.orEmpty()
    }.getOrDefault("")

    private fun scopeFile(appPackage: String): File {
        val dir = File(Environment.getExternalStorageDirectory(), "Android/data/$appPackage")
        return File(dir, sha1("$appPackage.scope"))
    }

    private fun mediaScopeFile(appPackage: String): File {
        val dir = File(Environment.getExternalStorageDirectory(), "Android/media/$appPackage")
        return File(dir, sha1("$appPackage.scope"))
    }

    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return digest.joinToString("") { "%02X".format(it) }
    }
}
