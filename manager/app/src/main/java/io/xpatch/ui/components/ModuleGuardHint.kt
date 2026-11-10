package io.xpatch.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.xpatch.data.ModuleGuardStore

/**
 * 首页"模块保护"横幅。
 *
 * 语义约定（与 HomeScreen 的调用方一一对应，改文案时两边一起改）：
 *  - 标题里的"N 个应用"是 [events] 的总数；
 *  - "查看"只打开最近一条（[events] 的第一条）对应的日志；
 *  - "忽略全部"清空 [events] 的全部事件，不是只清第一条（旧实现标题写 N 个、动作只处理第一条）。
 *  - 任一事件 [ModuleGuardStore.Event.scopeWritten] 为 false 时不能说"已自动移除"，
 *    因为磁盘上的作用域文件没写成功，目标应用下次启动仍可能加载该模块。
 */
@Composable
internal fun ModuleGuardHintBanner(
    events: List<ModuleGuardStore.Event>,
    onView: (ModuleGuardStore.Event) -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (events.isEmpty()) return
    val first = events.first()
    val shape = RoundedCornerShape(16.dp)
    val primary = MaterialTheme.colorScheme.primary
    val container = MaterialTheme.colorScheme.primaryContainer
    val title = if (events.size == 1) {
        "「${first.appLabel}」因模块「${first.moduleLabel}」频繁闪退"
    } else {
        "「${first.appLabel}」等 ${events.size} 个应用因模块频繁闪退"
    }
    val writeFailed = events.any { !it.scopeWritten }
    val subtitle = when {
        writeFailed -> "作用域文件未能写入，点按查看日志并手动处理"
        events.size == 1 -> "已自动移除该模块，点按查看日志"
        else -> "已自动移除该模块，点按查看最近一条的日志"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 10.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
            .clickable { onView(first) }
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = primary,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(container)
                .clickable { onView(first) }
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text(
                text = "查看",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = primary,
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { onHide() }
                .semantics { contentDescription = "忽略全部 ${events.size} 条模块保护事件" }
                .padding(horizontal = 8.dp, vertical = 7.dp),
        ) {
            Text(
                text = "忽略全部",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
