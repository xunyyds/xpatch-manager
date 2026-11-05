package io.xpatch.data

import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

class RuntimeLogStore {

    data class Entry(
        val id: String,
        val timeMillis: Long,
        val day: String,
        val timeText: String,
        val module: String,
        val target: String,
        val message: String,
        val detail: String,
        val sourcePath: String,
        val lineNumber: Int,
        val incomplete: Boolean,
    ) {
        val framework: Boolean get() = module == FRAMEWORK_MODULE

        val hasDetail: Boolean get() = detail.isNotEmpty()

        val fullText: String
            get() = "[" + day + "T" + timeText + "] " + module + "@" + target + " " + message +
                (if (hasDetail) "\n" + detail else "")
    }

    data class Failure(val path: String, val reason: String)

    data class ClearResult(
        val deletedFiles: Int,
        val affectedApps: Int,
        val failed: List<Failure>,
    ) {
        val ok: Boolean get() = failed.isEmpty()
    }

    data class Snapshot(
        val entries: List<Entry>,
        val truncatedByLimit: Boolean,
        val skippedFiles: Int,
        val failures: List<Failure>,
        val headTruncated: Boolean,
        val readAtMillis: Long,
        val viaShizuku: Boolean = false,
    ) {
        val totalLoaded: Int get() = entries.size

        val appCount: Int get() = entries.mapTo(HashSet<String>()) { it.target }.size

        val moduleCount: Int get() = entries.mapTo(HashSet<String>()) { it.module }.size
    }

    fun interface ShellReader {
        fun read(command: String): String?
    }

    private data class FileTail(val entries: List<Entry>, val truncated: Boolean)

    fun hasAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    suspend fun load(targets: Collection<String>, limit: Int = MAX_ENTRIES): Snapshot =
        withContext(Dispatchers.IO) {
            val failures = ConcurrentLinkedQueue<Failure>()
            val files = mutableListOf<Triple<File, String, String>>()
            targets.forEach { target ->
                collectFiles(logRootFor(target), target, files, failures)
            }
            snapshotOf(readAll(files, failures), failures, limit)
        }

    suspend fun loadAll(limit: Int = MAX_ENTRIES): Snapshot = withContext(Dispatchers.IO) {
        val failures = ConcurrentLinkedQueue<Failure>()
        val files = mutableListOf<Triple<File, String, String>>()
        childrenOf(dataRoot(), failures)?.forEach { appDir ->
            collectFiles(File(appDir, LOG_DIR), appDir.name, files, failures)
        }
        snapshotOf(readAll(files, failures), failures, limit)
    }

    suspend fun loadViaShell(
        reader: ShellReader,
        targets: Collection<String>,
        limit: Int = MAX_ENTRIES,
    ): Snapshot? = withContext(Dispatchers.IO) {
        val paths = mutableListOf<Triple<String, String, String>>()
        targets.forEach { target ->
            val appLogs = "$DATA_PATH/$target/$LOG_DIR"
            val modules = reader.read("ls $appLogs 2>/dev/null") ?: return@forEach
            modules.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { module ->
                val moduleDir = "$appLogs/$module"
                val listed = reader.read("ls $moduleDir 2>/dev/null") ?: return@forEach
                listed.lineSequence()
                    .map { it.trim() }
                    .filter { it.endsWith(LOG_SUFFIX) }
                    .forEach { name -> paths += Triple("$moduleDir/$name", target, module) }
            }
        }
        if (paths.isEmpty()) return@withContext null

        val entries = mutableListOf<Entry>()
        var headTruncated = false
        paths.forEach { (path, target, module) ->
            val content = reader.read("tail -c $TAIL_BYTES_PER_FILE $path 2>/dev/null")
                ?: return@forEach
            if (content.isEmpty()) return@forEach
            val parsed = parseTail(content.toByteArray(Charsets.UTF_8), path, target, module, 0L)
            entries += parsed.entries
            headTruncated = headTruncated || parsed.truncated
        }
        val snapshot = snapshotOf(entries, emptyList(), headTruncated, limit)
        snapshot.copy(viaShizuku = true)
    }

    private fun collectFiles(
        dir: File,
        target: String,
        files: MutableList<Triple<File, String, String>>,
        failures: MutableCollection<Failure>,
    ) {
        if (!dir.isDirectory) return
        childrenOf(dir, failures)?.forEach { moduleDir ->
            if (!moduleDir.isDirectory) return@forEach
            logFilesOf(moduleDir, failures)?.forEach { file ->
                files += Triple(file, target, moduleDir.name)
            }
        }
    }

