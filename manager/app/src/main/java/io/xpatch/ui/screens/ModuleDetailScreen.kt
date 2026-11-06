package io.xpatch.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.xpatch.model.ModuleInfo
import io.xpatch.ui.components.AppIcon
import io.xpatch.ui.components.GroupHeader
import io.xpatch.ui.components.SwitchableEntryRow
import io.xpatch.ui.theme.LocalMiuix
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixDivider
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 模块详情：展示模块简介、全部元信息，并可直接切换该模块对该应用的作用域开关。
 */
@Composable
internal fun ModuleDetailScreen(
    module: ModuleInfo,
    appLabel: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    scopeCount: Int,
) {
    val miuix = LocalMiuix.current
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ModuleDetailHeader(module) }
        item { GroupHeader("对本应用") }
        item {
            if (miuix) {
                MiuixCard(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    SwitchableEntryRow(
                        icon = module.icon,
                        title = if (checked) "已启用" else "未启用",
                        subtitle = "作用于 $appLabel",
                        checked = checked,
                        onCheckedChange = onToggle,
                    )
                    MiuixDivider()
                    DetailRow("已作用应用数", "$scopeCount 个", Modifier.padding(horizontal = 16.dp))
                }
            } else {
                SwitchableEntryRow(
                    icon = module.icon,
                    title = if (checked) "已启用" else "未启用",
                    subtitle = "作用于 $appLabel",
                    checked = checked,
                    onCheckedChange = onToggle,
                )
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    DetailRow("已作用应用数", "$scopeCount 个")
                }
            }
        }
        item { GroupHeader("模块信息") }
        item {
            if (miuix) {
                MiuixCard(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    val rows = infoRows(module)
                    rows.forEachIndexed { index, (label, value) ->
                        DetailRow(label, value, Modifier.padding(horizontal = 16.dp))
                        if (index != rows.lastIndex) MiuixDivider()
                    }
                }
            } else {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    infoRows(module).forEach { (label, value) -> DetailRow(label, value) }
                }
            }
        }
        if (module.description.isNotBlank()) {
            item { GroupHeader("模块简介") }
            item {
                Text(
                    module.description,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private fun infoRows(module: ModuleInfo): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()
    rows.add("包名" to module.packageName)
    rows.add("版本" to module.versionName.ifBlank { "未设置" })
    rows.add("类型" to when {
        module.modernModule && module.legacyModule -> "新版 + 旧版"
        module.modernModule -> "新版模块"
        module.legacyModule -> "旧版模块"
        else -> "未知"
    })
    rows.add("API 级别" to if (module.apiLevel > 0) module.apiLevel.toString() else "未声明")
    rows.add("自动选择" to apiText(module.autoApiLevel))
    if (module.availableApiLevels.isNotEmpty()) {
        rows.add("可用 API" to module.availableApiLevels.joinToString(" / ") { apiText(it) })
    }
    rows.add("推荐应用" to if (module.recommendedLabels.isEmpty()) "无" else module.recommendedLabels.joinToString("、"))
    return rows
}

private fun apiText(level: Int): String = "API $level"

@Composable
private fun ModuleDetailHeader(module: ModuleInfo) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(module.icon, size = 54)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                module.label,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                module.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = if (LocalMiuix.current) MiuixTheme.colorScheme.onSurfaceVariantSummary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
