package io.xpatch.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.xpatch.model.ModuleInfo
import io.xpatch.ui.components.EmptyState
import io.xpatch.ui.components.EntryRow
import io.xpatch.ui.components.GroupHeader
import io.xpatch.ui.components.PullRefreshBox
import io.xpatch.ui.components.RowDivider
import io.xpatch.ui.components.SearchField
import io.xpatch.ui.theme.LocalMiuix
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixDivider
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ModulesScreen(
    modules: List<ModuleInfo>,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpen: (ModuleInfo) -> Unit,
    onOpenRepo: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val miuix = LocalMiuix.current
    val filtered = remember(modules, query) {
        if (query.isBlank()) modules else modules.filter {
            it.label.contains(query, true) || it.packageName.contains(query, true)
        }
    }
    PullRefreshBox(
        refreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            item { SearchField(query, onQueryChange, "搜索模块 / 包名 / 作者") }
            item {
                if (miuix) {
                    MiuixCard(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ArrowPreference(
                            title = "模块仓库",
                            summary = "浏览并下载在线模块",
                            onClick = onOpenRepo,
                        )
                    }
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenRepo() }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("模块仓库", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            "浏览并下载在线模块",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    RowDivider(startPadding = 0.dp)
                }
            }
            item { GroupHeader("已安装 ${filtered.size} 个模块") }
            if (filtered.isEmpty()) {
                item { EmptyState("没有找到模块") }
            }
            if (miuix && filtered.isNotEmpty()) {
                item {
                    MiuixCard(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        filtered.forEachIndexed { index, module ->
                            ModuleRow(module, onOpen)
                            if (index != filtered.lastIndex) MiuixDivider()
                        }
                    }
                }
            } else if (filtered.isNotEmpty()) {
                items(filtered, key = { it.packageName }) { module ->
                    ModuleRow(module, onOpen)
                    RowDivider()
                }
            }
        }
    }
}

@Composable
internal fun ModuleRow(
    module: ModuleInfo,
    onOpen: (ModuleInfo) -> Unit,
    highlighted: Boolean = false,
) {
    EntryRow(
        icon = module.icon,
        title = if (highlighted) module.label + " · 推荐" else module.label,
        subtitle = moduleSubtitle(module),
        onClick = { onOpen(module) },
    )
}

internal fun moduleSubtitle(module: ModuleInfo): String {
    val parts = mutableListOf<String>()
    if (module.versionName.isNotBlank()) parts.add(module.versionName)
    parts.add("已启用 ${module.enabledApps} 个应用")
    if (module.recommendedLabels.isNotEmpty()) {
        parts.add("推荐 " + module.recommendedLabels.joinToString("/"))
    }
    parts.add(if (module.modernModule) "新版模块" else "旧版模块")
    return parts.joinToString(" · ")
}
