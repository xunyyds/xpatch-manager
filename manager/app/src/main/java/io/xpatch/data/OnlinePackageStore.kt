package io.xpatch.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

class OnlinePackageStore(private val context: Context) {

    class DownloadError(message: String) : Exception(message)

    data class OnlineApp(
        val packageName: String,
        val appId: String,
        val name: String,
        val source: SoftwareSource = SoftwareSource.WANDOUJIA,
    )

    data class OnlineVersion(
        val packageName: String,
        val appId: String,
        val version: String,
        val versionCode: String,
        val sizeText: String,
        val sizeBytes: Long,
        val releaseTime: Long,
        val releaseText: String,
        val summary: String,
        val detailUrl: String,
        val downloadUrl: String,
        val md5: String,
        val source: SoftwareSource = SoftwareSource.WANDOUJIA,
    )

    data class OnlineEntry(
        val key: String,
        val packageName: String,
        val name: String,
        val version: String,
        val versionCode: String,
        val size: Long,
        val time: Long,
        val path: String,
        val detailUrl: String,
    ) {
        companion object {
            fun keyOf(packageName: String, versionCode: String): String = packageName + ":" + versionCode
        }
    }

    private data class IndexRecord(
        val packageName: String,
        val name: String,
        val version: String,
        val versionCode: String,
        val size: Long,
        val time: Long,
        val path: String,
        val detailUrl: String,
    )

    private data class AppChinaPayload(
        val appName: String,
        val versions: List<OnlineVersion>,
    )

    private val indexFile: File
        get() = File(context.filesDir, "online-packages.json")

    private val readmeDir: File = File(context.filesDir, "online/original")

    private val lock = Any()

    @Volatile
    private var indexCache: Map<String, IndexRecord>? = null

    suspend fun resolve(
        packageName: String,
        source: SoftwareSource = SoftwareSource.AUTO,
    ): OnlineApp? = withContext(Dispatchers.IO) {
        val pkg = packageName.trim()
        if (!PACKAGE_RE.matcher(pkg).matches()) return@withContext null
        when (source) {
            SoftwareSource.APPCHINA -> resolveAppChina(pkg)
            SoftwareSource.WANDOUJIA -> resolveWandoujia(pkg)
            SoftwareSource.AUTO -> runCatching { resolveAppChina(pkg) }.getOrNull()
                ?: resolveWandoujia(pkg)
        }
    }

    private fun resolveWandoujia(pkg: String): OnlineApp? {
        val (finalUrl, html) = request(ALIAS_URL + pkg)
        val appId = findGroup(APP_ID_RE, finalUrl, 1)
            ?: findGroup(APP_ID_RE, html, 1)
            ?: return null
        if (!appId.matches(Regex("\\d+"))) return null
        val name = extractName(html, pkg)
        return OnlineApp(packageName = pkg, appId = appId, name = name)
    }

    private fun resolveAppChina(pkg: String): OnlineApp? {
        val payload = appChinaPayload(pkg, 1, requireDownloadable = false)
        val latest = payload.versions.firstOrNull() ?: return null
        return OnlineApp(
            packageName = pkg,
            appId = latest.versionCode,
            name = payload.appName.ifBlank { pkg },
            source = SoftwareSource.APPCHINA,
        )
    }

    suspend fun history(app: OnlineApp): List<OnlineVersion> = withContext(Dispatchers.IO) {
        if (app.source == SoftwareSource.APPCHINA) {
            return@withContext appChinaPayload(app.packageName, APPCHINA_PAGE_SIZE).versions
        }
        val html = runCatching { request(HISTORY_URL + app.appId + HISTORY_PATH).second }.getOrDefault("")
        val merged = LinkedHashMap<String, OnlineVersion>()
        parseHistory(html, app).forEach { merged.putIfAbsent(it.versionCode, it) }
        val years = YEAR_RE.findAll(html).map { it.groupValues[1] }.distinct().take(12).toList()
        for (year in years) {
            val extra = runCatching {
                request(HISTORY_URL + app.appId + HISTORY_PATH + "_y" + year).second
            }.getOrDefault("")
            parseHistory(extra, app).forEach { merged.putIfAbsent(it.versionCode, it) }
        }
        merged.values.toList().sortedByDescending { it.version }
    }

