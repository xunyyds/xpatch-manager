package io.xpatch.data

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 管理器自身的操作日志（"某时某刻做了什么"）。
 *
 * 不再按条数截断：日志页已经有搜索与分类，条数上限只会让用户在排查时
 * 找不到那条关键记录。存储改为滚动文件，避免 SharedPreferences 随日志膨胀。
 *
 * 并发安全：操作日志的写入口不止一处（崩溃上报、导出、设置保存……），
 * 而旧版迁移是"整文件 writeText"、追加是"appendText"，两者交错会把刚写进去的行整段覆盖掉。
 * 因此：
 * 1. 迁移与追加共用一把进程级锁 [LOCK]（[EventLog] 会被反复 new 出来，锁必须是 companion 级的）；
 * 2. 迁移成功后落一个 `.migrated` 标记文件，保证 prefs → 文件只发生一次，
 *    即使日后文件被清空也不会把旧记录再灌回来。
 */
class EventLog(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("events", Context.MODE_PRIVATE)
    private val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    /** 操作日志目录：filesDir/events。 */
    private val dir: File
        get() = File(appContext.filesDir, EVENTS_DIR)

    private val file: File
        get() = File(dir, LOG_FILE)

    /** 迁移完成标记，存在即表示"prefs 里的旧记录已经搬完了"。 */
    private val migratedMarker: File
        get() = File(dir, MIGRATED_MARKER)

    /** 按"最新在前"返回全部记录。 */
    fun entries(): List<String> = synchronized(LOCK) {
        val fromFile = readLinesLocked()
        if (fromFile.isNotEmpty()) return@synchronized fromFile.asReversed()
        // 文件是空的：尝试一次性迁移旧版本存在 SharedPreferences 里的记录。
        migrateLegacyLocked()
    }

    fun add(message: String) {
        synchronized(LOCK) {
            val line = formatter.format(Date()) + "  " + message
            runCatching {
                dir.mkdirs()
                // 逐行追加；entries() 读时做反转，所以这里写正序
                file.appendText(line + "\n", Charsets.UTF_8)
            }.onFailure {
                // 文件不可写时退回旧通道，保证这条记录不丢
                val updated = listOf(line) + entries()
                prefs.edit().putString(KEY, JSONArray(updated).toString()).apply()
            }
        }
    }

    /**
     * 清空操作日志：整个 `filesDir/events` 目录一起删。
     *
     * 只删单个文件的话，`.migrated` 标记、将来新增的轮转文件都会残留，
     * 于是"清空"之后还能在日志页看到旧内容。
     */
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

    /**
     * 旧版 SharedPreferences → 文件的一次性迁移。
     *
     * @return 迁移出来的记录（"最新在前"，与 [entries] 的返回方向一致）；
     *   没有可迁移内容时返回空列表。写盘失败时仍然把内存里的记录返回给 UI，
     *   只是不落标记，下次还会重试。
     */
    private fun migrateLegacyLocked(): List<String> {
        if (migratedMarker.isFile) {
            // 已经迁移过：文件被清空是用户行为，不能把旧记录再灌回来。
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
            // prefs 里是"最新在前"，文件里要"最旧在前"，末尾补一个换行以便后续 append 不粘行。
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

        /**
         * 进程级互斥锁：[EventLog] 没有单例（MainActivity 每次都 new 一个），
         * 实例锁拦不住跨实例的"迁移 vs 追加"竞争。
         */
        val LOCK = Any()
    }
}
