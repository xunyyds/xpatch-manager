package io.xpatch.data

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EventLog(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("events", Context.MODE_PRIVATE)
    private val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    private val dir: File
        get() = File(appContext.filesDir, EVENTS_DIR)

    private val file: File
        get() = File(dir, LOG_FILE)

    private val migratedMarker: File
        get() = File(dir, MIGRATED_MARKER)

    fun entries(): List<String> = synchronized(LOCK) {
        val fromFile = readLinesLocked()
        if (fromFile.isNotEmpty()) return@synchronized fromFile.asReversed()
        migrateLegacyLocked()
    }

    fun add(message: String) {
        synchronized(LOCK) {
            val line = formatter.format(Date()) + "  " + message
            runCatching {
                dir.mkdirs()
                file.appendText(line + "\n", Charsets.UTF_8)
            }.onFailure {
                val updated = listOf(line) + entries()
                prefs.edit().putString(KEY, JSONArray(updated).toString()).apply()
            }
        }
    }

    fun clear(): Boolean = synchronized(LOCK) {
        val removedDir = runCatching {
            if (!dir.exists()) true else dir.deleteRecursively()
        }.getOrDefault(false)
        val removedPrefs = prefs.edit().remove(KEY).commit()
        removedDir || removedPrefs
    }

    private fun readLinesLocked(): List<String> = runCatching {
        if (!file.isFile) emptyList() else file.readLines(Charsets.UTF_8).filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    private fun migrateLegacyLocked(): List<String> {
        if (migratedMarker.isFile) {
            if (prefs.contains(KEY)) prefs.edit().remove(KEY).apply()
            return emptyList()
        }
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val legacy = runCatching {
            val array = JSONArray(raw)
            buildList { for (i in 0 until array.length()) add(array.getString(i)) }
        }.getOrDefault(emptyList())
        if (legacy.isEmpty()) {
            prefs.edit().remove(KEY).apply()
            return emptyList()
        }
        runCatching {
            dir.mkdirs()
            file.writeText(legacy.asReversed().joinToString("\n") + "\n", Charsets.UTF_8)
            migratedMarker.writeText(MARKER_CONTENT, Charsets.UTF_8)
        }.onSuccess {
            prefs.edit().remove(KEY).apply()
        }
        return legacy
    }

    private companion object {
        const val KEY = "entries"
        const val EVENTS_DIR = "events"
        const val LOG_FILE = "operations.log"
        const val MIGRATED_MARKER = ".migrated"
        const val MARKER_CONTENT = "1"

        val LOCK = Any()
    }
}
