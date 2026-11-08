package io.xpatch.data

import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import io.xpatch.patch.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Locale

class RuntimeLogStore(
    private var pfdReader: ((String) -> ParcelFileDescriptor?)? = { path -> ShizukuBridge.openRead(path) },
) {

    enum class LogSourceKind {
        EXTERNAL,

        PRIVATE_FILES,
    }

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

    class FileCursor(
        val path: String,
        val target: String,
        val module: String,
        internal var cursor: Long,
        val viaShell: Boolean = false,
        val source: LogSourceKind = LogSourceKind.EXTERNAL,
        internal val totalBytes: Long = 0L,
    ) {
        internal var exhausted: Boolean = false
    }

    class LogCursor internal constructor(
        internal val files: List<FileCursor>,
        internal val failures: MutableList<Failure>,
        internal val truncated: Boolean,
        internal var shellReader: ShellReader? = null,
    ) {
        val fileCount: Int get() = files.size

        val viaShell: Boolean get() = shellReader != null || files.any { it.viaShell }

        internal fun hasMore(): Boolean = files.any { !it.exhausted && it.cursor > 0L }

        internal fun pendingFileCount(): Int = files.count { !it.exhausted && it.cursor > 0L }
    }

    data class Page(
        val entries: List<Entry>,
        val cursor: LogCursor,
        val done: Boolean,
        val headTruncated: Boolean,
        val failures: List<Failure>,
        val readAtMillis: Long,
    ) {
        val totalLoaded: Int get() = entries.size
    }

    data class Snapshot(
        val entries: List<Entry>,
        val cursor: LogCursor,
        val done: Boolean,
        val headTruncated: Boolean,
        val failures: List<Failure>,
        val readAtMillis: Long,
        val viaShizuku: Boolean = false,
        val sources: Set<LogSourceKind> = emptySet(),
    ) {
        val totalLoaded: Int get() = entries.size

        val appCount: Int get() = entries.mapTo(HashSet<String>()) { it.target }.size

        val moduleCount: Int get() = entries.mapTo(HashSet<String>()) { it.module }.size

        val loadedFiles: Int get() = entries.mapTo(HashSet<String>()) { it.sourcePath }.size

        val fileCount: Int get() = cursor.fileCount

        val pendingFiles: Int get() = cursor.pendingFileCount()

        val hasMore: Boolean get() = !done

        val hasPrivateDir: Boolean get() = LogSourceKind.PRIVATE_FILES in sources
    }

    fun interface FileReader {
        fun read(path: String, maxBytes: Int): String?
    }

    fun interface ShellReader {
        fun read(command: String): String?
    }

    fun hasAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun openCursor(targets: Collection<String>): LogCursor {
        val failures = ArrayList<Failure>()
        val files = ArrayList<FileCursor>()
        collectLocalFiles(targets, files, failures)
        files.sortWith(compareByDescending<FileCursor> { it.path })
        return LogCursor(files, failures, truncated = false)
    }

    private fun collectLocalFiles(
        targets: Collection<String>,
        files: MutableList<FileCursor>,
        failures: MutableList<Failure>,
    ) {
        if (targets.isEmpty()) {
            childrenOf(dataRoot(), failures)?.forEach { appDir ->
                collectFiles(File(appDir, LOG_DIR), appDir.name, files, failures)
            }
        } else {
            targets.forEach { target ->
                collectFiles(logRootFor(target), target, files, failures)
            }
        }
    }

    suspend fun loadNextPage(
        cursor: LogCursor,
        pageBytes: Int = PAGE_BYTES,
    ): Page = withContext(Dispatchers.IO) {
        val pending = cursor.files.filter { !it.exhausted && it.cursor > 0L }
        val budget = if (pending.isEmpty()) 0 else {
            (pageBytes / pending.size).coerceAtLeast(MIN_SLICE_BYTES)
        }
        val entries = ArrayList<Entry>(budget / 128)
        var truncated = cursor.truncated

        for (file in pending) {
            val slice = runCatching { readSlice(file, budget) }.getOrElse { error ->
                addFailure(cursor.failures, file.path, reasonOf(error))
                null
            } ?: continue
            entries += slice.entries
            truncated = truncated || slice.truncated
            if (slice.bytesRead > 0) file.cursor -= slice.bytesRead
            if (file.cursor <= 0L) {
                file.cursor = 0L
                file.exhausted = true
            } else if (slice.bytesRead <= 0L) {
                file.exhausted = true
            }
        }

        Page(
            entries = entries,
            cursor = cursor,
            done = !cursor.hasMore(),
            headTruncated = truncated,
            failures = cursor.failures.toList(),
            readAtMillis = System.currentTimeMillis(),
        )
    }

    suspend fun loadFirstPage(targets: Collection<String>): Snapshot {
        val cursor = openCursor(targets)
        val page = loadNextPage(cursor)
        return Snapshot(
            entries = page.entries.sortedByDescending { it.timeMillis },
            cursor = page.cursor,
            done = page.done,
            headTruncated = page.headTruncated,
            failures = page.failures,
            readAtMillis = page.readAtMillis,
            sources = sourcesOf(cursor),
        )
    }

    suspend fun loadMore(current: Snapshot, pageBytes: Int = PAGE_BYTES): Snapshot {
        if (current.done) return current
        val shell = current.cursor.shellReader
        val page = if (shell != null) {
            withContext(Dispatchers.IO) {
                readNextShellPage(shell, current.cursor, pageBytes, fileReader = null)
            }
        } else {
            loadNextPage(current.cursor, pageBytes)
        }
        if (page.entries.isEmpty() && page.done) {
            return current.copy(done = true, readAtMillis = page.readAtMillis)
        }
        val merged = ArrayList<Entry>(current.entries.size + page.entries.size)
        merged += current.entries
        merged += page.entries
        merged.sortWith(compareByDescending { it.timeMillis })
        return current.copy(
            entries = merged,
            done = page.done,
            headTruncated = current.headTruncated || page.headTruncated,
            failures = page.failures,
            readAtMillis = page.readAtMillis,
        )
    }

    private data class Slice(val entries: List<Entry>, val truncated: Boolean, val bytesRead: Long)

    private fun readSlice(file: FileCursor, maxBytes: Int): Slice? {
        RandomAccessFile(file.path, "r").use { raf ->
            val end = file.cursor
            if (end <= 0L) return null
            val start = (end - maxBytes).coerceAtLeast(0L)
            val size = (end - start).toInt()
            if (size <= 0) return null

            val bytes = ByteArray(size)
            raf.seek(start)
            raf.readFully(bytes)

            val aligned = start == 0L || startsAtLineBoundary(raf, start)
            return sliceFromBytes(bytes, aligned, start, file)
        }
    }

    private fun sliceFromBytes(
        bytes: ByteArray,
        aligned: Boolean,
        absoluteStart: Long,
        file: FileCursor,
    ): Slice {
        if (bytes.isEmpty()) return Slice(emptyList(), true, 0L)
        val size = bytes.size.toLong()

        var base = 0
        if (!aligned) {
            val firstBreak = bytes.indexOf(NEWLINE)
            if (firstBreak < 0) return Slice(emptyList(), true, size)
            base = firstBreak + 1
        }

        var endIndex = bytes.size
        val tailCut = bytes[endIndex - 1] != NEWLINE
        if (tailCut) {
            val lastBreak = bytes.lastIndexOf(NEWLINE)
            endIndex = if (lastBreak < base) base else lastBreak + 1
        }
        if (endIndex <= base) return Slice(emptyList(), true, size)

        val parsed = parseBlock(
            bytes = bytes,
            base = base,
            end = endIndex,
            path = file.path,
            target = file.target,
            module = file.module,
            absoluteStart = absoluteStart,
        )
        val consumed = if (aligned) size else size - base
        return Slice(parsed, tailCut, consumed)
    }

    private fun startsAtLineBoundary(raf: RandomAccessFile, offset: Long): Boolean {
        if (offset <= 0L) return true
        raf.seek(offset - 1)
        return raf.read() == NEWLINE.toInt()
    }

    private fun startsAtLineBoundary(channel: FileChannel, offset: Long): Boolean {
        if (offset <= 0L) return true
        val probe = readFullyAt(channel, offset - 1, 1)
        return probe.isNotEmpty() && probe[0] == NEWLINE
    }

    private fun <T> withPfd(path: String, block: (FileChannel) -> T): T? {
        val opener = pfdReader ?: return null
        val descriptor = runCatching { opener(path) }.getOrNull() ?: return null
        val stream = runCatching { FileInputStream(descriptor.fileDescriptor) }.getOrNull()
            ?: run {
                runCatching { descriptor.close() }
                return null
            }
        return try {
            block(stream.channel)
        } catch (error: Exception) {
            null
        } finally {
            runCatching { stream.close() }
            runCatching { descriptor.close() }
        }
    }

    private fun readFullyAt(channel: FileChannel, offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val buffer = ByteBuffer.allocate(length)
        var position = offset
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, position)
            if (read <= 0) break
            position += read
        }
        val filled = buffer.position()
        if (filled <= 0) return ByteArray(0)
        val bytes = ByteArray(filled)
        buffer.flip()
        buffer.get(bytes)
        return bytes
    }

    private fun parseBlock(
        bytes: ByteArray,
        base: Int,
        end: Int,
        path: String,
        target: String,
        module: String,
        absoluteStart: Long,
    ): List<Entry> {
        val format = SimpleDateFormat(STAMP_PATTERN, Locale.US)
        format.isLenient = false
        val result = ArrayList<Entry>()

        var lineStart = base
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

        var cursor = base
        while (cursor < end) {
            if (bytes[cursor] == NEWLINE || cursor == end - 1) {
                val lineEnd = if (bytes[cursor] == NEWLINE) cursor else end
                val line = String(bytes, lineStart, lineEnd - lineStart, Charsets.UTF_8)
                    .let { if (it.endsWith("\r")) it.dropLast(1) else it }
                val header = headerStamp(line)
                if (header != null) {
                    flush()
                    stamp = header
                    offset = (absoluteStart + lineStart).toInt()
                    message = line.substring(line.indexOf(']') + 1).trim()
                    detail.setLength(0)
                } else if (line.isNotEmpty() && stamp != null) {
                    if (detail.isNotEmpty()) detail.append('\n')
                    detail.append(line)
                }
                lineStart = cursor + 1
            }
            cursor++
        }
        flush()
        return result
    }

    suspend fun scanAll(
        targets: Collection<String>,
        chunkBytes: Int = SCAN_CHUNK_BYTES,
        onChunk: (List<Entry>) -> Unit,
    ): List<Failure> = withContext(Dispatchers.IO) {
        val failures = ArrayList<Failure>()
        val files = ArrayList<FileCursor>()
        if (targets.isEmpty()) {
            childrenOf(dataRoot(), failures)?.forEach { appDir ->
                collectFiles(File(appDir, LOG_DIR), appDir.name, files, failures)
            }
        } else {
            targets.forEach { target -> collectFiles(logRootFor(target), target, files, failures) }
        }
        files.sortWith(compareByDescending<FileCursor> { it.path })
        files.forEach { file ->
            while (file.cursor > 0L) {
                val slice = runCatching { readSlice(file, chunkBytes) }.getOrElse { error ->
                    addFailure(failures, file.path, reasonOf(error))
                    null
                } ?: break
                if (slice.entries.isNotEmpty()) onChunk(slice.entries)
                if (slice.bytesRead <= 0L) break
                file.cursor -= slice.bytesRead
            }
        }
        failures.toList()
    }

    private fun collectFiles(
        dir: File,
        target: String,
        files: MutableList<FileCursor>,
        failures: MutableCollection<Failure>,
    ) {
        if (!dir.isDirectory) return
        childrenOf(dir, failures)?.forEach { moduleDir ->
            if (!moduleDir.isDirectory) return@forEach
            logFilesOf(moduleDir, failures)?.forEach { file ->
                files += FileCursor(
                    path = file.absolutePath,
                    target = target,
                    module = moduleDir.name,
                    cursor = file.length(),
                    source = LogSourceKind.EXTERNAL,
                    totalBytes = file.length(),
                )
            }
        }
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

    data class LogFileInfo(
        val path: String,
        val target: String,
        val module: String,
        val size: Long,
    )

    fun listLocalFiles(
        targets: Collection<String>,
    ): Pair<List<LogFileInfo>, List<Failure>> {
        val failures = ArrayList<Failure>()
        val files = ArrayList<FileCursor>()
        collectLocalFiles(targets, files, failures)
        val infos = files.mapNotNull { cursor ->
            val file = File(cursor.path)
            if (!file.isFile) null
            else LogFileInfo(
                path = cursor.path,
                target = cursor.target,
                module = cursor.module,
                size = file.length(),
            )
        }
        return infos to failures.toList()
    }

    fun listShellFiles(
        reader: ShellReader,
        targets: Collection<String>,
    ): List<LogFileInfo> {
        val files = ArrayList<FileCursor>()
        collectExternalShellFiles(reader, targets, files)
        collectPrivateShellFiles(reader, targets, files)
        return files.map {
            LogFileInfo(
                path = it.path,
                target = it.target,
                module = it.module,
                size = it.totalBytes,
            )
        }
    }

    suspend fun deleteViaShell(
        reader: ShellReader,
        paths: Collection<String>,
    ): ClearResult = withContext(Dispatchers.IO) {
        val failures = mutableListOf<Failure>()
        var deleted = 0
        val apps = HashSet<String>()
        paths.chunked(20).forEach { chunk ->
            val quoted = chunk.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
            val output = reader.read("rm -f $quoted && echo __ok__")
            if (output?.contains("__ok__") == true) {
                deleted += chunk.size
                chunk.forEach { path ->
                    val segments = path.split('/')
                    val pkg = segments.getOrNull(segments.indexOf("data") - 1)
                    if (pkg != null && pkg.contains('.')) apps += pkg
                }
            } else {
                chunk.forEach { addFailure(failures, it, "shell 删除失败") }
            }
        }
        ClearResult(deletedFiles = deleted, affectedApps = apps.size, failed = failures.toList())
    }

    suspend fun clearFiles(paths: Collection<String>): ClearResult = withContext(Dispatchers.IO) {
        val failures = mutableListOf<Failure>()
        val apps = HashSet<String>()
        var deletedFiles = 0
        val unique = paths.map { File(it).absolutePath }.toSet()
        unique.forEach { path ->
            val file = File(path)
            if (!file.isFile) return@forEach
            val ok = runCatching { file.delete() }.getOrDefault(false)
            if (ok) {
                deletedFiles++
                val segments = file.absolutePath.split(File.separatorChar)
                val pkgIndex = segments.indexOfFirst { it.contains('.') && it != "Android" && it != "data" && it != "media" }
                if (pkgIndex >= 0) apps += segments[pkgIndex]
            } else {
                addFailure(failures, path, "删除失败（文件被占用或无权限）")
            }
        }
        unique.map { File(it).parentFile }.filterNotNull().distinct().forEach { dir ->
            runCatching {
                if (dir.isDirectory && dir.listFiles().isNullOrEmpty()) {
                    dir.delete()
                    val parent = dir.parentFile
                    if (parent != null && parent.isDirectory && parent.listFiles().isNullOrEmpty()) parent.delete()
                }
            }
        }
        ClearResult(
            deletedFiles = deletedFiles,
            affectedApps = apps.size,
            failed = failures.toList(),
        )
    }

    @Deprecated("改用 exportTo() 或导出侧 stream()，避免拼接超大字符串")
    fun export(entries: List<Entry>): String {
        val builder = StringBuilder(entries.size * 96)
        entries.forEachIndexed { index, entry ->
            if (index > 0) builder.append('\n')
            builder.append(entry.fullText)
        }
        return builder.toString()
    }

    fun exportTo(entries: Iterable<Entry>, sink: (String) -> Unit) {
        entries.forEach { sink(it.fullText) }
    }


    suspend fun loadViaShell(
        reader: ShellReader,
        fileReader: FileReader,
        targets: Collection<String>,
    ): Snapshot? = withContext(Dispatchers.IO) {
        loadShellSnapshot(
            reader = reader,
            fileReader = fileReader,
            targets = targets,
            includeExternal = true,
            includePrivate = true,
        )
    }

    suspend fun loadPrivateViaShell(
        reader: ShellReader,
        targets: Collection<String>,
    ): Snapshot? = withContext(Dispatchers.IO) {
        loadShellSnapshot(
            reader = reader,
            fileReader = null,
            targets = targets,
            includeExternal = false,
            includePrivate = true,
        )
    }

    suspend fun loadFirstPageMerged(
        reader: ShellReader,
        targets: Collection<String>,
        fileReader: FileReader? = null,
        pageBytes: Int = PAGE_BYTES,
    ): Snapshot? = withContext(Dispatchers.IO) {
        val failures = mutableListOf<Failure>()
        val files = mutableListOf<FileCursor>()
        collectLocalFiles(targets, files, failures)
        if (files.isEmpty()) {
            collectExternalShellFiles(reader, targets, files)
        }
        collectPrivateShellFiles(reader, targets, files)
        if (files.isEmpty()) return@withContext null
        files.sortWith(compareByDescending<FileCursor> { it.path })
        val cursor = LogCursor(files, failures, truncated = false, shellReader = reader)
        val page = readNextShellPage(reader, cursor, pageBytes, fileReader)
        Snapshot(
            entries = page.entries.sortedByDescending { it.timeMillis },
            cursor = page.cursor,
            done = page.done,
            headTruncated = page.headTruncated,
            failures = page.failures,
            readAtMillis = page.readAtMillis,
            viaShizuku = files.any { it.viaShell },
            sources = sourcesOf(cursor),
        )
    }

    suspend fun loadNextViaShell(
        reader: ShellReader,
        cursor: LogCursor,
        pageBytes: Int = PAGE_BYTES,
    ): Snapshot = withContext(Dispatchers.IO) {
        if (cursor.shellReader == null) cursor.shellReader = reader
        val page = readNextShellPage(reader, cursor, pageBytes, fileReader = null)
        Snapshot(
            entries = page.entries.sortedByDescending { it.timeMillis },
            cursor = page.cursor,
            done = page.done,
            headTruncated = page.headTruncated,
            failures = page.failures,
            readAtMillis = page.readAtMillis,
            viaShizuku = true,
            sources = sourcesOf(cursor),
        )
    }

    private fun loadShellSnapshot(
        reader: ShellReader,
        fileReader: FileReader?,
        targets: Collection<String>,
        includeExternal: Boolean,
        includePrivate: Boolean,
    ): Snapshot? {
        val cursor = openShellCursor(reader, targets, includeExternal, includePrivate)
        if (cursor.files.isEmpty()) return null
        val page = readNextShellPage(reader, cursor, PAGE_BYTES, fileReader)
        return Snapshot(
            entries = page.entries.sortedByDescending { it.timeMillis },
            cursor = page.cursor,
            done = page.done,
            headTruncated = page.headTruncated,
            failures = page.failures,
            readAtMillis = page.readAtMillis,
            viaShizuku = true,
            sources = sourcesOf(cursor),
        )
    }

    private fun openShellCursor(
        reader: ShellReader,
        targets: Collection<String>,
        includeExternal: Boolean,
        includePrivate: Boolean,
    ): LogCursor {
        val failures = mutableListOf<Failure>()
        val files = mutableListOf<FileCursor>()
        if (includeExternal) collectExternalShellFiles(reader, targets, files)
        if (includePrivate) collectPrivateShellFiles(reader, targets, files)
        files.sortWith(compareByDescending<FileCursor> { it.path })
        return LogCursor(files, failures, truncated = false, shellReader = reader)
    }

    private fun collectExternalShellFiles(
        reader: ShellReader,
        targets: Collection<String>,
        files: MutableList<FileCursor>,
    ) {
        val roots = if (targets.isEmpty()) {
            shellLines(reader, "ls $DATA_PATH 2>/dev/null")
        } else {
            targets.toList()
        }
        for (target in roots) {
            val appLogs = "$DATA_PATH/$target/$LOG_DIR"
            for (module in shellLines(reader, "ls $appLogs 2>/dev/null")) {
                val moduleDir = "$appLogs/$module"
                for (name in shellLines(reader, "ls $moduleDir 2>/dev/null")) {
                    if (!name.endsWith(LOG_SUFFIX)) continue
                    val path = "$moduleDir/$name"
                    val size = shellSizeOf(reader, path) ?: continue
                    files += FileCursor(
                        path = path,
                        target = target,
                        module = module,
                        cursor = size,
                        viaShell = true,
                        source = LogSourceKind.EXTERNAL,
                        totalBytes = size,
                    )
                }
            }
        }
    }

    private fun collectPrivateShellFiles(
        reader: ShellReader,
        targets: Collection<String>,
        files: MutableList<FileCursor>,
    ) {
        for ((pkg, root) in privateRootsOf(reader, targets)) {
            for (module in shellLines(reader, "ls $root 2>/dev/null")) {
                val moduleDir = "$root/$module"
                for (name in shellLines(reader, "ls $moduleDir 2>/dev/null")) {
                    if (!name.endsWith(LOG_SUFFIX)) continue
                    val path = "$moduleDir/$name"
                    val size = shellSizeOf(reader, path) ?: continue
                    files += FileCursor(
                        path = path,
                        target = pkg,
                        module = module,
                        cursor = size,
                        viaShell = true,
                        source = LogSourceKind.PRIVATE_FILES,
                        totalBytes = size,
                    )
                }
            }
        }
    }

    private fun privateRootsOf(reader: ShellReader, targets: Collection<String>): List<Pair<String, String>> {
        val wanted = if (targets.isEmpty()) null else targets.toHashSet()
        return shellLines(reader, "ls -d $PRIVATE_DATA_ROOT/*/$PRIVATE_FILES_DIR/$PRIVATE_LOG_DIR 2>/dev/null")
            .mapNotNull { path ->
                val trimmed = path.trimEnd('/')
                val parts = trimmed.split('/')
                if (parts.size != 6) return@mapNotNull null
                if (parts[1] != "data" || parts[2] != "data") return@mapNotNull null
                if (parts[4] != PRIVATE_FILES_DIR || parts[5] != PRIVATE_LOG_DIR) return@mapNotNull null
                val pkg = parts[3]
                if (pkg.isEmpty()) return@mapNotNull null
                if (wanted != null && pkg !in wanted) return@mapNotNull null
                pkg to trimmed
            }
    }

    private fun readNextShellPage(
        reader: ShellReader,
        cursor: LogCursor,
        pageBytes: Int,
        fileReader: FileReader?,
    ): Page {
        val pending = cursor.files.filter { !it.exhausted && it.cursor > 0L }
        val budget = if (pending.isEmpty()) 0 else {
            (pageBytes / pending.size).coerceAtLeast(MIN_SLICE_BYTES)
        }
        val entries = ArrayList<Entry>(budget / 128)
        var truncated = cursor.truncated

        for (file in pending) {
            if (!file.viaShell) {
                val slice = runCatching { readSlice(file, budget) }.getOrElse { error ->
                    addFailure(cursor.failures, file.path, reasonOf(error))
                    null
                } ?: continue
                entries += slice.entries
                truncated = truncated || slice.truncated
                if (slice.bytesRead > 0) file.cursor -= slice.bytesRead
                if (file.cursor <= 0L) {
                    file.cursor = 0L
                    file.exhausted = true
                } else if (slice.bytesRead <= 0L) {
                    file.exhausted = true
                }
                continue
            }
            val slice = runCatching { readSliceViaShell(reader, fileReader, file, budget) }
                .getOrElse { error ->
                    addFailure(cursor.failures, file.path, reasonOf(error))
                    null
                } ?: continue
            entries += slice.entries
            truncated = truncated || slice.truncated
            if (slice.bytesRead > 0) file.cursor -= slice.bytesRead
            if (file.cursor <= 0L) {
                file.cursor = 0L
                file.exhausted = true
            } else if (slice.bytesRead <= 0L) {
                file.exhausted = true
            }
        }

        return Page(
            entries = entries,
            cursor = cursor,
            done = !cursor.hasMore(),
            headTruncated = truncated,
            failures = cursor.failures.toList(),
            readAtMillis = System.currentTimeMillis(),
        )
    }

    private fun readSliceViaPfd(file: FileCursor, maxBytes: Int): Slice? {
        val end = file.cursor
        if (end <= 0L) return null
        val start = (end - maxBytes).coerceAtLeast(0L)
        val size = (end - start).toInt()
        if (size <= 0) return null
        return withPfd(file.path) { channel ->
            val bytes = readFullyAt(channel, start, size)
            if (bytes.isEmpty()) {
                Slice(entries = emptyList(), truncated = true, bytesRead = 0L)
            } else {
                sliceFromBytes(bytes, startsAtLineBoundary(channel, start), start, file)
            }
        }
    }

    private fun readSliceViaShell(
        reader: ShellReader,
        fileReader: FileReader?,
        file: FileCursor,
        maxBytes: Int,
    ): Slice? {
        val end = file.cursor
        if (end <= 0L) return null

        readSliceViaPfd(file, maxBytes)?.let { return it }


        if (end == file.totalBytes && end <= maxBytes) {
            val content = fileReader?.read(file.path, end.toInt())
                ?: reader.read("cat ${shellQuote(file.path)} 2>/dev/null")
            if (!content.isNullOrEmpty()) {
                val bytes = content.toByteArray(Charsets.UTF_8)
                if (bytes.isNotEmpty()) {
                    return sliceFromBytes(bytes, aligned = true, absoluteStart = 0L, file = file)
                        .copy(bytesRead = end)
                }
            }
        }

        val start = (end - maxBytes).coerceAtLeast(0L)
        val size = (end - start).toInt()
        if (size <= 0) return null

        if (start <= 0L) {
            val raw = readShellRange(reader, file.path, 0L, size) ?: return null
            val bytes = raw.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty()) return null
            return sliceFromBytes(bytes, aligned = true, absoluteStart = 0L, file = file)
        }

        val raw = readShellRange(reader, file.path, start - 1, size + 1) ?: return null
        if (raw.isEmpty()) return null
        val aligned = raw[0] == '\n'
        val body = if (aligned) raw.substring(1) else raw
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return null
        return sliceFromBytes(
            bytes = bytes,
            aligned = aligned,
            absoluteStart = if (aligned) start else start - 1,
            file = file,
        )
    }

    private fun readShellRange(reader: ShellReader, path: String, offset: Long, length: Int): String? {
        if (length <= 0) return null
        val quoted = shellQuote(path)
        val first = (offset + 1L).coerceAtLeast(1L)
        return reader.read("tail -c +$first $quoted 2>/dev/null | head -c $length")
            ?.takeIf { it.isNotEmpty() }
    }

    private fun shellSizeOf(reader: ShellReader, path: String): Long? {
        val quoted = shellQuote(path)
        reader.read("wc -c $quoted 2>/dev/null")
            ?.trim()
            ?.split(WHITESPACE, limit = 2)
            ?.firstOrNull()
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }
            ?.let { return it }
        return reader.read("stat -c %s $quoted 2>/dev/null")
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() }
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }
    }

    private fun shellLines(reader: ShellReader, command: String): List<String> =
        reader.read(command)
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toList()
            .orEmpty()

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun sourcesOf(cursor: LogCursor): Set<LogSourceKind> {
        if (cursor.files.isEmpty()) return emptySet()
        return cursor.files.mapTo(HashSet()) { it.source }
    }

    private fun dataRoot(): File = File(Environment.getExternalStorageDirectory(), "Android/data")

    private fun logRootFor(target: String): File = File(dataRoot(), target)

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

    companion object {
        const val FRAMEWORK_MODULE = "_framework"

        const val PAGE_BYTES = 256 * 1024

        const val MIN_SLICE_BYTES = 8 * 1024

        const val SCAN_CHUNK_BYTES = 1024 * 1024

        private const val LOG_DIR = ".logs"
        private const val LOG_SUFFIX = ".log"
        private const val DATA_PATH = "/storage/emulated/0/Android/data"

        private const val PRIVATE_DATA_ROOT = "/data/data"
        private const val PRIVATE_FILES_DIR = "files"

        private const val PRIVATE_LOG_DIR = "xpatch-logs"

        private val WHITESPACE = Regex("\\s+")

        private const val STAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS"
        private const val STAMP_LENGTH = 23
        private const val LEGACY_STAMP_LENGTH = 19
        private const val MAX_FAILURES = 20
        private const val REASON_LIMIT = 120
        private const val NEWLINE: Byte = 0x0A
    }
}
