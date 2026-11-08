package io.xpatch.data

import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 运行日志的增量读取层。
 *
 * 设计要点（对应"能容纳并查看全部日志，但不一次性渲染"）：
 *
 * 1. **没有条数上限**。总条数由磁盘内容决定，不再是 12000 这种硬闸门。
 * 2. **按文件反向分页**。日志按时间追加写入，所以从文件尾部往前读，
 *    每页只解析固定字节数，天然得到"最新在前"的第一页。
 * 3. **游标而不是拷贝**。[LogCursor] 记录每个文件"已经读到哪个偏移"，
 *    加载下一页 = 接着往更早的位置推进，不需要重新解析已加载内容，
 *    也不会持有整个文件的字节数组。
 * 4. **全量扫描只在搜索/导出时发生**，并且是流式的，逐条交给回调。
 *
 * 单条 Entry 的字段与旧实现保持一致，UI 层无需感知读取方式的变化。
 *
 * 内核 [io.xpatch.hook.LogSupport] 的写入顺序是三级降级：
 * ① 外部私有目录 Android/data/<pkg>/.logs/<module>/（本类直接扫）
 * ② 应用内部私有目录 /data/data/<pkg>/files/xpatch-logs/<module>/（只能经 shell 读，见 [loadPrivateViaShell]）
 * 因此③这类日志在无存储权限时也存在，但普通 App 读不到，必须 root / Shizuku shell。
 */
class RuntimeLogStore {

    /** 一条日志来自哪个存储位置。 */
    enum class LogSourceKind {
        /** 外部私有目录 Android/data/<pkg>/.logs（历史上还有 Android/media/<pkg>/.logs）。 */
        EXTERNAL,

        /**
         * 应用内部私有目录 /data/data/<pkg>/files/xpatch-logs。
         *
         * 内核在外部存储不可写时会降级写到这里；普通 App 无权限，只能经 root / Shizuku shell 读。
         */
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

    /** 一个日志文件的读取状态。cursor 指向"尚未读取的最右端"（已读区间的左界）。 */
    class FileCursor(
        val path: String,
        val target: String,
        val module: String,
        internal var cursor: Long,
        /** 该文件只能经 shell 读（私有目录 / shell 枚举出来的文件）。 */
        val viaShell: Boolean = false,
        /** 该文件来自哪一类目录，供 UI 提示"私有目录需 Shizuku"。 */
        val source: LogSourceKind = LogSourceKind.EXTERNAL,
        /** 枚举时文件的总大小，用于判断"这一段是否就是整个文件"。本地文件不依赖它。 */
        internal val totalBytes: Long = 0L,
    ) {
        internal var exhausted: Boolean = false
    }

