package io.xpatch.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object ModuleGuardStore {

    data class Event(
        val appPackage: String,
        val appLabel: String,
        val modulePackage: String,
        val moduleLabel: String,
        val time: Long,
    )

    private const val PREFS = "module_guard"
    private const val KEY = "events"
    private const val MAX = 20

    fun record(context: Context, event: Event) {
        val events = read(context)
        events.removeAll { it.appPackage == event.appPackage && it.modulePackage == event.modulePackage }
        events.add(0, event)
        save(context, events.take(MAX))
    }

    fun list(context: Context): List<Event> = read(context)

    fun dismiss(context: Context, appPackage: String, modulePackage: String) {
        val events = read(context)
        events.removeAll { it.appPackage == appPackage && it.modulePackage == modulePackage }
        save(context, events)
    }

    fun clear(context: Context) = save(context, emptyList())

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context): MutableList<Event> {
        val raw = runCatching { prefs(context).getString(KEY, null) }.getOrNull() ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        Event(
                            appPackage = item.optString("app"),
                            appLabel = item.optString("appLabel"),
                            modulePackage = item.optString("module"),
                            moduleLabel = item.optString("moduleLabel"),
                            time = item.optLong("time"),
                        ),
                    )
                }
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun save(context: Context, events: List<Event>) {
        val array = JSONArray()
        events.forEach { event ->
            array.put(
                JSONObject()
                    .put("app", event.appPackage)
                    .put("appLabel", event.appLabel)
                    .put("module", event.modulePackage)
                    .put("moduleLabel", event.moduleLabel)
                    .put("time", event.time),
            )
        }
        runCatching { prefs(context).edit().putString(KEY, array.toString()).apply() }
    }
}
