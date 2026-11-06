package io.xpatch.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.xpatch.data.RuntimeLogStore
import io.xpatch.ui.components.BottomSheet
import io.xpatch.ui.components.DialogAction
import io.xpatch.ui.components.SegmentedControl
import io.xpatch.ui.components.SheetHeader
import io.xpatch.ui.components.XPatchDialog
import io.xpatch.ui.theme.LocalMiuix
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun FilterPanel(
    state: LogsState,
    apps: List<LogAppFilter>,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
) {
    BackHandler(enabled = true) { onDismiss() }
    var appPickerOpen by remember { mutableStateOf(false) }
    BottomSheet(onDismiss = onDismiss) {
        SheetHeader(
            title = "筛选与排序",
            onReset = onReset,
            onDismiss = onDismiss,
        )
        Spacer(Modifier.height(12.dp))
        PanelRow("层级") {
            SegmentedControl(
                options = LogLayer.entries.map { it.label },
                selectedIndex = LogLayer.entries.indexOf(state.layer),
                modifier = Modifier.fillMaxWidth(),
            ) { index -> state.layer = LogLayer.entries[index] }
        }
        PanelRow("视图") {
            SegmentedControl(
                options = LogViewMode.entries.map { it.label },
                selectedIndex = LogViewMode.entries.indexOf(state.view),
                modifier = Modifier.fillMaxWidth(),
            ) { index -> state.view = LogViewMode.entries[index] }
        }
        PanelRow("模块先后") {
            SegmentedControl(
                options = GroupOrder.entries.map { it.short },
                selectedIndex = GroupOrder.entries.indexOf(state.groupOrder),
                modifier = Modifier.fillMaxWidth(),
            ) { index -> state.groupOrder = GroupOrder.entries[index] }
        }
        PanelRow("时间顺序") {
            SegmentedControl(
                options = TimeOrder.entries.map { it.label },
                selectedIndex = TimeOrder.entries.indexOf(state.timeOrder),
                modifier = Modifier.fillMaxWidth(),
            ) { index -> state.timeOrder = TimeOrder.entries[index] }
        }
        PanelRow("应用") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    appChipText(state.apps, apps),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "选择",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(
                            onClick = { appPickerOpen = true },
                            onLongClick = null,
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "筛选只影响显示，导出与清空的范围以对话框说明为准。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (appPickerOpen) {
        AppFilterDialog(
            apps = apps,
            selected = state.apps,
            onConfirm = {
                state.apps = it
                appPickerOpen = false
            },
            onDismiss = { appPickerOpen = false },
        )
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
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun AppFilterDialog(
    apps: List<LogAppFilter>,
    selected: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(selected) }
    XPatchDialog(
        onDismiss = onDismiss,
        title = "按应用筛选",
        actions = listOf(
            DialogAction("取消", onClick = onDismiss),
            DialogAction("应用", onClick = { onConfirm(draft) }),
        ),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { draft = emptySet() }) { Text("全部应用") }
            TextButton(
                onClick = { draft = apps.map { it.target }.toSet() },
                enabled = apps.isNotEmpty(),
            ) { Text("全选") }
            Spacer(Modifier.weight(1f))
        }
        Text(
            "只显示选中应用的日志，与层级筛选叠加生效。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        apps.forEach { app ->
            val checked = draft.isEmpty() || app.target in draft
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .toggleable(
                        value = checked,
                        onValueChange = {
                            val base = if (draft.isEmpty()) apps.map { it.target }.toSet() else draft
                            draft = if (checked) base - app.target else base + app.target
                        },
                    )
                    .padding(vertical = 10.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (checked) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (checked) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        app.label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        app.target,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    app.count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (apps.isEmpty()) {
            Text(
                "还没有加载到任何应用日志。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ClearConfirmDialog(
    tab: LogsTab,
    scope: ClearScope,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    XPatchDialog(
        onDismiss = onDismiss,
        title = if (tab == LogsTab.Runtime) "清空运行日志？" else "清空操作日志？",
        actions = listOf(
            DialogAction("取消", onClick = onDismiss),
            DialogAction("确认清空", destructive = true, onClick = onConfirm),
        ),
    ) {
        scope.lines.forEach { line ->
            Text(
                "· " + line,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
internal fun ClearResultDialog(result: ClearOutcome, onDismiss: () -> Unit) {
    XPatchDialog(
        onDismiss = onDismiss,
        title = result.title,
        actions = listOf(DialogAction("知道了", onClick = onDismiss)),
    ) {
        Text(
            result.message,
            style = MaterialTheme.typography.bodySmall,
            color = if (result.ok) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun DetailBody(
    entry: RuntimeLogStore.Entry,
    labelOf: (String) -> String,
    secondary: Color,
    onCopy: (RuntimeLogStore.Entry) -> Unit,
) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Column {
            Text(
                "时间  " + entry.day + " " + entry.timeText,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "模块  " + entry.module + if (entry.framework) "（框架层）" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "应用  " + labelOf(entry.target) + " · " + entry.target,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "来源  " + entry.sourcePath.substringAfterLast("Android/") + " : " + entry.lineNumber,
                style = MaterialTheme.typography.labelSmall,
                color = secondary,
            )
            if (entry.incomplete) {
                Text(
                    "该条内容可能被截断",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(entry.message, style = MaterialTheme.typography.bodyMedium)
            if (entry.hasDetail) {
                Spacer(Modifier.height(6.dp))
                Text(
                    entry.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "复制完整事件",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .combinedClickable(onClick = { onCopy(entry) }, onLongClick = null)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun DetailNavigation(
    previous: RuntimeLogStore.Entry?,
    next: RuntimeLogStore.Entry?,
    labelOf: (String) -> String,
    onNavigate: (RuntimeLogStore.Entry) -> Unit,
    onDismiss: () -> Unit,
    secondary: Color,
) {
    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    Spacer(Modifier.height(6.dp))
    Text(
        "相邻事件（按当前排序）",
        style = MaterialTheme.typography.labelSmall,
        color = secondary,
    )
    NavigatorRow("较早", previous, labelOf, secondary) { entry ->
        onNavigate(entry)
    }
    NavigatorRow("较晚", next, labelOf, secondary) { entry ->
        onNavigate(entry)
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "关闭后返回搜索结果",
        style = MaterialTheme.typography.labelSmall,
        color = secondary,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onDismiss, onLongClick = null)
            .padding(vertical = 6.dp),
    )
}

@Composable
private fun NavigatorRow(
    direction: String,
    entry: RuntimeLogStore.Entry?,
    labelOf: (String) -> String,
    secondary: Color,
    onClick: (RuntimeLogStore.Entry) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = { entry?.let { onClick(it) } },
                onLongClick = null,
            )
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            direction,
            style = MaterialTheme.typography.labelSmall,
            color = secondary,
            modifier = Modifier.width(34.dp),
        )
        if (entry == null) {
            Text(
                "没有更多",
                style = MaterialTheme.typography.labelSmall,
                color = secondary,
            )
        } else {
            Text(
                entry.timeText + "  " + entry.module + " → " + labelOf(entry.target),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun buildExportOptions(
    tab: LogsTab,
    matched: Int,
    ordered: List<RuntimeLogStore.Entry>,
    totalLoaded: Int,
    loadedEntries: List<RuntimeLogStore.Entry>,
    filteredOperations: List<String>,
    allOperations: List<String>,
    store: RuntimeLogStore,
): List<LogExportOption> {
    val stamp = System.currentTimeMillis()
    return if (tab == LogsTab.Runtime) {
        listOf(
            LogExportOption(
                label = "导出当前筛选结果",
                description = "共 " + matched + " 条，按当前排序，含包名与毫秒",
                fileName = "xpatch-runtime-filtered-$stamp.txt",
                build = { store.export(ordered) },
                hasContent = matched > 0,
            ),
            LogExportOption(
                label = "导出全部已加载日志",
                description = "共 " + totalLoaded + " 条，忽略筛选；仅本次加载范围，不是磁盘全部",
                fileName = "xpatch-runtime-loaded-$stamp.txt",
                build = { store.export(loadedEntries) },
                hasContent = totalLoaded > 0,
            ),
        )
    } else {
        listOf(
            LogExportOption(
                label = "导出当前筛选结果",
                description = "共 " + filteredOperations.size + " 条，按当前筛选",
                fileName = "xpatch-operations-filtered-$stamp.txt",
                build = { filteredOperations.joinToString("\n") },
                hasContent = filteredOperations.isNotEmpty(),
            ),
            LogExportOption(
                label = "导出全部已加载操作日志",
                description = "共 " + allOperations.size + " 条，忽略筛选",
                fileName = "xpatch-operations-loaded-$stamp.txt",
                build = { allOperations.joinToString("\n") },
                hasContent = allOperations.isNotEmpty(),
            ),
        )
    }
}

internal data class ClearScope(
    val lines: List<String>,
)

internal fun clearScope(
    tab: LogsTab,
    runtime: RuntimeLogsState,
    operations: List<String>,
    base: LogsBase?,
    labelOf: (String) -> String,
): ClearScope {
    if (tab == LogsTab.Operations) {
        return ClearScope(
            listOf(
                "只清除 XPatch 保存在本机的操作记录，共 " + operations.size + " 条。",
                "不会删除磁盘上的运行日志文件，也不影响其它应用。",
                "清空后不可恢复。",
            ),
        )
    }
    val apps = runtime.entries.map { it.target }.distinct()
    val modules = runtime.entries.map { it.module }.distinct()
    val files = runtime.snapshot?.entries?.map { it.sourcePath }?.distinct()?.size ?: 0
    return ClearScope(
        buildList {
            add("会删除磁盘上的日志文件，不只是当前页面显示的内容。")
            add("范围：所有应用在 Android/data 与 Android/media 下的 .logs 目录，含全部模块与框架层日志。")
            add(
                "已加载部分涉及 " + apps.size + " 个应用、" + modules.size + " 个模块、" +
                    files + " 个文件；未加载的文件同样会被删除。",
            )
            if (base?.filtered == true) {
                add("当前处于筛选状态，但清空不受筛选影响，仍会删除全部日志。")
            }
            add("删除后不可恢复。")
        },
    )
}

internal fun describeClear(result: RuntimeLogStore.ClearResult): String {
    if (result.ok) {
        return "已删除 " + result.deletedFiles + " 个文件，涉及 " + result.affectedApps + " 个应用。"
    }
    return buildString {
        append("已删除 ")
        append(result.deletedFiles)
        append(" 个文件，")
        append(result.failed.size)
        append(" 处删除失败，列表可能仍显示未删掉的日志。")
        result.failed.firstOrNull()?.let { failure ->
            append("\n首个失败：")
            append(failure.path)
            append("（")
            append(failure.reason)
            append("）")
        }
    }
}

internal fun appChipText(selected: Set<String>, apps: List<LogAppFilter>): String = when {
    selected.isEmpty() -> "全部应用"
    selected.size == 1 -> {
        val target = selected.first()
        apps.firstOrNull { it.target == target }?.label ?: target
    }

    else -> selected.size.toString() + " 个应用"
}
