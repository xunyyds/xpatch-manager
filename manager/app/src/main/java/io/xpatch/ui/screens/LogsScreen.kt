package io.xpatch.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.io.File
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
    onLoadMoreRuntime: () -> Unit,
    onClearRuntime: suspend () -> RuntimeLogStore.ClearResult,
    onClearOperations: () -> Boolean,
    onExport: (String, String, (suspend ((String) -> Unit) -> Unit)?) -> Unit,
    onOpenModule: (LogGroup) -> Unit,
    /** 列出可清理的日志文件（有 Shizuku 时走 shell，能覆盖 Android/data 与私有目录）。 */
    listLogFiles: suspend () -> List<RuntimeLogStore.LogFileInfo> = { emptyList() },
    /** 删除选中的日志文件（有 Shizuku 时走 shell rm）。 */
    deleteLogFiles: suspend (List<String>) -> RuntimeLogStore.ClearResult =
        { RuntimeLogStore.ClearResult(0, 0, emptyList()) },
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val store = remember { RuntimeLogStore() }

    var queryText by remember { mutableStateOf(state.query) }
    var opQueryText by remember { mutableStateOf(state.opQuery) }
    var copyText by remember { mutableStateOf("") }
    var copyTick by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<ClearOutcome?>(null) }
    // 选择性清理：按应用分组的可清理文件 + 操作日志/崩溃记录的体积
    var clearSheetOpen by remember { mutableStateOf(false) }
    var clearGroups by remember { mutableStateOf<List<ClearRuntimeGroup>>(emptyList()) }
    var operationsBytes by remember { mutableStateOf(0L) }
    var crashStats by remember { mutableStateOf(0 to 0L) }
    val appContext = LocalContext.current.applicationContext
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

    var labels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(runtime.entries) {
        val targets = runtime.entries.mapTo(LinkedHashSet()) { it.target }
        labels = withContext(Dispatchers.IO) {
            val cache = HashMap<String, String>(targets.size)
            targets.forEach { target -> cache[target] = labelOf(target) }
            cache
        }
    }

    val computed by produceState(
        initialValue = state.cachedBase,
        runtime.entries,
        labels,
        state.query,
        state.layer,
        state.apps,
        state.groupOrder,
        state.timeOrder,
    ) {
        val base = withContext(Dispatchers.Default) {
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
        if (state.cachedBase !== base) state.cachedBase = base
        value = base
    }
    val ready = computed ?: state.cachedBase
    val computing = computed == null && ready != null

    // 关键词非空且磁盘上还有未加载内容时，跨全量做一次流式搜索，
    // 避免"搜不到"其实只是因为那条日志还在未加载区间。
    val searchQuery = state.query.trim()
    var fullSearch by remember { mutableStateOf<FullSearchResult?>(null) }
    var fullSearching by remember { mutableStateOf(false) }
    LaunchedEffect(searchQuery, state.layer, state.apps, runtime.snapshot?.readAtMillis) {
        fullSearch = null
        if (searchQuery.isEmpty() || runtime.snapshot?.hasMore != true) {
            fullSearching = false
            return@LaunchedEffect
        }
        fullSearching = true
        delay(LOGS_SEARCH_DEBOUNCE_MS)
        val snapshotLabels = labels
        fullSearch = withContext(Dispatchers.IO) {
            val hits = ArrayList<RuntimeLogStore.Entry>()
            store.scanAll(targets = emptyList()) { chunk ->
                for (entry in chunk) {
                    if (state.apps.isNotEmpty() && entry.target !in state.apps) continue
                    if (state.layer != LogLayer.All && layerOf(entry) != state.layer) continue
                    if (!matchesLog(entry, searchQuery, snapshotLabels)) continue
                    hits += entry
                }
            }
            FullSearchResult(
                entries = hits.sortedWith(
                    compareByDescending<RuntimeLogStore.Entry> { it.timeMillis },
                ),
                query = searchQuery,
            )
        }
        fullSearching = false
    }

    // 全量命中并入列表的渲染数据源：命中优先（带完整 Entry），已加载结果去重后补齐。
    // 合并与排序放到 Default 线程，避免大结果集在组合期做重活；
    // 结果带上 query，用来丢弃"上一个关键词"迟到的合并结果。
    val searchHits = fullSearch?.takeIf { it.query == searchQuery }
    val mergedRender by produceState<MergedLogsBase?>(
        initialValue = null,
        ready,
        searchHits,
        state.groupOrder,
        state.timeOrder,
    ) {
        val loaded = ready
        val hits = searchHits
        value = if (loaded == null || hits == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                MergedLogsBase(
                    query = hits.query,
                    base = mergeFullSearchBase(
                        loaded = loaded,
                        hits = hits.entries,
                        query = hits.query,
                        groupOrder = state.groupOrder,
                        timeOrder = state.timeOrder,
                    ),
                )
            }
        }
    }
    // 只有"关键词仍然是当前关键词、且仍在运行日志页"时才用合并结果，
    // 否则回落到纯已加载结果（例如切到操作日志页或刚清空搜索词）。
    val searchMerged = mergedRender?.takeIf {
        it.query == searchQuery && state.tab == LogsTab.Runtime
    }
    val base = searchMerged?.base ?: ready

    val rows = remember(base, state.view) {
        base?.let { buildRows(it, state.view) }.orEmpty()
    }

    val needle = state.opQuery.trim()
    val shownOperations = remember(operations, needle) {
        if (needle.isEmpty()) operations else operations.filter { operationMatches(it, needle) }
    }

    val listState = if (state.tab == LogsTab.Runtime) state.runtimeList else state.operationsList
    val atLatest by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (state.timeOrder == TimeOrder.NewestFirst) {
                listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 8
            } else {
                total == 0 || lastVisible >= total - 1
            }
        }
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
    // matched/ordered 都取渲染数据源：全量搜索就绪后，"当前筛选结果"指的就是全部日志的命中。
    val matched = base?.matched ?: 0

    val exportOptions = remember(state.tab, base, shownOperations, operations, runtime.snapshot) {
        buildExportOptions(
            tab = state.tab,
            matched = base?.matched ?: 0,
            ordered = base?.ordered.orEmpty(),
            totalLoaded = runtime.entries.size,
            loadedEntries = runtime.entries,
            filteredOperations = shownOperations,
            allOperations = operations,
            store = store,
            fullExport = if (state.tab == LogsTab.Runtime) {
                { write ->
                    // 全量导出：按文件流式扫描磁盘，逐条交给写出方，
                    // 既不受"已加载"范围限制，也不会把全部日志读进内存。
                    store.scanAll(targets = emptyList()) { chunk ->
                        chunk.sortedByDescending { it.timeMillis }.forEach { write(it.fullText) }
                    }
                }
            } else {
                null
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LogsTopBar(
                tab = state.tab,
                loadedCount = totalLoaded,
                operationsCount = operations.size,
                filterActive = state.filterActive,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                exportOptions = exportOptions,
                canClear = if (state.tab == LogsTab.Runtime) totalLoaded > 0 || runtime.snapshot != null
                else operations.isNotEmpty(),
                onTabChange = { state.tab = it },
                onOpenFilter = { filterOpen = true },
                onExport = { option ->
                    menuOpen = false
                    scope.launch {
                        if (option.stream != null) {
                            onExport(option.fileName, "", option.stream)
                        } else {
                            val text = withContext(Dispatchers.Default) { option.build() }
                            onExport(option.fileName, text, null)
                        }
                    }
                },
                onClear = {
                    menuOpen = false
                    scope.launch {
                        val grouped = withContext(Dispatchers.IO) {
                            val files = listLogFiles()
                            files.groupBy { it.target }
                                .map { (target, list) ->
                                    ClearRuntimeGroup(target = target, label = labelOf(target), files = list)
                                }
                                .sortedByDescending { it.bytes }
                        }
                        clearGroups = grouped
                        operationsBytes = withContext(Dispatchers.IO) {
                            File(appContext.filesDir, "events/operations.log").takeIf { it.isFile }?.length() ?: 0L
                        }
                        crashStats = withContext(Dispatchers.IO) {
                            val dir = File(appContext.filesDir, "crash")
                            val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
                            files.size to files.sumOf { it.length() }
                        }
                        clearSheetOpen = true
                    }
                },
                onDoubleTap = {
                    scope.launch { listState.animateScrollToItem(0) }
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
                val truncated = snapshot?.headTruncated == true
                val readFailed = snapshot?.failures?.isNotEmpty() == true
                // 应用私有目录（/data/data/<pkg>/files/xpatch-logs）里的日志只能经 shell 读，
                // 有这类来源时即使没有任何筛选也要把状态行亮出来，否则用户不知道"日志不全"。
                val privateDir = snapshot?.hasPrivateDir == true
                val showStatus = base != null &&
                    (base.filtered || truncated || readFailed || privateDir)
                if (showStatus) {
                    SlimStatusLine(
                        matched = matched,
                        loaded = totalLoaded,
                        filtered = base?.filtered == true,
                        snapshot = snapshot,
                        refreshing = runtime.refreshing,
                        fullSearching = fullSearching,
                        // 非 null 表示列表已在展示全量搜索结果，文案据此改成"全部日志"。
                        fullSearchHits = searchMerged?.base?.matched,
                        privateDirHint = privateDir,
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
                    base = base,
                    computing = computing,
                    // 全量搜索结果已在列表里时，不再提示"还有文件未加载"——命中已覆盖磁盘全量。
                    fullSearchActive = searchMerged != null,
                    listState = listState,
                    hasNew = hasNew,
                    expandedEntries = expandedEntries,
                    labelOf = labelOf,
                    modifier = Modifier.weight(1f),
                    onToggleEntry = { id -> expandedEntries[id] = !(expandedEntries[id] ?: false) },
                    onOpenModule = onOpenModule,
                    onLoadMore = onLoadMoreRuntime,
                    onCopy = { entry ->
                        clipboard.setText(AnnotatedString(entry.fullText))
                        copyText = "已复制 " + entry.timeText
                        copyTick++
                    },
                    onRefresh = onRefreshRuntime,
                    onRequestStorage = onRequestStorage,
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
                .padding(bottom = io.xpatch.ui.util.rememberListBottomPadding(true)),
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
        if (clearSheetOpen) {
            ClearLogsSheet(
                groups = clearGroups,
                operationsCount = operations.size,
                operationsBytes = operationsBytes,
                crashCount = crashStats.first,
                crashBytes = crashStats.second,
                onDismiss = { clearSheetOpen = false },
                onConfirm = { targets, clearOps, clearCrash ->
                    clearSheetOpen = false
                    scope.launch {
                        var ok = true
                        val messages = StringBuilder()
                        if (targets.isNotEmpty()) {
                            val paths = clearGroups
                                .filter { it.target in targets }
                                .flatMap { group -> group.files.map { it.path } }
                            val cleared = deleteLogFiles(paths)
                            ok = ok && cleared.ok
                            messages.append(
                                "运行日志：删除 " + cleared.deletedFiles + " 个文件（" +
                                    targets.size + " 个应用）",
                            )
                        }
                        if (clearOps) {
                            val cleared = onClearOperations()
                            ok = ok && cleared
                            messages.append(if (messages.isEmpty()) "" else "；").append("操作日志已清空")
                        }
                        if (clearCrash) {
                            val removed = withContext(Dispatchers.IO) {
                                File(appContext.filesDir, "crash").deleteRecursively()
                            }
                            ok = ok && removed
                            messages.append(if (messages.isEmpty()) "" else "；").append("崩溃记录已清空")
                        }
                        if (messages.isEmpty()) messages.append("没有选择任何内容")
                        outcome = ClearOutcome(
                            ok = ok,
                            title = if (ok) "清理完成" else "部分内容未清理",
                            message = messages.toString(),
                        )
                        onRefreshRuntime()
                    }
                },
            )
        }

        outcome?.let { result ->
            ClearResultDialog(result = result, onDismiss = { outcome = null })
        }
    }
}

@Composable
private fun LogsTopBar(
    tab: LogsTab,
    loadedCount: Int,
    operationsCount: Int,
    filterActive: Boolean,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    exportOptions: List<LogExportOption>,
    canClear: Boolean,
    onTabChange: (LogsTab) -> Unit,
    onOpenFilter: () -> Unit,
    onExport: (LogExportOption) -> Unit,
    onClear: () -> Unit,
    onDoubleTap: () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDoubleTap() }) }
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
        TopBarIcon(
            icon = Icons.Rounded.Tune,
            description = "筛选与排序",
            highlighted = filterActive,
            onClick = onOpenFilter,
        )
        Box {
            TopBarIcon(
                icon = Icons.Rounded.MoreVert,
                description = "更多日志操作",
                onClick = { onMenuOpenChange(true) },
            )
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
private fun TopBarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(21.dp),
            tint = if (highlighted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (highlighted) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 9.dp, end = 9.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
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
    fullSearching: Boolean = false,
    /**
     * 全量搜索的命中数；非 null 表示列表里展示的已经是"全部日志"的搜索结果，
     * 文案要明确说出来，而不是再补一句"另有 N 条"。
     */
    fullSearchHits: Int? = null,
    /** 部分日志落在应用私有目录、需要 Shizuku 才能读取时的提示位。 */
    privateDirHint: Boolean = false,
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
                val hits = fullSearchHits
                if (hits != null) {
                    // 列表就是全量命中，不要说"另有 N 条"，否则用户以为还有隐藏结果。
                    append("已在全部日志中搜索：命中 ")
                    append(countText(hits))
                    append(" 条")
                } else if (filtered) {
                    append("匹配 ")
                    append(countText(matched))
                    append(" / 已加载 ")
                    append(countText(loaded))
                    append(" 条")
                } else {
                    append(countText(loaded))
                    append(" 条")
                }
                if (fullSearching) {
                    append(" · 正在搜索全部日志…")
                }
                if (privateDirHint) {
                    append(" · 部分日志在应用私有目录，需 Shizuku 读取")
                }
                if (snapshot != null && headTruncatedOf(snapshot)) {
                    append(" · 单文件过大，较早内容未载入")
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
internal fun LoadMoreFooter(
    loadingMore: Boolean,
    hasMore: Boolean,
    loaded: Int,
    pendingFiles: Int,
    onLoadMore: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            loadingMore -> {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.6.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "正在加载更早的日志…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            hasMore -> Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .combinedClickable(onClick = onLoadMore, onLongClick = null)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "已加载 " + countText(loaded) + " 条 · 还有 " + pendingFiles +
                        " 个文件未读取，点击继续加载",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            else -> Text(
                "已加载全部日志，共 " + countText(loaded) + " 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
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
    /** 列表数据源来自全量搜索结果时为 true：命中已覆盖磁盘全量，不再提示继续加载。 */
    fullSearchActive: Boolean = false,
    computing: Boolean,
    listState: LazyListState,
    hasNew: Boolean,
    expandedEntries: Map<String, Boolean>,
    labelOf: (String) -> String,
    modifier: Modifier = Modifier,
    onToggleEntry: (String) -> Unit,
    onOpenModule: (LogGroup) -> Unit,
    onLoadMore: () -> Unit,
    onCopy: (RuntimeLogStore.Entry) -> Unit,
    onRefresh: () -> Unit,
    onRequestStorage: () -> Unit,
    onJumpLatest: () -> Unit,
    onReset: () -> Unit,
) {
    PullRefreshBox(
        refreshing = runtime.refreshing,
        onRefresh = { if (!runtime.loading) onRefresh() },
        modifier = modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize()) {
            // 滚到接近底部时自动补下一页。
            // 只增量合并已加载部分，不会重新解析磁盘上全部日志。
            // 全量搜索结果不需要翻页：命中本身已经覆盖磁盘上全部文件。
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

                    // 全量搜索可能命中"还没加载"区间的日志：
                    // 只要 rows 非空就得渲染，不能被"已加载为空"的空白卡片挡住。
                    runtime.entries.isEmpty() && rows.isEmpty() -> item(key = "state:empty") {
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
                        EmptyResultCard(
                            query = base?.query.orEmpty(),
                            searchedAll = fullSearchActive,
                            onReset = onReset,
                        )
                    }

                    else -> items(
                        count = rows.size,
                        key = { index -> rows[index].key },
                    ) { index ->
                        when (val row = rows[index]) {
                            is LogRow.Header -> ModuleHeaderRow(
                                group = row.group,
                                showTarget = base?.multiApp == true,
                                labelOf = labelOf,
                                onOpen = { onOpenModule(row.group) },
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
                            )
                        }
                    }
                }

                if (rows.isNotEmpty() && !fullSearchActive) {
                    item(key = "state:load-more") {
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
private fun EmptyResultCard(
    query: String,
    onReset: () -> Unit,
    /** 结果来自全量搜索时为 true：此时"没搜到"就是全部日志都没搜到，不能再提示"只覆盖已加载"。 */
    searchedAll: Boolean = false,
) {
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
            if (searchedAll) "已在全部日志中搜索，没有命中。"
            else "搜索只覆盖已加载的日志，可重置筛选后再试。",
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
    showTarget: Boolean,
    labelOf: (String) -> String,
    onOpen: () -> Unit,
) {
    val title = if (group.framework) {
        LogLayer.Framework.label
    } else {
        remember(group.key) { labelOf(group.module) }
    }
    val subtitle = remember(group.key, showTarget) {
        buildString {
            if (!group.framework) {
                append(group.module)
                append(" · ")
            }
            if (showTarget) {
                append(group.targets.joinToString("、"))
                append(" · ")
            }
            append(group.entries.size)
            append(" 条")
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
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
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = "查看该模块日志",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun DayRow(day: String) {
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
internal fun LogEntryItem(
    entry: RuntimeLogStore.Entry,
    query: String,
    expanded: Boolean,
    timeline: Boolean,
    modal: Boolean,
    last: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
) {
    val truncated = remember(entry.id) { summaryTruncated(entry) }
    val description = remember(entry.id, timeline, truncated) {
        buildString {
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
            if (truncated) append("，可展开查看全文")
        }
    }
    val body = remember(entry.id, expanded, query) {
        highlightMatches(if (expanded) entry.message else summaryOf(entry), query)
    }
    val detailText = remember(entry.id, expanded, query) {
        if (expanded && entry.hasDetail) highlightMatches(entry.detail, query) else null
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(
                start = if (modal) 12.dp else 0.dp,
                end = if (modal) 12.dp else 0.dp,
                bottom = if (last) 5.dp else 0.dp,
            )
            .combinedClickable(onClick = onToggle, onLongClick = onCopy)
            .semantics {
                contentDescription = description
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
            text = body,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp),
            maxLines = if (expanded) Int.MAX_VALUE else LOGS_SUMMARY_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        if (detailText != null) {
            Text(
                text = detailText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (truncated) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TinyTextButton(if (expanded) "收起" else "查看全文", onToggle)
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

/**
 * 条数上限已经取消。剩余的"头部截断"只代表单个日志文件超出单次读取预算，
 * UI 借此提示"更早的内容还没有载入"，而不是暗示存在条数上限。
 */
internal fun headTruncatedOf(snapshot: RuntimeLogStore.Snapshot): Boolean =
    snapshot.headTruncated
