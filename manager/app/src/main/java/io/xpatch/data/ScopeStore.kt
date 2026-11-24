package io.xpatch.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import org.json.JSONArray
import java.util.UUID

class ScopeStore(
    context: Context,
    val userId: Int = UserDirectory.currentUserId(),
) {

    private val prefs = context.getSharedPreferences(prefsName(userId), Context.MODE_PRIVATE)

    val isCurrentUser: Boolean get() = userId == UserDirectory.currentUserId()

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

    fun appsOfModuleFor(modulePackage: String): Set<String> = synchronized(LOCK) {
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
            reverseCache().value = null
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
            reverseCache().value = null
            if (enabledChanged) {
                editor.putString(KEY_ENABLED, JSONArray(enabled.toList()).toString())
            }
            enqueueScopeSync(affectedApps, editor)
            editor.apply()
        }
        pendingSync().toSet()
    }

    fun copyAppScope(source: ScopeStore, appPackage: String) = synchronized(LOCK) {
        if (source.userId == userId) return@synchronized
        val modules = source.modulesOf(appPackage)
        if (modules.isEmpty()) return@synchronized
        val all = scope()
        val editor = prefs.edit()
        var changed = false
        modules.forEach { module ->
            val apps = all.getOrPut(module) { mutableSetOf() }
            if (apps.add(appPackage)) {
                editor.putString(prefix(module), JSONArray(apps.toList()).toString())
                changed = true
            }
        }
        if (changed) {
            reverseCache().value = null
            enqueueScopeSync(setOf(appPackage), editor)
            editor.apply()
        }
    }

    fun invalidate() {
        synchronized(LOCK) { caches.remove(userId) }
    }

    fun clearAll() {
        synchronized(LOCK) {
            val affectedApps = scope().values.flatten().toSet() + pendingSync()
            val editor = prefs.edit().clear()
            enqueueScopeSync(affectedApps, editor)
            runCatching { editor.apply() }
            invalidate()
        }
    }

    private fun enqueueScopeSync(apps: Collection<String>, editor: SharedPreferences.Editor) {
        val pending = pendingSync()
        apps.toSet().forEach { appPackage ->
            pending.add(appPackage)
            editor.putString(syncVersionPrefix(appPackage), UUID.randomUUID().toString())
        }
        editor.putString(KEY_PENDING_SYNC, JSONArray(pending.toList()).toString())
    }

    private fun storage(): Caches = synchronized(LOCK) { caches.getOrPut(userId) { Caches() } }

    private fun enabled(): MutableSet<String> {
        val holder = storage()
        holder.enabled?.let { return it }
        val raw = runCatching { prefs.getString(KEY_ENABLED, null) }.getOrNull()
        val loaded = parse(raw).toMutableSet()
        holder.enabled = loaded
        return loaded
    }

    private fun scope(): MutableMap<String, MutableSet<String>> {
        val holder = storage()
        holder.scope?.let { return it }
        val loaded = HashMap<String, MutableSet<String>>()
        runCatching {
            prefs.all.forEach { (key, value) ->
                if (key.startsWith(SCOPE_PREFIX)) {
                    val raw = value as? String
                    if (raw != null) loaded[key.removePrefix(SCOPE_PREFIX)] = parse(raw).toMutableSet()
                }
            }
        }
        holder.scope = loaded
        return loaded
    }

    private fun moduleStamps(): MutableMap<String, String> {
        val holder = storage()
        holder.stamps?.let { return it }
        val loaded = HashMap<String, String>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(STAMP_PREFIX) && value is String) {
                loaded[key.removePrefix(STAMP_PREFIX)] = value
            }
        }
        holder.stamps = loaded
        return loaded
    }

    private fun pendingSync(): MutableSet<String> {
        val holder = storage()
        holder.pending?.let { return it }
        val loaded = parse(prefs.getString(KEY_PENDING_SYNC, null)).toMutableSet()
        holder.pending = loaded
        return loaded
    }

    private fun reverseCache(): Holder<Map<String, Set<String>>> = storage().reverse

    private fun reverse(): Map<String, Set<String>> {
        val holder = reverseCache()
        holder.value?.let { return it }
        val built = HashMap<String, MutableSet<String>>()
        scope().forEach { (module, apps) ->
            apps.forEach { app -> built.getOrPut(app) { mutableSetOf() }.add(module) }
        }
        val frozen = built.mapValues { it.value.toSet() }
        holder.value = frozen
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

    private class Holder<T>(var value: T?)

    private class Caches(
        var enabled: MutableSet<String>? = null,
        var scope: MutableMap<String, MutableSet<String>>? = null,
        val reverse: Holder<Map<String, Set<String>>> = Holder(null),
        var stamps: MutableMap<String, String>? = null,
        var pending: MutableSet<String>? = null,
    )

    companion object {
        private const val KEY_ENABLED = "enabled_modules"
        private const val KEY_PENDING_SYNC = "pending_sync_apps"
        private const val SCOPE_PREFIX = "scope_"
        private const val STAMP_PREFIX = "module_stamp_"
        private const val SYNC_VERSION_PREFIX = "sync_version_"

        private val LOCK = Any()
        private val caches = HashMap<Int, Caches>()

        fun prefsName(userId: Int): String = "scope_u$userId"

        fun invalidateAll() = synchronized(LOCK) { caches.clear() }
    }
}
