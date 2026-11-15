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

    fun exportSettings(): JSONObject {
        val settings = settingsStore.load()
        return JSONObject().apply {
            put("themeMode", settings.themeMode)
            put("uiStyle", settings.uiStyle.index)
            put("bottomBarStyle", settings.bottomBarStyle)
            put("showSystemApps", settings.showSystemApps)
            put("patchLogLevel", settings.patchLogLevel)
            put("bypassLevel", settings.signatureBypassLevel)
            put("hookCore", settings.hookCore.index)
            put("injectSignature", settings.injectSignature)
            put("dynamicMode", settings.dynamicMode)
            put("fileProvider", settings.fileProvider)
            put("sigMode", settings.sigMode)
            put("forceOverwrite", settings.forceOverwrite)
            put("injectObfuscate", settings.injectObfuscate)
            put("injectKeepCrashPermission", settings.injectKeepCrashPermission)
            put("receiveBeta", settings.receiveBeta)
            put("patchHistoryLimit", settings.patchHistoryLimit)
            put("deleteArtifactOnRemove", settings.deleteArtifactOnRemove)
            put("skipArtifactDeletePrompt", settings.skipArtifactDeletePrompt)
            put("onboardingDone", settings.onboardingDone)
            put("disclaimerAcceptedAt", settings.disclaimerAcceptedAt)
            put("storageDeniedOnce", settings.storageDeniedOnce)
            put("lastUpdateCheckAt", settings.lastUpdateCheckAt)
            put("updateCheckCache", settings.updateCheckCache)
            put("updateGoodSource", settings.updateGoodSource)
        }
    }

    fun importSettings(json: JSONObject): Int {
        var applied = 0
        if (json.has("themeMode")) {
            settingsStore.setThemeMode(json.optInt("themeMode", SettingsStore.THEME_SYSTEM))
            applied++
        }
        if (json.has("uiStyle")) {
            settingsStore.setUiStyle(UiStyle.of(json.optInt("uiStyle", UiStyle.MATERIAL3.index)))
            applied++
        }
        if (json.has("bottomBarStyle")) {
            settingsStore.setBottomBarStyle(
                json.optInt("bottomBarStyle", SettingsStore.BOTTOM_BAR_STANDARD),
            )
            applied++
        }
        if (json.has("showSystemApps")) {
            settingsStore.setShowSystemApps(json.optBoolean("showSystemApps", false))
            applied++
        }
        if (json.has("patchLogLevel")) {
            settingsStore.setPatchLogLevel(json.optInt("patchLogLevel", SettingsStore.LOG_LEVEL_DEFAULT))
            applied++
        }
        if (json.has("bypassLevel")) {
            settingsStore.setSignatureBypassLevel(json.optInt("bypassLevel", DEFAULT_BYPASS_LEVEL))
            applied++
        }
        if (json.has("hookCore")) {
            HookCore.entries.getOrNull(json.optInt("hookCore", 0))?.let {
                settingsStore.setHookCore(it)
                applied++
            }
        }
        if (json.has("injectSignature")) {
            settingsStore.setInjectSignature(json.optBoolean("injectSignature", true))
            applied++
        }
        if (json.has("dynamicMode")) {
            settingsStore.setDynamicMode(json.optBoolean("dynamicMode", false))
            applied++
        }
        if (json.has("fileProvider")) {
            settingsStore.setFileProvider(json.optBoolean("fileProvider", false))
            applied++
        }
        if (json.has("sigMode")) {
            settingsStore.setSigMode(json.optString("sigMode", SettingsStore.SIG_MODE_DEFAULT))
            applied++
        }
        if (json.has("forceOverwrite")) {
            settingsStore.setForceOverwrite(json.optBoolean("forceOverwrite", false))
            applied++
        }
        if (json.has("injectObfuscate")) {
            settingsStore.setInjectObfuscate(json.optBoolean("injectObfuscate", false))
            applied++
        }
        if (json.has("injectKeepCrashPermission")) {
            settingsStore.setInjectKeepCrashPermission(
                json.optBoolean("injectKeepCrashPermission", false),
            )
            applied++
        }
        if (json.has("receiveBeta")) {
            settingsStore.setReceiveBeta(json.optBoolean("receiveBeta", false))
            applied++
        }
        if (json.has("patchHistoryLimit")) {
            settingsStore.setPatchHistoryLimit(
                json.optInt("patchHistoryLimit", SettingsStore.HISTORY_LIMIT_DEFAULT),
            )
            applied++
        }
        if (json.has("deleteArtifactOnRemove")) {
            settingsStore.setDeleteArtifactOnRemove(json.optBoolean("deleteArtifactOnRemove", true))
            applied++
        }
        if (json.has("skipArtifactDeletePrompt")) {
            settingsStore.setSkipArtifactDeletePrompt(json.optBoolean("skipArtifactDeletePrompt", false))
            applied++
        }
        if (json.has("onboardingDone")) {
            settingsStore.setOnboardingDone(json.optBoolean("onboardingDone", false))
            applied++
        }
        if (json.has("disclaimerAcceptedAt")) {
            settingsStore.setDisclaimerAcceptedAt(json.optLong("disclaimerAcceptedAt", 0))
            applied++
        }
        if (json.has("storageDeniedOnce")) {
            settingsStore.setStorageDeniedOnce(json.optBoolean("storageDeniedOnce", false))
            applied++
        }
        if (json.has("lastUpdateCheckAt") || json.has("updateCheckCache")) {
            settingsStore.setUpdateCheckCache(
                json.optLong("lastUpdateCheckAt", 0),
                json.optString("updateCheckCache", ""),
            )
            applied++
        }
        if (json.has("updateGoodSource")) {
            settingsStore.setUpdateGoodSource(json.optString("updateGoodSource", ""))
            applied++
        }
        return applied
    }

    fun exportScope(): JSONObject = JSONObject().apply {
        put("enabledModules", JSONArray(scopeStore.enabledModules().toList()))
        val scope = JSONObject()
        scopeStore.exportAll().forEach { (module, apps) -> scope.put(module, JSONArray(apps.toList())) }
        put("scope", scope)
        val api = JSONObject()
        apiStore.exportAll().forEach { (key, level) -> api.put(key, level) }
        put("moduleApi", api)
        put("guard", ModuleGuardStore.exportJson(context))
    }

    fun importScope(json: JSONObject): String {
        val enabled = buildList {
            json.optJSONArray("enabledModules")?.let { array ->
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
        val scopePairs = ArrayList<Pair<String, String>>()
        json.optJSONObject("scope")?.let { scope ->
            scope.keys().forEach { module ->
                val apps = scope.optJSONArray(module) ?: return@forEach
                for (index in 0 until apps.length()) {
                    apps.optString(index).takeIf { it.isNotBlank() }?.let { scopePairs += module to it }
                }
            }
        }
        val apis = ArrayList<Triple<String, String, Int>>()
        json.optJSONObject("moduleApi")?.let { api ->
            api.keys().forEach { key ->
                val parts = key.split(":")
                if (parts.size == 2) apis += Triple(parts[0], parts[1], api.optInt(key, 0))
            }
        }
        scopeStore.clearAll()
        apiStore.clearAll()
        try {
            enabled.forEach { scopeStore.setModuleEnabled(it, true) }
            scopePairs.forEach { (module, app) -> scopeStore.setInScope(module, app, true) }
            apis.forEach { (app, module, level) -> apiStore.setApi(app, module, level) }
            json.optJSONArray("guard")?.let { ModuleGuardStore.restoreJson(context, it) }
        } finally {
            scopeStore.invalidate()
        }
        return "已恢复 " + scopePairs.size + " 条作用域、" + apis.size + " 条 API 配置"
    }

    fun exportSignatures(): JSONObject = JSONObject().apply {
        put("default", signatureStore.defaultProfileId())
        put("profiles", signatureStore.exportCustomProfiles())
    }

    fun importSignatures(json: JSONObject, bins: Map<String, ByteArray>): Int {
        val profiles = json.optJSONArray("profiles") ?: JSONArray()
        val restored = signatureStore.restoreCustom(profiles, bins)
        val defaultId = json.optString("default")
        if (defaultId.isNotBlank() && signatureStore.allProfiles().any { it.id == defaultId }) {
            signatureStore.setDefaultProfile(defaultId)
        } else if (signatureStore.allProfiles().none { it.id == signatureStore.defaultProfileId() }) {
            signatureStore.setDefaultProfile(SignatureStore.BUILTIN_XPATCH)
        }
        return restored
    }

    fun importLegacy(json: String): ImportResult {
        val root = runCatching { JSONObject(json) }.getOrElse {
            return ImportResult(false, "文件格式不正确：" + it.message)
        }
        var scopeCount = 0
        var apiCount = 0

        scopeStore.clearAll()
        apiStore.clearAll()
        try {
            root.optJSONObject("scope")?.let { scope ->
                scope.keys().forEach { module ->
                    val apps = scope.optJSONArray(module) ?: return@forEach
                    for (index in 0 until apps.length()) {
                        apps.optString(index).takeIf { it.isNotBlank() }?.let { app ->
                            scopeStore.setInScope(module, app, true)
                            scopeCount++
                        }
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
                if (settings.has("themeMode")) settingsStore.setThemeMode(settings.optInt("themeMode", 0))
                if (settings.has("showSystemApps")) {
                    settingsStore.setShowSystemApps(settings.optBoolean("showSystemApps", false))
                }
                if (settings.has("bypassLevel")) {
                    settingsStore.setSignatureBypassLevel(
                        settings.optInt("bypassLevel", DEFAULT_BYPASS_LEVEL),
                    )
                }
                if (settings.has("injectSignature")) {
                    settingsStore.setInjectSignature(settings.optBoolean("injectSignature", true))
                }
                if (settings.has("fileProvider")) {
                    settingsStore.setFileProvider(settings.optBoolean("fileProvider", false))
                }
                if (settings.has("dynamicMode")) {
                    settingsStore.setDynamicMode(settings.optBoolean("dynamicMode", false))
                }
                if (settings.has("sigMode")) {
                    settingsStore.setSigMode(settings.optString("sigMode", SettingsStore.SIG_MODE_DEFAULT))
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
                    settingsStore.setBottomBarStyle(
                        settings.optInt("bottomBarStyle", SettingsStore.BOTTOM_BAR_STANDARD),
                    )
                }
                HookCore.entries.getOrNull(settings.optInt("hookCore", 0))?.let {
                    settingsStore.setHookCore(it)
                }
            }
        } finally {
            scopeStore.invalidate()
        }

        return ImportResult(true, "已恢复 $scopeCount 条作用域、$apiCount 条 API 配置")
    }

    private companion object {
        const val DEFAULT_BYPASS_LEVEL = 3
    }
}