    /**
     * 分页游标。可在两次 load 之间复用，实现"往下滚就继续加载"。
     *
     * [shellReader] 非空表示这批文件只能经 shell 读；[loadMore] 会自动改走
     * [loadNextViaShell]，因此调用方不需要关心游标是本地来的还是 shell 来的。
     */
    class LogCursor internal constructor(
        internal val files: List<FileCursor>,
        internal val failures: MutableList<Failure>,
        internal val truncated: Boolean,
        internal var shellReader: ShellReader? = null,
    ) {
        val fileCount: Int get() = files.size

        /** 该游标是否依赖 shell（root / Shizuku）。 */
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

    /**
     * 累积视图：已加载的日志 + 剩余部分的游标。
     *
     * [done] 为 false 表示磁盘上还有更早的日志没读，UI 应当继续提供"加载更多"。
     * 这里没有条数上限，[totalLoaded] 只是"当前已加载多少"，不是容量上限。
     */
    data class Snapshot(
        val entries: List<Entry>,
        val cursor: LogCursor,
        val done: Boolean,
        val headTruncated: Boolean,
        val failures: List<Failure>,
        val readAtMillis: Long,
        val viaShizuku: Boolean = false,
        /** 这批条目覆盖了哪些来源目录；默认空集，老调用点完全不受影响。 */
        val sources: Set<LogSourceKind> = emptySet(),
    ) {
        val totalLoaded: Int get() = entries.size

        val appCount: Int get() = entries.mapTo(HashSet<String>()) { it.target }.size

        val moduleCount: Int get() = entries.mapTo(HashSet<String>()) { it.module }.size

        val loadedFiles: Int get() = entries.mapTo(HashSet<String>()) { it.sourcePath }.size

        val fileCount: Int get() = cursor.fileCount

        /** 仍未读取的文件数，用于告诉用户"还有多少没加载"。 */
        val pendingFiles: Int get() = cursor.pendingFileCount()

        val hasMore: Boolean get() = !done

        /**
         * 是否包含应用私有目录（/data/data/<pkg>/files/xpatch-logs）里的日志。
         *
         * UI 可据此提示"该应用日志在私有目录，需 Shizuku 才能读取"。
         */
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

    /**
     * 建立游标：只枚举文件、记录大小，不读取内容。
     *
     * @param targets 目标应用包名；为空表示扫描 Android/data 下全部应用。
     */
    fun openCursor(targets: Collection<String>): LogCursor {
        val failures = ArrayList<Failure>()
        val files = ArrayList<FileCursor>()
        collectLocalFiles(targets, files, failures)
        // 新的文件先读：文件名里带日期，且内核按追加+轮转写，这个顺序与"最新在前"最接近。
        files.sortWith(compareByDescending<FileCursor> { it.path })
        return LogCursor(files, failures, truncated = false)
    }

    /** 枚举本地可读的外部私有日志文件（openCursor / 合并首屏共用）。 */
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

    /**
     * 读取下一页（更早的一段日志），并把游标向前推进。
     *
     * 每页总字节预算约 [PAGE_BYTES]，由所有文件均摊；单个文件不足时自动跳过。
     * 这样无论磁盘上是 5 MB 还是 5 GB，单次加载的内存占用都是常量级。
     */
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
                // 读不出进度（空返回 / 权限变化）：标记放弃，避免"还有更多"永远为真。
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

    /**
     * 首屏加载：只读最新的一页。
     *
     * 打开日志页时不再"把磁盘上所有日志解析一遍"，而是立刻显示最新一段，
     * 剩下的通过 [loadMore] 按需增量加载。
     */
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

    /**
     * 续页：在已有快照基础上继续往前读，返回合并后的新快照。
     *
     * 合并后仍保持"最新在前"。已加载条目不做重新排序以外的任何拷贝放大，
     * 内存里始终只持有"用户已经滚到过的那部分"。
     *
     * 若游标来自 shell（[LogCursor.shellReader] 非空），这里会自动改走
     * [loadNextViaShell]：调用方仍然只调 [loadMore]，签名与语义都不变。
     */
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

    /**
     * 从 file.cursor 往前读至多 maxBytes，解析成条目。
     *
     * 关键细节：切在字节中间会把首行切碎，因此读到一段后要把开头不完整的行丢掉，
     * 并把"已经消费到哪"如实回传给调用方，保证下一页不会漏行也不会重复。
     */
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

            // 起始位置若不是行首，丢弃第一段残行。
            val aligned = start == 0L || startsAtLineBoundary(raf, start)
            return sliceFromBytes(bytes, aligned, start, file)
        }
    }

    /**
     * 本地文件与 shell 读取共用的解析入口。
     *
     * 抽出来的目的：无论字节是从 [RandomAccessFile] 读来的，还是 `tail -c | head -c` 从
     * 私有目录取回来的，行边界对齐与[Entry]的产出必须完全一致（[parseBlock] 复用同一套规则）。
     */
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

        // 尾部：cursor 本身就是行边界（上页已对齐），但最后一页可能落在文件末尾半行。
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
        // 只有真正被解析消费的字节推进游标；被丢掉的残行留给下一页重新对齐。
        val consumed = if (aligned) size else size - base
        return Slice(parsed, tailCut, consumed)
    }

