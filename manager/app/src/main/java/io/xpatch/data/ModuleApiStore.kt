package io.xpatch.data

import android.content.Context

class ModuleApiStore(context: Context) {

    private val prefs = context.getSharedPreferences("module_api", Context.MODE_PRIVATE)

    fun apiOf(appPackage: String, modulePackage: String): Int =
        prefs.getInt(key(appPackage, modulePackage), AUTO)

    fun setApi(appPackage: String, modulePackage: String, api: Int) {
        prefs.edit().putInt(key(appPackage, modulePackage), api).apply()
    }

    fun clear(appPackage: String) {
        val prefix = "$appPackage:"
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
        }.apply()
    }

    fun exportAll(): Map<String, Int> =
        prefs.all.entries
            .filter { it.value is Int }
            .associate { it.key to it.value as Int }

    private fun key(appPackage: String, modulePackage: String) = "$appPackage:$modulePackage"

    companion object {
        const val AUTO = 0

        val LEVELS = listOf(AUTO) + ModuleApiInspector.LEVELS_FALLBACK

        fun label(api: Int, autoLevel: Int = ModuleApiInspector.API_100): String = when (api) {
            AUTO -> "自动 · " + ModuleApiInspector.wrapperLabel(autoLevel)
            else -> ModuleApiInspector.label(api)
        }

        fun supportedLevels(available: List<Int>): List<Int> =
            available.filter { it in ModuleApiInspector.WRAPPER_LEVELS }.distinct().sortedDescending()

        fun resolveApi(current: Int, available: List<Int>, autoLevel: Int): Int {
            if (current != AUTO && current in ModuleApiInspector.WRAPPER_LEVELS) return current
            val levels = supportedLevels(available)
            return levels.firstOrNull() ?: autoLevel
        }

        val ALL_LEVELS: List<Int> = ModuleApiInspector.WRAPPER_LEVELS.sortedDescending()

        fun optionsFor(available: List<Int>, autoLevel: Int): List<Pair<Int, String>> =
            listOf(AUTO to label(AUTO, resolveApi(AUTO, available, autoLevel))) +
                ALL_LEVELS.map { it to ModuleApiInspector.label(it) }
    }
}
