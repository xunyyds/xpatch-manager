package io.xpatch.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ConfigBackup(private val context: Context) {

    private val scopeStore = ScopeStore(context)
    private val apiStore = ModuleApiStore(context)
    private val signatureStore = SignatureStore(context)
    private val settingsStore = SettingsStore(context)

    data class ImportResult(val success: Boolean, val message: String)

    fun export(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())

        val scope = JSONObject()
        scopeStore.exportAll().forEach { (module, apps) -> scope.put(module, JSONArray(apps.toList())) }
        root.put("scope", scope)

        val api = JSONObject()
        apiStore.exportAll().forEach { (key, level) -> api.put(key, level) }
        root.put("moduleApi", api)

        val signatures = JSONArray()
        signatureStore.allProfiles().forEach { profile ->
            signatures.put(
                JSONObject().apply {
                    put("id", profile.id)
                    put("name", profile.name)
                    put("custom", profile.kind == SignatureStore.Kind.CUSTOM)
                },
            )
        }
        root.put("signatures", signatures)
        root.put("defaultSignature", signatureStore.defaultProfileId())

        val settings = settingsStore.load()
        root.put(
            "settings",
            JSONObject().apply {
                put("themeMode", settings.themeMode)
                put("showSystemApps", settings.showSystemApps)
                put("hookCore", settings.hookCore.index)
                put("bypassLevel", settings.signatureBypassLevel)
                put("injectSignature", settings.injectSignature)
                put("fileProvider", settings.fileProvider)
                put("dynamicMode", settings.dynamicMode)
                put("uiStyle", settings.uiStyle.index)
                put("bottomBarStyle", settings.bottomBarStyle)
                put("sigMode", settings.sigMode)
                put("entryHook", settings.entryHook)
                put("forceOverwrite", settings.forceOverwrite)
                put("injectObfuscate", settings.injectObfuscate)
                put("injectKeepCrashPermission", settings.injectKeepCrashPermission)
            },
        )
        return root.toString(2)
    }

    fun import(json: String): ImportResult {
        val root = runCatching { JSONObject(json) }.getOrElse {
            return ImportResult(false, "文件格式不正确：${it.message}")
        }

        var scopeCount = 0
        var apiCount = 0

        root.optJSONObject("scope")?.let { scope ->
            scope.keys().forEach { module ->
                val apps = scope.optJSONArray(module) ?: return@forEach
                for (i in 0 until apps.length()) {
                    scopeStore.setInScope(module, apps.getString(i), true)
                    scopeCount++
                }
            }
        }

        root.optJSONObject("moduleApi")?.let { api ->
            api.keys().forEach { key ->
                val parts = key.split(":")
                if (parts.size == 2) {
                    apiStore.setApi(parts[0], parts[1], api.optInt(key, 0))
                    apiCount++
                }
            }
        }

        root.optString("defaultSignature").takeIf { it.isNotEmpty() }?.let { id ->
            if (signatureStore.allProfiles().any { it.id == id }) {
                signatureStore.setDefaultProfile(id)
            }
        }

        root.optJSONObject("settings")?.let { settings ->
            settingsStore.setThemeMode(settings.optInt("themeMode", 0))
            settingsStore.setShowSystemApps(settings.optBoolean("showSystemApps", false))
            settingsStore.setSignatureBypassLevel(settings.optInt("bypassLevel", DEFAULT_BYPASS_LEVEL))
            settingsStore.setInjectSignature(settings.optBoolean("injectSignature", true))
            settingsStore.setFileProvider(settings.optBoolean("fileProvider", false))
            settingsStore.setDynamicMode(settings.optBoolean("dynamicMode", false))
            if (settings.has("sigMode")) {
                settingsStore.setSigMode(settings.optString("sigMode", SettingsStore.SIG_MODE_DEFAULT))
            }
            if (settings.has("entryHook")) {
                settingsStore.setEntryHook(settings.optBoolean("entryHook", false))
            }
            if (settings.has("forceOverwrite")) {
                settingsStore.setForceOverwrite(settings.optBoolean("forceOverwrite", false))
            }
            if (settings.has("injectObfuscate")) {
                settingsStore.setInjectObfuscate(settings.optBoolean("injectObfuscate", false))
            }
            if (settings.has("injectKeepCrashPermission")) {
                settingsStore.setInjectKeepCrashPermission(
                    settings.optBoolean("injectKeepCrashPermission", false),
                )
            }
            if (settings.has("uiStyle")) {
                settingsStore.setUiStyle(UiStyle.of(settings.optInt("uiStyle", UiStyle.MATERIAL3.index)))
            }
            if (settings.has("bottomBarStyle")) {
                settingsStore.setBottomBarStyle(settings.optInt("bottomBarStyle", SettingsStore.BOTTOM_BAR_STANDARD))
            }
            HookCore.entries.getOrNull(settings.optInt("hookCore", 0))?.let {
                settingsStore.setHookCore(it)
            }
        }

        return ImportResult(true, "已恢复 $scopeCount 条作用域、$apiCount 条 API 配置")
    }

    private companion object {
        const val DEFAULT_BYPASS_LEVEL = 3
    }
}
