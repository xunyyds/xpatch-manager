package io.xpatch.data

import android.content.Context
import io.xpatch.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope

object UpdateChecker {

    const val REPO_URL = "https://github.com/xunyyds/xpatch-manager"
    private const val GITHUB_API = "https://api.github.com/repos/xunyyds/xpatch-manager/releases/latest"
    private const val GITHUB_API_LIST = "https://api.github.com/repos/xunyyds/xpatch-manager/releases?per_page=20"

    data class ReleaseInfo(
        val tagName: String,
        val version: String,
        val notes: String,
        val apkName: String,
        val apkUrl: String,
        val apkSize: Long,
        val htmlUrl: String,
        val prerelease: Boolean = false,
    )

    sealed interface CheckResult {
        data object UpToDate : CheckResult

        data class Available(val release: ReleaseInfo) : CheckResult

        data class Failed(val message: String) : CheckResult
    }

    private val MIRROR_PREFIXES = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://gh.sevencdn.com/",
    )

    private const val CONNECT_TIMEOUT = 1800
    private const val READ_TIMEOUT = 4500
    private const val RACE_TIMEOUT_MS = 6500L
    private const val FAN_OUT_DELAY_MS = 1000L
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val USER_AGENT = "XPatch/" + BuildConfig.VERSION_NAME

    suspend fun check(context: Context, force: Boolean = false): CheckResult = withContext(Dispatchers.IO) {
        val store = SettingsStore(context)
        val settings = store.load()
        val now = System.currentTimeMillis()
        val beta = settings.receiveBeta
        if (!force && settings.updateCheckCache.isNotBlank() &&
            now - settings.lastUpdateCheckAt < CHECK_INTERVAL_MS
        ) {
            parseBest(settings.updateCheckCache, beta)?.let { return@withContext judge(it) }
        }

        val body = fetchLatest(store, beta)
        if (body == null) {
            if (settings.updateCheckCache.isNotBlank()) {
                parseBest(settings.updateCheckCache, beta)?.let { return@withContext judge(it) }
            }
            return@withContext CheckResult.Failed("网络不可用，或所有检查源均失败，请稍后重试")
        }
        val release = parseBest(body, beta)
            ?: return@withContext CheckResult.Failed("更新信息解析失败")
        store.setUpdateCheckCache(now, body)
        judge(release)
    }

    suspend fun download(
        context: Context,
        release: ReleaseInfo,
        control: DownloadControl = DownloadControl(),
        onProgress: (Int, Long) -> Unit = { _, _ -> },
    ): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val target = File(dir, release.apkName)
        val tmp = File(dir, release.apkName + ".tmp")
        val urls = buildList {
            MIRROR_PREFIXES.forEach { add(it + release.apkUrl) }
            add(release.apkUrl)
        }
        val ok = Downloader.download(urls, tmp, control, USER_AGENT) { progress ->
            val percent = if (progress.total > 0) {
                ((progress.read * 100) / progress.total).toInt().coerceIn(0, 100)
            } else {
                0
            }
            onProgress(percent, progress.bytesPerSecond)
        }
        if (!ok) return@withContext null
        runCatching { target.delete() }
        if (tmp.renameTo(target)) target else null
    }

    fun compareVersion(left: String, right: String): Int {
        val l = left.trim().removePrefix("v").removePrefix("V").split('.')
        val r = right.trim().removePrefix("v").removePrefix("V").split('.')
        for (index in 0 until maxOf(l.size, r.size)) {
            val a = l.getOrNull(index)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            val b = r.getOrNull(index)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            if (a != b) return a - b
        }
        return 0
    }

    private fun judge(release: ReleaseInfo): CheckResult =
        if (compareVersion(release.version, BuildConfig.VERSION_NAME) > 0) {
            CheckResult.Available(release)
        } else {
            CheckResult.UpToDate
        }

    private suspend fun fetchLatest(store: SettingsStore, beta: Boolean): String? {
        val preferred = store.load().updateGoodSource
        val api = if (beta) GITHUB_API_LIST else GITHUB_API
        val urls = buildList {
            if (preferred.isNotBlank()) add(preferred + api)
            add(api)
            MIRROR_PREFIXES.forEach { add(it + api) }
        }.distinct()
        val jobScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val hit = Channel<String>(1)
        val stop = AtomicBoolean(false)
        urls.forEachIndexed { index, url ->
            jobScope.launch {
                if (index > 0) delay(FAN_OUT_DELAY_MS)
                if (stop.get()) return@launch
                val body = runCatching { httpGet(url) }.getOrNull() ?: return@launch
                if (stop.get()) return@launch
                if (parseBest(body, beta) != null && stop.compareAndSet(false, true)) {
                    store.setUpdateGoodSource(url.removeSuffix(api))
                    hit.trySend(body)
                }
            }
        }
        val body = withTimeoutOrNull(RACE_TIMEOUT_MS) { hit.receive() }
        stop.set(true)
        jobScope.cancel()
        return body
    }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connect()
            require(connection.responseCode in 200..299) { "HTTP " + connection.responseCode }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseBest(body: String, beta: Boolean): ReleaseInfo? {
        val single = parseReleaseObject(body)
        if (single != null) {
            if (single.prerelease && !beta) return null
            return single
        }
        val list = runCatching {
            val array = org.json.JSONArray(body)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { parseReleaseObject(it) }
            }
        }.getOrNull() ?: return null
        val usable = list.filter { beta || !it.prerelease }
        return usable.maxWithOrNull { a, b -> compareVersion(a.version, b.version) }
    }

    private fun parseRelease(body: String): ReleaseInfo? = parseReleaseObject(body)

    private fun parseReleaseObject(body: String): ReleaseInfo? = runCatching {
        org.json.JSONObject(body).let { parseReleaseObject(it) }
    }.getOrNull()

    private fun parseReleaseObject(root: JSONObject): ReleaseInfo? = runCatching {
        val assets = root.optJSONArray("assets") ?: return@runCatching null
        var apkName = ""
        var apkUrl = ""
        var apkSize = 0L
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            if (name.startsWith("XPatch-manager-") && name.endsWith(".apk")) {
                apkName = name
                apkUrl = asset.optString("browser_download_url")
                apkSize = asset.optLong("size")
                break
            }
        }
        if (apkUrl.isEmpty()) return@runCatching null
        val tag = root.optString("tag_name")
        ReleaseInfo(
            tagName = tag,
            version = tag.removePrefix("v").removePrefix("V"),
            notes = root.optString("body"),
            apkName = apkName,
            apkUrl = apkUrl,
            apkSize = apkSize,
            htmlUrl = root.optString("html_url"),
            prerelease = root.optBoolean("prerelease", false),
        )
    }.getOrNull()

}
