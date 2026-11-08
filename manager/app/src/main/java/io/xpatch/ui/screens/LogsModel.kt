package io.xpatch.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import io.xpatch.data.RuntimeLogStore
import java.util.Locale

internal enum class LogsTab(val label: String) {
    Runtime("运行日志"),
    Operations("操作日志"),
}

internal enum class LogLayer(val label: String) {
    All("全部层级"),
    Framework("框架层"),
    Module("模块层"),
}

internal enum class LogViewMode(val label: String) {
    Module("模块视图"),
    Timeline("时间线视图"),
}

internal enum class GroupOrder(val label: String, val short: String) {
    LayerFirst("框架层在前", "框架优先"),
    ModuleFirst("模块层在前", "模块优先"),
}
internal enum class TimeOrder(val label: String, val short: String) {
    NewestFirst("最新在前", "时间 ↓"),
    OldestFirst("最早在前", "时间 ↑"),
}

internal enum class LogLevel(val label: String, val short: String) {
    All("全部级别", "全部"),
    Error("错误", "ERROR"),
    Warn("警告", "WARN"),
    Info("信息", "INFO"),
    Debug("调试", "DEBUG"),
}

internal const val LOGS_HIGHLIGHT_LIMIT = 32
internal const val LOGS_SEARCH_DEBOUNCE_MS = 220L
internal const val LOGS_SUMMARY_MAX_LINES = 4
internal const val LOGS_SUMMARY_MAX_CHARS = 260

private val LEVEL_ERROR = Regex("\\[(ERROR|E|FATAL|SEVERE)\\]|\\b(ERROR|FATAL|SEVERE)\\b|Exception|Error:|错误|失败")
private val LEVEL_WARN = Regex("\\[(WARN|WARNING|W)\\]|\\b(WARN|WARNING)\\b|警告")
private val LEVEL_INFO = Regex("\\[(INFO|I)\\]|\\bINFO\\b")
private val LEVEL_DEBUG = Regex("\\[(DEBUG|D|TRACE|V|VERBOSE)\\]|\\b(DEBUG|TRACE|VERBOSE)\\b")

internal fun levelOf(entry: RuntimeLogStore.Entry): LogLevel {
    val text = entry.message
    return when {
        LEVEL_ERROR.containsMatchIn(text) -> LogLevel.Error
        LEVEL_WARN.containsMatchIn(text) -> LogLevel.Warn
        LEVEL_DEBUG.containsMatchIn(text) -> LogLevel.Debug
        LEVEL_INFO.containsMatchIn(text) -> LogLevel.Info
        else -> LogLevel.Info
    }
}

internal fun levelColors(level: LogLevel): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> =
    when (level) {
        LogLevel.Error -> Color(0xFFB3261E) to Color(0xFFFFFFFF)
        LogLevel.Warn -> Color(0xFFB26A00) to Color(0xFFFFFFFF)
        LogLevel.Info -> Color(0xFF2F80ED) to Color(0xFFFFFFFF)
        LogLevel.Debug -> Color(0xFF4F5B66) to Color(0xFFFFFFFF)
        LogLevel.All -> Color(0xFF6B7280) to Color(0xFFFFFFFF)
    }

internal class LogDetailState {
    var query by mutableStateOf("")
    var level by mutableStateOf(LogLevel.All)
    var timeOrder by mutableStateOf(TimeOrder.NewestFirst)
    var dayFrom by mutableStateOf<String?>(null)
    var dayTo by mutableStateOf<String?>(null)
    val expanded = mutableStateMapOf<String, Boolean>()

    val filterActive: Boolean
        get() = level != LogLevel.All ||
            dayFrom != null ||
            dayTo != null ||
            timeOrder != TimeOrder.NewestFirst

    fun reset() {
        query = ""
        level = LogLevel.All
        timeOrder = TimeOrder.NewestFirst
        dayFrom = null
        dayTo = null
    }

    fun toggleEntry(id: String) {
        expanded[id] = !(expanded[id] ?: false)
    }
}

internal data class RuntimeLogsState(
    val snapshot: RuntimeLogStore.Snapshot? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val hasAccess: Boolean = true,
) {
    val entries: List<RuntimeLogStore.Entry> get() = snapshot?.entries.orEmpty()

    val refreshing: Boolean get() = loading && snapshot != null

    /** 磁盘上是否还有更早的日志没加载。 */
    val hasMore: Boolean get() = snapshot?.hasMore == true

    val pendingFiles: Int get() = snapshot?.pendingFiles ?: 0
}

internal data class ClearOutcome(
    val ok: Boolean,
    val title: String,
    val message: String,
)

