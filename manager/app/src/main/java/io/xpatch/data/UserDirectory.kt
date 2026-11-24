package io.xpatch.data

import android.content.Context
import android.os.Process
import android.os.UserManager
import io.xpatch.patch.ShizukuBridge

data class XpUser(
    val id: Int,
    val name: String,
    val flags: Int = 0,
) {
    val isCurrent: Boolean get() = id == UserDirectory.currentUserId()

    val label: String get() = name.ifBlank { "用户 $id" }

    val subtitle: String
        get() = "ID $id" + when {
            id == 0 -> " · 主用户"
            isCurrent -> " · 当前"
            else -> " · 分身"
        }
}

object UserDirectory {

    private const val FLAG_PROFILE = 0x1000
    private const val FLAG_DOUBLE_APP = 0x20000000
    private const val FLAG_XSPACE = Int.MIN_VALUE
    private const val PER_USER_RANGE = 100000

    private val lock = Any()
    private var cache: List<XpUser>? = null

    fun currentUserId(): Int = Process.myUid() / PER_USER_RANGE

    fun cached(): List<XpUser> = synchronized(lock) { cache } ?: listOf(currentUser())

    fun refresh(context: Context): List<XpUser> {
        val listed = readShellUsers().ifEmpty { readContextUsers(context) }
        val resolved = listed.ifEmpty { listOf(currentUser()) }
        synchronized(lock) { cache = resolved }
        return resolved
    }

    fun of(id: Int): XpUser = cached().firstOrNull { it.id == id } ?: currentUser()

    fun labelOf(id: Int): String = of(id).label

    fun currentUser(): XpUser {
        val id = currentUserId()
        return XpUser(id, "")
    }

    fun reachableShell(): Boolean = ShizukuBridge.isAvailable() && ShizukuBridge.hasPermission()

    fun channelLabel(): String = when {
        ShizukuBridge.isAvailable() && ShizukuBridge.hasPermission() -> "当前使用 Shizuku"
        ShizukuBridge.isAvailable() -> "Shizuku 可用但未授权"
        else -> "未启用提权通道，只能管理当前用户"
    }

    private fun readShellUsers(): List<XpUser> {
        val raw = ShizukuBridge.exec("cmd user list")
        if (raw.isNullOrBlank()) return emptyList()
        return parse(raw)
    }

    private fun readContextUsers(context: Context): List<XpUser> {
        val manager = context.getSystemService(UserManager::class.java) ?: return emptyList()
        val name = runCatching { manager.userName }.getOrDefault("")
        return listOf(XpUser(currentUserId(), name))
    }

    fun parse(raw: String): List<XpUser> {
        val found = LinkedHashMap<Int, XpUser>()
        raw.lineSequence().forEach { line ->
            val open = line.indexOf('{')
            if (open < 0) return@forEach
            val close = line.indexOf('}', open + 1)
            if (close <= open) return@forEach
            val parts = line.substring(open + 1, close).split(':')
            if (parts.size < 2) return@forEach
            val id = parts[0].trim().toIntOrNull() ?: return@forEach
            val flags = parts.getOrNull(2)?.trim()?.toLongOrNull(16)?.toInt() ?: 0
            found[id] = XpUser(id, parts[1].trim(), flags)
        }
        val current = currentUserId()
        val listed = found.values
            .filter { user -> user.id == current || isAllowedProfile(user.id, user.flags) }
            .sortedBy { it.id }
        return listed.ifEmpty { listOf(currentUser()) }
    }

    private fun isAllowedProfile(id: Int, flags: Int): Boolean {
        if (id == 0 || id == currentUserId()) return true
        if (flags == 0) return false
        if (flags and FLAG_XSPACE != 0) return false
        return flags and FLAG_PROFILE != 0 || flags and FLAG_DOUBLE_APP != 0
    }
}
