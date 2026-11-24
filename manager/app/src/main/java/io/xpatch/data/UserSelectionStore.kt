package io.xpatch.data

import android.content.Context
import android.os.SystemClock
import io.xpatch.patch.ShizukuBridge

class UserSelectionStore(context: Context) {

    private val prefs = context.getSharedPreferences("user_view", Context.MODE_PRIVATE)

    fun of(slot: String): Int {
        val stored = prefs.getInt("slot_$slot", -1)
        return if (stored < 0) UserDirectory.currentUserId() else stored
    }

    fun set(slot: String, userId: Int) =
        prefs.edit().putInt("slot_$slot", userId).apply()

    fun allSlots(): Map<String, Int> = SLOTS.associateWith { of(it) }

    companion object {
        const val HOME = "home"
        const val MODULES = "modules"
        const val LOGS = "logs"

        val SLOTS = listOf(HOME, MODULES, LOGS)
    }
}

object UserPackages {

    private val lock = Any()
    private val cache = HashMap<Int, Entry>()

    private class Entry(val packages: Set<String>, val at: Long)

    fun packagesOf(userId: Int): Set<String>? {
        if (userId == UserDirectory.currentUserId()) return null
        synchronized(lock) {
            cache[userId]?.let { entry -> return entry.packages }
        }
        return refresh(userId)
    }

    fun refresh(userId: Int): Set<String>? {
        if (userId == UserDirectory.currentUserId()) return null
        val now = SystemClock.elapsedRealtime()
        val listed = runCatching { ShizukuBridge.exec("pm list packages --user $userId") }.getOrNull()
        if (listed.isNullOrBlank()) return null
        val parsed = listed.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (parsed.isEmpty()) return null
        synchronized(lock) { cache[userId] = Entry(parsed, now) }
        return parsed
    }

    fun refreshAll(users: Collection<Int>) {
        synchronized(lock) { cache.clear() }
        users.forEach { refresh(it) }
    }

    fun visibleIn(userId: Int, packages: Collection<String>): Set<String> {
        val listed = packagesOf(userId) ?: return packages.toSet()
        return packages.filterTo(LinkedHashSet()) { it in listed }
    }

    fun invalidate(userId: Int? = null) {
        synchronized(lock) {
            if (userId == null) cache.clear() else cache.remove(userId)
        }
    }
}