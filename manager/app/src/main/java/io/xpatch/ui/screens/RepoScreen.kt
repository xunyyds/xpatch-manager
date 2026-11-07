package io.xpatch.ui.screens

import android.content.pm.PackageManager
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.xpatch.data.RepoStore
import io.xpatch.ui.components.DialogAction
import io.xpatch.ui.components.EmptyState
import io.xpatch.ui.components.GroupHeader
import io.xpatch.ui.components.PullRefreshBox
import io.xpatch.ui.components.PulsingDots
import io.xpatch.ui.components.SearchField
import io.xpatch.ui.components.XPatchDialog
import io.xpatch.ui.format.formatTime

private const val SCOPE_SYSTEM = "system"
private const val SCOPE_FRAMEWORK = "android"

@Composable
internal fun RepoPageScreen(
    modules: List<RepoStore.RepoModule>,
    busy: Boolean,
    installedVersion: (String) -> String?,
    installedTime: (String) -> Long?,
    isCached: (RepoStore.RepoModule) -> Boolean,
    readmeText: (String) -> String?,
    downloadedBadge: Int,
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
    var scopePicker by remember { mutableStateOf(false) }

    val readmeCache = remember {
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > 300
        }
    }

    fun statusOf(module: RepoStore.RepoModule): Int {
        val installed = installedVersion(module.packageName)
        return when {
            installed != null && installed == module.version -> 0
            installed != null -> 1
            isCached(module) -> 2
            else -> 3
        }
    }

    val lowered = query.trim().lowercase()
    val filtered = modules.asSequence()
        .filter { m ->
            if (chip89 && !m.supports89) return@filter false
            if (chip100 && !m.supports100) return@filter false
            if (chipLsp && m.lspMax <= 0) return@filter false
            if (recentDays > 0) {
                val cutoff = System.currentTimeMillis() - recentDays * 86_400_000L
                if (m.updateTime < cutoff) return@filter false
            }
            if (statuses.isNotEmpty() && statusOf(m) !in statuses) return@filter false
            if (authorFilter != null && m.author != authorFilter) return@filter false
            if (scopeTarget != null && !(m.scope89.contains(scopeTarget) || m.scope100.contains(scopeTarget))) {
                return@filter false
            }
            true
        }
        .filter { m ->
            if (lowered.isEmpty()) return@filter true
            if (m.name.contains(lowered, true) ||
                m.packageName.contains(lowered, true) ||
                m.author.contains(lowered, true) ||
                m.summary.contains(lowered, true)
            ) {
                return@filter true
            }
            if (!broadSearch || lowered.length < 2) return@filter false
            val text = readmeCache.getOrPut(m.packageName) { readmeText(m.packageName) ?: "" }
            text.lowercase().contains(lowered)
        }
        .toList()

    val sortLabels = listOf(
        "可更新优先", "更新时间", "安装时间", "应用名", "已下载优先",
        "创建时间", "版本号", "作用域数量", "包名 A-Z",
    )
    val ordered = when (sort) {
        1 -> filtered.sortedByDescending { it.updateTime }
        2 -> filtered.sortedByDescending { installedTime(it.packageName) }
        3 -> filtered.sortedBy { it.name.lowercase() }
        4 -> filtered.sortedWith(
            compareByDescending<RepoStore.RepoModule> { isCached(it) }.thenBy { it.name.lowercase() },
        )
        5 -> filtered.sortedByDescending { it.createTime }
        6 -> filtered.sortedByDescending { it.versionCode }
        7 -> filtered.sortedByDescending { it.scope89.size + it.scope100.size }
        8 -> filtered.sortedBy { it.packageName }
        else -> filtered.sortedWith(
            compareByDescending<RepoStore.RepoModule> {
                val installed = installedVersion(it.packageName)
                installed != null && installed != it.version
            }.thenBy { it.name.lowercase() },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                IconButton(onClick = onOpenDownloads) {
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = "下载管理",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                if (downloadedBadge > 0) {
                    Text(
                        downloadedBadge.toString(),
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

        LazyRow(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(selected = chip89, onClick = { chip89 = !chip89 }, label = { Text("XP89") })
            }
            item {
                FilterChip(selected = chip100, onClick = { chip100 = !chip100 }, label = { Text("XP100") })
            }
            item {
                FilterChip(selected = chipLsp, onClick = { chipLsp = !chipLsp }, label = { Text("LSPatch") })
            }
            item {
                FilterChip(
                    selected = recentDays == 7,
                    onClick = { recentDays = if (recentDays == 7) 0 else 7 },
                    label = { Text("近7天更新") },
                )
            }
            item {
                FilterChip(
                    selected = recentDays == 30,
                    onClick = { recentDays = if (recentDays == 30) 0 else 30 },
                    label = { Text("近30天更新") },
                )
            }
            listOf(0 to "已安装", 1 to "可更新", 2 to "已下载", 3 to "未安装").forEach { (code, label) ->
                item(key = "status$code") {
                    FilterChip(
                        selected = code in statuses,
                        onClick = {
                            statuses = if (code in statuses) statuses - code else statuses + code
                        },
                        label = { Text(label) },
                    )
                }
            }
            item(key = "scope") {
                FilterChip(
                    selected = scopeTarget != null,
                    onClick = {
                        if (scopeTarget != null) scopeTarget = null else scopePicker = true
                    },
                    label = { Text(scopeTarget?.let { "作用域: " + scopeLabel(it) + " ✕" } ?: "作用于应用") },
                )
            }
            item(key = "broad") {
                FilterChip(
                    selected = broadSearch,
                    onClick = { broadSearch = !broadSearch },
                    label = { Text("全文检索") },
                )
            }
        }

        if (authorFilter != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = true,
                    onClick = { authorFilter = null },
                    label = { Text("作者: " + authorFilter + " ✕", maxLines = 1, softWrap = false) },
                )
            }
        }

        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                        sortLabels[sort],
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Spacer(Modifier.weight(1f))
            }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                sortLabels.forEachIndexed { index, label ->
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
                            installedVersion = installedVersion(module.packageName),
                            cached = isCached(module),
                            onOpen = { onOpenDetail(module) },
                            onAction = { onAction(module) },
                            onAuthor = { authorFilter = module.author },
                        )
                    }
                }
            }
        }
    }
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
        options = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getInstalledPackages(0)
                .mapNotNull { info ->
                    val label = info.applicationInfo?.loadLabel(context.packageManager)?.toString()
                        ?: return@mapNotNull null
                    label to info.packageName
                }
                .sortedBy { it.first.lowercase() }
        }.getOrDefault(emptyList())
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
    onOpen: () -> Unit,
    onAction: () -> Unit,
    onAuthor: (() -> Unit)? = null,
) {
    val state = when {
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
            val parts = mutableListOf<String>()
            if (module.author.isNotBlank()) parts.add(module.author)
            if (module.updateTime > 0L) parts.add(formatTime(module.updateTime))
            if (state.isNotBlank()) parts.add(state)
            if (parts.isNotEmpty()) {
                Text(
                    parts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onAction) {
            Text(if (cached) "安装" else "下载")
        }
    }
    if (onAuthor != null && module.author.isNotBlank()) {
        Text(
            "作者: " + module.author,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onAuthor() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

@Composable
internal fun DownloadsScreen(
    modules: List<RepoStore.RepoModule>,
    entries: List<RepoStore.DownloadEntry>,
    installedVersion: (String) -> String?,
    busy: Boolean,
    onInstall: (RepoStore.DownloadEntry) -> Unit,
    onDelete: (RepoStore.DownloadEntry) -> Unit,
    onClearAll: () -> Unit,
    onOpenRepo: () -> Unit,
    onOpenDetail: (RepoStore.RepoModule) -> Unit,
) {
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
                    val module = modules.firstOrNull { it.packageName == entry.packageName }
                    val installed = installedVersion(entry.packageName)
                    val outdated = module != null && module.version != entry.version
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
    cached: Boolean,
    busy: Boolean,
    readme: String?,
    onAction: () -> Unit,
    onOpenStore: () -> Unit,
) {
    val actionLabel = when {
        installed != null && installed == module.version -> "已安装"
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
                    "说明（README）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(6.dp))
                val html = remember(readme) { readmeBody(readme) }
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = false
                            settings.loadsImagesAutomatically = true
                            isVerticalScrollBarEnabled = true
                        }
                    },
                    update = { it.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(460.dp)
                        .padding(horizontal = 16.dp),
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

private fun readmeBody(body: String): String =
    "<html><head><meta charset='utf-8'>" +
        "<meta name='viewport' content='width=device-width, initial-scale=1'>" +
        "<style>body{font-family:sans-serif;font-size:15px;line-height:1.6;padding:12px;}" +
        "img{max-width:100%;height:auto;}pre{white-space:pre-wrap;}</style></head>" +
        "<body>" + body + "</body></html>"

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes / 1024.0)
    else -> bytes.toString() + " B"
}
