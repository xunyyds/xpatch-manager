package io.xpatch.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.xpatch.model.ModuleInfo
import io.xpatch.ui.components.AppIcon
import io.xpatch.ui.components.GroupHeader
import io.xpatch.ui.theme.LocalMiuix
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixDivider
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun moduleTypeText(module: ModuleInfo): String = when {
    module.modernModule && module.legacyModule -> "新版 + 旧版"
    module.modernModule -> "新版模块"
    module.legacyModule -> "旧版模块"
    else -> "未知"
}

internal fun moduleInfoRows(module: ModuleInfo): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()
    rows.add("包名" to module.packageName)
    rows.add("版本" to module.versionName.ifBlank { "未设置" })
    rows.add("类型" to moduleTypeText(module))
    rows.add("API 级别" to if (module.apiLevel > 0) module.apiLevel.toString() else "未声明")
    rows.add("自动选择" to "API " + module.autoApiLevel)
    if (module.availableApiLevels.isNotEmpty()) {
        rows.add("可用 API" to module.availableApiLevels.joinToString(" / ") { "API " + it })
    }
    rows.add(
        "推荐应用" to if (module.recommendedLabels.isEmpty()) {
            "无"
        } else {
            module.recommendedLabels.joinToString("、")
        },
    )
    return rows
}

@Composable
internal fun ModuleHeader(module: ModuleInfo, metaLine: String? = null) {
    val hint = if (LocalMiuix.current) MiuixTheme.colorScheme.onSurfaceVariantSummary
    else MaterialTheme.colorScheme.onSurfaceVariant
    val primary = if (LocalMiuix.current) MiuixTheme.colorScheme.primary
    else MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(module.icon, size = 54)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    module.label,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    module.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = hint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!metaLine.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(metaLine, style = MaterialTheme.typography.labelMedium, color = primary)
        }
    }
}

@Composable
internal fun ModuleInfoCard(module: ModuleInfo, title: String = "模块信息") {
    GroupHeader(title)
    if (LocalMiuix.current) {
        MiuixCard(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        ) {
            val rows = moduleInfoRows(module)
            rows.forEachIndexed { index, (label, value) ->
                DetailRow(label, value, Modifier.padding(horizontal = 16.dp))
                if (index != rows.lastIndex) MiuixDivider()
            }
        }
    } else {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            moduleInfoRows(module).forEach { (label, value) -> DetailRow(label, value) }
        }
    }
}

@Composable
internal fun ModuleIntro(module: ModuleInfo) {
    if (module.description.isBlank()) return
    GroupHeader("模块简介")
    Text(
        module.description,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
    )
}

internal fun moduleMetaLine(vararg parts: String?): String =
    parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