    suspend fun detail(app: OnlineApp, item: OnlineVersion): OnlineVersion = withContext(Dispatchers.IO) {
        if (item.source == SoftwareSource.APPCHINA) {
            return@withContext appChinaPayload(app.packageName, APPCHINA_PAGE_SIZE).versions
                .firstOrNull { it.versionCode == item.versionCode } ?: item
        }
        val page = runCatching { request(item.detailUrl) }.getOrNull()
        if (page == null) {
            return@withContext item.copy(
                downloadUrl = item.downloadUrl.ifBlank { "https://www.25pp.com/xiazai/" + app.appId + "/history_v" + item.versionCode + "/" },
            )
        }
        val (finalUrl, html) = page
        val plain = htmlToText(html)
        val version = extractAfter(plain, "官方版本号", VERSION_VALUE_RE)?.ifBlank { item.version } ?: item.version
        val releaseText = extractAfter(plain, "更新时间", RELEASE_RE)
            ?.ifBlank { item.releaseText }.orEmpty()
        val releaseTime = parseReleaseTime(releaseText).takeIf { it > 0L }
            ?: dateFromIconPath(html).takeIf { it > 0L }
            ?: item.releaseTime
        var rawDownload = extractDownloadUrl(html, finalUrl)
        if (rawDownload.isBlank()) {
            val code = item.versionCode
            val fallback = runCatching {
                val altUrl = "https://www.25pp.com/xiazai/" + app.appId + "/history_v" + code + "/"
                request(altUrl).second
            }.getOrDefault("")
            rawDownload = extractDownloadUrl(fallback, "https://www.25pp.com/")
        }
        val query = queryOf(rawDownload)
        val sizeBytes = query["size"]?.toLongOrNull()?.takeIf { it > 0L }
            ?: item.sizeBytes.takeIf { it > 0L }
            ?: sizeTextToBytes(extractAfter(plain, "大小", SIZE_VALUE_RE) ?: item.sizeText)
        val md5 = query["md5"].orEmpty()
        val summary = extractSummary(html, plain)
        item.copy(
            version = version,
            sizeText = if (sizeBytes > 0L) bytesToSizeText(sizeBytes) else item.sizeText,
            sizeBytes = sizeBytes,
            releaseTime = releaseTime,
            releaseText = releaseText.ifBlank { formatRelease(releaseTime) },
            summary = summary,
            downloadUrl = rawDownload,
            md5 = md5,
        )
    }