    private suspend fun readAll(
        files: List<Triple<File, String, String>>,
        failures: MutableCollection<Failure>,
    ): Pair<List<Entry>, Boolean> {
        val slices = if (files.size <= 1) {
            files.map { readSlice(it, failures) }
        } else {
            coroutineScope {
                files
                    .chunked(files.size.coerceAtMost(READ_CONCURRENCY))
                    .map { chunk -> async { chunk.map { readSlice(it, failures) } } }
                    .awaitAll()
                    .flatten()
            }
        }
        val entries = ArrayList<Entry>(slices.sumOf { it.entries.size })
        var headTruncated = false
        slices.forEach { slice ->
            entries += slice.entries
            headTruncated = headTruncated || slice.truncated
        }
        return entries to headTruncated
    }

    private fun snapshotOf(
        read: Pair<List<Entry>, Boolean>,
        failures: ConcurrentLinkedQueue<Failure>,
        limit: Int,
    ): Snapshot = snapshotOf(read.first, failures.toList(), read.second, limit)

    private fun dataRoot(): File = File(Environment.getExternalStorageDirectory(), "Android/data")

    private fun logRootFor(target: String): File = File(dataRoot(), target)

    private fun snapshotOf(
        entries: List<Entry>,
        failures: List<Failure>,
        headTruncated: Boolean,
        limit: Int,
    ): Snapshot {
        val sorted = entries.sortedByDescending { it.timeMillis }
        val cap = limit.coerceAtLeast(0)
        val kept = if (sorted.size > cap) sorted.take(cap) else sorted
        val truncatedByLimit = kept.size < sorted.size
        val skippedFiles = if (truncatedByLimit) {
            val keptPaths = kept.mapTo(HashSet<String>()) { it.sourcePath }
            sorted.mapTo(HashSet<String>()) { it.sourcePath }.count { it !in keptPaths }
        } else {
            0
        }
        return Snapshot(
            entries = kept,
            truncatedByLimit = truncatedByLimit,
            skippedFiles = skippedFiles,
            failures = failures,
            headTruncated = headTruncated,
            readAtMillis = System.currentTimeMillis(),
        )
    }

    private fun readSlice(
        target: Triple<File, String, String>,
        failures: MutableCollection<Failure>,
    ): FileTail = runCatching {
        readTail(target.first, target.second, target.third)
    }.getOrElse { error ->
        addFailure(failures, target.first.absolutePath, reasonOf(error))
        FileTail(emptyList(), false)
    }

    suspend fun clear(): ClearResult = withContext(Dispatchers.IO) {
        val failures = mutableListOf<Failure>()
        val apps = HashSet<String>()
        var deletedFiles = 0
        logRoots().forEach { root ->
            childrenOf(root, failures)?.forEach { appDir ->
                val logsDir = File(appDir, LOG_DIR)
                if (!logsDir.isDirectory) return@forEach
                apps += appDir.absolutePath
                runCatching { deleteTree(logsDir, failures) }
                    .onSuccess { deletedFiles += it }
                    .onFailure { addFailure(failures, logsDir.absolutePath, reasonOf(it)) }
            }
        }
        ClearResult(
            deletedFiles = deletedFiles,
            affectedApps = apps.size,
            failed = failures.toList(),
        )
    }

    fun export(entries: List<Entry>): String = entries.joinToString("\n") { it.fullText }

    private fun logRoots(): List<File> {
        val storage = Environment.getExternalStorageDirectory()
        return listOf(
            File(storage, "Android/media"),
            File(storage, "Android/data"),
        ).filter { it.isDirectory }
    }

    private fun childrenOf(dir: File, failures: MutableCollection<Failure>): Array<File>? =
        runCatching { dir.listFiles() ?: error("无法列出目录") }.getOrElse {
            addFailure(failures, dir.absolutePath, reasonOf(it))
            null
        }

    private fun logFilesOf(dir: File, failures: MutableCollection<Failure>): List<File>? =
        runCatching {
            dir.listFiles { file -> file.isFile && file.name.endsWith(LOG_SUFFIX) }
                ?: error("无法列出目录")
        }.getOrElse {
            addFailure(failures, dir.absolutePath, reasonOf(it))
            null
        }?.sortedBy { it.name }

