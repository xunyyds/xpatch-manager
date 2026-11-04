package io.xpatch.data

import android.content.Context
import org.json.JSONArray

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
        val current = scope().getOrPut(modulePackage) { mutableSetOf() }
        val changed = if (inScope) current.add(appPackage) else current.remove(appPackage)
        if (changed) {
            reverseCache = null
            prefs.edit().putString(prefix(modulePackage), JSONArray(current.toList()).toString()).apply()
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

    private companion object {
        const val KEY_ENABLED = "enabled_modules"
        const val SCOPE_PREFIX = "scope_"

        val LOCK = Any()
        var enabledCache: MutableSet<String>? = null
        var scopeCache: MutableMap<String, MutableSet<String>>? = null
        var reverseCache: Map<String, Set<String>>? = null
    }
}
