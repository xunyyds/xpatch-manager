package io.xpatch.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.util.UUID

class ScopeStore(context: Context) {

    private val prefs = context.getSharedPreferences("scope", Context.MODE_PRIVATE)

    fun enabledModules(): Set<String> = synchronized(LOCK) { enabled().toSet() }

    fun isModuleEnabled(modulePackage: String): Boolean = synchronized(LOCK) {
        modulePackage in enabled()
    }

    fun setModuleEnabled(modulePackage: String, enabled: Boolean) = synchronized(LOCK) {
        val current = enabled()
        val changed = if (enabled) current.add(modulePackage) else current.remove(modulePackage)
        if (changed) prefs.edit().putString(KEY_ENABLED, JSONArray(current.toList()).toString()).apply()
    }

    fun appsOf(modulePackage: String): Set<String> = synchronized(LOCK) {
        scope().get(modulePackage)?.toSet() ?: emptySet()
    }

    fun modulesOf(appPackage: String): Set<String> = synchronized(LOCK) {
        reverse()[appPackage] ?: emptySet()
    }

    fun setInScope(modulePackage: String, appPackage: String, inScope: Boolean) = synchronized(LOCK) {
        val all = scope()
        val current = all.getOrPut(modulePackage) { mutableSetOf() }
        val changed = if (inScope) current.add(appPackage) else current.remove(appPackage)
        if (changed) {
            reverseCache = null
            val editor = prefs.edit()
            if (current.isEmpty()) {
                all.remove(modulePackage)
                editor.remove(prefix(modulePackage))
            } else {
                editor.putString(prefix(modulePackage), JSONArray(current.toList()).toString())
            }
            enqueueScopeSync(setOf(appPackage), editor)
            editor.apply()
        }
    }

    fun countApps(modulePackage: String): Int = synchronized(LOCK) {
        scope().get(modulePackage)?.size ?: 0
    }

    fun countModules(appPackage: String): Int = synchronized(LOCK) {
        reverse()[appPackage]?.size ?: 0
    }

    fun exportAll(): Map<String, Set<String>> = synchronized(LOCK) {
        scope().mapValues { it.value.toSet() }
    }

    fun knownModulePackages(): Set<String> = synchronized(LOCK) { moduleStamps().keys.toSet() }

    fun pendingScopeSyncApps(): Set<String> = synchronized(LOCK) { pendingSync().toSet() }

    fun scopeSyncVersion(appPackage: String): String = synchronized(LOCK) {
        prefs.getString(syncVersionPrefix(appPackage), null).orEmpty()
    }

    fun requestScopeSync(apps: Collection<String>) = synchronized(LOCK) {
        if (apps.isNotEmpty()) {
            val editor = prefs.edit()
            enqueueScopeSync(apps, editor)
            editor.apply()
        }
    }

    fun acknowledgeScopeSync(appPackage: String, version: String): Boolean = synchronized(LOCK) {
        if (scopeSyncVersion(appPackage) != version) return@synchronized false
        val pending = pendingSync()
        if (pending.remove(appPackage)) {
            prefs.edit().putString(KEY_PENDING_SYNC, JSONArray(pending.toList()).toString()).apply()
        }
        true
    }

    fun reconcileModules(stamps: Map<String, String>, removed: Set<String>): Set<String> = synchronized(LOCK) {
        val all = scope()
        val known = moduleStamps()
        val affectedApps = HashSet<String>()
        val editor = prefs.edit()
        var changed = false
        stamps.forEach { (module, stamp) ->
            if (module !in removed && known[module] != stamp) {
                known[module] = stamp
                affectedApps.addAll(all[module].orEmpty())
                editor.putString(stampPrefix(module), stamp)
                changed = true
            }
        }
        val enabled = enabled()
        var enabledChanged = false
        removed.forEach { module ->
            affectedApps.addAll(all.remove(module).orEmpty())
            known.remove(module)
            enabledChanged = enabled.remove(module) || enabledChanged
            editor.remove(prefix(module)).remove(stampPrefix(module))
            changed = true
        }
        if (changed) {
            reverseCache = null
            if (enabledChanged) {
                editor.putString(KEY_ENABLED, JSONArray(enabled.toList()).toString())
            }
            enqueueScopeSync(affectedApps, editor)
            editor.apply()
        }
        pendingSync().toSet()
    }