internal data class LogExportOption(
    val label: String,
    val description: String,
    val fileName: String,
    val build: () -> String,
    val hasContent: Boolean,
    /**
     * 流式写出。非 null 时导出走这条路径，不再把全部日志拼成一个 String，
     * 从而支持"把磁盘上全部日志导出"而不受内存限制。
     */
    val stream: (suspend (write: (String) -> Unit) -> Unit)? = null,
)

internal class LogsState {
    var tab by mutableStateOf(LogsTab.Runtime)
    var query by mutableStateOf("")
    var opQuery by mutableStateOf("")
    var layer by mutableStateOf(LogLayer.All)
    var apps by mutableStateOf<Set<String>>(emptySet())
    var view by mutableStateOf(LogViewMode.Module)
    var groupOrder by mutableStateOf(GroupOrder.LayerFirst)
    var timeOrder by mutableStateOf(TimeOrder.NewestFirst)
    var seenNewest by mutableStateOf<String?>(null)
    var hasNew by mutableStateOf(false)
    val runtimeList = LazyListState()
    val operationsList = LazyListState()

    var cachedBase by mutableStateOf<LogsBase?>(null)

    val filterActive: Boolean
        get() = layer != LogLayer.All ||
            apps.isNotEmpty() ||
            view != LogViewMode.Module ||
            groupOrder != GroupOrder.LayerFirst ||
            timeOrder != TimeOrder.NewestFirst

    fun resetFilters() {
        query = ""
        layer = LogLayer.All
        apps = emptySet()
        view = LogViewMode.Module
        groupOrder = GroupOrder.LayerFirst
        timeOrder = TimeOrder.NewestFirst
    }
}

internal data class LogAppFilter(
    val target: String,
    val label: String,
    val count: Int,
)

internal data class LogGroup(
    val key: String,
    val module: String,
    val framework: Boolean,
    val targets: List<String>,
    val entries: List<RuntimeLogStore.Entry>,
)

internal sealed interface LogRow {
    val key: String

    data class Header(val group: LogGroup) : LogRow {
        override val key: String get() = "h:" + group.key
    }

    data class Day(val day: String, val groupKey: String) : LogRow {
        override val key: String get() = "d:" + groupKey + ":" + day
    }

    data class EntryRow(
        val entry: RuntimeLogStore.Entry,
        val groupKey: String,
        val showTarget: Boolean,
        val last: Boolean,
    ) : LogRow {
        override val key: String get() = "e:" + entry.id
    }
}

internal data class FullSearchResult(
    val entries: List<RuntimeLogStore.Entry>,
    val query: String,
)

/**
 * 全量命中并入已加载结果后的渲染基座。
 *
 * [query] 用来丢弃"上一个关键词"迟到的合并结果：全量扫描是异步的，
 * 关键词一变就必须立刻停止使用旧结果，否则列表会短暂显示别的关键词的条目。
 */
internal data class MergedLogsBase(
    val query: String,
    val base: LogsBase,
)

internal data class LogsBase(
    val groups: List<LogGroup>,
    val ordered: List<RuntimeLogStore.Entry>,
    val apps: List<LogAppFilter>,
    val matched: Int,
    val total: Int,
    val newestKey: String?,
    val multiApp: Boolean,
    val filtered: Boolean,
    val query: String,
)

internal fun layerOf(entry: RuntimeLogStore.Entry): LogLayer =
    if (entry.framework) LogLayer.Framework else LogLayer.Module

internal fun sortSummary(
    view: LogViewMode,
    groupOrder: GroupOrder,
    timeOrder: TimeOrder,
): String = if (view == LogViewMode.Timeline) {
    timeOrder.short
} else {
    groupOrder.short + " · " + timeOrder.short
}

internal fun matchesLog(
    entry: RuntimeLogStore.Entry,
    needle: String,
    labels: Map<String, String>,
): Boolean =
    entry.message.contains(needle, true) ||
        entry.detail.contains(needle, true) ||
        entry.module.contains(needle, true) ||
        entry.target.contains(needle, true) ||
        (labels[entry.target] ?: "").contains(needle, true)

private fun entryComparator(): Comparator<RuntimeLogStore.Entry> =
    compareBy({ it.timeMillis }, { it.target }, { it.module }, { it.lineNumber })

