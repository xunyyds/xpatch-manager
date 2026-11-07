package io.xpatch.ui.screens

import android.content.pm.PackageManager
import android.webkit.WebView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.xpatch.data.RepoStore
import io.xpatch.ui.components.BottomSheet
import io.xpatch.ui.components.DialogAction
import io.xpatch.ui.components.EmptyState
import io.xpatch.ui.components.GroupHeader
import io.xpatch.ui.components.PullRefreshBox
import io.xpatch.ui.components.PulsingDots
import io.xpatch.ui.components.SearchField
import io.xpatch.ui.components.SegmentedControl
import io.xpatch.ui.components.SheetHeader
import io.xpatch.ui.components.XPatchDialog
import io.xpatch.ui.format.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val SCOPE_SYSTEM = "system"
private const val SCOPE_FRAMEWORK = "android"

private val RepoSortLabels = listOf(
    "可更新优先", "更新时间", "安装时间", "应用名", "已下载优先",
    "创建时间", "版本号", "作用域数量", "包名 A-Z",
)

/**
 * 某个包在本机的安装信息。
 * 由 MainScaffold 在 IO 线程一次性批量取出，组合期只做 Map 查表。
 */
internal data class RepoInstalledApp(
    val versionName: String?,
    val versionCode: Long?,
    val lastUpdateTime: Long,
)

/**
 * 仓库页的安装态快照：包名 → 安装信息 + 下载缓存命中情况 + 角标数量。
 *
 * 0.1.3 的卡顿主要来自组合期逐行调用 PackageManager / 解析 repo-cache.json：
 * 1290 个模块 × 每行多次 IPC + 磁盘解析，每次重组都要重跑一遍。
 * 现在快照在 IO 线程构建，页面只做不可变数据查表。
 */
internal data class RepoInstallSnapshot(
    val installed: Map<String, RepoInstalledApp> = emptyMap(),
    val cache: RepoStore.CacheSnapshot = RepoStore.CacheSnapshot.Empty,
    /** 已下载但本机未安装同版本的模块数（下载图标角标）。 */
    val badge: Int = 0,
) {
    fun versionNameOf(pkg: String): String? = installed[pkg]?.versionName

    fun versionCodeOf(pkg: String): Long? = installed[pkg]?.versionCode

    fun lastUpdateTimeOf(pkg: String): Long = installed[pkg]?.lastUpdateTime ?: 0L

    fun isCached(module: RepoStore.RepoModule): Boolean = cache.isCached(module)

    companion object {
        val Empty = RepoInstallSnapshot()
    }
}

/** 模块状态：0 已安装 · 1 可更新 · 2 已下载 · 3 未安装（与筛选面板的口径一致）。 */
private fun statusOf(module: RepoStore.RepoModule, snapshot: RepoInstallSnapshot): Int {
    val installed = snapshot.versionNameOf(module.packageName)
    if (installed == null) return if (snapshot.isCached(module)) 2 else 3
    val code = snapshot.versionCodeOf(module.packageName)
    if (code != null && module.versionCode > 0) return if (code == module.versionCode) 0 else 1
    return if (installed == module.version) 0 else 1
}

/**
 * 过滤 + 排序的全部输入。
 * 作为 produceState 的 key：任一输入变化才重算，纯重组不会重复计算。
 */
private data class RepoQuery(
    val modules: List<RepoStore.RepoModule>,
    val query: String,
    val broadSearch: Boolean,
    val sort: Int,
    val chip89: Boolean,
    val chip100: Boolean,
    val chipLsp: Boolean,
    val recentDays: Int,
    val statuses: Set<Int>,
    val authorFilter: String?,
    val scopeTarget: String?,
    val snapshot: RepoInstallSnapshot,
)

/**
 * README 全文搜索缓存（容量 300，LRU）。
 * 只在后台协程里访问并加锁，绝不在组合期读取。
 */
private class ReadmeTextCache(private val maxSize: Int = 300) {
    private val map = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > maxSize
    }

    @Synchronized
    fun textOf(pkg: String, load: (String) -> String?): String {
        map[pkg]?.let { return it }
        val text = load(pkg) ?: ""
        map[pkg] = text
        return text
    }
}

