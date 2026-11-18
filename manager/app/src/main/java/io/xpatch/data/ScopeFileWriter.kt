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
                        append(if (hasScope) "media 写入失败；" else "media 清空失败；")
                    }
                    if (!legacyDataCleaned) {
                        append("遗留 data 作用域清理失败")
                        if (!dataViaShizuku) append("（Shizuku 不可用）")
                        append("；")
                    }
                    if (errors.isNotEmpty()) append(errors.joinToString("；"))
                }
            }
    }

    fun syncAll(targets: Collection<String> = emptyList(), onEvent: (String) -> Unit = {}): Int {
        val scopeStore = ScopeStore(context)
        val all = LinkedHashSet(targets)
        scopeStore.exportAll().values.forEach { all.addAll(it) }
        val results = all.map { target -> sync(target, scopeStore) }.toMutableList()
        runCatching { sweepLegacyScopes(results, onEvent) }
        return results.count { !it.ok }
    }

    private fun sweepLegacyScopes(results: MutableList<Result>, onEvent: (String) -> Unit) {
        val dataRoot = File(Environment.getExternalStorageDirectory(), "Android/data")
        val files = dataRoot.listFiles() ?: return
        for (dir in files) {
            if (!dir.isDirectory || results.any { it.appPackage == dir.name }) continue
            val legacy = File(dir, sha1(dir.name + ".scope"))
            if (!legacy.isFile) continue
            val result = sync(dir.name)
            if (!result.ok) {
                onEvent("遗留 data 作用域清理失败 " + dir.name + "（Shizuku 通道未生效）")
            } else if (result.dataViaShizuku) {
                onEvent("已通过 Shizuku 清理 " + dir.name + " 的遗留 data 作用域")
            }
            results.add(result)
        }
    }

    fun sync(appPackage: String): Result = sync(appPackage, ScopeStore(context))

    private fun sync(appPackage: String, scopeStore: ScopeStore): Result {
        val lock = synchronized(SYNC_LOCKS) { SYNC_LOCKS.getOrPut(appPackage) { Any() } }
        return synchronized(lock) { syncLocked(appPackage, scopeStore) }
    }

    private fun syncLocked(appPackage: String, scopeStore: ScopeStore): Result {
        runCatching { migrateLegacyMedia(appPackage) }
        val modules = scopeStore.modulesOf(appPackage)
        val mediaFile = mediaScopeFile(appPackage)
        val legacyDataFile = scopeFile(appPackage)
        val errors = mutableListOf<String>()

        if (modules.isEmpty()) {
            val existing = runCatching { mediaFile.readText() }.getOrNull()
            val hasPayload = existing != null && existing.contains("\"packageName\"")
            if (hasPayload) {
                return Result(
                    appPackage = appPackage,
                    hasScope = true,
                    mediaReady = true,
                    legacyDataCleaned = true,
                    dataViaShizuku = false,
                    errors = errors,
                )
            }
            val mediaOk = runCatching {
                mediaFile.parentFile?.mkdirs()
                writeAtomic(mediaFile, JSONObject().put("scopes", JSONArray()).toString())
            }.onFailure { errors.add("media 清空: " + (it.message ?: "未知错误")) }.isSuccess
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
                mediaReady = mediaOk,
                legacyDataCleaned = !legacyDataFile.isFile || readScopeIsEmpty(legacyDataFile),
                dataViaShizuku = dataViaShizuku,
                errors = errors,
            )
        }

        val payload = payloadOf(appPackage, modules)
        val mediaOk = runCatching {
            mediaFile.parentFile?.mkdirs()
            writeAtomic(mediaFile, payload)
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

    private fun writeAtomic(target: File, payload: String) {
        if (target.isFile && runCatching { target.readText() }.getOrNull() == payload) {
            return
        }
        val tmp = File(target.absolutePath + ".tmp")
        val backup = File(target.absolutePath + ".bak")
        try {
            tmp.writeText(payload)
            if (target.isFile) {
                backup.delete()
                target.copyTo(backup, overwrite = true)
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (error: Throwable) {
            runCatching { tmp.delete() }
            throw error
        }
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
        val SYNC_LOCKS = HashMap<String, Any>()
    }
}