    suspend fun download(
        app: OnlineApp,
        version: OnlineVersion,
        control: DownloadControl = DownloadControl(),
        onProgress: (Int, Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val dir = File(readmeDir, safe(app.packageName)).apply { mkdirs() }
        val safeVersion = safe(version.version.ifBlank { version.versionCode })
        val fileName = app.packageName + "_" + safeVersion + "_" + version.versionCode + ".apk"
        val target = File(dir, fileName)
        val resolved = if (version.source == SoftwareSource.APPCHINA || version.downloadUrl.isBlank()) {
            detail(app, version)
        } else {
            version
        }
        val url = resolved.downloadUrl.ifBlank { throw DownloadError("该版本没有可用的下载链接") }
        val finalUrl = if (resolved.source == SoftwareSource.APPCHINA) appChinaFinalUrl(url) else url
        val ok = Downloader.download(listOf(finalUrl), target, control, USER_AGENT) { progress ->
            val percent = if (progress.total > 0) {
                ((progress.read * 100) / progress.total).toInt().coerceIn(0, 100)
            } else {
                0
            }
            onProgress(percent, progress.bytesPerSecond)
        }
        if (!ok) throw DownloadError("下载失败：" + app.name + " v" + version.version)
        if (resolved.sizeBytes > 0L && target.length() != resolved.sizeBytes) {
            throw DownloadError("安装包大小与页面标注不一致，已停止")
        }
        val expectedMd5 = resolved.md5.trim().lowercase(Locale.US)
        if (expectedMd5.isNotBlank() && !expectedMd5.equals(md5Of(target), ignoreCase = true)) {
            target.delete()
            throw DownloadError("安装包校验失败，请重试")
        }
        recordDownload(app, resolved, target)
        target
    }

    fun downloads(): List<OnlineEntry> {
        val records = readIndex()
        val entries = ArrayList<OnlineEntry>(records.size)
        for ((key, record) in records) {
            val file = File(record.path)
            if (!file.isFile) continue
            entries.add(
                OnlineEntry(
                    key = key,
                    packageName = record.packageName,
                    name = record.name,
                    version = record.version,
                    versionCode = record.versionCode,
                    size = record.size.takeIf { it > 0L } ?: file.length(),
                    time = record.time,
                    path = record.path,
                    detailUrl = record.detailUrl,
                ),
            )
        }
        return entries.sortedByDescending { it.time }
    }

    fun cachedApk(packageName: String, versionCode: String): File? {
        val record = readIndex()[OnlineEntry.keyOf(packageName, versionCode)] ?: return null
        val file = File(record.path)
        return file.takeIf { it.isFile }
    }

    fun findAny(packageName: String): OnlineEntry? =
        downloads().firstOrNull { it.packageName == packageName }

    fun removeDownload(key: String): Boolean {
        val current = readIndex()
        val record = current[key] ?: return false
        runCatching { File(record.path).delete() }
        return writeIndex(current - key)
    }

    fun cacheSize(): Long = runCatching {
        readmeDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    fun clearCache() {
        runCatching {
            readmeDir.deleteRecursively()
            indexFile.delete()
        }
        synchronized(lock) { indexCache = null }
    }

    fun invalidate() {
        synchronized(lock) { indexCache = null }
    }

    private fun recordDownload(app: OnlineApp, version: OnlineVersion, file: File) {
        val key = OnlineEntry.keyOf(app.packageName, version.versionCode)
        val next = readIndex().toMutableMap()
        next[key] = IndexRecord(
            packageName = app.packageName,
            name = app.name,
            version = version.version,
            versionCode = version.versionCode,
            size = file.length(),
            time = System.currentTimeMillis(),
            path = file.absolutePath,
            detailUrl = version.detailUrl,
        )
        writeIndex(next)
    }

    private fun readIndex(): Map<String, IndexRecord> {
        indexCache?.let { return it }
        val parsed = runCatching {
            if (!indexFile.isFile) {
                emptyMap<String, IndexRecord>()
            } else {
                parseIndex(JSONObject(indexFile.readText()))
            }
        }.getOrDefault(emptyMap())
        synchronized(lock) {
            indexCache?.let { return it }
            indexCache = parsed
        }
        return parsed
    }

    private fun parseIndex(json: JSONObject): Map<String, IndexRecord> {
        if (json.length() == 0) return emptyMap()
        val out = HashMap<String, IndexRecord>(json.length() * 2)
        for (key in json.keys()) {
            val item = json.optJSONObject(key) ?: continue
            out[key] = IndexRecord(
                packageName = item.optString("pkg"),
                name = item.optString("name"),
                version = item.optString("version"),
                versionCode = item.optString("code"),
                size = item.optLong("size"),
                time = item.optLong("time"),
                path = item.optString("path"),
                detailUrl = item.optString("detail"),
            )
        }
        return out
    }

    private fun writeIndex(records: Map<String, IndexRecord>): Boolean {
        val json = JSONObject()
        for ((key, record) in records) {
            json.put(
                key,
                JSONObject().apply {
                    put("pkg", record.packageName)
                    put("name", record.name)
                    put("version", record.version)
                    put("code", record.versionCode)
                    put("size", record.size)
                    put("time", record.time)
                    put("path", record.path)
                    put("detail", record.detailUrl)
                },
            )
        }
        val ok = runCatching { indexFile.writeText(json.toString()) }.isSuccess
        synchronized(lock) { indexCache = if (ok) records else null }
        return ok
    }

    private fun request(url: String): Pair<String, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            connection.connect()
            val code = connection.responseCode
            require(code in 200..299) { "豌豆荚请求失败：HTTP $code" }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return Pair(connection.url?.toString().orEmpty(), body)
        } finally {
            connection.disconnect()
        }
    }