internal fun filterLogs(
    entries: List<RuntimeLogStore.Entry>,
    labels: Map<String, String>,
    query: String,
    layer: LogLayer,
    apps: Set<String>,
    groupOrder: GroupOrder,
    timeOrder: TimeOrder,
    /**
     * 跳过"按应用 / 层级 / 关键词"的逐条过滤，只做排序、分组与统计。
     * 供 [mergeFullSearchBase] 使用：全量扫描阶段已经复核过这些条件，
     * 这里再跑一遍只会白白重复字符串匹配。
     */
    preselected: Boolean = false,
): LogsBase {
    val appCounts = HashMap<String, Int>()
    for (entry in entries) appCounts[entry.target] = (appCounts[entry.target] ?: 0) + 1
    val appFilters = appCounts
        .map { (target, count) -> LogAppFilter(target, labels[target] ?: target, count) }
        .sortedBy { it.label.lowercase(Locale.ROOT) }

    val needle = query.trim()
    val filtered = ArrayList<RuntimeLogStore.Entry>()
    for (entry in entries) {
        if (!preselected) {
            if (apps.isNotEmpty() && entry.target !in apps) continue
            if (layer != LogLayer.All && layerOf(entry) != layer) continue
            if (needle.isNotEmpty() && !matchesLog(entry, needle, labels)) continue
        }
        filtered += entry
    }

    val effectiveApps = apps.ifEmpty { appCounts.keys }
    val singleApp = effectiveApps.size <= 1
    val comparator = entryComparator()
    val ordered = if (timeOrder == TimeOrder.NewestFirst) {
        filtered.sortedWith(comparator.reversed())
    } else {
        filtered.sortedWith(comparator)
    }

    val newest = if (timeOrder == TimeOrder.NewestFirst) ordered.firstOrNull() else ordered.lastOrNull()
    val newestKey = newest?.let { "e:" + it.id }

    val buckets = LinkedHashMap<String, MutableList<RuntimeLogStore.Entry>>()
    ordered.forEach { entry ->
        val key = if (singleApp) entry.module else entry.target + "\u0000" + entry.module
        buckets.getOrPut(key) { ArrayList() } += entry
    }
    val groupComparator = if (groupOrder == GroupOrder.LayerFirst) {
        compareBy<LogGroup>(
            { if (it.framework) 0 else 1 },
            { it.module.lowercase(Locale.ROOT) },
            { it.key },
        )
    } else {
        compareBy<LogGroup>(
            { if (it.framework) 1 else 0 },
            { it.module.lowercase(Locale.ROOT) },
            { it.key },
        )
    }
    val groups = buckets.map { (key, bucket) ->
        val head = bucket.first()
        LogGroup(
            key = key,
            module = head.module,
            framework = head.framework,
            targets = bucket.map { it.target }.distinct(),
            entries = bucket,
        )
    }.sortedWith(groupComparator)

    return LogsBase(
        groups = groups,
        ordered = ordered,
        apps = appFilters,
        matched = filtered.size,
        total = entries.size,
        newestKey = newestKey,
        multiApp = !singleApp,
        filtered = needle.isNotEmpty() || layer != LogLayer.All || apps.isNotEmpty(),
        query = needle,
    )
}

/**
 * 全量搜索命中并入已加载结果，得到列表真正渲染用的 [LogsBase]。
 *
 * - 命中来自磁盘全量扫描，带完整 [RuntimeLogStore.Entry]，优先保留；
 * - 与已加载结果去重按"物理行身份"：`sourcePath + 字节偏移`，也就是
 *   [RuntimeLogStore.Entry.id]（见 `RuntimeLogStore.parseBlock` 里的 `id = path + "@" + offset`）。
 *   不再用 `timeMillis + text` 兜底：同一次重试循环里多行完全相同的日志是真实存在的，
 *   按内容去重会把它们吞掉；文件被截断/轮转导致偏移漂移时，宁可多显示一条，也不隐藏真实日志。
 * - 排序、分组、日期分组仍然全部交给 [filterLogs]/[buildRows]，
 *   所以模块视图与时间线视图的渲染逻辑没有任何变化；
 * - 应用筛选列表沿用已加载结果，避免筛选面板只剩"命中的应用"；
 *   multiApp / total / groups 则与合并后的数据保持一致。
 *
 * 只在关键词非空、且全量扫描已经返回结果时调用，因此恒为筛选态。
 */
