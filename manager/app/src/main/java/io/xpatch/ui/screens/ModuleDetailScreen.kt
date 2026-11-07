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
        item { ModuleHeader(module) }
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
        item { ModuleInfoCard(module) }
        if (module.description.isNotBlank()) {
            item { ModuleIntro(module) }
        }
    }
}
