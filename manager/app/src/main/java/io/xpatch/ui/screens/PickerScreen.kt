package io.xpatch.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.xpatch.model.AppInfo
import io.xpatch.ui.components.EmptyState
import io.xpatch.ui.components.EntryRow
import io.xpatch.ui.components.GroupHeader
import io.xpatch.ui.components.RowDivider
import io.xpatch.ui.components.SortMenu
import io.xpatch.ui.components.SortOption
import io.xpatch.ui.components.StickySearchBar
import io.xpatch.ui.components.TrailingText
import io.xpatch.ui.format.readableSize
import io.xpatch.ui.theme.LocalMiuix
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixDivider
import top.yukonga.miuix.kmp.theme.MiuixTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PickerScreen(
    apps: List<AppInfo>,
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (AppInfo) -> Unit,
    includeSystem: Boolean,
    onToggleSystem: (Boolean) -> Unit,
) {
    val miuix = LocalMiuix.current
    val background = if (miuix) MiuixTheme.colorScheme.background else MaterialTheme.colorScheme.background
    var sortId by remember { mutableStateOf("default") }
    var reversed by remember { mutableStateOf(false) }
    val options = remember {
        listOf(
            SortOption("letter", "首字母"),
            SortOption("default", "默认"),
            SortOption("size", "大小"),
        )
    }

    val filtered = apps
        .filter { includeSystem || !it.isSystem }
        .filter {
            query.isBlank() ||
                it.label.contains(query, true) ||
                it.packageName.contains(query, true)
        }
    val sorted = when (sortId) {
        "letter" -> filtered.sortedBy { it.label.lowercase() }
        "size" -> filtered.sortedBy { it.size }
        else -> filtered
    }
    val ordered = if (reversed) sorted.reversed() else sorted
    val groups = if (sortId == "letter") {
        ordered.groupBy { letterOf(it.label) }
            .toList()
            .sortedWith(compareBy({ if (it.first == "#") 1 else 0 }, { it.first }))
            .let { if (reversed) it.reversed() else it }
    } else {
        emptyList()
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val letterHeaders = remember(groups, miuix) {
        var idx = 1
        groups.map { (letter, group) ->
            val header = idx
            idx += if (miuix) 2 else 1 + group.size
            letter to header
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            StickySearchBar(background, query, onQueryChange, "搜索应用")
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        bottom = 24.dp,
                        end = if (letterHeaders.isNotEmpty()) 30.dp else 0.dp,
                    ),
                ) {
                    item(key = "picker-count") { GroupHeader("共 ${ordered.size} 个应用") }
                    if (ordered.isEmpty()) {
                        item { EmptyState("没有找到应用") }
                    } else if (sortId == "letter") {
                        groups.forEach { (letter, group) ->
                            stickyHeader(key = "letter-$letter") {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(background),
                                ) {
                                    GroupHeader(letter)
                                }
                            }
                            if (miuix) {
                                item(key = "card-$letter") {
                                    MiuixCard(
                                        modifier = Modifier
                                            .padding(start = 12.dp)
                                            .padding(bottom = 12.dp),
                                    ) {
                                        group.forEachIndexed { index, app ->
                                            PickerRow(app, sortId) { onPick(app) }
                                            if (index != group.lastIndex) MiuixDivider()
                                        }
                                    }
                                }
                            } else {
                                items(group, key = { it.packageName }) { app ->
                                    PickerRow(app, sortId) { onPick(app) }
                                    RowDivider()
                                }
                            }
                        }
                    } else if (miuix) {
                        item {
                            MiuixCard(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 12.dp),
                            ) {
                                ordered.forEachIndexed { index, app ->
                                    PickerRow(app, sortId) { onPick(app) }
                                    if (index != ordered.lastIndex) MiuixDivider()
                                }
                            }
                        }
                    } else {
                        items(ordered, key = { it.packageName }) { app ->
                            PickerRow(app, sortId) { onPick(app) }
                            RowDivider()
                        }
                    }
                }
                if (letterHeaders.isNotEmpty()) {
                    val current = listState.firstVisibleItemIndex
                    val activeLetter = letterHeaders.lastOrNull { it.second <= current }?.first
                    Column(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 3.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (miuix) MiuixTheme.colorScheme.surfaceContainerHighest
                                else MaterialTheme.colorScheme.surfaceContainerHighest,
                            )
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        letterHeaders.forEach { (letter, header) ->
                            val active = letter == activeLetter
                            Text(
                                letter,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { scope.launch { listState.scrollToItem(header) } }
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
        SortMenu(
            options = options,
            selectedId = sortId,
            reversed = reversed,
            onSelect = { sortId = it },
            onReverse = { reversed = !reversed },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 8.dp, top = 6.dp),
            extra = {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "显示系统应用",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = includeSystem, onCheckedChange = onToggleSystem)
                }
            },
            trigger = { open ->
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            if (miuix) MiuixTheme.colorScheme.surfaceContainerHigh
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                        .clickable { open() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Tune,
                        contentDescription = "列表设置",
                        modifier = Modifier.size(19.dp),
                        tint = if (miuix) MiuixTheme.colorScheme.onSurfaceVariantSummary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}

@Composable
private fun PickerRow(app: AppInfo, sortId: String, onPick: () -> Unit) {
    val patched = app.runtimeVersion > 0 || app.hookCore != null
    EntryRow(
        icon = app.icon,
        title = app.label,
        subtitle = app.packageName,
        caption = if (patched) "已打包" else null,
        onClick = onPick,
        trailing = if (sortId == "size") {
            { TrailingText(readableSize(app.size)) }
        } else {
            null
        },
    )
}

private fun letterOf(label: String): String {
    val c = label.firstOrNull()?.uppercaseChar() ?: return "#"
    return if (c in 'A'..'Z') c.toString() else "#"
}
