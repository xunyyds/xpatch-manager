package io.xpatch.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模块熔断事件（"某模块导致某应用频繁闪退，已移除"）。
 *
 * 不变量：
 *  1) 唯一数据源是内存里的 [events]（StateFlow），SharedPreferences 只是持久化副本——
 *     旧实现"读 prefs → 改 → 写回"没有锁，广播线程与 UI 线程并发写会丢更新，首页也收不到通知。
 *  2) 所有写操作都在 [LOCK] 内完成"读-改-写-发布"，读操作只读 [events]。
 *  3) 落盘用 commit()：事件是用户唯一的处置证据，apply() 的异步落盘在进程被回收时会丢。
 */
object ModuleGuardStore {

    data class Event(
        val appPackage: String,
        val appLabel: String,
        val modulePackage: String,
        val moduleLabel: String,
        val time: Long,
        /**
         * 作用域文件是否真的写成功。false 表示"内存里移除了、磁盘上没写成功"，
         * UI 与通知必须据此换文案，不能一律宣称"已自动移除"。
         */
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

    /** 兼容旧调用点：先确保已从磁盘载入，再返回当前快照。 */
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

    /** 作用域文件写入失败时回填标记，让首页横幅与通知说真话。 */
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

    /** "×"= 全部忽略：一次清空所有事件（旧实现只清第一条，横幅却显示"N 个应用"）。 */
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
                    // 包名为空的事件无法回查、也无法操作，直接丢弃（旧版本可能写下脏数据）。
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
