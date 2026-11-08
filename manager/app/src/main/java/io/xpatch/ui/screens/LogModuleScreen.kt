package io.xpatch.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.xpatch.data.RuntimeLogStore
import io.xpatch.ui.components.BottomSheet
import io.xpatch.ui.components.SearchField
import io.xpatch.ui.components.SheetHeader
import io.xpatch.ui.theme.LocalMiuix
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun LogModuleScreen(
    module: String,
    framework: Boolean,
    targets: List<String>,
    entries: List<RuntimeLogStore.Entry>,
    labelOf: (String) -> String,
    state: LogDetailState,
    listState: LazyListState,
    onCopy: (RuntimeLogStore.Entry) -> Unit,
) {
    var filterOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val filtered = remember(entries, state.query, state.level, state.dayFrom, state.dayTo, state.timeOrder) {
        filterGroupEntries(
            entries = entries,
            query = state.query,
            level = state.level,
            dayFrom = state.dayFrom,
            dayTo = state.dayTo,
            timeOrder = state.timeOrder,
        )
    }
    val days = remember(entries) { daysOf(entries) }
    val counts = remember(entries) { levelCounts(entries) }

    Column(Modifier.fillMaxSize()) {
        // 双击顶部摘要卡 = 回到列表最上面（长列表快速返回）
        Box(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        scope.launch { listState.animateScrollToItem(0) }
                    })
                },
        ) {
        ModuleSummaryCard(
            module = module,
            framework = framework,
            targets = targets,
            total = entries.size,
            filteredCount = filtered.size,
            counts = counts,
            filterActive = state.filterActive || state.query.isNotEmpty(),
            onOpenFilter = { filterOpen = true },
            onReset = { state.reset() },
        )
        }

        SearchField(
            value = state.query,
            onValueChange = { state.query = it },
            placeholder = "搜索该模块的日志内容",
        )
        if (filtered.isEmpty()) {
            EmptyModuleCard(
                hasFilters = state.filterActive || state.query.isNotEmpty(),
                onReset = { state.reset() },
            )
        } else {
            ModuleLogList(
                entries = filtered,
                state = state,
                listState = listState,
                modifier = Modifier.weight(1f),
                onCopy = onCopy,
            )
        }
    }

    if (filterOpen) {
        LogDetailFilterPanel(
            state = state,
            days = days,
            counts = counts,
            onDismiss = { filterOpen = false },
            onReset = { state.reset() },
        )
    }
}

@Composable
private fun ModuleSummaryCard(
    module: String,
    framework: Boolean,
    targets: List<String>,
    total: Int,
    filteredCount: Int,
    counts: Map<LogLevel, Int>,
    filterActive: Boolean,
    onOpenFilter: () -> Unit,
    onReset: () -> Unit,
) {
    val miuix = LocalMiuix.current
    val container = if (miuix) MiuixTheme.colorScheme.surfaceContainerHigh
    else MaterialTheme.colorScheme.surfaceContainerHigh
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(container)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                module,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenFilter),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Tune,
                    contentDescription = "筛选日志",
                    modifier = Modifier.size(20.dp),
                    tint = if (filterActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            if (targets.size == 1) targets.first() else targets.joinToString("、"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (filteredCount == total) "$total 条" else "$filteredCount / $total 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (filterActive) {
                Text(
                    "重置",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(onClick = onReset, onLongClick = null)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        if (counts.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FlowRowCompat {
                LevelChip(LogLevel.Error, counts[LogLevel.Error] ?: 0)
                LevelChip(LogLevel.Warn, counts[LogLevel.Warn] ?: 0)
                LevelChip(LogLevel.Info, counts[LogLevel.Info] ?: 0)
                LevelChip(LogLevel.Debug, counts[LogLevel.Debug] ?: 0)
            }
        }
    }
}

@Composable
private fun LevelChip(level: LogLevel, count: Int) {
    val dot = levelColors(level).first
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(dot.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dot),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            level.short + " " + count,
            style = MaterialTheme.typography.labelSmall,
            color = dot,
        )
    }
}

@Composable
private fun ModuleLogList(
    entries: List<RuntimeLogStore.Entry>,
    state: LogDetailState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onCopy: (RuntimeLogStore.Entry) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 120.dp),
    ) {
        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
            val previousDay = entries.getOrNull(index - 1)?.day
            if (entry.day != previousDay) {
                DayRow(entry.day)
            }
            LogEntryItem(
                entry = entry,
                query = state.query,
                expanded = state.expanded[entry.id] ?: false,
                timeline = false,
                modal = true,
                last = index == entries.lastIndex,
                onToggle = { state.toggleEntry(entry.id) },
                onCopy = { onCopy(entry) },
            )
        }
    }
}

@Composable
private fun EmptyModuleCard(hasFilters: Boolean, onReset: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (hasFilters) "当前筛选下没有日志" else "该模块暂无日志",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (hasFilters) {
            Spacer(Modifier.height(14.dp))
            Text(
                "重置筛选",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .combinedClickable(onClick = onReset, onLongClick = null)
                    .padding(horizontal = 18.dp, vertical = 11.dp),
            )
        }
    }
}

@Composable
internal fun LogDetailFilterPanel(
    state: LogDetailState,
    days: List<String>,
    counts: Map<LogLevel, Int>,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
) {
    BackHandler(enabled = true) { onDismiss() }
    BottomSheet(onDismiss = onDismiss) {
        SheetHeader(
            title = "筛选日志",
            onReset = onReset,
            onDismiss = onDismiss,
        )
        Spacer(Modifier.height(12.dp))
        FilterLabel("级别")
        FlowRowCompat {
            LogLevel.entries.forEach { level ->
                FilterChip(
                    level = level,
                    count = if (level == LogLevel.All) null else counts[level] ?: 0,
                    selected = state.level == level,
                    onClick = { state.level = level },
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        FilterLabel("时间顺序")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TimeOrder.entries.forEach { order ->
                FilterChip(
                    level = null,
                    label = order.label,
                    count = null,
                    selected = state.timeOrder == order,
                    onClick = { state.timeOrder = order },
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        FilterLabel("按日期")
        FlowRowCompat {
            FilterChip(
                level = null,
                label = "全部",
                count = null,
                selected = state.dayFrom == null && state.dayTo == null,
                onClick = {
                    state.dayFrom = null
                    state.dayTo = null
                },
            )
            days.take(4).forEach { day ->
                FilterChip(
                    level = null,
                    label = day,
                    count = null,
                    selected = state.dayFrom == day && state.dayTo == day,
                    onClick = {
                        state.dayFrom = day
                        state.dayTo = day
                    },
                )
            }
        }
    }
}

@Composable
private fun FilterLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun FlowRowCompat(content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun FilterChip(
    level: LogLevel?,
    label: String? = null,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = level?.let { levelColors(it).first } ?: MaterialTheme.colorScheme.primary
    val container = if (selected) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(container)
            .combinedClickable(onClick = onClick, onLongClick = null)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (level != null && level != LogLevel.All) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            buildString {
                append(label ?: level?.label.orEmpty())
                if (count != null) {
                    append(" ")
                    append(count)
                }
            },
            style = MaterialTheme.typography.labelMedium,
            color = content,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}
