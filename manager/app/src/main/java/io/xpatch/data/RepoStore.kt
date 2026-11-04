package io.xpatch.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模块在线仓库（同 NPatch 使用的源）。
 *
 *   GET https://repo.fpfast.top/repo/modules                 模块列表
 *   GET https://repo.fpfast.top/repo/info/<pkg>              模块详情
 *   GET https://repo.fpfast.top/repo/file/<pkg>/<tag>/<file> 下载
 *
 * 下载结果记录在 repo-cache.json，同版本不重复下载。
 */
class RepoStore(private val context: Context) {

    data class RepoModule(
        val packageName: String,
        val name: String,
        val version: String,
        val summary: String,
        val author: String,
        val updateTime: Long,
        val supports89: Boolean,
        val supports100: Boolean,
    )

    private val prefs = context.getSharedPreferences("repo", Context.MODE_PRIVATE)
    private val indexFile: File
        get() = File(context.filesDir, "repo-cache.json")

    fun sourceUrl(): String = prefs.getString(KEY_URL, MODULES_URL) ?: MODULES_URL

    fun setSourceUrl(url: String) = prefs.edit().putString(KEY_URL, url.trim()).apply()

    suspend fun fetch(): List<RepoModule> = withContext(Dispatchers.IO) {
        val root = JSONObject(request(sourceUrl()))
        val array = root.optJSONArray("modules") ?: JSONArray()

        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val pkg = item.optString("pkg")
                if (pkg.isBlank()) continue
                add(
                    RepoModule(
                        packageName = pkg,
                        name = item.optString("desc").ifBlank { pkg },
                        version = item.optString("new_version"),
                        summary = item.optString("summary"),
                        author = item.optString("author"),
                        updateTime = item.optLong("new_update_time"),
                        supports89 = item.optBoolean("isXP89") || item.optBoolean("isXp89"),
                        supports100 = item.optBoolean("isXP100") || item.optBoolean("isXp100"),
                    ),
                )
            }
        }.sortedBy { it.name.lowercase() }
    }

    /** 已下载且仍可用的缓存文件（版本一致才算命中）。 */
    fun cachedApk(module: RepoModule): File? {
        val record = readIndex().optJSONObject(module.packageName) ?: return null
        if (record.optString("version") != module.version) return null
        val file = File(record.optString("path"))
        return if (file.exists() && file.length() == record.optLong("size")) file else null
    }

    suspend fun download(module: RepoModule, onProgress: (Int) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            cachedApk(module)?.let { return@withContext it }

            val info = JSONObject(request(INFO_URL + module.packageName))
                .optJSONArray("modules")
                ?.optJSONObject(0)
                ?: error("仓库未返回该模块的详情")

            val tag = info.optString("tag")
            val fileName = info.optString("file_name")
            require(tag.isNotBlank() && fileName.isNotBlank()) { "模块缺少可下载文件" }

            val version = info.optString("version").ifBlank { module.version }
            val url = FILE_URL + module.packageName + "/" + tag + "/" + fileName
            val dir = File(context.cacheDir, "repo").apply { mkdirs() }
            val target = File(dir, "$fileName")

            val connection = open(url)
            try {
                val total = connection.contentLength
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0
                        while (input.read(buffer).also { read = it } > 0) {
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) onProgress(done * 100 / total)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }

            recordDownload(module.packageName, version, tag, fileName, target)
            target
        }

    fun clearCache() {
        runCatching {
            File(context.cacheDir, "repo").deleteRecursively()
            indexFile.delete()
        }
    }

    fun cacheSize(): Long = runCatching {
        File(context.cacheDir, "repo").walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private fun recordDownload(
        packageName: String,
        version: String,
        tag: String,
        fileName: String,
        file: File,
    ) {
        val index = readIndex()
        index.put(
            packageName,
            JSONObject().apply {
                put("version", version)
                put("tag", tag)
                put("file", fileName)
                put("path", file.absolutePath)
                put("size", file.length())
                put("time", System.currentTimeMillis())
            },
        )
        runCatching { indexFile.writeText(index.toString()) }
    }

    private fun readIndex(): JSONObject = runCatching {
        if (!indexFile.exists()) JSONObject() else JSONObject(indexFile.readText())
    }.getOrDefault(JSONObject())

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
        const val KEY_URL = "source_url"
        const val BASE = "https://repo.fpfast.top/repo/"
        const val MODULES_URL = BASE + "modules"
        const val INFO_URL = BASE + "info/"
        const val FILE_URL = BASE + "file/"
        const val USER_AGENT = "XPatch/0.1.0"
    }
}