/**
 * 过滤 + 排序。纯计算，调用方必须在后台线程执行。
 *
 * 分三步，避免主线程任何重活：
 *  1. Default 线程：非文本筛选 + 基本信息匹配；
 *  2. IO 线程：仅对「基本信息没命中、开了全文搜索」的模块读 README，逐条检查取消信号；
 *  3. Default 线程：合并 + 排序。
 */
private suspend fun computeOrdered(
    input: RepoQuery,
    readmeCache: ReadmeTextCache,
    readmeText: (String) -> String?,
): List<RepoStore.RepoModule> {
    val lowered = input.query.trim().lowercase()
    // 时间截点提到循环外：原来每个模块都算一次 System.currentTimeMillis()。
    val cutoff = if (input.recentDays > 0) {
        System.currentTimeMillis() - input.recentDays * 86_400_000L
    } else {
        0L
    }
    val matched = ArrayList<RepoStore.RepoModule>(input.modules.size)
    val readmeCandidates = ArrayList<RepoStore.RepoModule>()

    for (module in input.modules) {
        if (input.chip89 && !module.supports89) continue
        if (input.chip100 && !module.supports100) continue
        if (input.chipLsp && module.lspMax <= 0) continue
        if (cutoff > 0L && module.updateTime < cutoff) continue
        if (input.statuses.isNotEmpty() && statusOf(module, input.snapshot) !in input.statuses) continue
        if (input.authorFilter != null && module.author != input.authorFilter) continue
        if (input.scopeTarget != null &&
            !(module.scope89.contains(input.scopeTarget) || module.scope100.contains(input.scopeTarget))
        ) {
            continue
        }
        if (lowered.isEmpty()) {
            matched.add(module)
            continue
        }
        if (module.name.contains(lowered, true) ||
            module.packageName.contains(lowered, true) ||
            module.author.contains(lowered, true) ||
            module.summary.contains(lowered, true)
        ) {
            matched.add(module)
            continue
        }
        if (!input.broadSearch || lowered.length < 2) continue
        readmeCandidates.add(module)
    }

    if (readmeCandidates.isNotEmpty()) {
        // README 读盘放 IO 线程；输入变化时 produceState 会取消上一次计算，
        // 这里逐条 ensureActive() 让取消立刻生效（原实现是主线程逐条读盘 + lowercase）。
        val hits = withContext(Dispatchers.IO) {
            val out = ArrayList<RepoStore.RepoModule>(readmeCandidates.size)
            for (module in readmeCandidates) {
                currentCoroutineContext().ensureActive()
                val text = readmeCache.textOf(module.packageName, readmeText)
                if (text.lowercase().contains(lowered)) out.add(module)
            }
            out
        }
        matched.addAll(hits)
    }

    return sortModules(matched, input)
}

private fun sortModules(
    list: List<RepoStore.RepoModule>,
    input: RepoQuery,
): List<RepoStore.RepoModule> {
    if (list.size < 2) return list
    // 预先把小写名算一次：比较次数是 O(n log n)，逐次 lowercase 会产生上万次临时字符串。
    val lowerNames = HashMap<String, String>(list.size * 2)
    for (module in list) lowerNames[module.packageName] = module.name.lowercase()
    fun nameOf(module: RepoStore.RepoModule): String = lowerNames[module.packageName] ?: module.name

    return when (input.sort) {
        1 -> list.sortedByDescending { it.updateTime }
        2 -> list.sortedByDescending { input.snapshot.lastUpdateTimeOf(it.packageName) }
        3 -> list.sortedBy { nameOf(it) }
        4 -> list.sortedWith(
            compareByDescending<RepoStore.RepoModule> { input.snapshot.isCached(it) }
                .thenBy { nameOf(it) },
        )
        5 -> list.sortedByDescending { it.createTime }
        6 -> list.sortedByDescending { it.versionCode }
        7 -> list.sortedByDescending { it.scope89.size + it.scope100.size }
        8 -> list.sortedBy { it.packageName }
        else -> list.sortedWith(
            compareByDescending<RepoStore.RepoModule> { statusOf(it, input.snapshot) == 1 }
                .thenBy { nameOf(it) },
        )
    }
}