    private fun appChinaPayload(pkg: String, size: Int, requireDownloadable: Boolean = true): AppChinaPayload {
        val params = JSONObject().apply {
            put("apiVer", 23)
            put("guid", APPCHINA_GUID)
            put("imei", APPCHINA_DEVICE)
            put("type", "app.pastdetails")
            put("id", 0)
            put("packagename", pkg)
            put("start", 0)
            put("size", size)
            put("clientTime", System.currentTimeMillis())
            put("imglevel", 3)
            put("gpuType", 4)
            put("clientVersionCode", 30061345)
            put("channel", "ac.ex.360")
        }
        val body = "deviceId=" + encode(APPCHINA_DEVICE) +
            "&referer=" + encode(APPCHINA_GUID) +
            "&api=market.MarketAPI" +
            "&param=" + encode(params.toString())
        val connection = URL(APPCHINA_API).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            require(code in 200..299) { "应用汇请求失败：HTTP $code" }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val array = json.optJSONArray("list")
            val out = ArrayList<OnlineVersion>(array?.length() ?: 0)
            var name = ""
            if (array != null) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val versionName = item.optString("versionName")
                    val versionCode = item.optString("versionCode")
                    val apkUrl = item.optString("apkUrl")
                    if (versionName.isBlank() || versionCode.isBlank()) continue
                    if (requireDownloadable) {
                        if (apkUrl.isBlank()) continue
                        if (item.optBoolean("closeDownload") || item.optInt("closeDownload") != 0) continue
                        if (item.optBoolean("offShelf") || item.optInt("offShelf") != 0) continue
                    }
                    if (name.isBlank()) name = item.optString("name")
                    val bytes = item.optLong("size")
                    val date = item.optString("lastUpdate").ifBlank { item.optString("createTime") }
                    out.add(
                        OnlineVersion(
                            packageName = pkg,
                            appId = versionCode,
                            version = versionName,
                            versionCode = versionCode,
                            sizeText = if (bytes > 0L) bytesToSizeText(bytes) else "",
                            sizeBytes = bytes,
                            releaseTime = parseDateText(date),
                            releaseText = date,
                            summary = item.optString("shorDesc"),
                            detailUrl = APPCHINA_DETAIL_URL + pkg,
                            downloadUrl = apkUrl,
                            md5 = item.optString("md5"),
                            source = SoftwareSource.APPCHINA,
                        ),
                    )
                }
            }
            out.sortByDescending { it.versionCode.toLongOrNull() ?: 0L }
            return AppChinaPayload(name, out)
        } finally {
            connection.disconnect()
        }
    }

    private fun appChinaFinalUrl(raw: String): String {
        val start = httpsOf(raw)
        val connection = URL(start).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connect()
            val code = connection.responseCode
            if (code !in 300..399) return start
            val location = connection.getHeaderField("Location") ?: return start
            return httpsOf(location)
        } finally {
            connection.disconnect()
        }
    }

    private fun httpsOf(value: String): String = when {
        value.startsWith("http://") -> "https://" + value.removePrefix("http://")
        value.startsWith("//") -> "https:" + value
        else -> value
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun parseDateText(text: String): Long {
        if (text.isBlank()) return 0L
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            }.parse(text)?.time ?: 0L
        }.getOrDefault(0L)
    }

    private fun parseHistory(html: String, app: OnlineApp): List<OnlineVersion> {
        val matcher = HISTORY_LINK_RE.matcher(html)
        val out = LinkedHashMap<String, OnlineVersion>()
        while (matcher.find()) {
            val code = matcher.group(1) ?: matcher.group(4) ?: continue
            val appId = matcher.group(2) ?: matcher.group(3) ?: continue
            if (appId != app.appId) continue
            val start = matcher.start()
            val end = minOf(start + 600, html.length)
            val chunk = htmlToText(html.substring(start, end))
            val version = VERSION_TOKEN_RE.find(chunk)?.value ?: code
            val sizeText = SIZE_MB_RE.find(chunk)?.let { it.groupValues[1] + "MB" }
                ?: SIZE_KB_RE.find(chunk)?.let { it.groupValues[1] + "KB" }.orEmpty()
            val sizeBytes = sizeTextToBytes(sizeText)
            val detailUrl = DETAIL_URL + appId + "/history_v" + code
            val key = code
            if (!out.containsKey(key)) {
                out[key] = OnlineVersion(
                    packageName = app.packageName,
                    appId = appId,
                    version = version,
                    versionCode = code,
                    sizeText = sizeText,
                    sizeBytes = sizeBytes,
                    releaseTime = 0L,
                    releaseText = "",
                    summary = "",
                    detailUrl = detailUrl,
                    downloadUrl = "",
                    md5 = "",
                )
            }
        }
        return out.values.toList()
    }

    private fun extractDownloadUrl(html: String, baseUrl: String): String {
        val matcher = DOWNLOAD_URL_RE.matcher(html)
        var encoded: String? = null
        while (matcher.find()) {
            val candidate = matcher.group(1) ?: continue
            if (candidate.length > 16) {
                encoded = candidate
                break
            }
        }
        val raw = encoded?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }.orEmpty()
        val normalized = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("//") -> "https:" + raw
            raw.startsWith("/") -> runCatching {
                val url = URL(baseUrl)
                url.protocol + "://" + url.authority + raw
            }.getOrDefault("")
            else -> ""
        }
        return if (normalized.startsWith("http://")) {
            "https://" + normalized.removePrefix("http://")
        } else {
            normalized
        }
    }

    private fun findGroup(pattern: Pattern, text: String, group: Int): String? {
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(group) else null
    }

    private fun queryOf(url: String): Map<String, String> {
        val query = url.substringAfter('?', "")
        if (query.isBlank()) return emptyMap()
        val out = HashMap<String, String>()
        for (part in query.split('&')) {
            val key = part.substringBefore('=', "")
            val value = part.substringAfter('=', "")
            if (key.isNotBlank()) out[key] = value
        }
        return out
    }

    private fun extractAfter(text: String, label: String, valuePattern: Pattern): String? {
        val index = text.indexOf(label)
        if (index < 0) return null
        val tail = text.substring(index + label.length, minOf(index + label.length + 120, text.length))
        val matcher = valuePattern.matcher(tail)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun extractName(html: String, packageName: String): String {
        val matcher = NAME_ATTR_RE.matcher(html)
        if (matcher.find()) {
            val value = (matcher.group(1) ?: matcher.group(2)).orEmpty().trim()
            if (value.isNotBlank()) return value
        }
        val titleMatch = TITLE_RE.matcher(html)
        if (titleMatch.find()) {
            val value = titleMatch.group(1).orEmpty()
                .substringBeforeLast("_")
                .substringBeforeLast("-")
                .trim()
            if (value.isNotBlank()) return value
        }
        return packageName
    }

    private fun extractSummary(html: String, plain: String): String {
        val desc = DESCRIPTION_RE.matcher(html)
        if (desc.find()) {
            val value = htmlToText(desc.group(1).orEmpty()).trim()
            if (value.length in 2..300) return value
        }
        val markers = listOf("截图", "介绍", "网友评论", "相关信息")
        var cursor = plain.indexOf("截图")
        if (cursor < 0) cursor = plain.indexOf("版本介绍")
        if (cursor < 0) return ""
        val body = plain.substring(cursor + 2, minOf(cursor + 400, plain.length))
        val line = body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.length in 4..200 && markers.none { marker -> it.startsWith(marker) } }
        return line.orEmpty()
    }

    private fun dateFromIconPath(html: String): Long {
        val matcher = ICON_DATE_RE.matcher(html)
        if (!matcher.find()) return 0L
        val year = matcher.group(1)?.toIntOrNull() ?: return 0L
        val month = matcher.group(2)?.toIntOrNull() ?: return 0L
        val day = matcher.group(3)?.toIntOrNull() ?: return 0L
        return runCatching {
            java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai")).apply {
                clear()
                set(year, month - 1, day)
            }.timeInMillis
        }.getOrDefault(0L)
    }

    private fun parseReleaseTime(text: String): Long {
        val matcher = RELEASE_FULL_RE.matcher(text)
        if (!matcher.find()) return 0L
        val year = matcher.group(1)?.toIntOrNull() ?: return 0L
        val month = matcher.group(2)?.toIntOrNull() ?: return 0L
        val day = matcher.group(3)?.toIntOrNull() ?: return 0L
        val hour = matcher.group(4)?.toIntOrNull() ?: 0
        val minute = matcher.group(5)?.toIntOrNull() ?: 0
        return runCatching {
            java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai")).apply {
                clear()
                set(year, month - 1, day, hour, minute, 0)
            }.timeInMillis
        }.getOrDefault(0L)
    }

    private fun formatRelease(millis: Long): String {
        if (millis <= 0L) return ""
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(millis))
    }

    private fun sizeTextToBytes(text: String): Long {
        val value = NUMBER_RE.find(text)?.value?.toDoubleOrNull() ?: return 0L
        return when {
            text.contains("GB", true) -> (value * 1024 * 1024 * 1024).toLong()
            text.contains("MB", true) -> (value * 1024 * 1024).toLong()
            text.contains("KB", true) -> (value * 1024).toLong()
            else -> value.toLong()
        }
    }

    private fun bytesToSizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.2fMB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format(Locale.US, "%.0fKB", bytes / 1024.0)
        else -> bytes.toString() + "B"
    }

    private fun htmlToText(html: String): String = html
        .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</(?:p|div|li|h[1-6]|dd|dt|tr)>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace(Regex("[ \t]+"), " ")
        .replace(Regex("\n\\s*\n+"), "\n")

    private fun md5Of(file: File): String = runCatching {
        val digest = java.security.MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { byte ->
            ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1)
        }
    }.getOrDefault("")

    private fun safe(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val ALIAS_URL = "https://www.wandoujia.com/apps/"
        const val HISTORY_URL = "https://www.wandoujia.com/apps/"
        const val HISTORY_PATH = "/history"
        const val DETAIL_URL = "https://www.wandoujia.com/apps/"
        const val APPCHINA_API = "https://mobile.appchina.com/market/api"
        const val APPCHINA_DETAIL_URL = "https://www.appchina.com/app/"
        const val APPCHINA_GUID = "06b15137-908f-4f51-b035-e296d5fda1b6"
        const val APPCHINA_DEVICE = "869394021134371"
        const val APPCHINA_PAGE_SIZE = 100
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        val PACKAGE_RE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
        val APP_ID_RE = Pattern.compile("/apps/(\\d+)")
        val HISTORY_LINK_RE = Pattern.compile("history_v(\\d+)[^\"']{0,40}?apps/(\\d+)|apps/(\\d+)/history_v(\\d+)")
        val VERSION_TOKEN_RE = Regex("(?<!v)(\\d+\\.\\d+(?:\\.\\d+){0,3}[A-Za-z0-9_-]*)")
        val SIZE_MB_RE = Regex("(\\d+(?:\\.\\d+)?)\\s*MB")
        val SIZE_KB_RE = Regex("(\\d+(?:\\.\\d+)?)\\s*KB")
        val NUMBER_RE = Regex("[\\d.]+")
        val DOWNLOAD_URL_RE = Pattern.compile("downloadUrl=([^&\"'\\s<>]+)")
        val RELEASE_RE = Pattern.compile("([0-9]{4}年[0-9]{1,2}月[0-9]{1,2}日(?:\\s+[0-9]{1,2}:[0-9]{2})?)")
        val RELEASE_FULL_RE = Pattern.compile("([0-9]{4})年([0-9]{1,2})月([0-9]{1,2})日(?:\\s+([0-9]{1,2}):([0-9]{2}))?")
        val VERSION_VALUE_RE = Pattern.compile("(v?[0-9][0-9A-Za-z._+-]*)")
        val SIZE_VALUE_RE = Pattern.compile("([\\d.]+\\s*[KMG]B)")
        val NAME_ATTR_RE = Pattern.compile("data-app-name=[\"']([^\"']+)[\"']|data-name=[\"']([^\"']+)[\"']")
        val TITLE_RE = Pattern.compile("<title[^>]*>([\\s\\S]*?)</title>", Pattern.CASE_INSENSITIVE)
        val DESCRIPTION_RE = Pattern.compile("class=[\"'][^\"']*(?:desc|summary|feature)[^\"']*[\"'][^>]*>([\\s\\S]{0,600}?)</", Pattern.CASE_INSENSITIVE)
        val ICON_DATE_RE = Pattern.compile("/fs08/(\\d{4})/(\\d{1,2})/(\\d{1,2})/")
        val YEAR_RE = Regex("history_y(\\d{4})")
    }
}