    private fun readTail(file: File, target: String, module: String): FileTail {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val start = (length - TAIL_BYTES_PER_FILE).coerceAtLeast(0L)
            val size = (length - start).toInt()
            if (size <= 0) return FileTail(emptyList(), false)

            val bytes = ByteArray(size)
            raf.seek(start)
            raf.readFully(bytes)
            return parseTail(bytes, file.absolutePath, target, module, start)
        }
    }

    private fun parseTail(
        bytes: ByteArray,
        path: String,
        target: String,
        module: String,
        start: Long,
    ): FileTail {
        var truncated = start > 0L
        var base = 0
        if (start > 0L) {
            val firstBreak = bytes.indexOf(NEWLINE)
            if (firstBreak < 0) return FileTail(emptyList(), true)
            base = firstBreak + 1
        }

        var end = bytes.size
        val tailCut = bytes[end - 1] != NEWLINE
        if (tailCut) {
            truncated = true
            val lastBreak = bytes.lastIndexOf(NEWLINE)
            end = if (lastBreak < base) base else lastBreak + 1
        }
        if (end <= base) return FileTail(emptyList(), truncated)

        val parts = String(bytes, base, end - base, Charsets.UTF_8).split('\n')
        val lines = if (parts.size > 1 && parts.last().isEmpty()) {
            parts.subList(0, parts.size - 1)
        } else {
            parts
        }

        val starts = ArrayList<Int>(lines.size)
        var lineStart = base
        var cursor = base
        while (cursor < end) {
            if (bytes[cursor] == NEWLINE) {
                starts += start.toInt() + lineStart
                lineStart = cursor + 1
            }
            cursor++
        }

        val skip = (lines.size - TAIL_LINES_PER_FILE).coerceAtLeast(0)
        if (skip > 0) truncated = true

        val format = SimpleDateFormat(STAMP_PATTERN, Locale.US)
        format.isLenient = false
        val result = mutableListOf<Entry>()
        var stamp: String? = null
        var offset = 0
        var message = ""
        val detail = StringBuilder()

        fun flush() {
            val header = stamp ?: return
            val parsed = runCatching { format.parse(header) }.getOrNull()
            val complete = parsed != null && header.length >= STAMP_LENGTH
            result += Entry(
                id = path + "@" + offset,
                timeMillis = parsed?.time ?: 0L,
                day = header.take(10),
                timeText = when {
                    complete -> header.substring(11, STAMP_LENGTH)
                    header.length > 11 -> header.substring(11)
                    else -> header
                },
                module = module,
                target = target,
                message = message,
                detail = detail.toString(),
                sourcePath = path,
                lineNumber = offset,
                incomplete = !complete,
            )
        }

        for (index in skip until lines.size) {
            val raw = lines[index]
            val line = if (raw.endsWith("\r")) raw.dropLast(1) else raw
            val header = headerStamp(line)
            if (header != null) {
                flush()
                stamp = header
                offset = if (index < starts.size) starts[index] else 0
                message = line.substring(line.indexOf(']') + 1).trim()
                detail.setLength(0)
            } else if (line.isNotEmpty()) {
                if (stamp == null) {
                    truncated = true
                } else {
                    if (detail.isNotEmpty()) detail.append('\n')
                    detail.append(line)
                }
            }
        }
        flush()

        if (tailCut && result.isNotEmpty()) {
            result[result.size - 1] = result[result.size - 1].copy(incomplete = true)
        }
        return FileTail(result, truncated)
    }

    private fun headerStamp(line: String): String? {
        if (!line.startsWith("[")) return null
        val close = line.indexOf(']')
        if (close < 2) return null
        val stamp = line.substring(1, close)
        if (stamp.length < LEGACY_STAMP_LENGTH) return null
        stamp.forEachIndexed { index, char ->
            val valid = when (index) {
                4, 7 -> char == '-'
                10 -> char == 'T'
                13, 16 -> char == ':'
                19 -> char == '.'
                else -> char.isDigit()
            }
            if (!valid) return null
        }
        return stamp
    }

    private fun deleteTree(file: File, failures: MutableList<Failure>): Int {
        var count = 0
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children == null) {
                addFailure(failures, file.absolutePath, "无法列出目录")
            } else {
                children.forEach { child ->
                    if (child.isDirectory) {
                        count += deleteTree(child, failures)
                    } else if (child.delete()) {
                        count++
                    } else {
                        addFailure(failures, child.absolutePath, "删除失败")
                    }
                }
            }
            if (!file.delete()) addFailure(failures, file.absolutePath, "删除失败")
        } else if (file.delete()) {
            count++
        } else {
            addFailure(failures, file.absolutePath, "删除失败")
        }
        return count
    }

    private fun addFailure(failures: MutableCollection<Failure>, path: String, reason: String) {
        if (failures.size < MAX_FAILURES) failures += Failure(path, reason)
    }

    private fun reasonOf(error: Throwable): String =
        error.message?.take(REASON_LIMIT) ?: error.javaClass.simpleName

    companion object {
        const val FRAMEWORK_MODULE = "_framework"
        const val MAX_ENTRIES = 12000
        const val TAIL_LINES_PER_FILE = 2000
        const val TAIL_BYTES_PER_FILE = 512 * 1024
        const val READ_CONCURRENCY = 4

        private const val LOG_DIR = ".logs"
        private const val LOG_SUFFIX = ".log"
        private const val DATA_PATH = "/storage/emulated/0/Android/data"
        private const val STAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS"
        private const val STAMP_LENGTH = 23
        private const val LEGACY_STAMP_LENGTH = 19
        private const val MAX_FAILURES = 20
        private const val REASON_LIMIT = 120
        private const val NEWLINE: Byte = 0x0A
    }
}
