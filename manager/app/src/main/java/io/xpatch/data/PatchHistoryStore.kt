package io.xpatch.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class PatchExportRef(
    val path: String,
    val name: String,
    val time: Long,
)

data class PatchRecord(
    val id: String,
    val time: Long,
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val sourceKey: String,
    val fingerprint: String,
    val optionSummary: String,
    val apkPath: String,
    val apkSize: Long,
    val apkModified: Long,
    val bootDex: String,
    val originalFactory: String?,
    val config: String,
    val forceOverwrite: Boolean = false,
    val exports: List<PatchExportRef> = emptyList(),
) {
    fun artifactFile(): File = File(apkPath)

    fun hasArtifact(): Boolean = artifactFile().let { file ->
        file.isFile && file.length() == apkSize && file.lastModified() == apkModified
    }

    fun exportExists(ref: PatchExportRef): Boolean = File(ref.path).isFile
}

class PatchHistoryStore(private val context: Context) {

    private val historyFile: File
        get() = File(context.filesDir, "patch-history.json")

    private val lock = Any()

    @Volatile
    private var cache: MutableList<PatchRecord>? = null

    fun limit(): Int = SettingsStore(context).load().patchHistoryLimit.coerceIn(1, 500)

    fun list(): List<PatchRecord> = synchronized(lock) {
        loadLocked().sortedByDescending { it.time }
    }

    fun findReusable(packageName: String, fingerprint: String): PatchRecord? =
        list().firstOrNull {
            it.packageName == packageName && it.fingerprint == fingerprint && it.hasArtifact()
        }

    fun newId(): String = UUID.randomUUID().toString()

    fun record(record: PatchRecord): PatchRecord = synchronized(lock) {
        val current = loadLocked().toMutableList()
        current.removeAll { it.packageName == record.packageName && it.fingerprint == record.fingerprint }
        current.add(record)
        val trimmed = current.sortedByDescending { it.time }.take(limit()).toMutableList()
        saveLocked(trimmed)
        record
    }

    fun addExport(id: String, ref: PatchExportRef): Boolean = synchronized(lock) {
        val current = loadLocked().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized false
        val record = current[index]
        val exports = record.exports.filterNot { it.path == ref.path } + ref
        current[index] = record.copy(exports = exports)
        saveLocked(current)
        true
    }

    fun removeExport(recordId: String, path: String, deleteFile: Boolean): Boolean = synchronized(lock) {
        val current = loadLocked().toMutableList()
        val index = current.indexOfFirst { it.id == recordId }
        if (index < 0) return@synchronized false
        val record = current[index]
        if (deleteFile) runCatching { File(path).delete() }
        current[index] = record.copy(exports = record.exports.filterNot { it.path == path })
        saveLocked(current)
        true
    }

    fun remove(id: String, deleteArtifact: Boolean): Boolean = synchronized(lock) {
        val current = loadLocked().toMutableList()
        val record = current.firstOrNull { it.id == id } ?: return@synchronized false
        if (deleteArtifact) {
            runCatching { record.artifactFile().delete() }
            record.exports.forEach { runCatching { File(it.path).delete() } }
        }
        current.removeAll { it.id == id }
        saveLocked(current)
        true
    }

    fun clearMissing(): Int = synchronized(lock) {
        val current = loadLocked()
        val kept = current.filter { it.hasArtifact() }.toMutableList()
        val removed = current.size - kept.size
        if (removed > 0) saveLocked(kept)
        removed
    }

    fun totalSize(): Long = synchronized(lock) {
        loadLocked().sumOf { record ->
            val artifact = if (record.hasArtifact()) record.apkSize else 0L
            artifact + record.exports.sumOf { ref -> File(ref.path).takeIf { it.isFile }?.length() ?: 0L }
        }
    }

    private fun loadLocked(): MutableList<PatchRecord> {
        cache?.let { return it }
        val parsed = runCatching {
            if (!historyFile.isFile) mutableListOf() else parse(historyFile.readText())
        }.getOrDefault(mutableListOf())
        val sorted = parsed.sortedByDescending { it.time }.toMutableList()
        cache = sorted
        return sorted
    }

    private fun saveLocked(records: List<PatchRecord>) {
        val json = JSONArray()
        records.forEach { json.put(toJson(it)) }
        val ok = runCatching {
            val temp = File(historyFile.parentFile, historyFile.name + ".tmp")
            temp.writeText(json.toString())
            if (historyFile.exists()) historyFile.delete()
            temp.renameTo(historyFile)
        }.isSuccess
        cache = if (ok) records.sortedByDescending { it.time }.toMutableList() else null
    }

    private fun parse(text: String): MutableList<PatchRecord> {
        if (text.isBlank()) return mutableListOf()
        val array = JSONArray(text)
        val out = ArrayList<PatchRecord>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val exports = ArrayList<PatchExportRef>()
            val exportArray = item.optJSONArray("exports") ?: JSONArray()
            for (j in 0 until exportArray.length()) {
                val ref = exportArray.optJSONObject(j) ?: continue
                exports.add(
                    PatchExportRef(
                        path = ref.optString("path"),
                        name = ref.optString("name"),
                        time = ref.optLong("time"),
                    ),
                )
            }
            out.add(
                PatchRecord(
                    id = item.optString("id"),
                    time = item.optLong("time"),
                    packageName = item.optString("packageName"),
                    label = item.optString("label"),
                    versionName = item.optString("versionName"),
                    versionCode = item.optLong("versionCode"),
                    sourceKey = item.optString("sourceKey"),
                    fingerprint = item.optString("fingerprint"),
                    optionSummary = item.optString("optionSummary"),
                    apkPath = item.optString("apkPath"),
                    apkSize = item.optLong("apkSize"),
                    apkModified = item.optLong("apkModified"),
                    bootDex = item.optString("bootDex"),
                    originalFactory = item.optString("originalFactory").takeIf { it.isNotBlank() },
                    config = item.optString("config"),
                    forceOverwrite = item.optBoolean("forceOverwrite", false),
                    exports = exports,
                ),
            )
        }
        return out
    }

    private fun toJson(record: PatchRecord): JSONObject = JSONObject().apply {
        put("id", record.id)
        put("time", record.time)
        put("packageName", record.packageName)
        put("label", record.label)
        put("versionName", record.versionName)
        put("versionCode", record.versionCode)
        put("sourceKey", record.sourceKey)
        put("fingerprint", record.fingerprint)
        put("optionSummary", record.optionSummary)
        put("apkPath", record.apkPath)
        put("apkSize", record.apkSize)
        put("apkModified", record.apkModified)
        put("bootDex", record.bootDex)
        put("originalFactory", record.originalFactory.orEmpty())
        put("config", record.config)
        put("forceOverwrite", record.forceOverwrite)
        val exports = JSONArray()
        record.exports.forEach { ref ->
            exports.put(
                JSONObject().apply {
                    put("path", ref.path)
                    put("name", ref.name)
                    put("time", ref.time)
                },
            )
        }
        put("exports", exports)
    }
}