    private fun startsAtLineBoundary(raf: RandomAccessFile, offset: Long): Boolean {
        if (offset <= 0L) return true
        raf.seek(offset - 1)
        return raf.read() == NEWLINE.toInt()
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

    /**
     * 全量流式扫描：搜索与导出使用。
     *
     * 逐文件、逐块回调，绝不把全部条目聚合进内存；
     * 调用方可以随时统计、过滤、写出，内存占用同样与日志总量无关。
     *
     * 只扫本地可读的外部私有目录；私有目录（需 root / Shizuku）请用 [loadPrivateViaShell]。
     */
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

    /**
     * 磁盘上可清理的日志文件（按应用分组前的原始清单）。
     *
     * 只枚举本地可读目录（Android/data、Android/media）；私有目录在非 root 下读不到，
     * 也就不该出现在"可清理"列表里——否则用户勾了却删不掉。
     */
    data class LogFileInfo(
        val path: String,
        val target: String,
        val module: String,
        val size: Long,
    )

    /** 列出可清理的日志文件。失败信息通过 [failures] 返回，便于 UI 提示。 */
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

    /**
     * 经 shell 枚举可清理文件（本地读不到 Android/data 时，例如只有 Shizuku 权限）。
     *
     * 与 [listLocalFiles] 同构，区别只是取数据要过 binder；返回的 path 可直接交给 [deleteViaShell]。
     */
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

    /** 经 shell 删除选中的文件（`rm -f`，一次批量执行，失败逐条记入 failures）。 */
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

    /**
     * 只删除选中的文件（日志清理的"选择性删除"）。
     *
     * 与 [clear] 的区别：这里按路径精确删，删空后的模块目录/应用目录一并回收，
     * 但不会碰到未被选中的日志。
     */
    suspend fun clearFiles(paths: Collection<String>): ClearResult = withContext(Dispatchers.IO) {
        val failures = mutableListOf<Failure>()
        val apps = HashSet<String>()
        var deletedFiles = 0
        // 去掉重复路径，并按"最深优先"排序，方便删空目录
        val unique = paths.map { File(it).absolutePath }.toSet()
        unique.forEach { path ->
            val file = File(path)
            if (!file.isFile) return@forEach
            val ok = runCatching { file.delete() }.getOrDefault(false)
            if (ok) {
                deletedFiles++
                // 记录所属应用：路径形如 /.../Android/data/<pkg>/.logs/<module>/x.log
                val segments = file.absolutePath.split(File.separatorChar)
                val pkgIndex = segments.indexOfFirst { it.contains('.') && it != "Android" && it != "data" && it != "media" }
                if (pkgIndex >= 0) apps += segments[pkgIndex]
            } else {
                addFailure(failures, path, "删除失败（文件被占用或无权限）")
            }
        }
        // 回收空目录：模块目录 → 日志目录（只删空目录，绝不递归删除未选内容）
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

    /**
     * 把一批条目拼成文本。
     *
     * 兼容入口，仅为既有调用点保留：它会把全部日志拼成一个超大 String，
     * 条目一多就是一次几百 MB 的分配。新代码请用 [exportTo] 或流式写出。
     */
    @Deprecated("改用 exportTo() 或导出侧 stream()，避免拼接超大字符串")
    fun export(entries: List<Entry>): String {
        val builder = StringBuilder(entries.size * 96)
        entries.forEachIndexed { index, entry ->
            if (index > 0) builder.append('\n')
            builder.append(entry.fullText)
        }
        return builder.toString()
    }

    /** 增量写出一份导出文本，避免把全部日志拼成一个超大 String。 */
    fun exportTo(entries: Iterable<Entry>, sink: (String) -> Unit) {
        entries.forEach { sink(it.fullText) }
    }

    // ------------------------------------------------------------------
    // shell / root 读取（私有目录只能走这条路径）
    // ------------------------------------------------------------------

    /**
     * 经由外部 shell（Shizuku / root）读取的兼容入口。
     *
     * 与本地读取共用同一套解析逻辑，但取数据要经过 binder；同时覆盖两处外部 shell 才能到达的位置：
     * 1. Android/data/<pkg>/.logs —— 老路径（无权限时 App 自己也读不到）
     * 2. /data/data/<pkg>/files/xpatch-logs —— 内核降级写入的私有目录
     *
     * 语义与 [loadFirstPage] 对齐：只读最新一页。之后的翻页由 [loadMore] 自动接管
     * （游标里带着 [ShellReader]，会改走 [loadNextViaShell]）。
     *
     * @param fileReader 直接读文件内容的通道（`ShizukuBridge.readText`）。只在"整个文件能装进一页"时
     *   用作一次读完的快通道（省掉 tail|head 两个进程）；可为 null。
     * @return 没枚举到任何文件（或没有 shell 权限）时返回 null，由调用方决定降级。
     */
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

    /**
     * 只读应用私有目录 /data/data/<pkg>/files/xpatch-logs。
     *
     * 权限语义（重要，决定这个函数在什么设备上有返回值）：
     * - 只有 **root**（例如 Shizuku 以 root 启动、user service 以 uid 0 运行，或 root 通道给的 ShellReader）
     *   才读得到；普通设备上 Shizuku 的 shell（uid 2000）同样被 SELinux 拦在 /data/data/<pkg> 外面。
     * - 读不到时的降级是"就当没有"：命令 stderr 已丢弃、输出为空，函数返回 null，不抛异常。
     *
     * @param targets 目标包名；为空时用一条 `ls -d /data/data/&lt;pkg&gt;/files/xpatch-logs`
     *   让 shell 自己找出所有带私有日志的应用（避免逐包探测）。
     */
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

    /**
     * 首屏合并加载：本地可读的外部日志 + 只能经 shell 读的私有目录日志，一次拿到。
     *
     * 为什么需要它：[loadFirstPage] 只扫本地，[loadViaShell] 通常只在"本地为空"时才会被调用，
     * 于是"外部有日志、私有目录也有日志"的应用会漏掉私有目录那部分。
     * 这里把两批文件放进同一个游标（用 [FileCursor.viaShell] 区分读取通道），
     * 之后的 [loadMore] 会自动按各自的通道继续翻页，调用方不必维护两个游标。
     *
     * 降级语义：
     * - 本地枚举拿不到文件（没有存储权限）时，自动改成经 shell 读同一批外部文件；
     * - 私有目录进不去（无 root / shell 被 SELinux 拦住）时，只是少这部分条目，不影响外部日志；
     * - 两边都没有文件时返回 null。
     *
     * @param fileReader 可选的直读通道（`ShizukuBridge.readText`），只用于"整个文件能装进一页"的优化；可为 null。
     */
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
            // 本地拿不到（无存储权限 / 尚未产生日志）：改由 shell 读同一批外部文件。
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

    /**
     * shell 文件的续页：与 [loadNextPage] 同构，但按字节区间远程取数据。
     *
     * 每次只取 `tail -c +<offset> '<path>' | head -c <bytes>` 那么一段，
     * 行边界对齐规则与本地读取完全一致，因此可以和 [loadMore] 混用。
     */
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
        // 新的文件先读：与本地枚举保持同一种"最新在前"的顺序。
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
                    // wc -c 失败说明不是可读的普通文件（目录 / 权限不足）：跳过，而不是当成 0 字节。
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

    /**
     * 私有日志根：/data/data/<pkg>/files/xpatch-logs。
     *
     * 只发**一条** glob 命令，让 shell 自己列出所有带该目录的应用，再按 targets 过滤：
     * 逐个包探测在"刷新一次日志页"里要付 N 次跨进程往返，代价太高。
     *
     * 没有任何输出时（非 root 设备上 /data/data 不可枚举、或确实没有私有日志）返回空列表，
     * 上层据此不产出任何私有目录条目 —— 不会误报"读到 0 字节文件"。
     */
    private fun privateRootsOf(reader: ShellReader, targets: Collection<String>): List<Pair<String, String>> {
        val wanted = if (targets.isEmpty()) null else targets.toHashSet()
        return shellLines(reader, "ls -d $PRIVATE_DATA_ROOT/*/$PRIVATE_FILES_DIR/$PRIVATE_LOG_DIR 2>/dev/null")
            .mapNotNull { path ->
                val trimmed = path.trimEnd('/')
                val parts = trimmed.split('/')
                // 期望形状：/data/data/<pkg>/files/xpatch-logs
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
                // 混合游标里的本地文件仍然走本地 IO，不必绕 binder。
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
                // 取不到进度：放弃该文件，避免"还有更多"一直为真却不前进。
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

    /**
     * 从 shell 文件取一段并解析。语义与 [readSlice] 一致：
     * 返回的 [Slice.bytesRead] 是"这段里真正被消费的字节"，
     * 若起点落在行中，残行字节会留到下一页重新对齐。
     */
    private fun readSliceViaShell(
        reader: ShellReader,
        fileReader: FileReader?,
        file: FileCursor,
        maxBytes: Int,
    ): Slice? {
        val end = file.cursor
        if (end <= 0L) return null

        // 文件小到一页能装下、且这一段就是整个文件：一次 cat 读完，省掉 tail|head 两个进程。
        if (end == file.totalBytes && end <= maxBytes) {
            val content = fileReader?.read(file.path, end.toInt())
                ?: reader.read("cat ${shellQuote(file.path)} 2>/dev/null")
            if (!content.isNullOrEmpty()) {
                val bytes = content.toByteArray(Charsets.UTF_8)
                if (bytes.isNotEmpty()) {
                    // 整文件已全部解析：游标直接归零，避免解码误差让"还有更多"永远为真。
                    return sliceFromBytes(bytes, aligned = true, absoluteStart = 0L, file = file)
                        .copy(bytesRead = end)
                }
            }
            // cat 也不可用时继续走下面的分页命令。
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

        // 多读一个字节用于判断 start 是否落在行首，避免为此多跑一次 shell。
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

    /**
     * 取从 offset 开始、长度 length 的那一段字节。
     *
     * 用 `tail -c +<offset+1> | head -c <length>`：只搬运这一段，不把整文件拉回内存。
     * 取不到（命令缺失 / 权限不足）时返回 null，由调用方决定降级——
     * 这里**不做**"整文件 cat 再在本地截断"的退路：文件可能在枚举后继续增长，
     * 那样会把已经读过的尾部再解析一遍。整文件的 cat 只在上层确认
     * "这一段就是整个文件"时才发生（见 [readSliceViaShell]）。
     */
    private fun readShellRange(reader: ShellReader, path: String, offset: Long, length: Int): String? {
        if (length <= 0) return null
        val quoted = shellQuote(path)
        val first = (offset + 1L).coerceAtLeast(1L)
        return reader.read("tail -c +$first $quoted 2>/dev/null | head -c $length")
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * 取文件大小：`wc -c` 优先（所有 Android 版本都有），`stat -c %s` 兜底（老 toybox 才有）。
     *
     * 两者都失败时返回 null —— 调用方据此把"不是普通文件 / 读不到"的文件跳过，
     * 而不是当成 0 字节塞进游标。
     */
    private fun shellSizeOf(reader: ShellReader, path: String): Long? {
        val quoted = shellQuote(path)
        // wc -c <file> 输出 "<字节数> <路径>"，取第一个字段即可；stderr 已丢弃。
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

        /** 单页字节预算：决定"往下滚一次加载多少"，与总条数无关。 */
        const val PAGE_BYTES = 256 * 1024

        /** 单文件单页下限，避免文件很多时每页只读几十字节、把 IO 拆得过碎。 */
        const val MIN_SLICE_BYTES = 8 * 1024

        /** 全量扫描时的分块大小。 */
        const val SCAN_CHUNK_BYTES = 1024 * 1024

        private const val LOG_DIR = ".logs"
        private const val LOG_SUFFIX = ".log"
        private const val DATA_PATH = "/storage/emulated/0/Android/data"

        /** 应用私有数据根（root / Shizuku 才能进）。 */
        private const val PRIVATE_DATA_ROOT = "/data/data"
        private const val PRIVATE_FILES_DIR = "files"

        /** 内核降级写入的私有日志目录名，见 LogSupport#resolveLogFile。 */
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
