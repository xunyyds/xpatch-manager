package io.xpatch.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.xpatch.data.RepoStore
import io.xpatch.ui.components.DialogAction
import io.xpatch.ui.components.EmptyState
import io.xpatch.ui.components.GroupHeader
import io.xpatch.ui.components.PullRefreshBox
import io.xpatch.ui.components.PulsingDots
import io.xpatch.ui.components.SearchField
import io.xpatch.ui.components.XPatchDialog
import io.xpatch.ui.format.formatTime
import io.xpatch.ui.theme.LocalMiuix
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField

@Composable
internal fun RepoSourceDialog(
    sourceUrl: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var draft by remember(sourceUrl) { mutableStateOf(sourceUrl) }
    XPatchDialog(
        onDismiss = onDismiss,
        title = "仓库地址",
        actions = listOf(
            DialogAction("取消", onClick = onDismiss),
            DialogAction("保存", onClick = { onSave(draft.trim()) }),
        ),
    ) {
        Text("模块列表 JSON 地址", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        if (LocalMiuix.current) {
            MiuixTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        } else {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun RepoPageScreen(
    modules: List<RepoStore.RepoModule>,
    busy: Boolean,
    installedVersion: (String) -> String?,
    installedTime: (String) -> Long?,
    isCached: (RepoStore.RepoModule) -> Boolean,
    onRefresh: () -> Unit,
    onClearCache: () -> Unit,
    onOpenDetail: (RepoStore.RepoModule) -> Unit,
    onAction: (RepoStore.RepoModule) -> Unit,
    sourceUrl: String,
    onSourceChange: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableIntStateOf(0) }
    var sortMenu by remember { mutableStateOf(false) }
    var editingSource by remember { mutableStateOf(false) }
    if (editingSource) {
        RepoSourceDialog(
            sourceUrl = sourceUrl,
            onDismiss = { editingSource = false },
            onSave = {
                editingSource = false
                onSourceChange(it)
            },
        )
    }
    val sortLabels = listOf("可更新优先", "更新时间", "安装时间", "应用名", "已下载优先")
    val filtered = modules
        .filter { it.name.contains(query, true) || it.packageName.contains(query, true) || it.author.contains(query, true) }
        .filter { true }
    val ordered = when (sort) {
        1 -> filtered.sortedByDescending { it.updateTime }
        2 -> filtered.sortedByDescending { installedTime(it.packageName) ?: 0L }
        3 -> filtered.sortedBy { it.name.lowercase() }
        4 -> filtered.sortedWith(compareByDescending<RepoStore.RepoModule> { isCached(it) }.thenBy { it.name.lowercase() })
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
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "仓库地址",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable { editingSource = true }
                    .padding(8.dp),
            )
            Text(
                "清缓存",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable { onClearCache() }
                    .padding(8.dp),
            )

        }
        SearchField(query, { query = it }, "搜索模块 / 包名 / 作者")
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
            Spacer(Modifier.weight(1f))
        }
        PullRefreshBox(
            refreshing = busy,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                item { GroupHeader("仓库 " + ordered.size + " 个模块") }
                if (ordered.isEmpty()) {
                    item { EmptyState("下拉从仓库获取模块列表") }
                }
                items(ordered, key = { it.packageName }) { module ->
                    RepoModuleRow(
                        module = module,
                        installedVersion = installedVersion(module.packageName),
                        cached = isCached(module),
                        onOpen = { onOpenDetail(module) },
                        onAction = { onAction(module) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun RepoModuleRow(
    module: RepoStore.RepoModule,
    installedVersion: String?,
    cached: Boolean,
    onOpen: () -> Unit,
    onAction: () -> Unit,
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
    HorizontalDivider(
        modifier = Modifier.padding(start = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

@Composable
internal fun RepoDetailScreen(
    module: RepoStore.RepoModule,
    installed: String?,
    cached: Boolean,
    busy: Boolean,
    onAction: () -> Unit,
    onOpenStore: () -> Unit,
) {
    val actionLabel = when {
        installed != null && installed == module.version -> "已安装"
        cached -> "安装模块"
        else -> "下载并安装"
    }
    Column(Modifier.fillMaxSize()) {
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
            }
        }
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
        Spacer(Modifier.weight(1f))
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
