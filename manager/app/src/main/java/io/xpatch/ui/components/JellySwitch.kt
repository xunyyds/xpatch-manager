package io.xpatch.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Jelly Toggle：圆点滑向另一端，途中横向拉长成胶囊形，到达终点回弹恢复圆形；
 * 底色由灰色渐变为绿色，时长约 350ms，使用带回弹的弹簧曲线。
 */
@Composable
fun JellySwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackWidth: androidx.compose.ui.unit.Dp = 46.dp,
    trackHeight: androidx.compose.ui.unit.Dp = 27.dp,
) {
    val travel = remember(trackWidth, trackHeight) { trackWidth - trackHeight }
    val stretch = remember { Animatable(0f) }
    val knobX = remember { Animatable(if (checked) 1f else 0f) }
    val trackColor = remember { Animatable(if (checked) 1f else 0f) }

    LaunchedEffect(checked) {
        // 拉伸与位移同时开始：先拉长成胶囊，到位后回弹恢复圆形。
        knobX.animateTo(
            targetValue = if (checked) 1f else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
                visibilityThreshold = 0.001f,
            ),
        )
    }
    LaunchedEffect(checked) {
        stretch.animateTo(
            targetValue = 1f,
            animationSpec = spring(stiffness = Spring.StiffnessHigh, dampingRatio = 1f),
        )
        // 到达终点后回弹恢复圆形。
        stretch.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }
    LaunchedEffect(checked) {
        trackColor.animateTo(
            targetValue = if (checked) 1f else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        )
    }

    val knob = trackHeight - 6.dp
    val offColor = Color(0xFFC7CBD1)
    val onColor = Color(0xFF34C759)

    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .width(trackWidth)
            .height(trackHeight)
            .clip(RoundedCornerShape(trackHeight / 2))
            .background(lerpColor(offColor, onColor, trackColor.value))
            .toggleable(
                value = checked,
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onValueChange = onCheckedChange,
            )
            .then(if (enabled) Modifier else Modifier.graphicsLayer { alpha = 0.45f }),
    ) {
        // 拉伸量在滑动最快时最大，两端最小，避免起点就变形。
        val progress = knobX.value
        val stretchFactor = 1f + stretch.value * 0.55f
        val edgeFade = (progress * (1f - progress) * 4f).coerceIn(0f, 1f)
        val knobW = knob.value * (1f + (stretchFactor - 1f) * edgeFade)
        val leftPx = travel.value * progress
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    translationX = leftPx * density + (knob.value - knobW) / 2f * density
                    scaleX = knobW / knob.value
                }
                .size(knob)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

private fun lerpColor(start: Color, end: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + (end.red - start.red) * f,
        green = start.green + (end.green - start.green) * f,
        blue = start.blue + (end.blue - start.blue) * f,
        alpha = start.alpha + (end.alpha - start.alpha) * f,
    )
}

internal fun stretchAmount(progress: Float): Float = abs(0.5f - progress) * 2f
