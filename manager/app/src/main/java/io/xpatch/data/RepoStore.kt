package io.xpatch.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class RepoStore(private val context: Context) {

    data class RepoModule(
        val packageName: String,
        val name: String,
        val version: String,
        val versionCode: Long,
        val summary: String,
        val author: String,
        val updateTime: Long,
        val createTime: Long,
        val supports89: Boolean,
        val supports100: Boolean,
        val legacyMax: Int,
        val lspMax: Int,
        val scope89: List<String>,
        val scope100: List<String>,
    )

    data class RemoteVersion(
        val version: String,
        val tag: String,
        val fileName: String,
        val updateTime: Long,
    )

    data class DownloadEntry(
        val key: String,
        val packageName: String,
        val version: String,
        val tag: String,
        val fileName: String,
        val path: String,
        val size: Long,
        val time: Long,
    ) {
        companion object {
            fun keyOf(packageName: String, version: String): String = packageName + ":" + version
        }
    }

    private data class IndexRecord(
        val packageName: String,
        val version: String,
        val tag: String,
        val fileName: String,
        val path: String,
        val size: Long,
        val time: Long,
    )

    data class CacheSnapshot(
        val versions: Map<String, String>,
        val entries: List<DownloadEntry>,
    ) {
        fun isCached(module: RepoModule): Boolean = versions[module.packageName] == module.version

        companion object {
            val Empty = CacheSnapshot(emptyMap(), emptyList())
        }
    }

    private val prefs = context.getSharedPreferences("repo", Context.MODE_PRIVATE)
    private val indexFile: File
        get() = File(context.filesDir, "repo-cache.json")

    private val readmeDir: File = File(context.filesDir, "repo/readmes")

    private val indexLock = Any()

    @Volatile
    private var indexCache: Map<String, IndexRecord>? = null

    fun readmeText(pkg: String): String? {
        val file = File(readmeDir, "$pkg.txt")
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    suspend fun fetch(): List<RepoModule> = withContext(Dispatchers.IO) {
        val root = JSONObject(request(MODULES_URL))
        val array = root.optJSONArray("modules") ?: JSONArray()
        runCatching { readmeDir.mkdirs() }

        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val pkg = item.optString("pkg")
                if (pkg.isBlank()) continue

                val readme = item.optString("readme_text")
                if (readme.isNotBlank()) {
                    val file = File(readmeDir, "$pkg.txt")
                    val existing = runCatching { if (file.isFile) file.readText() else null }.getOrNull()
                    if (existing != readme) {
                        runCatching { file.writeText(readme) }
                    }
                }

                add(
                    RepoModule(
                        packageName = pkg,
                        name = item.optString("desc").ifBlank { pkg },
                        version = item.optString("new_version"),
                        versionCode = item.optLong("new_version_code"),
                        summary = item.optString("summary"),
                        author = item.optString("author"),
                        updateTime = item.optLong("new_update_time"),
                        createTime = item.optLong("createTime").takeIf { it > 0 }
                            ?: item.optLong("create_time"),
                        supports89 = item.optBoolean("isXP89") || item.optBoolean("isXp89"),
                        supports100 = item.optBoolean("isXP100") || item.optBoolean("isXp100"),
                        legacyMax = item.optInt("xplegacymax"),
                        lspMax = item.optInt("xplspmax"),
                        scope89 = item.optJSONArray("xp89scope").toStringList(),
                        scope100 = item.optJSONArray("xp100scope").toStringList(),
                    ),
                )
            }
        }.sortedBy { it.name.lowercase() }
    }

    fun downloads(): List<DownloadEntry> {
        val records = readIndex()
        if (records.isEmpty()) return emptyList()
        val entries = ArrayList<DownloadEntry>(records.size)
        for ((key, record) in records) {
            val path = record.path
            if (path.isBlank()) continue
            val file = File(path)
            if (!file.isFile || file.length() != record.size) continue
            entries.add(
                DownloadEntry(
                    key = key,
                    packageName = record.packageName.ifBlank { key.substringBefore(':', key) },
                    version = record.version,
                    tag = record.tag,
                    fileName = record.fileName,
                    path = path,
                    size = record.size,
                    time = record.time,
                ),
            )
        }
        return entries.sortedByDescending { it.time }
    }

    fun cacheSnapshot(): CacheSnapshot {
        val records = readIndex()
        if (records.isEmpty()) return CacheSnapshot.Empty
        val entries = ArrayList<DownloadEntry>(records.size)
        for ((key, record) in records) {
            if (record.path.isBlank()) continue
            val file = File(record.path)
            if (!file.isFile || file.length() != record.size) continue
            entries.add(
                DownloadEntry(
                    key = key,
                    packageName = record.packageName.ifBlank { key.substringBefore(':', key) },
                    version = record.version,
                    tag = record.tag,
                    fileName = record.fileName,
                    path = record.path,
                    size = record.size,
                    time = record.time,
                ),
            )
        }
        val sorted = entries.sortedByDescending { it.time }
        val versions = HashMap<String, String>(sorted.size * 2)
        for (entry in sorted) {
            if (!versions.containsKey(entry.packageName)) {
                versions[entry.packageName] = entry.version
            }
        }
        return CacheSnapshot(versions, sorted)
    }

    fun removeDownload(key: String): Boolean {
        val current = readIndex()
        val record = current[key] ?: return false
        runCatching { File(record.path).delete() }
        return writeIndex(current - key)
    }

    fun cachedApk(module: RepoModule): File? {
        val key = DownloadEntry.keyOf(module.packageName, module.version)
        val record = readIndex()[key] ?: return null
        val file = File(record.path)
        return if (file.exists() && file.length() == record.size) file else null
    }

    suspend fun versions(packageName: String): List<RemoteVersion> = withContext(Dispatchers.IO) {
        val root = JSONObject(request(INFO_URL + packageName))
        val array = root.optJSONArray("modules") ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val tag = item.optString("tag")
                val fileName = item.optString("file_name")
                if (tag.isBlank() || fileName.isBlank()) continue
                val timeKeys = listOf(
                    "update_time", "new_update_time", "newUpdateTime",
                    "time", "upload_time", "uploadTime", "create_time", "createTime",
                )
                val updateTime = timeKeys.firstNotNullOfOrNull { key ->
                    item.optLong(key).takeIf { it > 0L }
                } ?: 0L
                add(
                    RemoteVersion(
                        version = item.optString("version").ifBlank { tag },
                        tag = tag,
                        fileName = fileName,
                        updateTime = updateTime,
                    ),
                )
            }
        }
    }

    suspend fun download(
        module: RepoModule,
        control: DownloadControl = DownloadControl(),
        version: RemoteVersion? = null,
        onProgress: (Int, Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        if (version == null) {
            cachedApk(module)?.let { return@withContext it }
        }

        val tag: String
        val fileName: String
        val resolvedVersion: String
        if (version != null) {
            tag = version.tag
            fileName = version.fileName
            resolvedVersion = version.version
        } else {
            val info = JSONObject(request(INFO_URL + module.packageName))
                .optJSONArray("modules")
                ?.optJSONObject(0)
                ?: error("仓库未返回该模块的详情")
            tag = info.optString("tag")
            fileName = info.optString("file_name")
            resolvedVersion = info.optString("version").ifBlank { module.version }
        }
        require(tag.isNotBlank() && fileName.isNotBlank()) { "模块缺少可下载文件" }

        val url = FILE_URL + module.packageName + "/" + tag + "/" + fileName
        val dir = File(context.cacheDir, "repo").apply { mkdirs() }
        val safeVersion = resolvedVersion.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(dir, module.packageName + "_" + safeVersion + "_" + fileName)

        val ok = Downloader.download(listOf(url), target, control, USER_AGENT) { progress ->
            val percent = if (progress.total > 0) {
                ((progress.read * 100) / progress.total).toInt().coerceIn(0, 100)
            } else {
                0
            }
            onProgress(percent, progress.bytesPerSecond)
        }
        if (!ok) error("下载失败：" + module.name)

        recordDownload(module.packageName, resolvedVersion, tag, fileName, target)
        target
    }

    fun clearCache() {
        runCatching {
            File(context.cacheDir, "repo").deleteRecursively()
            indexFile.delete()
        }
        synchronized(indexLock) { indexCache = emptyMap() }
    }

    fun cacheSize(): Long = runCatching {
        File(context.cacheDir, "repo").walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    fun snapshotIndex(): String = runCatching {
        if (indexFile.isFile) indexFile.readText() else ""
    }.getOrDefault("")

    fun restoreIndex(text: String): Boolean {
        val ok = runCatching { indexFile.writeText(text) }.isSuccess
        if (ok) invalidate()
        return ok
    }

    fun readmeDirectory(): File = readmeDir

    fun invalidate() {
        synchronized(indexLock) { indexCache = null }
    }

    private fun recordDownload(
        packageName: String,
        version: String,
        tag: String,
        fileName: String,
        file: File,
    ) {
        val next = readIndex().toMutableMap()
        next[DownloadEntry.keyOf(packageName, version)] = IndexRecord(
            packageName = packageName,
            version = version,
            tag = tag,
            fileName = fileName,
            path = file.absolutePath,
            size = file.length(),
            time = System.currentTimeMillis(),
        )
        writeIndex(next)
    }

    private fun readIndex(): Map<String, IndexRecord> {
        indexCache?.let { return it }
        val parsed = runCatching {
            if (!indexFile.exists()) {
                emptyMap<String, IndexRecord>()
            } else {
                parseIndex(JSONObject(indexFile.readText()))
            }
        }.getOrDefault(emptyMap())
        synchronized(indexLock) {
            indexCache?.let { return it }
            indexCache = parsed
        }
        return parsed
    }

    private fun parseIndex(json: JSONObject): Map<String, IndexRecord> {
        if (json.length() == 0) return emptyMap()
        val out = HashMap<String, IndexRecord>(json.length() * 2)
        for (key in json.keys()) {
            val record = json.optJSONObject(key) ?: continue
            val version = record.optString("version")
            val storedPkg = record.optString("pkg")
            val pkg = storedPkg.ifBlank { key.substringBefore(':', key) }
            val normalizedKey = if (key.contains(':')) key else DownloadEntry.keyOf(pkg, version)
            out[normalizedKey] = IndexRecord(
                packageName = pkg,
                version = version,
                tag = record.optString("tag"),
                fileName = record.optString("file"),
                path = record.optString("path"),
                size = record.optLong("size"),
                time = record.optLong("time"),
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
                    put("version", record.version)
                    put("tag", record.tag)
                    put("file", record.fileName)
                    put("path", record.path)
                    put("size", record.size)
                    put("time", record.time)
                },
            )
        }
        val ok = runCatching { indexFile.writeText(json.toString()) }.isSuccess
        synchronized(indexLock) { indexCache = if (ok) records else null }
        return ok
    }

    private fun request(url: String): String {
        val connection = open(url)
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.connect()
        require(connection.responseCode in 200..299) { "请求失败：HTTP ${connection.responseCode}" }
        return connection
    }

    private companion object {
        const val BASE = "https://repo.fpfast.top/repo/"
        const val MODULES_URL = BASE + "modules"
        const val INFO_URL = BASE + "info/"
        const val FILE_URL = BASE + "file/"
        const val USER_AGENT = "XPatch/0.1.0"

        fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return buildList {
                for (i in 0 until length()) {
                    optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
    }
}