    fun invalidate() = synchronized(LOCK) {
        enabledCache = null
        scopeCache = null
        reverseCache = null
        moduleStampCache = null
        pendingSyncCache = null
    }

    fun clearAll() = synchronized(LOCK) {
        val affectedApps = scope().values.flatten().toSet() + pendingSync()
        val editor = prefs.edit().clear()
        enqueueScopeSync(affectedApps, editor)
        runCatching { editor.apply() }
        invalidate()
    }

    private fun enqueueScopeSync(apps: Collection<String>, editor: SharedPreferences.Editor) {
        val pending = pendingSync()
        apps.toSet().forEach { appPackage ->
            pending.add(appPackage)
            editor.putString(syncVersionPrefix(appPackage), UUID.randomUUID().toString())
        }
        editor.putString(KEY_PENDING_SYNC, JSONArray(pending.toList()).toString())
    }

    private fun enabled(): MutableSet<String> {
        enabledCache?.let { return it }
        val raw = runCatching { prefs.getString(KEY_ENABLED, null) }.getOrNull()
        val loaded = parse(raw).toMutableSet()
        enabledCache = loaded
        return loaded
    }

    private fun scope(): MutableMap<String, MutableSet<String>> {
        scopeCache?.let { return it }
        val loaded = HashMap<String, MutableSet<String>>()
        runCatching {
            prefs.all.forEach { (key, value) ->
                if (key.startsWith(SCOPE_PREFIX)) {
                    val raw = value as? String
                    if (raw != null) loaded[key.removePrefix(SCOPE_PREFIX)] = parse(raw).toMutableSet()
                }
            }
        }
        scopeCache = loaded
        return loaded
    }

    private fun moduleStamps(): MutableMap<String, String> {
        moduleStampCache?.let { return it }
        val loaded = HashMap<String, String>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(STAMP_PREFIX) && value is String) {
                loaded[key.removePrefix(STAMP_PREFIX)] = value
            }
        }
        moduleStampCache = loaded
        return loaded
    }

    private fun pendingSync(): MutableSet<String> {
        pendingSyncCache?.let { return it }
        val loaded = parse(prefs.getString(KEY_PENDING_SYNC, null)).toMutableSet()
        pendingSyncCache = loaded
        return loaded
    }

    private fun reverse(): Map<String, Set<String>> {
        reverseCache?.let { return it }
        val built = HashMap<String, MutableSet<String>>()
        scope().forEach { (module, apps) ->
            apps.forEach { app -> built.getOrPut(app) { mutableSetOf() }.add(module) }
        }
        val frozen = built.mapValues { it.value.toSet() }
        reverseCache = frozen
        return frozen
    }

    private fun parse(raw: String?): Set<String> {
        if (raw.isNullOrEmpty()) return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            buildSet { for (i in 0 until array.length()) add(array.getString(i)) }
        }.getOrDefault(emptySet())
    }

    private fun prefix(modulePackage: String) = "$SCOPE_PREFIX$modulePackage"

    private fun stampPrefix(modulePackage: String) = "$STAMP_PREFIX$modulePackage"

    private fun syncVersionPrefix(appPackage: String) = "$SYNC_VERSION_PREFIX$appPackage"

    private companion object {
        const val KEY_ENABLED = "enabled_modules"
        const val KEY_PENDING_SYNC = "pending_sync_apps"
        const val SCOPE_PREFIX = "scope_"
        const val STAMP_PREFIX = "module_stamp_"
        const val SYNC_VERSION_PREFIX = "sync_version_"

        val LOCK = Any()
        var enabledCache: MutableSet<String>? = null
        var scopeCache: MutableMap<String, MutableSet<String>>? = null
        var reverseCache: Map<String, Set<String>>? = null
        var moduleStampCache: MutableMap<String, String>? = null
        var pendingSyncCache: MutableSet<String>? = null
    }
}
