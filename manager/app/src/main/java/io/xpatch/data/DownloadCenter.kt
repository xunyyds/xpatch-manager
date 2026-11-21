package io.xpatch.data

import android.content.Context
import org.json.JSONObject
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

    enum class Kind { UPDATE, MODULE, ONLINE }

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
        data class OnlineReady(val packageName: String, val version: OnlinePackageStore.OnlineVersion, val file: File) : Event
        data class OnlineFailed(val packageName: String, val version: OnlinePackageStore.OnlineVersion, val message: String) : Event
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow<Map<Long, Task>>(emptyMap())
    val tasks: StateFlow<Map<Long, Task>> = _tasks
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events
    private val _openDialog = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val openDialog: SharedFlow<Long> = _openDialog

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

    fun startModule(
        context: Context,
        repoStore: RepoStore,
        module: RepoStore.RepoModule,
        version: RepoStore.RemoteVersion? = null,
    ): Long {
        val app = context.applicationContext
        appContext = app
        AppNotifications.ensureChannels(app)
        val id = nextId.getAndIncrement()
        val control = DownloadControl()
        controls[id] = control
        val label = if (version != null) module.name + " v" + version.version else module.name
        putTask(Task(id, Kind.MODULE, "正在下载 " + label))
        scope.launch {
            val file = runCatching {
                repoStore.download(module, control, version) { percent, speed ->
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

    fun startOnline(
        context: Context,
        store: OnlinePackageStore,
        app: OnlinePackageStore.OnlineApp,
        version: OnlinePackageStore.OnlineVersion,
    ): Long {
        val ctx = context.applicationContext
        appContext = ctx
        AppNotifications.ensureChannels(ctx)
        val id = nextId.getAndIncrement()
        val control = DownloadControl()
        controls[id] = control
        val label = app.name + " v" + version.version
        putTask(Task(id, Kind.ONLINE, "正在下载原包 " + label))
        saveOnlineTask(app, version, id)
        scope.launch {
            val result = runCatching {
                store.download(app, version, control) { percent, speed ->
                    onProgress(id, percent, speed)
                }
            }
            val file = result.getOrNull()
            val failure = result.exceptionOrNull()
            controls.remove(id)
            if (file != null) {
                finishTask(id, done = true)
                _events.emit(Event.OnlineReady(app.packageName, version, file))
            } else if (control.cancelled) {
                updateTask(id) { it.copy(failed = "已取消") }
                _events.emit(Event.OnlineFailed(app.packageName, version, "已取消"))
            } else {
                val message = (failure as? OnlinePackageStore.DownloadError)?.message
                    ?: "下载失败，请检查网络后重试"
                failTask(id, message)
                _events.emit(Event.OnlineFailed(app.packageName, version, message))
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

    fun requestOpenDialog(id: Long) {
        if (_tasks.value[id] == null) return
        _openDialog.tryEmit(id)
    }

    fun saveOnlineTask(app: OnlinePackageStore.OnlineApp, version: OnlinePackageStore.OnlineVersion, id: Long) {
        val ctx = appContext ?: return
        val key = "online_task_" + id
        val json = JSONObject().apply {
            put("pkg", app.packageName)
            put("appId", app.appId)
            put("name", app.name)
            put("version", version.version)
            put("code", version.versionCode)
            put("sizeText", version.sizeText)
            put("sizeBytes", version.sizeBytes)
            put("releaseText", version.releaseText)
            put("detailUrl", version.detailUrl)
            put("downloadUrl", version.downloadUrl)
            put("summary", version.summary)
            put("source", version.source.key)
        }
        ctx.getSharedPreferences("download_tasks", Context.MODE_PRIVATE)
            .edit().putString(key, json.toString()).apply()
    }

    fun onlineTaskOf(id: Long): Pair<OnlinePackageStore.OnlineApp, OnlinePackageStore.OnlineVersion>? {
        val ctx = appContext ?: return null
        val raw = ctx.getSharedPreferences("download_tasks", Context.MODE_PRIVATE)
            .getString("online_task_" + id, null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val app = OnlinePackageStore.OnlineApp(
            packageName = json.optString("pkg"),
            appId = json.optString("appId"),
            name = json.optString("name"),
            source = SoftwareSource.of(json.optString("source")),
        )
        val version = OnlinePackageStore.OnlineVersion(
            packageName = json.optString("pkg"),
            appId = json.optString("appId"),
            version = json.optString("version"),
            versionCode = json.optString("code"),
            sizeText = json.optString("sizeText"),
            sizeBytes = json.optLong("sizeBytes"),
            releaseTime = 0L,
            releaseText = json.optString("releaseText"),
            summary = json.optString("summary"),
            detailUrl = json.optString("detailUrl"),
            downloadUrl = json.optString("downloadUrl"),
            md5 = "",
            source = SoftwareSource.of(json.optString("source")),
        )
        return Pair(app, version)
    }

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
        if (task.kind == Kind.ONLINE) {
            AppNotifications.post(
                app,
                AppNotifications.downloadId(id),
                AppNotifications.onlineDownload(
                    context = app,
                    taskId = id,
                    title = task.title,
                    text = text,
                    percent = percent,
                    indeterminate = task.percent <= 0,
                ),
            )
        } else {
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
        val app = appContext
        val task = _tasks.value[id]
        if (done && app != null && task?.kind == Kind.ONLINE) {
            AppNotifications.post(
                app,
                AppNotifications.downloadId(id),
                AppNotifications.onlineReady(app, id, task.title),
            )
        }
    }

    private fun failTask(id: Long, message: String) {
        updateTask(id) { it.copy(failed = message, paused = false) }
        val app = appContext
        val task = _tasks.value[id]
        if (app == null || message == "已取消") return
        if (task?.kind == Kind.ONLINE) {
            AppNotifications.post(
                app,
                AppNotifications.downloadId(id),
                AppNotifications.onlineFailed(app, id, task.title, message),
            )
        } else {
            AppNotifications.cancel(app, AppNotifications.downloadId(id))
        }
    }

    private fun readableSpeed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return "0 B/s"
        val kb = bytesPerSecond / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.0f KB/s", kb)
        return String.format(java.util.Locale.US, "%.1f MB/s", kb / 1024.0)
    }
}
