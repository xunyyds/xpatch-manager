package io.xpatch.data

import android.content.Context
import io.xpatch.notify.AppNotifications
import io.xpatch.patch.InstallHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object DownloadCenter {

    enum class Kind { UPDATE, MODULE }

    data class Task(
        val id: Long,
        val kind: Kind,
        val title: String,
        val percent: Int = 0,
        val speed: Long = 0L,
        val paused: Boolean = false,
        val done: Boolean = false,
        val failed: String? = null,
    )

    sealed interface Event {
        data class ModuleReady(val module: RepoStore.RepoModule, val file: File) : Event
        data class Failed(val module: RepoStore.RepoModule?, val message: String) : Event
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow<Map<Long, Task>>(emptyMap())
    val tasks: StateFlow<Map<Long, Task>> = _tasks
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events

    private val controls = ConcurrentHashMap<Long, DownloadControl>()
    private val lastNotifyAt = ConcurrentHashMap<Long, Long>()
    private val nextId = AtomicLong(1L)
    private var appContext: Context? = null

    fun startUpdate(context: Context, release: UpdateChecker.ReleaseInfo): Long {
        val app = context.applicationContext
        appContext = app
        AppNotifications.ensureChannels(app)
        val id = nextId.getAndIncrement()
        val control = DownloadControl()
        controls[id] = control
        putTask(Task(id, Kind.UPDATE, "正在下载 XPatch v" + release.version))
        scope.launch {
            val apk = runCatching {
                UpdateChecker.download(app, release, control) { percent, speed ->
                    onProgress(id, percent, speed)
                }
            }.getOrNull()
            controls.remove(id)
            if (apk != null) {
                finishTask(id, done = true)
                AppNotifications.post(
                    app,
                    AppNotifications.downloadId(id),
                    AppNotifications.installReady(app, id, "XPatch v" + release.version + " 下载完成", apk),
                )
                InstallHelper.installWithSystem(app, apk)
            } else if (control.cancelled) {
                updateTask(id) { it.copy(failed = "已取消") }
            } else {
                failTask(id, "下载失败，请检查网络后重试")
            }
        }
        return id
    }

    fun startModule(context: Context, repoStore: RepoStore, module: RepoStore.RepoModule): Long {
        val app = context.applicationContext
        appContext = app
        AppNotifications.ensureChannels(app)
        val id = nextId.getAndIncrement()
        val control = DownloadControl()
        controls[id] = control
        putTask(Task(id, Kind.MODULE, "正在下载 " + module.name))
        scope.launch {
            val file = runCatching {
                repoStore.download(module, control) { percent, speed ->
                    onProgress(id, percent, speed)
                }
            }.getOrNull()
            controls.remove(id)
            if (file != null) {
                finishTask(id, done = true)
                AppNotifications.cancel(app, AppNotifications.downloadId(id))
                _events.emit(Event.ModuleReady(module, file))
            } else if (control.cancelled) {
                updateTask(id) { it.copy(failed = "已取消") }
            } else {
                failTask(id, "下载失败：" + module.name)
                _events.emit(Event.Failed(module, "下载失败"))
            }
        }
        return id
    }

    fun pause(id: Long) {
        val control = controls[id] ?: return
        control.paused = true
        updateTask(id) { it.copy(paused = true) }
        refreshNotification(id)
    }

    fun resume(id: Long) {
        val control = controls[id] ?: return
        control.paused = false
        updateTask(id) { it.copy(paused = false) }
        refreshNotification(id)
    }

    fun cancel(id: Long) {
        val control = controls[id] ?: return
        control.cancelled = true
        controls.remove(id)
        val app = appContext
        if (app != null) AppNotifications.cancel(app, AppNotifications.downloadId(id))
        failTask(id, "已取消")
    }

    fun taskOf(id: Long): Task? = _tasks.value[id]

    private fun onProgress(id: Long, percent: Int, speed: Long) {
        val task = _tasks.value[id] ?: return
        if (task.paused) return
        updateTask(id) { it.copy(percent = percent, speed = speed) }
        val now = System.currentTimeMillis()
        val last = lastNotifyAt[id] ?: 0L
        if (now - last >= 900L) {
            lastNotifyAt[id] = now
            refreshNotification(id)
        }
    }

    private fun refreshNotification(id: Long) {
        val app = appContext ?: return
        val task = _tasks.value[id] ?: return
        if (task.done) return
        val percent = task.percent.coerceIn(0, 100)
        val text = when {
            task.failed != null -> task.failed
            task.paused -> "已暂停 · " + percent + "%"
            task.speed > 0 -> percent.toString() + "% · " + readableSpeed(task.speed)
            else -> percent.toString() + "%"
        }
        AppNotifications.post(
            app,
            AppNotifications.downloadId(id),
            AppNotifications.download(
                context = app,
                taskId = id,
                title = task.title,
                text = text ?: "",
                percent = percent,
                paused = task.paused,
                indeterminate = task.percent <= 0 && !task.paused,
            ),
        )
    }

    private fun putTask(task: Task) {
        _tasks.value = _tasks.value + (task.id to task)
    }

    private fun updateTask(id: Long, transform: (Task) -> Task) {
        val current = _tasks.value[id] ?: return
        _tasks.value = _tasks.value + (id to transform(current))
    }

    private fun finishTask(id: Long, done: Boolean) {
        updateTask(id) { it.copy(done = done, percent = if (done) 100 else it.percent, speed = 0L) }
    }

    private fun failTask(id: Long, message: String) {
        updateTask(id) { it.copy(failed = message, paused = false) }
        val app = appContext
        if (app != null) AppNotifications.cancel(app, AppNotifications.downloadId(id))
    }

    private fun readableSpeed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return "0 B/s"
        val kb = bytesPerSecond / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.0f KB/s", kb)
        return String.format(java.util.Locale.US, "%.1f MB/s", kb / 1024.0)
    }
}
