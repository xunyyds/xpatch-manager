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

    fun syncAll(onEvent: (String) -> Unit = {}) {
        val scopeStore = ScopeStore(context)
        val targets = LinkedHashSet<String>()
        scopeStore.exportAll().values.forEach { targets.addAll(it) }
        val results = targets.map { target -> sync(target, scopeStore) }.toMutableList()
        runCatching { sweepLegacyScopes(results, onEvent) }
    }

    private fun sweepLegacyScopes(results: MutableList<Result>, onEvent: (String) -> Unit) {
        val dataRoot = File(Environment.getExternalStorageDirectory(), "Android/data")
        val files = dataRoot.listFiles() ?: return
        for (dir in files) {
            if (!dir.isDirectory) continue
            val legacy = File(dir, sha1(dir.name + ".scope"))
            if (!legacy.isFile) continue
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

    private fun mediaScopeFile(appPackage: String): File {
        val dir = File(
            File(Environment.getExternalStorageDirectory(), "Android/media/$appPackage"),
            CONFIG_DIR,
        )
        return File(dir, sha1("$appPackage.scope"))
    }

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
