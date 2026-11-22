package io.xpatch.data

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class ScopeFileWriter(private val context: Context) {

    data class Result(
        val appPackage: String,
        val hasScope: Boolean,
        val mediaReady: Boolean,
        val errors: List<String>,
    ) {
        val ok: Boolean get() = mediaReady

        val failureText: String?
            get() {
                if (ok) return null
                return buildString {
                    append(appPackage).append("：")
                    if (!mediaReady) {
                        append(if (hasScope) "media 写入失败" else "media 清空失败")
                    }
                    if (errors.isNotEmpty()) {
                        if (!mediaReady) append("；")
                        append(errors.joinToString("；"))
                    }
                }
            }
    }

    fun syncAll(targets: Collection<String> = emptyList(), onEvent: (String) -> Unit = {}): Int {
        val scopeStore = ScopeStore(context)
        val all = LinkedHashSet(targets)
        scopeStore.exportAll().values.forEach { all.addAll(it) }
        val results = all.map { target -> sync(target, scopeStore) }
        return results.count { !it.ok }
    }

    fun sync(appPackage: String): Result = sync(appPackage, ScopeStore(context))

    private fun sync(appPackage: String, scopeStore: ScopeStore): Result {
        val lock = synchronized(SYNC_LOCKS) { SYNC_LOCKS.getOrPut(appPackage) { Any() } }
        return synchronized(lock) { syncLocked(appPackage, scopeStore) }
    }

    private fun syncLocked(appPackage: String, scopeStore: ScopeStore): Result {
        val modules = scopeStore.modulesOf(appPackage)
        val mediaFile = mediaScopeFile(appPackage)
        val errors = mutableListOf<String>()

        if (modules.isEmpty()) {
            val existing = runCatching { mediaFile.readText() }.getOrNull()
            val hasPayload = existing != null && existing.contains("\"packageName\"")
            if (hasPayload) {
                return Result(
                    appPackage = appPackage,
                    hasScope = true,
                    mediaReady = true,
                    errors = errors,
                )
            }
            val mediaOk = runCatching {
                mediaFile.parentFile?.mkdirs()
                writeAtomic(mediaFile, JSONObject().put("scopes", JSONArray()).toString())
            }.onFailure { errors.add("media 清空: " + (it.message ?: "未知错误")) }.isSuccess
            return Result(
                appPackage = appPackage,
                hasScope = false,
                mediaReady = mediaOk,
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

    private fun apkPathOf(modulePackage: String): String = runCatching {
        context.packageManager.getApplicationInfo(modulePackage, 0).sourceDir.orEmpty()
    }.getOrDefault("")

    private fun mediaScopeFile(appPackage: String): File {
        val dir = File(
            File(Environment.getExternalStorageDirectory(), "Android/media/$appPackage"),
            CONFIG_DIR,
        )
        return File(dir, sha1("$appPackage.scope"))
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
