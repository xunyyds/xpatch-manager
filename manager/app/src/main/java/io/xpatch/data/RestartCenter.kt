package io.xpatch.data

import android.content.Context
import io.xpatch.notify.AppNotifications
import io.xpatch.patch.ShizukuBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object RestartCenter {

    data class Request(
        val packageName: String,
        val label: String,
        val reason: String,
        val userId: Int = UserDirectory.currentUserId(),
    )

    private const val FILE_NAME = "restart_pending.json"

    private val _pending = MutableStateFlow<List<Request>>(emptyList())
    val pending: StateFlow<List<Request>> = _pending

    private val _dialog = MutableStateFlow<Request?>(null)
    val dialog: StateFlow<Request?> = _dialog

    private val _bannerHidden = MutableStateFlow(false)
    val bannerHidden: StateFlow<Boolean> = _bannerHidden

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        _pending.value = readPending(app)
        AppNotifications.ensureChannels(app)
        _pending.value.forEach { request ->
            runCatching {
                AppNotifications.post(
                    app,
                    AppNotifications.restartId(request.packageName),
                    AppNotifications.restart(app, request.packageName, request.label, request.reason),
                )
            }
        }
    }

    fun request(
        context: Context,
        packageName: String,
        label: String,
        reason: String,
        userId: Int = UserDirectory.currentUserId(),
    ) {
        if (packageName.isBlank()) return
        init(context)
        val request = Request(packageName, label.ifBlank { packageName }, reason, userId)
        _pending.value = _pending.value.filterNot { it.packageName == packageName && it.userId == userId } + request
        _dialog.value = request
        _bannerHidden.value = false
        persist()
        AppNotifications.post(
            context,
            AppNotifications.restartId(packageName, userId),
            AppNotifications.restart(context, request.packageName, request.label, request.reason, userId),
        )
    }

    fun dismissDialog() {
        _dialog.value = null
    }

    fun hideBanner() {
        _bannerHidden.value = true
    }

    fun ignore(context: Context, packageName: String, userId: Int = UserDirectory.currentUserId()) {
        _pending.value = _pending.value.filterNot {
            it.packageName == packageName && it.userId == userId
        }
        if (_dialog.value?.packageName == packageName && _dialog.value?.userId == userId) {
            _dialog.value = null
        }
        if (_pending.value.isEmpty()) {
            _bannerHidden.value = false
        }
        persist()
        AppNotifications.cancel(context, AppNotifications.restartId(packageName, userId))
    }

    fun ignoreAll(context: Context) {
        _pending.value.forEach {
            AppNotifications.cancel(context, AppNotifications.restartId(it.packageName, it.userId))
        }
        _pending.value = emptyList()
        _dialog.value = null
        _bannerHidden.value = false
        persist()
    }

    fun restartNow(context: Context, packageName: String, userId: Int = UserDirectory.currentUserId()) {
        if (restartInternal(context, packageName, userId)) {
            AppNotifications.cancel(context, AppNotifications.restartId(packageName, userId))
        } else {
            toastFailure(context)
        }
    }

    fun restartAll(context: Context) {
        val targets = _pending.value.toList()
        if (targets.isEmpty()) return
        var failed = 0
        targets.forEach { request ->
            if (restartInternal(context, request.packageName, request.userId)) {
                AppNotifications.cancel(context, AppNotifications.restartId(request.packageName, request.userId))
            } else {
                failed++
            }
        }
        if (failed > 0) toastFailure(context)
    }

    private fun restartInternal(context: Context, packageName: String, userId: Int): Boolean {
        val ok = runCatching { ShizukuBridge.restart(context, packageName, userId) }.getOrDefault(false)
        if (ok) {
            _pending.value = _pending.value.filterNot {
                it.packageName == packageName && it.userId == userId
            }
            if (_dialog.value?.packageName == packageName && _dialog.value?.userId == userId) {
                _dialog.value = null
            }
            if (_pending.value.isEmpty()) {
                _bannerHidden.value = false
            }
            persist()
            runCatching { EventLog(context).add("已重启 " + packageName) }
        } else {
            runCatching { EventLog(context).add("重启失败：请先授权 Shizuku（" + packageName + "）") }
        }
        return ok
    }

    private fun toastFailure(context: Context) {
        runCatching {
            android.widget.Toast.makeText(context, "重启失败：请先授权 Shizuku", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun readPending(context: Context): List<Request> = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText())
        val list = ArrayList<Request>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val packageName = item.optString("packageName")
            if (packageName.isBlank()) continue
            list += Request(
                packageName = packageName,
                label = item.optString("label").ifBlank { packageName },
                reason = item.optString("reason"),
                userId = item.optInt("userId", UserDirectory.currentUserId()),
            )
        }
        list
    }.getOrDefault(emptyList())

    private fun persist() {
        val context = appContext ?: return
        runCatching {
            val array = JSONArray()
            _pending.value.forEach { request ->
                array.put(
                    JSONObject().apply {
                        put("packageName", request.packageName)
                        put("label", request.label)
                        put("reason", request.reason)
                        put("userId", request.userId)
                    },
                )
            }
            File(context.filesDir, FILE_NAME).writeText(array.toString())
        }
    }
}
