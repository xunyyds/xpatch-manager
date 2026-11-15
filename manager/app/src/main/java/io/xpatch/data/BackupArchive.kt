package io.xpatch.data

import android.content.Context
import android.os.Environment
import io.xpatch.BuildConfig
import io.xpatch.patch.PatchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupArchive(private val context: Context) {

    enum class Scope(val key: String, val title: String, val detail: String) {
        SETTINGS("settings", "设置", "外观、打包、作用域与历史偏好"),
        SCOPE("scope", "作用域与模块", "模块开关、作用域、模块 API 与保护记录"),
        SIGNATURES("signatures", "签名", "默认签名档与自定义证书文件"),
        HISTORY("history", "打包历史与导出记录", "每次打包的参数、产物信息与导出位置"),
        ARTIFACTS("artifacts", "打包产物", "历史记录对应的 APK 文件，需同时备份打包历史，体积可能很大"),
        LOGS("logs", "操作日志与崩溃记录", "管理器操作记录与崩溃堆栈"),
        RUNTIME("runtime", "运行日志", "目标应用目录下的模块日志，只备份不还原"),
        CACHE("cache", "仓库缓存", "模块仓库索引与模块说明"),
        ;

        companion object {
            fun of(key: String): Scope? = entries.firstOrNull { it.key == key }

            fun ofKeys(keys: Collection<String>): Set<Scope> =
                entries.filterTo(LinkedHashSet()) { it.key in keys }

            val default: Set<Scope>
                get() = linkedSetOf(SETTINGS, SCOPE, SIGNATURES, HISTORY, LOGS, CACHE)
        }
    }

    data class Usage(val bytes: Long, val files: Int)

    data class Result(val bytes: Long, val entries: Int, val skipped: List<String>)

    data class Preview(
        val legacy: Boolean,
        val createdAt: Long,
        val versionName: String,
        val scopes: List<Scope>,
        val counts: Map<String, Int>,
    )

    data class RestoreResult(
        val ok: Boolean,
        val summary: String,
        val warnings: List<String>,
    )

    fun usage(scopes: Set<Scope>): Map<Scope, Usage> = plan(scopes).usage()

    fun defaultFileName(): String =
        "xpatch-backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(java.util.Date()) + ".zip"

    suspend fun inspect(file: File): Preview? = withContext(Dispatchers.IO) {
        if (isZip(file)) manifestOf(file) else legacyPreview(file)
    }

    suspend fun write(
        sink: OutputStream,
        scopes: Set<Scope>,
        onProgress: (String) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        require(scopes.isNotEmpty()) { "至少选择一项备份内容" }
        val planned = plan(scopes)
        val skipped = ArrayList<String>()
        val used = HashSet<String>()
        var bytes = 0L
        var entries = 0
        val zip = ZipOutputStream(BufferedOutputStream(sink, BUFFER))

        fun uniqueEntry(name: String): String {
            if (used.add(name)) return name
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var index = 1
            var candidate = "$stem($index)$ext"
            while (!used.add(candidate)) {
                index++
                candidate = "$stem($index)$ext"
            }
            return candidate
        }

        fun putText(name: String, text: String) {
            val data = text.toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry(uniqueEntry(name)))
            zip.write(data)
            zip.closeEntry()
            bytes += data.size
            entries++
        }

        fun putFile(name: String, file: File) {
            if (!file.isFile || !file.canRead()) {
                skipped += name
                return
            }
            val readable = runCatching { file.inputStream().use { it.read() } }.isSuccess
            if (!readable) {
                skipped += name
                return
            }
            zip.putNextEntry(ZipEntry(uniqueEntry(name)))
            try {
                file.inputStream().buffered(BUFFER).use { input -> input.copyTo(zip) }
                zip.closeEntry()
                bytes += file.length()
                entries++
            } catch (error: Throwable) {
                skipped += name
                runCatching { zip.closeEntry() }
            }
        }

        try {
            onProgress("正在写入清单")
            putText(MANIFEST, manifest(scopes, planned.counts()).toString(2))
            planned.settings?.let {
                onProgress("正在写入设置")
                putText(SETTINGS_DOC, it)
            }
            planned.scopeDoc?.let {
                onProgress("正在写入作用域与模块")
                putText(SCOPE_DOC, it)
            }
            planned.signatures?.let {
                onProgress("正在写入签名")
                putText(SIGNATURES_DOC, it)
            }
            planned.signatureBins.forEach { file ->
                putFile(SIGNATURES_PREFIX + safeName(file.name), file)
            }
            planned.history?.let {
                onProgress("正在写入打包历史")
                putText(HISTORY_DOC, it)
            }
            if (planned.artifacts.isNotEmpty()) {
                onProgress("正在写入打包产物 " + planned.artifacts.size + " 个")
                planned.artifacts.forEach { artifact ->
                    putFile(ARTIFACTS_PREFIX + artifact.recordId + "/" + safeName(artifact.file.name), artifact.file)
                }
            }
            planned.operations?.let {
                onProgress("正在写入操作日志")
                putText(OPERATIONS_DOC, it)
            }
            planned.crashes.forEach { file ->
                putFile(CRASH_PREFIX + safeName(file.name), file)
            }
            if (planned.runtime.isNotEmpty()) {
                onProgress("正在写入运行日志 " + planned.runtime.size + " 个文件")
                planned.runtime.forEach { entry -> putFile(entry.relative, entry.file) }
            }
            planned.cacheIndex?.let {
                onProgress("正在写入仓库缓存")
                putText(CACHE_INDEX_DOC, it)
            }
            planned.readmes.forEach { file ->
                putFile(README_PREFIX + safeName(file.name), file)
            }
            zip.finish()
            zip.flush()
        } finally {
            runCatching { zip.close() }
        }
        Result(bytes = bytes, entries = entries, skipped = skipped)
    }

    suspend fun restore(file: File, onProgress: (String) -> Unit = {}): RestoreResult =
        withContext(Dispatchers.IO) {
            if (!isZip(file)) return@withContext restoreLegacy(file)
            if (manifestOf(file) == null) {
                return@withContext RestoreResult(false, "这不是 XPatch 备份文件", emptyList())
            }
            val temp = File(context.cacheDir, "backup-restore").apply {
                deleteRecursively()
                mkdirs()
            }
            val warnings = ArrayList<String>()
            val summary = ArrayList<String>()
            try {
                onProgress("正在解包备份")
                val extracted = extract(file, temp)
                val missingDocs = extracted.missing.count { !it.startsWith(RUNTIME_PREFIX) }
                if (missingDocs > 0) {
                    warnings += "解包失败 " + missingDocs + " 个条目"
                }
                val config = ConfigBackup(context)

                fun textOf(entry: String): String? =
                    extracted.files[entry]?.let { runCatching { it.readText() }.getOrNull() }

                textOf(SETTINGS_DOC)?.let { text ->
                    onProgress("正在恢复设置")
                    runCatching { JSONObject(text) }
                        .onSuccess { summary += "设置 " + config.importSettings(it) + " 项" }
                        .onFailure { warnings += "设置：" + (it.message ?: "格式不正确") }
                }

                textOf(SCOPE_DOC)?.let { text ->
                    onProgress("正在恢复作用域与模块")
                    runCatching { config.importScope(JSONObject(text)) }
                        .onSuccess { summary += it }
                        .onFailure { warnings += "作用域与模块：" + (it.message ?: "格式不正确") }
                }

                textOf(SIGNATURES_DOC)?.let { text ->
                    onProgress("正在恢复签名")
                    val bins = HashMap<String, ByteArray>()
                    extracted.files.forEach { (entry, staged) ->
                        if (!entry.startsWith(SIGNATURES_PREFIX) || !entry.endsWith(BIN_SUFFIX)) return@forEach
                        val id = entry.removePrefix(SIGNATURES_PREFIX).removeSuffix(BIN_SUFFIX)
                        runCatching { staged.readBytes() }.getOrNull()?.let { bins[id] = it }
                    }
                    val document = runCatching { JSONObject(text) }.getOrNull()
                    if (document == null) {
                        warnings += "签名：格式不正确"
                    } else {
                        val requested = document.optJSONArray("profiles")?.length() ?: 0
                        runCatching { config.importSignatures(document, bins) }
                            .onSuccess { restored ->
                                summary += "签名 " + restored + " 个"
                                if (restored < requested) {
                                    warnings += "签名缺少证书文件 " + (requested - restored) + " 个"
                                }
                            }
                            .onFailure { warnings += "签名：" + (it.message ?: "无法恢复") }
                    }
                }

                val historyText = textOf(HISTORY_DOC)
                if (historyText != null) {
                    onProgress("正在恢复打包历史")
                    val artifacts = extracted.artifacts
                    runCatching {
                        val count = PatchHistoryStore(context).restoreFrom(historyText) { record ->
                            restoreArtifact(record, artifacts[record.id], warnings)
                        }
                        summary += "打包历史 " + count + " 条"
                    }.onFailure {
                        warnings += "打包历史：" + (it.message ?: "格式不正确")
                        artifacts.values.forEach { staged -> runCatching { staged.delete() } }
                    }
                } else if (extracted.artifacts.isNotEmpty()) {
                    extracted.artifacts.values.forEach { runCatching { it.delete() } }
                    warnings += "备份只含打包产物，缺少打包历史，产物未还原"
                }

                textOf(OPERATIONS_DOC)?.let { text ->
                    onProgress("正在恢复操作日志")
                    if (!runCatching { EventLog(context).restore(text) }.getOrDefault(false)) {
                        warnings += "操作日志：写入失败"
                    }
                }

                val crashes = HashMap<String, ByteArray>()
                extracted.files.forEach { (entry, staged) ->
                    if (!entry.startsWith(CRASH_PREFIX)) return@forEach
                    val name = entry.removePrefix(CRASH_PREFIX)
                    if (name.isBlank() || name.contains('/')) return@forEach
                    runCatching { staged.readBytes() }.getOrNull()?.let { crashes[name] = it }
                }
                if (extracted.files.containsKey(OPERATIONS_DOC) || crashes.isNotEmpty()) {
                    summary += "崩溃记录 " + CrashRecorder.restore(context, crashes) + " 个"
                }

                textOf(CACHE_INDEX_DOC)?.let { text ->
                    onProgress("正在恢复仓库缓存")
                    val repo = RepoStore(context)
                    var failed = 0
                    if (!runCatching { repo.restoreIndex(text) }.getOrDefault(false)) failed++
                    runCatching { repo.readmeDirectory().mkdirs() }
                    extracted.files.forEach { (entry, staged) ->
                        if (!entry.startsWith(README_PREFIX)) return@forEach
                        val name = entry.removePrefix(README_PREFIX)
                        if (name.isBlank() || name.contains('/')) return@forEach
                        val ok = runCatching {
                            File(repo.readmeDirectory(), name).writeBytes(staged.readBytes())
                        }.isSuccess
                        if (!ok) failed++
                    }
                    if (failed > 0) warnings += "仓库缓存写入失败 " + failed + " 处"
                    summary += "仓库缓存"
                }

                if (extracted.hasRuntime) {
                    summary += "运行日志仅备份，未还原"
                }

                onProgress("正在整理")
                RestoreResult(
                    ok = warnings.isEmpty(),
                    summary = if (summary.isEmpty()) "备份中没有可恢复的数据" else summary.joinToString(" · "),
                    warnings = warnings,
                )
            } catch (error: Throwable) {
                RestoreResult(
                    ok = false,
                    summary = "恢复失败：" + (error.message ?: "无法读取备份文件"),
                    warnings = warnings,
                )
            } finally {
                runCatching { temp.deleteRecursively() }
            }
        }

    private fun restoreArtifact(
        record: PatchRecord,
        source: File?,
        warnings: MutableList<String>,
    ): PatchRecord {
        if (source == null || !source.isFile) return record
        if (record.apkSize > 0 && source.length() != record.apkSize) {
            warnings += "打包产物大小不一致：" + record.label.ifBlank { record.packageName }
            runCatching { source.delete() }
            return record
        }
        return record.copy(
            apkPath = source.absolutePath,
            apkSize = source.length(),
            apkModified = source.lastModified(),
        )
    }

    private fun restoreLegacy(file: File): RestoreResult {
        val text = runCatching { file.readText() }.getOrElse {
            return RestoreResult(false, "读取文件失败：" + (it.message ?: "未知错误"), emptyList())
        }
        val result = ConfigBackup(context).importLegacy(text)
        return RestoreResult(result.success, result.message, emptyList())
    }

    private class Extracted(
        val files: Map<String, File>,
        val artifacts: Map<String, File>,
        val missing: List<String>,
        val hasRuntime: Boolean,
    )

    private class BackupLimitException(message: String) : Exception(message)

    private fun extract(file: File, root: File): Extracted {
        val files = LinkedHashMap<String, File>()
        val artifacts = HashMap<String, File>()
        val missing = ArrayList<String>()
        var hasRuntime = false
        var total = 0L
        var count = 0
        try {
            ZipInputStream(file.inputStream().buffered(BUFFER)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.name.startsWith(RUNTIME_PREFIX)) {
                        count++
                        if (count > MAX_ENTRIES) error("备份条目过多，超过 " + MAX_ENTRIES + " 个")
                    }
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    val name = entryTargetName(entry.name)
                    if (name == null) {
                        zip.closeEntry()
                        continue
                    }
                    if (name.startsWith(RUNTIME_PREFIX)) {
                        hasRuntime = true
                        zip.closeEntry()
                        continue
                    }
                    val budget = MAX_BYTES - total
                    val isArtifact = name.startsWith(ARTIFACTS_PREFIX)
                    val recordId: String
                    val target: File
                    if (isArtifact) {
                        val rest = name.removePrefix(ARTIFACTS_PREFIX)
                        recordId = rest.substringBefore('/', "")
                        val fileName = rest.substringAfter('/', "").substringAfterLast('/')
                        if (recordId.isBlank() || fileName.isBlank()) {
                            zip.closeEntry()
                            continue
                        }
                        val dir = PatchService(context).outputDir().apply { mkdirs() }
                        target = uniqueTarget(dir, fileName)
                    } else {
                        recordId = ""
                        target = File(root, name)
                        target.parentFile?.mkdirs()
                    }
                    val written = runCatching { copyEntry(zip, target, budget) }.getOrElse { error ->
                        runCatching { target.delete() }
                        missing += name
                        if (error is BackupLimitException) throw error
                        -1L
                    }
                    if (written >= 0) {
                        total += written
                        if (isArtifact) artifacts[recordId] = target else files[name] = target
                    }
                    zip.closeEntry()
                }
            }
        } catch (error: Throwable) {
            artifacts.values.forEach { runCatching { it.delete() } }
            throw error
        }
        return Extracted(files, artifacts, missing, hasRuntime)
    }

    private fun copyEntry(zip: ZipInputStream, target: File, budget: Long): Long {
        var written = 0L
        target.outputStream().buffered(BUFFER).use { out ->
            val buffer = ByteArray(BUFFER)
            var read = zip.read(buffer)
            while (read > 0) {
                written += read
                if (written > budget) {
                    throw BackupLimitException("备份内容超出 " + MAX_BYTES / 1024 / 1024 + " MB")
                }
                out.write(buffer, 0, read)
                read = zip.read(buffer)
            }
        }
        return written
    }

    private fun readEntryText(zip: ZipInputStream, limit: Int): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (out.size() < limit) {
            val read = zip.read(buffer, 0, buffer.size.coerceAtMost(limit - out.size()))
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toString("UTF-8")
    }

    private fun manifestOf(file: File): Preview? = runCatching {
        ZipInputStream(file.inputStream().buffered(BUFFER)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == MANIFEST) {
                    return@use previewOf(JSONObject(readEntryText(zip, MAX_MANIFEST)))
                }
                zip.closeEntry()
            }
            null
        }
    }.getOrNull()

    private fun previewOf(json: JSONObject): Preview {
        val array = json.optJSONArray("scopes") ?: JSONArray()
        val keys = buildList { for (index in 0 until array.length()) add(array.optString(index)) }
        val counts = HashMap<String, Int>()
        json.optJSONObject("counts")?.let { source ->
            source.keys().forEach { key -> counts[key] = source.optInt(key) }
        }
        return Preview(
            legacy = false,
            createdAt = json.optLong("createdAt"),
            versionName = json.optString("appVersion"),
            scopes = Scope.ofKeys(keys).toList(),
            counts = counts,
        )
    }

    private fun legacyPreview(file: File): Preview? {
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        val scopes = buildSet {
            if (json.optJSONObject("settings") != null) add(Scope.SETTINGS)
            if (json.optJSONObject("scope") != null || json.optJSONObject("moduleApi") != null) {
                add(Scope.SCOPE)
            }
            if (json.optString("defaultSignature").isNotBlank()) add(Scope.SIGNATURES)
        }
        return Preview(
            legacy = true,
            createdAt = json.optLong("exportedAt"),
            versionName = "",
            scopes = scopes.toList(),
            counts = emptyMap(),
        )
    }

    private fun isZip(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(4)
            val read = input.read(head)
            read >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)

    private fun manifest(scopes: Set<Scope>, counts: Map<String, Int>): JSONObject =
        JSONObject().apply {
            put("format", FORMAT)
            put("version", FORMAT_VERSION)
            put("app", "XPatch")
            put("appVersion", BuildConfig.VERSION_NAME)
            put("createdAt", System.currentTimeMillis())
            put("scopes", JSONArray(scopes.map { it.key }))
            put("counts", JSONObject(HashMap<String, Any>(counts)))
        }

    private fun entryTargetName(name: String): String? {
        val clean = name.replace('\\', '/').trimStart('/')
        if (clean.isBlank()) return null
        if (clean.split('/').any { it == ".." || it.isBlank() }) return null
        return clean
    }

    private fun uniqueTarget(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "$stem($index)$ext")
            index++
        }
        return candidate
    }

    private fun safeName(name: String): String {
        val clean = name.replace(UNSAFE_NAME, "_").trim()
        return clean.ifBlank { "file" }
    }

    private class RuntimeLog(val relative: String, val file: File)

    private data class Artifact(val recordId: String, val file: File)

    private data class Planned(
        val settings: String?,
        val scopeDoc: String?,
        val signatures: String?,
        val signatureBins: List<File>,
        val history: String?,
        val artifacts: List<Artifact>,
        val operations: String?,
        val crashes: List<File>,
        val runtime: List<RuntimeLog>,
        val cacheIndex: String?,
        val readmes: List<File>,
    ) {
        fun counts(): Map<String, Int> = buildMap {
            put(Scope.SETTINGS.key, if (settings == null) 0 else 1)
            put(Scope.SCOPE.key, if (scopeDoc == null) 0 else 1)
            put(Scope.SIGNATURES.key, signatureBins.size + if (signatures == null) 0 else 1)
            put(Scope.HISTORY.key, if (history == null) 0 else 1)
            put(Scope.ARTIFACTS.key, artifacts.size)
            put(
                Scope.LOGS.key,
                crashes.size + (if (operations == null) 0 else 1),
            )
            put(Scope.RUNTIME.key, runtime.size)
            put(Scope.CACHE.key, readmes.size + (if (cacheIndex == null) 0 else 1))
        }

        fun usage(): Map<Scope, Usage> = buildMap {
            put(Scope.SETTINGS, Usage(settings?.toByteArray()?.size?.toLong() ?: 0L, if (settings == null) 0 else 1))
            put(Scope.SCOPE, Usage(scopeDoc?.toByteArray()?.size?.toLong() ?: 0L, if (scopeDoc == null) 0 else 1))
            put(
                Scope.SIGNATURES,
                Usage(
                    signatureBins.sumOf { it.length() } + (signatures?.toByteArray()?.size?.toLong() ?: 0L),
                    signatureBins.size + if (signatures == null) 0 else 1,
                ),
            )
            put(Scope.HISTORY, Usage(history?.toByteArray()?.size?.toLong() ?: 0L, if (history == null) 0 else 1))
            put(Scope.ARTIFACTS, Usage(artifacts.sumOf { it.file.length() }, artifacts.size))
            put(
                Scope.LOGS,
                Usage(
                    crashes.sumOf { it.length() } + (operations?.toByteArray()?.size?.toLong() ?: 0L),
                    crashes.size + if (operations == null) 0 else 1,
                ),
            )
            put(Scope.RUNTIME, Usage(runtime.sumOf { it.file.length() }, runtime.size))
            put(
                Scope.CACHE,
                Usage(
                    readmes.sumOf { it.length() } + (cacheIndex?.toByteArray()?.size?.toLong() ?: 0L),
                    readmes.size + if (cacheIndex == null) 0 else 1,
                ),
            )
        }
    }

    private fun plan(scopes: Set<Scope>): Planned {
        val config = ConfigBackup(context)
        val signatureStore = SignatureStore(context)
        val historyStore = PatchHistoryStore(context)
        val repo = RepoStore(context)
        val wantsArtifacts = Scope.ARTIFACTS in scopes
        return Planned(
            settings = if (Scope.SETTINGS in scopes) config.exportSettings().toString(2) else null,
            scopeDoc = if (Scope.SCOPE in scopes) config.exportScope().toString(2) else null,
            signatures = if (Scope.SIGNATURES in scopes) config.exportSignatures().toString(2) else null,
            signatureBins = if (Scope.SIGNATURES in scopes) {
                signatureStore.customBinFiles().values.toList()
            } else {
                emptyList()
            },
            history = if (Scope.HISTORY in scopes) historyStore.snapshot() else null,
            artifacts = if (wantsArtifacts) existingArtifacts(historyStore) else emptyList(),
            operations = if (Scope.LOGS in scopes) EventLog(context).snapshot() else null,
            crashes = if (Scope.LOGS in scopes) CrashRecorder.snapshot(context) else emptyList(),
            runtime = if (Scope.RUNTIME in scopes) runtimeLogs() else emptyList(),
            cacheIndex = if (Scope.CACHE in scopes) repo.snapshotIndex() else null,
            readmes = if (Scope.CACHE in scopes) {
                repo.readmeDirectory().listFiles { file -> file.isFile }?.sortedBy { it.name } ?: emptyList()
            } else {
                emptyList()
            },
        )
    }

    private fun existingArtifacts(historyStore: PatchHistoryStore): List<Artifact> {
        val records = runCatching { historyStore.list() }.getOrDefault(emptyList())
        val out = ArrayList<Artifact>()
        records.forEach { record ->
            if (record.hasArtifact()) out += Artifact(record.id, record.artifactFile())
        }
        return out
    }

    private fun runtimeLogs(): List<RuntimeLog> {
        val storage = Environment.getExternalStorageDirectory()
        val roots = listOf(
            File(storage, "Android/data"),
            File(storage, "Android/media"),
        )
        val out = ArrayList<RuntimeLog>()
        roots.forEach { root ->
            val apps = runCatching { root.listFiles() }.getOrNull() ?: return@forEach
            apps.forEach { app ->
                val logs = File(app, ".logs")
                if (!logs.isDirectory) return@forEach
                val modules = runCatching { logs.listFiles() }.getOrNull() ?: return@forEach
                modules.forEach { module ->
                    if (!module.isDirectory) return@forEach
                    val found = runCatching {
                        module.listFiles { file -> file.isFile && file.name.endsWith(LOG_SUFFIX) }
                    }.getOrNull() ?: return@forEach
                    found.forEach { file ->
                        out += RuntimeLog(
                            relative = RUNTIME_PREFIX + safeName(app.name) + "/" +
                                safeName(module.name) + "/" + safeName(file.name),
                            file = file,
                        )
                    }
                }
            }
        }
        return out.sortedBy { it.relative }
    }

    private companion object {
        const val FORMAT = "xpatch-backup"
        const val FORMAT_VERSION = 1

        const val MANIFEST = "manifest.json"
        const val SETTINGS_DOC = "settings.json"
        const val SCOPE_DOC = "scope.json"
        const val SIGNATURES_DOC = "signatures.json"
        const val HISTORY_DOC = "history/patch-history.json"
        const val OPERATIONS_DOC = "logs/operations.log"
        const val CACHE_INDEX_DOC = "cache/repo-cache.json"

        const val SIGNATURES_PREFIX = "signatures/"
        const val ARTIFACTS_PREFIX = "artifacts/"
        const val CRASH_PREFIX = "logs/crash/"
        const val RUNTIME_PREFIX = "logs/runtime/"
        const val README_PREFIX = "cache/readmes/"

        const val BIN_SUFFIX = ".bin"
        const val LOG_SUFFIX = ".log"

        const val BUFFER = 64 * 1024

        const val MAX_ENTRIES = 4096

        const val MAX_BYTES = 3L * 1024 * 1024 * 1024

        const val MAX_MANIFEST = 256 * 1024

        val UNSAFE_NAME = Regex("[\\\\/:*?\"<>|]")
    }
}