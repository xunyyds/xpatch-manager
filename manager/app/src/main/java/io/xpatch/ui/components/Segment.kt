package io.xpatch.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.xpatch.ui.theme.AnimationQuality
import io.xpatch.ui.theme.LocalAnimationQuality
import io.xpatch.ui.theme.LocalMiuix
import io.xpatch.ui.theme.Motion
import io.xpatch.ui.theme.xpShape
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
    val shape = xpShape(if (miuix) 14.dp else 10.dp)
    val track = if (miuix) MiuixTheme.colorScheme.surfaceContainerHigh
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val reduced = LocalAnimationQuality.current == AnimationQuality.REDUCED
    Row(
        modifier
            .clip(shape)
            .background(track)
            .padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val targetFill = when {
                !selected -> Color.Transparent
                miuix -> MiuixTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.primaryContainer
            }
            val targetText = when {
                !selected -> if (miuix) MiuixTheme.colorScheme.onSurfaceVariantSummary
                else MaterialTheme.colorScheme.onSurfaceVariant

                miuix -> MiuixTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onPrimaryContainer
            }
            val spec: AnimationSpec<Color> = if (reduced) snap<Color>() else Motion.controlColor
            val fill by animateColorAsState(targetFill, animationSpec = spec, label = "segmentFill")
            val textColor by animateColorAsState(targetText, animationSpec = spec, label = "segmentText")
            val selectedScale by animateFloatAsState(
                targetValue = if (selected) 1f else 0.94f,
                animationSpec = if (reduced) {
                    snap()
                } else {
                    Motion.control
                },
                label = "segmentScale",
            )
            Box(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(fill)
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    )
                    .graphicsLayer {
                        scaleX = selectedScale
                        scaleY = selectedScale
                    }
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
