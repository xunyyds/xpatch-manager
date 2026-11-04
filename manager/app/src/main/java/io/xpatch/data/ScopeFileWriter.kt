package io.xpatch.data

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class ScopeFileWriter(private val context: Context) {

    fun syncAll() {
        val scopeStore = ScopeStore(context)
        val targets = HashSet<String>()
        scopeStore.exportAll().values.forEach { targets.addAll(it) }
        targets.forEach { target -> sync(target, scopeStore) }
    }

    fun sync(appPackage: String) {
        sync(appPackage, ScopeStore(context))
    }

    private fun sync(appPackage: String, scopeStore: ScopeStore) {
        val modules = scopeStore.modulesOf(appPackage)
        val file = scopeFile(appPackage)
        val mirror = mediaScopeFile(appPackage)
        if (modules.isEmpty()) {
            runCatching { file.delete() }
            runCatching { mirror.delete() }
            return
        }
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
        val payload = JSONObject().put("scopes", scopes).toString()
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(payload)
        }
        runCatching {
            val mirror = mediaScopeFile(appPackage)
            mirror.parentFile?.mkdirs()
            mirror.writeText(payload)
        }
    }

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