@Composable
internal fun RepoPageScreen(
    modules: List<RepoStore.RepoModule>,
    busy: Boolean,
    snapshot: RepoInstallSnapshot,
    readmeText: (String) -> String?,
    downloadingPkg: String?,
    downloadProgress: () -> Int,
    downloadDoneTick: Int,
    onOpenDownloads: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDetail: (RepoStore.RepoModule) -> Unit,
    onAction: (RepoStore.RepoModule) -> Unit,
) {
    val context = LocalContext.current

    var query by rememberSaveable { mutableStateOf("") }
    var broadSearch by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var sortMenu by remember { mutableStateOf(false) }
    var chip89 by rememberSaveable { mutableStateOf(false) }
    var chip100 by rememberSaveable { mutableStateOf(false) }
    var chipLsp by rememberSaveable { mutableStateOf(false) }
    var recentDays by rememberSaveable { mutableIntStateOf(0) }
    var statuses by rememberSaveable { mutableStateOf(emptySet<Int>()) }
    var authorFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var scopeTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var filterSheet by remember { mutableStateOf(false) }
    var scopePicker by remember { mutableStateOf(false) }
    var showDoneFlash by remember { mutableStateOf(false) }

    val flyProgress = remember { Animatable(1f) }
    val iconPulse = remember { Animatable(1f) }
    var flyStart by remember { mutableStateOf<Offset?>(null) }
    var downloadIconCenter by remember { mutableStateOf<Offset?>(null) }
    var overlayOrigin by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(downloadDoneTick) {
        if (downloadDoneTick > 0) {
            showDoneFlash = true
            delay(900)
            showDoneFlash = false
        }
    }

    // 收纳动画：飞行物从点击位置沿弧线飞入右上角下载图标，落地时图标弹跳。
    LaunchedEffect(flyStart) {
        val start = flyStart ?: return@LaunchedEffect
        val end = downloadIconCenter ?: return@LaunchedEffect
        flyProgress.snapTo(0f)
        flyProgress.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        flyStart = null
        iconPulse.snapTo(1.2f)
        iconPulse.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
    }

    // 输入防抖：搜索框每次击键都会写 query，延迟 160ms 后再参与过滤/排序，
    // 避免逐键对 1000+ 模块重算（README 全文模式下每次重算还要读盘）。
    val debouncedQuery by produceState(initialValue = query, query) {
        delay(160)
        value = query
    }

    // README 全文搜索缓存：只在后台协程里访问（内部加锁），组合期不读盘。
    val readmeCache = remember { ReadmeTextCache() }

    // 过滤 + 排序：key 覆盖全部筛选输入，输入不变就完全跳过；
    // 计算在 Default 线程，README 读盘在 IO 线程，输入变化时上一次计算会被取消。
    val repoQuery = RepoQuery(
        modules = modules,
        query = debouncedQuery,
        broadSearch = broadSearch,
        sort = sort,
        chip89 = chip89,
        chip100 = chip100,
        chipLsp = chipLsp,
        recentDays = recentDays,
        statuses = statuses,
        authorFilter = authorFilter,
        scopeTarget = scopeTarget,
        snapshot = snapshot,
    )
    // 首帧先用未过滤列表占位，避免页面刚进入时闪一下「没有符合筛选条件的模块」。
    val ordered by produceState(initialValue = modules, repoQuery) {
        value = withContext(Dispatchers.Default) {
            computeOrdered(repoQuery, readmeCache, readmeText)
        }
    }

    val filtersActive = chip89 || chip100 || chipLsp || recentDays > 0 || statuses.isNotEmpty() ||
        authorFilter != null || scopeTarget != null || broadSearch

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = iconPulse.value
                        scaleY = iconPulse.value
                    }
                    .onGloballyPositioned { coords ->
                        downloadIconCenter = Offset(
                            coords.positionInRoot().x + coords.size.width / 2f,
                            coords.positionInRoot().y + coords.size.height / 2f,
                        )
                    },
            ) {
                if (downloadingPkg != null) {
                    // 进度只在独立的绘制作用域里读取：进度变化只重绘进度圈，
                    // 不会让 RepoPageScreen（含整个列表）重组。
                    DownloadProgressRing(downloadProgress)
                }
                IconButton(onClick = onOpenDownloads) {
                    Icon(
                        imageVector = if (showDoneFlash) Icons.Rounded.Check else Icons.Rounded.Download,
                        contentDescription = "下载管理",
                        tint = if (showDoneFlash) {
                            Color(0xFF2BA84A)
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                if (!showDoneFlash && snapshot.badge > 0) {
                    Text(
                        snapshot.badge.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .padding(horizontal = 4.dp),
                    )
                }
            }
        }
        SearchField(query, { query = it }, "搜索 模块 / 包名 / 作者 / 简介")

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { filterSheet = true }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Tune,
                        "筛选",
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "筛选",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                    )
                    if (filtersActive) {
                        Spacer(Modifier.width(4.dp))
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        )
                    }
                }
            }
            Box {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { sortMenu = true }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Sort,
                        "排序",
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        RepoSortLabels[sort],
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    RepoSortLabels.forEachIndexed { index, label ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                sort = index
                                sortMenu = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
        }

        if (filterSheet) {
            RepoFilterSheet(
                chip89 = chip89, onChip89 = { chip89 = it },
                chip100 = chip100, onChip100 = { chip100 = it },
                chipLsp = chipLsp, onChipLsp = { chipLsp = it },
                recentDays = recentDays, onRecentDays = { recentDays = it },
                statuses = statuses, onStatuses = { statuses = it },
                authorFilter = authorFilter, onClearAuthor = { authorFilter = null },
                scopeTarget = scopeTarget, onClearScope = { scopeTarget = null }, onPickScope = { scopePicker = true },
                broadSearch = broadSearch, onBroadSearch = { broadSearch = it },
                onDismiss = { filterSheet = false },
                onReset = {
                    chip89 = false; chip100 = false; chipLsp = false
                    recentDays = 0; statuses = emptySet()
                    authorFilter = null; scopeTarget = null; broadSearch = false
                },
            )
        }

        if (scopePicker) {
            ScopePickerDialog(
                onDismiss = { scopePicker = false },
                onPick = { target ->
                    scopeTarget = target
                    scopePicker = false
                },
            )
        }

        PullRefreshBox(
            refreshing = busy,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                item {
                    GroupHeader(
                        if (ordered.size == modules.size) {
                            "仓库 " + ordered.size + " 个模块"
                        } else {
                            "仓库 " + ordered.size + " / " + modules.size + " 个模块"
                        },
                    )
                }
                when {
                    modules.isEmpty() -> item { EmptyState("下拉从仓库获取模块列表") }
                    ordered.isEmpty() -> item { EmptyState("没有符合筛选条件的模块") }
                    else -> items(ordered, key = { it.packageName }) { module ->
                        RepoModuleRow(
                            module = module,
                            // 安装态全部来自快照（不可变数据），组合期不再查 PackageManager。
                            installedVersion = snapshot.versionNameOf(module.packageName),
                            cached = snapshot.isCached(module),
                            downloading = downloadingPkg == module.packageName,
                            onOpen = { onOpenDetail(module) },
                            onAction = { origin ->
                                if (!snapshot.isCached(module)) flyStart = origin
                                onAction(module)
                            },
                            onAuthor = { authorFilter = module.author },
                        )
                    }
                }
            }
        }
    }

    // 飞行物层：从点击位置沿弧线飞向右上角下载图标
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOrigin = it.positionInRoot() },
    )
    flyStart?.let { start ->
        val end = downloadIconCenter
        if (end != null) {
            Box(
                Modifier
                    .offset {
                        val t = flyProgress.value
                        val u = 1f - t
                        val cx = (start.x + end.x) / 2f
                        val cy = minOf(start.y, end.y) - 140f
                        val x = u * u * start.x + 2f * u * t * cx + t * t * end.x
                        val y = u * u * start.y + 2f * u * t * cy + t * t * end.y
                        val o = overlayOrigin ?: Offset.Zero
                        IntOffset((x - o.x).roundToInt(), (y - o.y).roundToInt())
                    }
                    .graphicsLayer {
                        val t = flyProgress.value
                        alpha = if (t > 0.85f) (1f - t) / 0.15f else 1f
                        scaleX = 1f - 0.3f * t
                        scaleY = 1f - 0.3f * t
                    }
                    .size(13.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
    }
    }
}

/**
 * 下载进度圈。
 * progress 用 lambda 传入，读取发生在 CircularProgressIndicator 的绘制阶段：
 * 下载进度变化只重绘这个圈，不会连带整个仓库列表重组。
 */
@Composable
private fun DownloadProgressRing(progress: () -> Int) {
    CircularProgressIndicator(
        progress = { progress() / 100f },
        modifier = Modifier.size(48.dp),
        strokeWidth = 2.5.dp,
        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
    )
}

@Composable
private fun ScopePickerDialog(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    var pick by remember { mutableStateOf("") }
    var options by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        // 枚举已安装应用（几百个 + loadLabel）开销不小，放 IO 线程，别卡住打开面板的点击。
        options = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getInstalledPackages(0)
                    .mapNotNull { info ->
                        val label = info.applicationInfo?.loadLabel(context.packageManager)?.toString()
                            ?: return@mapNotNull null
                        label to info.packageName
                    }
                    .sortedBy { it.first.lowercase() }
            }.getOrDefault(emptyList<Pair<String, String>>())
        }
    }

    XPatchDialog(
        onDismiss = onDismiss,
        title = "按作用域筛选",
        actions = listOf(DialogAction("取消", onClick = onDismiss)),
    ) {
        Text("模块作用域包含所选目标时才会显示", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        SearchField(pick, { pick = it }, "搜索应用")
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
            val fixed = listOf(SCOPE_SYSTEM to "系统框架 (system)", SCOPE_FRAMEWORK to "框架进程 (android)")
                .filter { (pkg, label) -> pkg.contains(pick, true) || label.contains(pick, true) }
            items(fixed, key = { "fixed:" + it.first }) { (pkg, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(pkg) }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                ) {
                    Text(label)
                }
            }
            val apps = options.filter { (label, pkg) ->
                (label + pkg).contains(pick, true)
            }
            items(apps, key = { it.second }) { (label, pkg) ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(pkg) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        pkg,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun scopeLabel(target: String): String = when (target) {
    SCOPE_SYSTEM -> "系统框架"
    SCOPE_FRAMEWORK -> "框架进程"
    else -> target.substringAfterLast('.').ifBlank { target }
}

@Composable
internal fun RepoModuleRow(
    module: RepoStore.RepoModule,
    installedVersion: String?,
    cached: Boolean,
    downloading: Boolean,
    onOpen: () -> Unit,
    onAction: (Offset) -> Unit,
    onAuthor: (() -> Unit)? = null,
) {
    val state = when {
        downloading -> "下载中"
        installedVersion != null && installedVersion == module.version -> "已安装"
        cached -> "已下载"
        installedVersion != null -> "可更新"
        else -> ""
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                module.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                module.packageName + " · " + module.version,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (module.author.isNotBlank()) {
                    Text(
                        module.author,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (onAuthor != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = onAuthor != null) { onAuthor?.invoke() },
                    )
                    Text(
                        " · ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val parts = mutableListOf<String>()
                if (module.updateTime > 0L) parts.add(formatTime(module.updateTime))
                if (state.isNotBlank()) parts.add(state)
                if (parts.isNotEmpty()) {
                    Text(
                        parts.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        var buttonCenter by remember { mutableStateOf(Offset.Zero) }
        TextButton(
            onClick = { onAction(buttonCenter) },
            enabled = !downloading,
            modifier = Modifier.onGloballyPositioned { coords ->
                buttonCenter = Offset(
                    coords.positionInRoot().x + coords.size.width / 2f,
                    coords.positionInRoot().y + coords.size.height / 2f,
                )
            },
        ) {
            Text(if (downloading) "下载中" else if (cached) "安装" else "下载")
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepoFilterSheet(
    chip89: Boolean,
    onChip89: (Boolean) -> Unit,
    chip100: Boolean,
    onChip100: (Boolean) -> Unit,
    chipLsp: Boolean,
    onChipLsp: (Boolean) -> Unit,
    recentDays: Int,
    onRecentDays: (Int) -> Unit,
    statuses: Set<Int>,
    onStatuses: (Set<Int>) -> Unit,
    authorFilter: String?,
    onClearAuthor: () -> Unit,
    scopeTarget: String?,
    onClearScope: () -> Unit,
    onPickScope: () -> Unit,
    broadSearch: Boolean,
    onBroadSearch: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
) {
    BackHandler(enabled = true) { onDismiss() }
    BottomSheet(onDismiss = onDismiss) {
        SheetHeader(title = "筛选", onReset = onReset, onDismiss = onDismiss)
        Spacer(Modifier.height(12.dp))
        PanelRow("框架支持") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = chip89, onClick = { onChip89(!chip89) }, label = { Text("XP89") })
                FilterChip(selected = chip100, onClick = { onChip100(!chip100) }, label = { Text("XP100") })
                FilterChip(selected = chipLsp, onClick = { onChipLsp(!chipLsp) }, label = { Text("LSPatch") })
            }
        }
        PanelRow("更新时间") {
            SegmentedControl(
                options = listOf("不限", "近7天", "近30天"),
                selectedIndex = when (recentDays) {
                    7 -> 1
                    30 -> 2
                    else -> 0
                },
                modifier = Modifier.fillMaxWidth(),
            ) { index -> onRecentDays(listOf(0, 7, 30)[index]) }
        }
        PanelRow("安装状态") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "已安装", 1 to "可更新", 2 to "已下载", 3 to "未安装").forEach { (code, label) ->
                    FilterChip(
                        selected = code in statuses,
                        onClick = {
                            onStatuses(if (code in statuses) statuses - code else statuses + code)
                        },
                        label = { Text(label) },
                    )
                }
            }
        }
        PanelRow("作用域") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    scopeTarget?.let { "作用目标：" + scopeLabel(it) } ?: "未选择",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (scopeTarget != null) {
                    Text(
                        "清除",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onClearScope() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                Text(
                    "选择",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPickScope() }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        PanelRow("作者") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    authorFilter ?: "未筛选（点击列表中的作者名可筛选）",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (authorFilter != null) {
                    Text(
                        "清除",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onClearAuthor() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
        PanelRow("搜索范围") {
            SegmentedControl(
                options = listOf("基本信息", "含 README 全文"),
                selectedIndex = if (broadSearch) 1 else 0,
                modifier = Modifier.fillMaxWidth(),
            ) { index -> onBroadSearch(index == 1) }
        }
    }
}

@Composable
private fun PanelRow(label: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 5.dp),
        )
        content()
    }
}

@Composable
internal fun DownloadsScreen(
    modules: List<RepoStore.RepoModule>,
    entries: List<RepoStore.DownloadEntry>,
    snapshot: RepoInstallSnapshot,
    busy: Boolean,
    onInstall: (RepoStore.DownloadEntry) -> Unit,
    onDelete: (RepoStore.DownloadEntry) -> Unit,
    onClearAll: () -> Unit,
    onOpenRepo: () -> Unit,
    onOpenDetail: (RepoStore.RepoModule) -> Unit,
) {
    // 包名 → 模块：避免每行都对 1290 条模块做一次线性查找。
    val modulesByPackage = remember(modules) { modules.associateBy { it.packageName } }
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) {
        XPatchDialog(
            onDismiss = { confirmClear = false },
            title = "清空下载",
            actions = listOf(
                DialogAction("取消", onClick = { confirmClear = false }),
                DialogAction("清空", onClick = {
                    confirmClear = false
                    onClearAll()
                }),
            ),
        ) {
            Text(
                "将删除 " + entries.size + " 个已下载的模块安装包（约 " +
                    formatSize(entries.sumOf { it.size }) + "），已安装的模块不受影响。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "共 " + entries.size + " 个 · " + formatSize(entries.sumOf { it.size }),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) {
                Text("全部清理")
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            if (entries.isEmpty()) {
                item { EmptyState("还没有下载过模块") }
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        OutlinedButton(onClick = onOpenRepo) {
                            Text("去仓库逛逛")
                        }
                    }
                }
            } else {
                items(entries, key = { it.packageName }) { entry ->
                    val module = modulesByPackage[entry.packageName]
                    // 安装态来自快照，不再逐行调 PackageManager。
                    val installed = snapshot.versionNameOf(entry.packageName)
                    val code = snapshot.versionCodeOf(entry.packageName)
                    val outdated = module != null && when {
                        module.versionCode > 0 && code != null -> code != module.versionCode
                        else -> module.version != entry.version
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = module != null) { module?.let(onOpenDetail) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Text(
                            module?.name ?: entry.fileName,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val status = when {
                            installed != null && installed == entry.version -> "已安装"
                            installed != null -> "已安装（旧版本）"
                            else -> "未安装"
                        }
                        val meta = mutableListOf<String>()
                        meta.add("v" + entry.version)
                        meta.add(formatSize(entry.size))
                        meta.add(formatTime(entry.time))
                        meta.add(status)
                        if (outdated) meta.add("仓库已有 " + module?.version)
                        Text(
                            meta.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { onDelete(entry) }, enabled = !busy) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                            TextButton(onClick = { onInstall(entry) }, enabled = !busy) {
                                Text("安装")
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

@Composable
internal fun RepoDetailScreen(
    module: RepoStore.RepoModule,
    installed: String?,
    installedVersionCode: Long?,
    cached: Boolean,
    busy: Boolean,
    readme: String?,
    onAction: () -> Unit,
    onOpenStore: () -> Unit,
) {
    val installedMatch = when {
        installedVersionCode != null && module.versionCode > 0 -> installedVersionCode == module.versionCode
        else -> installed != null && installed == module.version
    }
    val actionLabel = when {
        installed != null && installedMatch -> "已安装"
        cached -> "安装模块"
        else -> "下载并安装"
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(module.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        module.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "版本 " + module.version,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (module.author.isNotBlank()) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                module.author,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (installed != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "已安装 " + installed + if (installedMatch) " · 已是最新" else " · 可更新",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (installedMatch) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    val times = mutableListOf<String>()
                    if (module.createTime > 0L) times.add("创建 " + formatTime(module.createTime))
                    if (module.updateTime > 0L) times.add("更新 " + formatTime(module.updateTime))
                    if (times.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            times.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            CompatibilityCard(module)

            if (module.summary.isNotBlank()) {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text("简介", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        module.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!readme.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "说明",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(6.dp))
                val dark = isSystemInDarkTheme()
                val html = remember(readme, dark) { readmeBody(readme, dark) }
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = false
                            settings.loadsImagesAutomatically = true
                            isVerticalScrollBarEnabled = false
                            setBackgroundColor(Color.Transparent.toArgb())
                            // 首次加载交给 factory；tag 记住已加载内容，update 里据此判断是否需要重载。
                            tag = html
                            loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                        }
                    },
                    update = { webView ->
                        // 原来的 update 每次重组都 loadDataWithBaseURL：重新解析 HTML + 重新布局，
                        // 在 verticalScroll 里会反复触发测量，是详情页卡顿的来源。
                        if (webView.tag != html) {
                            webView.tag = html
                            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        // 外层是 verticalScroll，无界高度会让 WebView 反复测量/布局：
                        // 给一个下限 + 上限，超出部分由 WebView 内部滚动（JS 关闭，无法用 evaluateJavascript 测高）。
                        .heightIn(min = 96.dp, max = 520.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        Button(
            onClick = onAction,
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            if (busy) PulsingDots() else Text(actionLabel)
        }
        OutlinedButton(
            onClick = onOpenStore,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                .height(48.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("在仓库中查看")
        }
    }
}

@Composable
private fun CompatibilityCard(module: RepoStore.RepoModule) {
    val scopes = (module.scope89 + module.scope100).distinct()
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("兼容性", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TagBadge("XP89", module.supports89)
            TagBadge("XP100", module.supports100)
            TagBadge("LSPatch≤" + module.lspMax, module.lspMax > 0)
            TagBadge("Legacy≤" + module.legacyMax, module.legacyMax > 0)
        }
        if (scopes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "作用域 " + scopes.size + " 个：" + scopes.take(8).joinToString("、") +
                    if (scopes.size > 8) " 等" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TagBadge(text: String, enabled: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        },
        modifier = Modifier
            .background(
                if (enabled) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    Color.Gray.copy(alpha = 0.08f)
                },
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private fun readmeBody(body: String, dark: Boolean): String =
    "<html><head><meta charset='utf-8'>" +
        "<meta name='viewport' content='width=device-width, initial-scale=1'>" +
        "<style>body{font-family:sans-serif;font-size:15px;line-height:1.6;padding:4px 0;" +
        "background:transparent;color:" + (if (dark) "#e2e2e6" else "#1c1b1f") + ";}" +
        "img{max-width:100%;height:auto;}pre{white-space:pre-wrap;}" +
        "a{color:" + (if (dark) "#9ecbff" else "#1a73e8") + ";}</style></head>" +
        "<body>" + body + "</body></html>"

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes / 1024.0)
    else -> bytes.toString() + " B"
}
