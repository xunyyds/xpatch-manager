package io.xpatch.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.xpatch.data.RuntimeLogStore
import io.xpatch.ui.components.PullRefreshBox
import io.xpatch.ui.components.SearchField
import io.xpatch.ui.theme.LocalMiuix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun LogsScreen(
    runtime: RuntimeLogsState,
    operations: List<String>,
    state: LogsState,
    labelOf: (String) -> String,
    onRefreshRuntime: () -> Unit,
    onRequestStorage: () -> Unit,
    onClearRuntime: suspend () -> RuntimeLogStore.ClearResult,
    onClearOperations: () -> Boolean,
    onExport: (String, String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val store = remember { RuntimeLogStore() }

    var queryText by remember { mutableStateOf(state.query) }
    var opQueryText by remember { mutableStateOf(state.opQuery) }
    var copyText by mutableStateOf("")
    var copyTick by mutableIntStateOf(0)
    var menuOpen by mutableStateOf(false)
    var filterOpen by mutableStateOf(false)
    var clearTarget by mutableStateOf<LogsTab?>(null)
    var outcome by mutableStateOf<ClearOutcome?>(null)
    var detailOf by mutableStateOf<RuntimeLogStore.Entry?>(null)
    val expandedEntries = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(state.tab) {
        queryText = state.query
        opQueryText = state.opQuery
    }

    LaunchedEffect(queryText) {
        if (queryText == state.query) return@LaunchedEffect
        delay(LOGS_SEARCH_DEBOUNCE_MS)
        state.query = queryText
    }

    LaunchedEffect(opQueryText) {
        if (opQueryText == state.opQuery) return@LaunchedEffect
        delay(LOGS_SEARCH_DEBOUNCE_MS)
        state.opQuery = opQueryText
    }

    LaunchedEffect(copyTick) {
        if (copyTick > 0) {
            delay(1600)
            copyTick = 0
        }
    }

    val labels by produceState(
        initialValue = emptyMap<String, String>(),
        runtime.entries,
    ) {
        val targets = runtime.entries.mapTo(LinkedHashSet()) { it.target }
        value = withContext(Dispatchers.IO) {
            val cache = HashMap<String, String>(targets.size)
            targets.forEach { target -> cache[target] = labelOf(target) }
            cache
        }
    }

    val computed by produceState(
        initialValue = null as LogsBase?,
        runtime.entries,
        labels,
        state.query,
        state.layer,
        state.apps,
        state.groupOrder,
        state.timeOrder,
    ) {
        value = withContext(Dispatchers.Default) {
            filterLogs(
                entries = runtime.entries,
                labels = labels,
                query = state.query,
                layer = state.layer,
                apps = state.apps,
                groupOrder = state.groupOrder,
                timeOrder = state.timeOrder,
            )
        }
    }

    val lastGood = remember { arrayOfNulls<LogsBase>(1) }
    val ready = computed ?: lastGood[0]
    if (computed != null) lastGood[0] = computed
    val computing = computed == null && ready != null

    val rows by remember(ready, state.view) {
        derivedStateOf { ready?.let { buildRows(it, state.view, state) }.orEmpty() }
    }

    LaunchedEffect(ready, state.expansionReady) {
        val current = ready ?: return@LaunchedEffect
        if (!state.expansionReady && runtime.snapshot != null) {
            state.defaultExpanded = current.groups.size in 1..LOGS_EXPAND_ALL_THRESHOLD
            state.expansionReady = true
        }
    }

    val needle = state.opQuery.trim()
    val shownOperations = remember(operations, needle) {
        if (needle.isEmpty()) operations else operations.filter { operationMatches(it, needle) }
    }

    val listState = if (state.tab == LogsTab.Runtime) state.runtimeList else state.operationsList
    val atLatest by produceState(initialValue = true, listState, state.timeOrder) {
        snapshotFlow {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (state.timeOrder == TimeOrder.NewestFirst) {
                listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 8
            } else {
                total == 0 || lastVisible >= total - 1
            }
        }.collect { value = it }
    }

    LaunchedEffect(ready?.newestKey, atLatest, state.tab) {
        val current = ready ?: return@LaunchedEffect
        if (state.tab == LogsTab.Runtime && atLatest && current.newestKey != null) {
            state.seenNewest = current.newestKey
        }
    }

    val newestKey = ready?.newestKey
    val hasNew = state.tab == LogsTab.Runtime &&
        newestKey != null &&
        state.seenNewest != null &&
        newestKey != state.seenNewest

    val totalLoaded = runtime.entries.size
    val matched = ready?.matched ?: 0

    val exportOptions = remember(state.tab, ready, shownOperations, operations) {
        buildExportOptions(
            tab = state.tab,
            matched = ready?.matched ?: 0,
            ordered = ready?.ordered.orEmpty(),
            totalLoaded = runtime.entries.size,
            loadedEntries = runtime.entries,
            filteredOperations = shownOperations,
            allOperations = operations,
            store = store,
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LogsTopBar(
                tab = state.tab,
                loadedCount = totalLoaded,
                operationsCount = operations.size,
                filterActive = state.filterActive,
                canToggleAll = state.view == LogViewMode.Module && ready?.groups?.isNotEmpty() == true,
                allOpen = ready?.let { state.allOpen(it.groups) } == true,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                exportOptions = exportOptions,
                canClear = if (state.tab == LogsTab.Runtime) totalLoaded > 0 || runtime.snapshot != null
                else operations.isNotEmpty(),
                onTabChange = { state.tab = it },
                onOpenFilter = { filterOpen = true },
                onToggleAll = {
                    ready?.let { state.setAll(it.groups, !state.allOpen(it.groups)) }
                },
                onExport = { option ->
                    menuOpen = false
                    onExport(option.fileName, option.build())
                },
                onClear = {
                    menuOpen = false
                    clearTarget = state.tab
                },
            )

            if (state.tab == LogsTab.Runtime) {
                SearchField(
                    value = queryText,
                    onValueChange = { queryText = it },
                    placeholder = "搜索日志内容、模块或应用",
                )
            } else {
                SearchField(
                    value = opQueryText,
                    onValueChange = { opQueryText = it },
                    placeholder = "搜索操作记录",
                )
            }

            if (state.tab == LogsTab.Runtime) {
                val snapshot = runtime.snapshot
                val showStatus = ready?.filtered == true ||
                    snapshot?.truncatedByLimit == true ||
                    snapshot?.headTruncated == true ||
                    snapshot?.failures?.isNotEmpty() == true
                if (showStatus) {
                    SlimStatusLine(
                        matched = matched,
                        loaded = totalLoaded,
                        filtered = ready?.filtered == true,
                        snapshot = snapshot,
                        refreshing = runtime.refreshing,
                        onReset = {
                            state.resetFilters()
                            queryText = ""
                        },
                    )
                }
                RuntimeLogList(
                    runtime = runtime,
                    state = state,
                    rows = rows,
                    base = ready,
                    computing = computing,
                    listState = listState,
                    hasNew = hasNew,
                    expandedEntries = expandedEntries,
                    modifier = Modifier.weight(1f),
                    onToggleEntry = { id -> expandedEntries[id] = !(expandedEntries[id] ?: false) },
                    onOpenDetail = { detailOf = it },
                    onCopy = { entry ->
                        clipboard.setText(AnnotatedString(entry.fullText))
                        copyText = "已复制 " + entry.timeText
                        copyTick++
                    },
                    onRefresh = onRefreshRuntime,
                    onRequestStorage = onRequestStorage,
                    onToggleGroup = { group ->
                        state.expanded[group.key] = !state.isOpen(group)
                    },
                    onJumpLatest = {
                        val target = if (state.timeOrder == TimeOrder.NewestFirst) 0 else rows.lastIndex
                        if (target >= 0) scope.launch { listState.animateScrollToItem(target) }
                        state.seenNewest = ready?.newestKey
                    },
                    onReset = {
                        state.resetFilters()
                        queryText = ""
                    },
                )
            } else {
                if (needle.isNotEmpty()) {
                    SlimStatusLine(
                        matched = shownOperations.size,
                        loaded = operations.size,
                        filtered = true,
                        snapshot = null,
                        refreshing = false,
                        onReset = {
                            state.opQuery = ""
                            opQueryText = ""
                        },
                    )
                }
                OperationLogList(
                    operations = shownOperations,
                    total = operations.size,
                    query = needle,
                    listState = listState,
                    modifier = Modifier.weight(1f),
                    onCopy = { line ->
                        clipboard.setText(AnnotatedString(line))
                        copyText = "已复制操作日志"
                        copyTick++
                    },
                    onReset = {
                        state.opQuery = ""
                        opQueryText = ""
                    },
                )
            }
        }

        AnimatedVisibility(
            visible = copyTick > 0,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp),
        ) {
            Text(
                copyText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        if (filterOpen) {
            FilterPanel(
                state = state,
                apps = ready?.apps.orEmpty(),
                onDismiss = { filterOpen = false },
                onReset = {
                    state.resetFilters()
                    queryText = ""
                },
            )
        }

        clearTarget?.let { tab ->
            ClearConfirmDialog(
                tab = tab,
                scope = clearScope(tab, runtime, operations, ready, labelOf),
                onConfirm = {
                    clearTarget = null
                    scope.launch {
                        outcome = if (tab == LogsTab.Runtime) {
                            val cleared = onClearRuntime()
                            ClearOutcome(
                                ok = cleared.ok,
                                title = if (cleared.ok) "运行日志已清空" else "运行日志未完全清空",
                                message = describeClear(cleared),
                            )
                        } else {
                            val ok = onClearOperations()
                            ClearOutcome(
                                ok = ok,
                                title = if (ok) "操作日志已清空" else "操作日志清空失败",
                                message = if (ok) "已移除本机保存的操作记录，运行日志未受影响"
                                else "写入本机存储失败，界面内容已保留，可稍后重试",
                            )
                        }
                    }
                },
                onDismiss = { clearTarget = null },
            )
        }

        outcome?.let { result ->
            ClearResultDialog(result = result, onDismiss = { outcome = null })
        }

        detailOf?.let { entry ->
            EntryDetailDialog(
                entry = entry,
                ordered = ready?.ordered.orEmpty(),
                labelOf = labelOf,
                onNavigate = { detailOf = it },
                onCopy = { copied ->
                    clipboard.setText(AnnotatedString(copied.fullText))
                    copyText = "已复制 " + copied.timeText
                    copyTick++
                },
                onDismiss = { detailOf = null },
            )
        }
    }
}

@Composable
private fun LogsTopBar(
    tab: LogsTab,
    loadedCount: Int,
    operationsCount: Int,
    filterActive: Boolean,
    canToggleAll: Boolean,
    allOpen: Boolean,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    exportOptions: List<LogExportOption>,
    canClear: Boolean,
    onTabChange: (LogsTab) -> Unit,
    onOpenFilter: () -> Unit,
    onToggleAll: () -> Unit,
    onExport: (LogExportOption) -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogsPill(
            text = LogsTab.Runtime.label + " " + countText(loadedCount),
            selected = tab == LogsTab.Runtime,
            onClick = { onTabChange(LogsTab.Runtime) },
        )
        LogsPill(
            text = LogsTab.Operations.label + " " + countText(operationsCount),
            selected = tab == LogsTab.Operations,
            onClick = { onTabChange(LogsTab.Operations) },
        )
        Spacer(Modifier.weight(1f))
        if (tab == LogsTab.Runtime && canToggleAll) {
            IconButton(onClick = onToggleAll) {
                Icon(
                    if (allOpen) Icons.Rounded.UnfoldLess else Icons.Rounded.UnfoldMore,
                    contentDescription = if (allOpen) "全部收起" else "全部展开",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onOpenFilter) {
            Box {
                Icon(
                    Icons.Rounded.Tune,
                    contentDescription = "筛选与排序",
                    modifier = Modifier.size(20.dp),
                    tint = if (filterActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (filterActive) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { onMenuOpenChange(true) }) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = "更多日志操作",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                exportOptions.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column(Modifier.width(236.dp)) {
                                Text(
                                    option.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (option.hasContent) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    option.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        enabled = option.hasContent,
                        onClick = { onExport(option) },
                    )
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                DropdownMenuItem(
                    text = {
                        Column(Modifier.width(236.dp)) {
                            Text(
                                if (tab == LogsTab.Runtime) "清空运行日志…" else "清空操作日志…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                "下一步会说明清空范围并再次确认",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    enabled = canClear,
                    onClick = onClear,
                )
            }
        }
    }
}

@Composable
private fun LogsPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val miuix = LocalMiuix.current
    val container = when {
        selected && miuix -> MiuixTheme.colorScheme.tertiaryContainer
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val content = when {
        selected && miuix -> MiuixTheme.colorScheme.onTertiaryContainer
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        miuix -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(container)
            .combinedClickable(onClick = onClick, onLongClick = null)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SlimStatusLine(
    matched: Int,
    loaded: Int,
    filtered: Boolean,
    snapshot: RuntimeLogStore.Snapshot?,
    refreshing: Boolean,
    onReset: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildString {
                if (filtered) {
                    append("匹配 ")
                    append(countText(matched))
                    append(" / 共 ")
                    append(countText(loaded))
                } else {
                    append(countText(loaded))
                    append(" 条")
                }
                if (snapshot != null && (snapshot.truncatedByLimit || snapshot.headTruncated)) {
                    append(" · 已截断")
                }
                if (snapshot != null && snapshot.failures.isNotEmpty()) {
                    append(" · ")
                    append(snapshot.failures.size)
                    append(" 个文件读取失败")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (refreshing) {
            Spacer(Modifier.width(6.dp))
            CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
        }
        Spacer(Modifier.weight(1f))
        if (filtered) {
            TinyTextButton("重置筛选", onReset)
        }
    }
}

@Composable
internal fun TinyTextButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = null)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

@Composable
private fun RuntimeLogList(
    runtime: RuntimeLogsState,
    state: LogsState,
    rows: List<LogRow>,
    base: LogsBase?,
    computing: Boolean,
    listState: LazyListState,
    hasNew: Boolean,
    expandedEntries: Map<String, Boolean>,
    modifier: Modifier = Modifier,
    onToggleEntry: (String) -> Unit,
    onOpenDetail: (RuntimeLogStore.Entry) -> Unit,
    onCopy: (RuntimeLogStore.Entry) -> Unit,
    onRefresh: () -> Unit,
    onRequestStorage: () -> Unit,
    onToggleGroup: (LogGroup) -> Unit,
    onJumpLatest: () -> Unit,
    onReset: () -> Unit,
) {
    PullRefreshBox(
        refreshing = runtime.refreshing,
        onRefresh = { if (!runtime.loading) onRefresh() },
        modifier = modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 120.dp),
            ) {
                when {
                    !runtime.hasAccess -> item(key = "state:no-access") {
                        LogStateCard(
                            title = "无法读取运行日志",
                            body = "运行日志写在其它应用的 Android/data 目录下，需要「所有文件访问」权限才能读取。授权后返回本页会自动重新检查。",
                            action = "前往授权",
                            onAction = onRequestStorage,
                        )
                    }

                    runtime.snapshot == null && runtime.loading -> item(key = "state:loading") {
                        LogLoadingCard("正在读取运行日志…")
                    }

                    runtime.snapshot == null && runtime.error != null -> item(key = "state:error") {
                        LogStateCard(
                            title = "读取失败",
                            body = runtime.error,
                            action = null,
                            onAction = {},
                        )
                    }

                    runtime.entries.isEmpty() -> item(key = "state:empty") {
                        LogStateCard(
                            title = "暂无运行日志",
                            body = "已安装模块的应用运行后才会生成日志，路径为 Android/data/应用包名/.logs。下拉可重新加载。",
                            action = null,
                            onAction = {},
                        )
                    }

                    rows.isEmpty() && computing -> item(key = "state:computing") {
                        LogLoadingCard("正在筛选日志…")
                    }

                    rows.isEmpty() -> item(key = "state:no-match") {
                        EmptyResultCard(query = base?.query.orEmpty(), onReset = onReset)
                    }

                    else -> items(
                        count = rows.size,
                        key = { index -> rows[index].key },
                    ) { index ->
                        when (val row = rows[index]) {
                            is LogRow.Header -> ModuleHeaderRow(
                                group = row.group,
                                open = row.open,
                                showTarget = base?.multiApp == true,
                                onToggle = { onToggleGroup(row.group) },
                            )

                            is LogRow.Day -> DayRow(row.day)

                            is LogRow.EntryRow -> LogEntryItem(
                                entry = row.entry,
                                query = base?.query.orEmpty(),
                                expanded = expandedEntries[row.entry.id] ?: false,
                                timeline = state.view == LogViewMode.Timeline,
                                modal = state.view == LogViewMode.Module,
                                last = row.last,
                                onToggle = { onToggleEntry(row.entry.id) },
                                onCopy = { onCopy(row.entry) },
                                onDetail = { onOpenDetail(row.entry) },
                            )
                        }
                    }
                }
            }

            if (hasNew) {
                Text(
                    if (state.timeOrder == TimeOrder.NewestFirst) "有新日志 · 跳到最新 ↑"
                    else "有新日志 · 跳到末尾 ↓",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 24.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .combinedClickable(onClick = onJumpLatest, onLongClick = null)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
    }
}

@Composable
private fun OperationLogList(
    operations: List<String>,
    total: Int,
    query: String,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onCopy: (String) -> Unit,
    onReset: () -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        when {
            total == 0 -> item(key = "state:empty-ops") {
                LogStateCard(
                    title = "暂无操作日志",
                    body = "打包、安装、作用域与仓库操作会记录在这里，最多保留 200 条。",
                    action = null,
                    onAction = {},
                )
            }

            operations.isEmpty() -> item(key = "state:no-match-ops") {
                EmptyResultCard(query = query, onReset = onReset)
            }

            else -> items(
                count = operations.size,
                key = { index -> "op:" + index + ":" + operations[index].hashCode() },
            ) { index ->
                OperationRow(
                    line = highlightMatches(operations[index], query),
                    onCopy = { onCopy(operations[index]) },
                )
            }
        }
    }
}

@Composable
private fun LogStateCard(
    title: String,
    body: String,
    action: String?,
    onAction: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 28.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .combinedClickable(onClick = onAction, onLongClick = null)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    action,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun LogLoadingCard(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyResultCard(query: String, onReset: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (query.isBlank()) "当前筛选下没有日志" else "没有匹配「" + query + "」的日志",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "搜索只覆盖已加载的日志，可重置筛选后再试。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .combinedClickable(onClick = onReset, onLongClick = null)
                .padding(horizontal = 18.dp, vertical = 11.dp),
        ) {
            Text(
                "重置筛选",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun ModuleHeaderRow(
    group: LogGroup,
    open: Boolean,
    showTarget: Boolean,
    onToggle: () -> Unit,
) {
    val shape = if (open) RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    else RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onToggle, onLongClick = null)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (group.framework) LogLayer.Framework.label else LogLayer.Module.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (group.framework) MaterialTheme.colorScheme.onTertiaryContainer
                else MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (group.framework) MaterialTheme.colorScheme.tertiaryContainer
                        else MaterialTheme.colorScheme.primaryContainer,
                    )
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    group.module,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        if (showTarget) {
                            append(group.targets.joinToString("、"))
                            append(" · ")
                        }
                        append(group.entries.size)
                        append(" 条")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (open) "收起该模块日志" else "展开该模块日志",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DayRow(day: String) {
    Text(
        day,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 26.dp, end = 16.dp, top = 10.dp, bottom = 3.dp),
    )
}

@Composable
private fun LogEntryItem(
    entry: RuntimeLogStore.Entry,
    query: String,
    expanded: Boolean,
    timeline: Boolean,
    modal: Boolean,
    last: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
    onDetail: () -> Unit,
) {
    val shape = RoundedCornerShape(
        topStart = 0.dp,
        topEnd = 0.dp,
        bottomStart = if (last) 18.dp else 0.dp,
        bottomEnd = if (last) 18.dp else 0.dp,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(
                start = if (modal) 12.dp else 0.dp,
                end = if (modal) 12.dp else 0.dp,
                bottom = if (last) 5.dp else 0.dp,
            )
            .clip(shape)
            .background(
                if (modal) MaterialTheme.colorScheme.surfaceContainerLow
                else MaterialTheme.colorScheme.background,
            )
            .combinedClickable(onClick = onToggle, onLongClick = onCopy)
            .semantics {
                contentDescription = buildString {
                    append(entry.day)
                    append(' ')
                    append(entry.timeText)
                    append(' ')
                    if (timeline) {
                        append(entry.module)
                        append(' ')
                        append(entry.target)
                        append(' ')
                    }
                    if (entry.framework) append("框架层 ")
                    append(entry.message.take(120))
                    if (summaryTruncated(entry)) append("，可展开查看全文")
                }
            }
            .padding(
                start = if (modal) 24.dp else 18.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 8.dp,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
            )
            Spacer(Modifier.size(7.dp))
            Text(
                entry.timeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (timeline) {
                Spacer(Modifier.size(6.dp))
                Text(
                    entry.module,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    entry.target,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = highlightMatches(
                if (expanded) entry.message else summaryOf(entry),
                query,
            ),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp),
            maxLines = if (expanded) Int.MAX_VALUE else LOGS_SUMMARY_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        if (expanded && entry.hasDetail) {
            Text(
                text = highlightMatches(entry.detail, query),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (summaryTruncated(entry)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TinyTextButton(if (expanded) "收起" else "查看全文", onToggle)
                TinyTextButton("详情", onDetail)
            }
        }
    }
}

@Composable
private fun OperationRow(
    line: AnnotatedString,
    onCopy: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onCopy, onLongClick = onCopy)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
    )
}

internal fun countText(value: Int): String =
    if (value < 1000) value.toString()
    else String.format(java.util.Locale.US, "%.1fk", value / 1000.0)
