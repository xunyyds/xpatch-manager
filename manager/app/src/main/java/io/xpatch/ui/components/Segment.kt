package io.xpatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.xpatch.ui.theme.LocalMiuix
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val miuix = LocalMiuix.current
    val shape = RoundedCornerShape(if (miuix) 14.dp else 10.dp)
    val track = if (miuix) MiuixTheme.colorScheme.surfaceContainerHigh
    else MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        modifier
            .clip(shape)
            .background(track)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val fill = when {
                !selected -> Color.Transparent
                miuix -> MiuixTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.primaryContainer
            }
            val textColor = when {
                !selected -> if (miuix) MiuixTheme.colorScheme.onSurfaceVariantSummary
                else MaterialTheme.colorScheme.onSurfaceVariant

                miuix -> MiuixTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onPrimaryContainer
            }
            Box(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(fill)
                    .clickable { onSelect(index) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (miuix) {
                    MiuixText(
                        label,
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
