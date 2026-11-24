package io.xpatch.data

import android.content.Context
import android.os.Environment
import android.os.Process
import io.xpatch.patch.ShizukuBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class ScopeFileWriter(private val context: Context) {

    data class Result(
        val appPackage: String,
        val userId: Int,
        val hasScope: Boolean,
        val mediaReady: Boolean,
        val errors: List<String>,
    ) {
        val ok: Boolean get() = mediaReady

        val failureText: String?
            get() {
                if (ok) return null
                return buildString {
                    append(appPackage).append("（").append(UserDirectory.labelOf(userId)).append("）：")
                    if (!mediaReady) {
                        append(if (hasScope) "作用域写入失败" else "作用域清空失败")
                    }
                    if (errors.isNotEmpty()) {
                        if (!mediaReady) append("；")
                        append(errors.joinToString("；"))
                    }
                }
            }
    }

    fun syncAll(
        targets: Collection<String> = emptyList(),
        userIds: Collection<Int> = listOf(UserDirectory.currentUserId()),
        onEvent: (String) -> Unit = {},
    ): Int {
        val all = LinkedHashSet(targets)
        val stores = userIds.distinct().map { ScopeStore(context, it) }
        stores.forEach { store -> all.addAll(store.exportAll().values.flatten()) }
        val results = stores.flatMap { store ->
            all.map { target -> sync(target, store.userId) }
        }
        results.filter { !it.ok }.forEach { result ->
            runCatching { onEvent(result.failureText ?: result.appPackage) }
        }
        return results.count { !it.ok }
    }

    fun sync(appPackage: String, userId: Int = UserDirectory.currentUserId()): Result =
        sync(appPackage, userId, ScopeStore(context, userId))

    private fun sync(appPackage: String, userId: Int, scopeStore: ScopeStore): Result {
        val lock = synchronized(SYNC_LOCKS) { SYNC_LOCKS.getOrPut("$userId|$appPackage") { Any() } }
        return synchronized(lock) { syncLocked(appPackage, userId, scopeStore) }
    }

    private fun syncLocked(appPackage: String, userId: Int, scopeStore: ScopeStore): Result {
        val modules = scopeStore.modulesOf(appPackage)
        val mediaFile = mediaScopeFile(appPackage, userId)
        val errors = mutableListOf<String>()
        val remote = userId != UserDirectory.currentUserId()

        if (modules.isEmpty()) {
            if (remote) {
                return Result(appPackage, userId, false, true, errors)
            }
            val existing = runCatching { mediaFile.readText() }.getOrNull()
            val hasPayload = existing != null && existing.contains("\"packageName\"")
            if (hasPayload) {
                return Result(appPackage, userId, true, true, errors)
            }
            val mediaOk = runCatching {
                mediaFile.parentFile?.mkdirs()
                writeAtomic(mediaFile, JSONObject().put("scopes", JSONArray()).toString())
            }.onFailure { errors.add("media 清空: " + (it.message ?: "未知错误")) }.isSuccess
            return Result(appPackage, userId, false, mediaOk, errors)
        }

        val payload = payloadOf(appPackage, modules, userId)
        val mediaOk = if (remote) {
            runCatching { writeViaShell(mediaFile, payload) }
                .onFailure { errors.add("media: " + (it.message ?: "未知错误")) }
                .isSuccess
        } else {
            runCatching {
                mediaFile.parentFile?.mkdirs()
                writeAtomic(mediaFile, payload)
            }.onFailure { errors.add("media: " + (it.message ?: "未知错误")) }.isSuccess
        }
        return Result(appPackage, userId, true, mediaOk, errors)
    }

    private fun writeViaShell(target: File, payload: String) {
        if (!ShizukuBridge.isAvailable() || !ShizukuBridge.hasPermission()) {
            error("需要 Shizuku 才能写入其他用户")
        }
        val dir = target.parentFile?.absolutePath ?: error("缺少目标目录")
        val tmp = target.absolutePath + ".tmp"
        val command = buildString {
            append("mkdir -p ").append(shellQuote(dir))
            append(" && cat > ").append(shellQuote(tmp)).append(" <<'XPATCH_EOF'\n")
            append(payload).append("\nXPATCH_EOF")
            append(" && mv -f ").append(shellQuote(tmp)).append(' ').append(shellQuote(target.absolutePath))
            append(" && echo XPATCH_OK")
        }
        val output = ShizukuBridge.exec(command)
        if (output == null || !output.contains(XPATCH_OK)) {
            error(output?.trim()?.takeIf { it.isNotEmpty() } ?: "写入被拒绝")
        }
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

    private fun payloadOf(appPackage: String, modules: Set<String>, userId: Int): String {
        val apiStore = ModuleApiStore(context, userId)
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

    private fun mediaScopeFile(appPackage: String, userId: Int): File {
        val dir = File(
            File(externalRoot(userId), "Android/media/$appPackage"),
            CONFIG_DIR,
        )
        return File(dir, sha1("$appPackage.scope"))
    }

    private fun externalRoot(userId: Int): File =
        if (userId == UserDirectory.currentUserId()) {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage/emulated/$userId")
        }

    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val CONFIG_DIR = "XPatch"
        const val XPATCH_OK = "XPATCH_OK"
        val SYNC_LOCKS = HashMap<String, Any>()
    }
}
