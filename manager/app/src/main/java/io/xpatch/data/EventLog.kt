package io.xpatch.data

import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EventLog(context: Context) {

    private val prefs = context.getSharedPreferences("events", Context.MODE_PRIVATE)
    private val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    fun entries(): List<String> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList { for (i in 0 until array.length()) add(array.getString(i)) }
        }.getOrDefault(emptyList())
    }

    fun add(message: String) {
        val line = "${formatter.format(Date())}  $message"
        val updated = (listOf(line) + entries()).take(MAX)
        prefs.edit().putString(KEY, JSONArray(updated).toString()).apply()
    }

    fun clear(): Boolean = prefs.edit().remove(KEY).commit()

    private companion object {
        const val KEY = "entries"
        const val MAX = 200
    }
}

