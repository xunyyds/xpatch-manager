package io.xpatch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.xpatch.data.PatchExportRef
import io.xpatch.data.PatchRecord
import io.xpatch.ui.components.DialogAction
import io.xpatch.ui.components.EmptyState
import io.xpatch.ui.components.PullRefreshBox
import io.xpatch.ui.components.RowDivider
import io.xpatch.ui.components.XPatchDialog
import io.xpatch.ui.format.formatDateTime
import io.xpatch.ui.format.readableSize
import io.xpatch.ui.theme.LocalMiuix
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PatchHistoryScreen(
    records: List<PatchRecord>,
    totalSize: Long,
    limit: Int,
    refreshing: Boolean,
    deletePromptRequired: Boolean,
    defaultDeleteArtifact: Boolean,
    onRefresh: () -> Unit,
    onInstall: (PatchRecord) -> Unit,
    onShare: (PatchRecord) -> Unit,
    onExport: (PatchRecord) -> Unit,
    onDeleteExport: (PatchRecord, PatchExportRef) -> Unit,
    onDelete: (PatchRecord, Boolean, Boolean) -> Unit,
    onClearMissing: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var pending by remember { mutableStateOf<PatchRecord?>(null) }
    val requestDelete: (PatchRecord) -> Unit = { record ->
        if (deletePromptRequired) {
            pending = record
        } else {
            onDelete(record, defaultDeleteArtifact, false)
        }
    }
    pending?.let { record ->
        DeleteRecordDialog(
            record = record,
            defaultDeleteArtifact = defaultDeleteArtifact,
            onDismiss = { pending = null },
            onConfirm = { deleteArtifact, rememberChoice ->
                pending = null
                onDelete(record, deleteArtifact, rememberChoice)
            },
        )
    }
    Column(Modifier.fillMaxSize()) {
        HistoryHeader(
            count = records.size,
            limit = limit,
            totalSize = totalSize,
            canClear = records.any { !it.hasArtifact() },
            onClearMissing = onClearMissing,
        )
        PullRefreshBox(
            refreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                if (records.isEmpty()) {
                    item(key = "history-empty") {
                        EmptyState("还没有打包过的产物\n回到首页添加应用，就能去打包了")
                    }
                } else {
                    items(records, key = { it.id }) { record ->
                        HistoryEntry(
                            record = record,
                            onInstall = onInstall,
                            onShare = onShare,
                            onExport = onExport,
                            onDeleteExport = onDeleteExport,
                            onDelete = requestDelete,
                        )
                    }
                    item(key = "history-settings") {
                        HistorySettingsHint(
                            count = records.size,
                            limit = limit,
                            onOpenSettings = onOpenSettings,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryHeader(
    count: Int,
    limit: Int,
    totalSize: Long,
    canClear: Boolean,
    onClearMissing: () -> Unit,
) {
    val miuix = LocalMiuix.current
    val secondary = if (miuix) {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "共 " + count + " 条 · 上限 " + limit + " 条 · 占用 " + readableSize(totalSize),
            style = MaterialTheme.typography.labelMedium,
            color = secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClearMissing, enabled = canClear) {
            Text("清理失效")
        }
    }
}

@Composable
private fun HistoryEntry(
    record: PatchRecord,
    onInstall: (PatchRecord) -> Unit,
    onShare: (PatchRecord) -> Unit,
    onExport: (PatchRecord) -> Unit,
    onDeleteExport: (PatchRecord, PatchExportRef) -> Unit,
    onDelete: (PatchRecord) -> Unit,
) {
    if (LocalMiuix.current) {
        MiuixCard(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 8.dp),
        ) {
            HistoryEntryBody(record, onInstall, onShare, onExport, onDeleteExport, onDelete)
        }
    } else {
        HistoryEntryBody(record, onInstall, onShare, onExport, onDeleteExport, onDelete)
        RowDivider(startPadding = 20.dp)
    }
}

@Composable
private fun HistoryEntryBody(
    record: PatchRecord,
    onInstall: (PatchRecord) -> Unit,
    onShare: (PatchRecord) -> Unit,
    onExport: (PatchRecord) -> Unit,
    onDeleteExport: (PatchRecord, PatchExportRef) -> Unit,
    onDelete: (PatchRecord) -> Unit,
) {
    val miuix = LocalMiuix.current
    val primary = if (miuix) MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary
    val secondary = if (miuix) {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val artifact = record.hasArtifact()
    var exportsOpen by remember(record.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                record.label.ifBlank { record.packageName },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            HistoryBadge(
                text = if (artifact) "可复用" else "仅记录",
                color = if (artifact) primary else secondary,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            historySummary(record),
            style = MaterialTheme.typography.labelSmall,
            color = secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (record.optionSummary.isNotBlank()) {
            Text(
                record.optionSummary,
                style = MaterialTheme.typography.labelSmall,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HistoryAction("安装", enabled = artifact) { onInstall(record) }
            HistoryAction("分享", enabled = artifact) { onShare(record) }
            HistoryAction("导出", enabled = artifact) { onExport(record) }
            HistoryAction("删除", destructive = true) { onDelete(record) }
        }
        if (record.exports.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { exportsOpen = !exportsOpen }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已导出 " + record.exports.size + " 份",
                    style = MaterialTheme.typography.labelSmall,
                    color = primary,
                )
                Icon(
                    imageVector = if (exportsOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (exportsOpen) "收起导出记录" else "展开导出记录",
                    tint = primary,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(16.dp),
                )
            }
            if (exportsOpen) {
                record.exports.forEach { ref ->
                    ExportLine(
                        ref = ref,
                        exists = record.exportExists(ref),
                        secondary = secondary,
                        onRemove = { onDeleteExport(record, ref) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportLine(
    ref: PatchExportRef,
    exists: Boolean,
    secondary: Color,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                ref.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (exists) {
                    formatDateTime(ref.time)
                } else {
                    formatDateTime(ref.time) + " · 文件已丢失"
                },
                style = MaterialTheme.typography.labelSmall,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HistoryAction("删除", destructive = true, onClick = onRemove)
    }
}

@Composable
private fun HistoryBadge(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun HistoryAction(
    text: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier.height(36.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
    }
}

@Composable
private fun HistorySettingsHint(
    count: Int,
    limit: Int,
    onOpenSettings: () -> Unit,
) {
    val miuix = LocalMiuix.current
    val primary = if (miuix) MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary
    val reached = limit > 0 && count >= limit
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onOpenSettings() }
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            if (reached) {
                "已达到记录上限 " + limit + " 条，新记录会挤掉最旧的记录 · 前往设置调整"
            } else {
                "记录上限与删除策略可在设置中调整"
            },
            style = MaterialTheme.typography.labelMedium,
            color = primary,
        )
    }
}

@Composable
private fun DeleteRecordDialog(
    record: PatchRecord,
    defaultDeleteArtifact: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Boolean, Boolean) -> Unit,
) {
    var deleteArtifact by remember(record.id) { mutableStateOf(defaultDeleteArtifact) }
    var rememberChoice by remember(record.id) { mutableStateOf(false) }
    XPatchDialog(
        onDismiss = onDismiss,
        title = "删除打包记录",
        actions = listOf(
            DialogAction("取消", onClick = onDismiss),
            DialogAction("删除", destructive = true) { onConfirm(deleteArtifact, rememberChoice) },
        ),
    ) {
        Text(
            record.label.ifBlank { record.packageName } + " · " + readableSize(record.apkSize),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (deleteArtifact) {
                "记录与产物文件将一起删除，删除后无法恢复。"
            } else {
                "只删除记录，产物文件会保留在设备上。"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        CheckLine("同时删除产物文件", deleteArtifact) { deleteArtifact = it }
        CheckLine("记住我的选择，下次不再询问", rememberChoice) { rememberChoice = it }
    }
}

@Composable
private fun CheckLine(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun historySummary(record: PatchRecord): String {
    val version = record.versionName.ifBlank { "未知版本" } + "(" + record.versionCode + ")"
    return listOf(
        version,
        formatDateTime(record.time),
        readableSize(record.apkSize),
    ).filter { it.isNotBlank() }.joinToString(" · ")
}