internal fun mergeFullSearchBase(
    loaded: LogsBase,
    hits: List<RuntimeLogStore.Entry>,
    query: String,
    groupOrder: GroupOrder,
    timeOrder: TimeOrder,
): LogsBase {
    val merged = ArrayList<RuntimeLogStore.Entry>(hits.size + loaded.ordered.size)
    val seenIds = HashSet<String>(hits.size * 2)

    fun keep(entry: RuntimeLogStore.Entry) {
        if (!seenIds.add(entry.id)) return
        merged += entry
    }

    // 命中优先：它们是全量扫描结果，理论上已经覆盖下方"已加载"的全部匹配项。
    hits.forEach { keep(it) }
    // 已加载结果补齐：兜住"扫描期间日志文件又被写入/轮转"这类边界情况。
    loaded.ordered.forEach { keep(it) }

    val base = filterLogs(
        entries = merged,
        // 命中在扫描阶段已经按关键词/应用/层级复核过（见 LogsScreen 的 scanAll 过滤），
        // preselected = true 时 labels/layer/apps 都不参与命中判断，这里只做排序与分组。
        labels = emptyMap(),
        query = query,
        layer = LogLayer.All,
        apps = emptySet(),
        groupOrder = groupOrder,
        timeOrder = timeOrder,
        preselected = true,
    )
    return base.copy(
        // 应用筛选面板沿用"已加载"的应用清单：命中只覆盖的部分应用不该让面板突然变短。
        // multiApp 不覆盖：它必须与 base.groups 的 key 规则一致（多应用时按 target+module 分组），
        // 否则模块视图会出现"同名分组却看不到 target"。
        apps = loaded.apps,
        filtered = true,
    )
}

internal fun buildRows(
    base: LogsBase,
    view: LogViewMode,
): List<LogRow> {
    val rows = ArrayList<LogRow>(base.matched + base.groups.size + 8)
    if (view == LogViewMode.Timeline) {
        var previousDay: String? = null
        base.ordered.forEach { entry ->
            if (entry.day != previousDay) {
                rows += LogRow.Day(entry.day, "")
                previousDay = entry.day
            }
            rows += LogRow.EntryRow(entry, "", base.multiApp, false)
        }
        return rows
    }
    val searching = base.query.isNotEmpty()
    base.groups.forEach { group ->
        rows += LogRow.Header(group)
        if (!searching) return@forEach
        var previousDay: String? = null
        group.entries.forEachIndexed { index, entry ->
            if (entry.day != previousDay) {
                rows += LogRow.Day(entry.day, group.key)
                previousDay = entry.day
            }
            rows += LogRow.EntryRow(
                entry,
                group.key,
                base.multiApp,
                index == group.entries.lastIndex,
            )
        }
    }
    return rows
}

internal fun summaryOf(entry: RuntimeLogStore.Entry): String {
    val text = entry.message
    if (text.length <= LOGS_SUMMARY_MAX_CHARS) return text
    return text.take(LOGS_SUMMARY_MAX_CHARS)
}

internal fun summaryTruncated(entry: RuntimeLogStore.Entry): Boolean =
    entry.hasDetail ||
        entry.message.length > LOGS_SUMMARY_MAX_CHARS ||
        entry.message.count { it == '\n' } >= LOGS_SUMMARY_MAX_LINES

internal fun highlightMatches(text: String, needle: String): AnnotatedString {
    if (needle.isEmpty() || text.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        var hits = 0
        while (cursor <= text.length - needle.length && hits < LOGS_HIGHLIGHT_LIMIT) {
            val index = text.indexOf(needle, cursor, ignoreCase = true)
            if (index < 0) break
            append(text.substring(cursor, index))
            withStyle(
                SpanStyle(
                    background = Color(0x552F80ED),
                    fontWeight = FontWeight.SemiBold,
                ),
            ) {
                append(text.substring(index, index + needle.length))
            }
            cursor = index + needle.length
            hits++
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}

internal fun operationMatches(line: String, needle: String): Boolean =
    needle.isEmpty() || line.contains(needle, true)

internal fun filterGroupEntries(
    entries: List<RuntimeLogStore.Entry>,
    query: String,
    level: LogLevel,
    dayFrom: String?,
    dayTo: String?,
    timeOrder: TimeOrder,
): List<RuntimeLogStore.Entry> {
    val needle = query.trim()
    val low = dayFrom?.takeIf { it.isNotBlank() }
    val high = dayTo?.takeIf { it.isNotBlank() }
    val kept = entries.filter { entry ->
        if (level != LogLevel.All && levelOf(entry) != level) return@filter false
        if (low != null && entry.day < low) return@filter false
        if (high != null && entry.day > high) return@filter false
        if (needle.isNotEmpty() &&
            !entry.message.contains(needle, true) &&
            !entry.detail.contains(needle, true)
        ) {
            return@filter false
        }
        true
    }
    val comparator = entryComparator()
    return if (timeOrder == TimeOrder.NewestFirst) kept.sortedWith(comparator.reversed())
    else kept.sortedWith(comparator)
}

internal fun daysOf(entries: List<RuntimeLogStore.Entry>): List<String> =
    entries.mapTo(LinkedHashSet()) { it.day }.sortedDescending()

internal fun levelCounts(entries: List<RuntimeLogStore.Entry>): Map<LogLevel, Int> {
    val counts = HashMap<LogLevel, Int>()
    for (entry in entries) {
        val level = levelOf(entry)
        counts[level] = (counts[level] ?: 0) + 1
    }
    return counts
}
