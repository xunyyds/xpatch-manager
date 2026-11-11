package io.xpatch.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

object ModuleGuardStore {

    data class Event(
        val appPackage: String,
        val appLabel: String,
        val modulePackage: String,
        val moduleLabel: String,
        val time: Long,
        val scopeWritten: Boolean = true,
    )

    private const val PREFS = "module_guard"
    private const val KEY = "events"
    private const val MAX = 20

    private val LOCK = Any()

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events

    @Volatile
    private var loaded = false

    fun init(context: Context) {
        val app = context.applicationContext
        synchronized(LOCK) {
            if (loaded) return
            _events.value = read(app)
            loaded = true
        }
    }

    fun list(context: Context): List<Event> {
        init(context)
        return _events.value
    }

    fun record(context: Context, event: Event) {
        val app = context.applicationContext
        init(app)
        synchronized(LOCK) {
            val next = _events.value
                .filterNot { it.appPackage == event.appPackage && it.modulePackage == event.modulePackage }
                .toMutableList()
            next.add(0, event)
            publish(app, next.take(MAX))
        }
    }

    fun markSyncFailed(context: Context, appPackage: String, modulePackage: String) {
        val app = context.applicationContext
        init(app)
        synchronized(LOCK) {
            val current = _events.value
            if (current.none { it.appPackage == appPackage && it.modulePackage == modulePackage && it.scopeWritten }) {
                return
            }
            publish(
                app,
                current.map {
                    if (it.appPackage == appPackage && it.modulePackage == modulePackage) {
                        it.copy(scopeWritten = false)
                    } else {
                        it
                    }
                },
            )
        }
    }

    fun dismiss(context: Context, appPackage: String, modulePackage: String) {
        val app = context.applicationContext
        init(app)
        synchronized(LOCK) {
            val next = _events.value.filterNot {
                it.appPackage == appPackage && it.modulePackage == modulePackage
            }
            publish(app, next)
        }
    }

    fun clear(context: Context) {
        val app = context.applicationContext
        init(app)
        synchronized(LOCK) { publish(app, emptyList()) }
    }

    private fun publish(context: Context, events: List<Event>) {
        _events.value = events
        save(context, events)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context): List<Event> {
        val raw = runCatching { prefs(context).getString(KEY, null) }.getOrNull() ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val app = item.optString("app")
                    val module = item.optString("module")
                    if (app.isEmpty() || module.isEmpty()) continue
                    add(
                        Event(
                            appPackage = app,
                            appLabel = item.optString("appLabel").ifEmpty { app },
                            modulePackage = module,
                            moduleLabel = item.optString("moduleLabel").ifEmpty { module },
                            time = item.optLong("time", 0L),
                            scopeWritten = item.optBoolean("scopeWritten", true),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
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
                    .put("time", event.time)
                    .put("scopeWritten", event.scopeWritten),
            )
        }
        runCatching { prefs(context).edit().putString(KEY, array.toString()).commit() }
    }
}
