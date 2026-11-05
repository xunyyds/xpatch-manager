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

internal const val LOGS_EXPAND_ALL_THRESHOLD = 4
internal const val LOGS_HIGHLIGHT_LIMIT = 32
internal const val LOGS_SEARCH_DEBOUNCE_MS = 220L
internal const val LOGS_SUMMARY_MAX_LINES = 4
internal const val LOGS_SUMMARY_MAX_CHARS = 260

internal data class RuntimeLogsState(
    val snapshot: RuntimeLogStore.Snapshot? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val hasAccess: Boolean = true,
) {
    val entries: List<RuntimeLogStore.Entry> get() = snapshot?.entries.orEmpty()

    val refreshing: Boolean get() = loading && snapshot != null
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
    var defaultExpanded by mutableStateOf(false)
    var expansionReady by mutableStateOf(false)
    var seenNewest by mutableStateOf<String?>(null)
    var hasNew by mutableStateOf(false)
    val expanded = mutableStateMapOf<String, Boolean>()
    val runtimeList = LazyListState()
    val operationsList = LazyListState()

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

    fun isOpen(group: LogGroup): Boolean = expanded[group.key] ?: defaultExpanded

    fun allOpen(groups: List<LogGroup>): Boolean =
        groups.isNotEmpty() && groups.all { isOpen(it) }

    fun toggle(group: LogGroup) {
        expanded[group.key] = !isOpen(group)
    }

    fun setAll(groups: List<LogGroup>, open: Boolean) {
        groups.forEach { expanded[it.key] = open }
        defaultExpanded = open
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

    data class Header(val group: LogGroup, val open: Boolean) : LogRow {
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
): LogsBase {
    val appCounts = HashMap<String, Int>()
    for (entry in entries) appCounts[entry.target] = (appCounts[entry.target] ?: 0) + 1
    val appFilters = appCounts
        .map { (target, count) -> LogAppFilter(target, labels[target] ?: target, count) }
        .sortedBy { it.label.lowercase(Locale.ROOT) }

    val needle = query.trim()
    val filtered = ArrayList<RuntimeLogStore.Entry>()
    for (entry in entries) {
        if (apps.isNotEmpty() && entry.target !in apps) continue
        if (layer != LogLayer.All && layerOf(entry) != layer) continue
        if (needle.isNotEmpty() && !matchesLog(entry, needle, labels)) continue
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

    val newest = filtered.maxWithOrNull(comparator)
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

internal fun buildRows(
    base: LogsBase,
    view: LogViewMode,
    state: LogsState,
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
    base.groups.forEach { group ->
        val open = state.isOpen(group)
        rows += LogRow.Header(group, open)
        if (!open) return@forEach
        var previousDay: String? = null
        group.entries.forEachIndexed { index, entry ->
            if (entry.day != previousDay) {
                rows += LogRow.Day(entry.day, group.key)
                previousDay = entry.day
            }
            rows += LogRow.EntryRow(entry, group.key, base.multiApp, index == group.entries.lastIndex)
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
