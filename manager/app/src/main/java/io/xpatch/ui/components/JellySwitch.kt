package io.xpatch.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Jelly Toggle：外观与 Material3 开关一致（52×32 的轨道、20dp 圆球、四周 6dp 间隙），
 * 在此基础上叠加动效：圆球滑向另一端，途中横向拉长成胶囊形，到达终点回弹恢复圆形；
 * 底色由灰色渐变为绿色，约 350ms，带回弹的弹簧曲线。
 *
 * [onSettled] 在整段动画（位移、拉伸回弹、底色渐变）全部结束后回调，
 * 供调用方在动画播完后再做列表重排等位移动作。
 */
@Composable
fun JellySwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackWidth: Dp = 52.dp,
    trackHeight: Dp = 32.dp,
    onSettled: (() -> Unit)? = null,
) {
    // 圆球与轨道边缘的间距，四周保持相等，关闭状态下也不会贴边。
    val margin = 6.dp
    val knob = trackHeight - margin * 2
    val travel = trackWidth - knob - margin * 2

    val knobX = remember { Animatable(if (checked) 1f else 0f) }
    val stretch = remember { Animatable(0f) }
    val trackColor = remember { Animatable(if (checked) 1f else 0f) }
    // 首次组合不回调，只有用户切换后才通知"动画已播完"。
    val settledCallback = rememberUpdatedState(onSettled)
    var animateOnSettle by remember { mutableStateOf(false) }

    LaunchedEffect(checked) {
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
        // 到达另一端后回弹，恢复圆形。
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
        // 三条动画同时启动，以最慢的回弹结束作为整段动画的终点。
        if (animateOnSettle) settledCallback.value?.invoke()
    }

    val offColor = Color(0xFFC7CBD1)
    val onColor = Color(0xFF34C759)
    val interaction = remember { MutableInteractionSource() }

    // 拉伸在行程中段最明显，两端归零，避免静止时也是胶囊。
    val progress = knobX.value
    val edgeFade = (progress * (1f - progress) * 4f).coerceIn(0f, 1f)
    val stretchFactor = 1f + stretch.value * 0.5f * edgeFade
    val knobWidth = knob * stretchFactor
    // 拉伸时以行程位置为中心向外扩展，并把左右边界夹在轨道内，保证间隙始终存在。
    val rawLeft = margin + travel * progress - (knobWidth - knob) / 2
    val maxLeft = trackWidth - margin - knobWidth
    val left = rawLeft.coerceIn(margin, maxLeft)

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
                onValueChange = {
                    animateOnSettle = true
                    onCheckedChange(it)
                },
            )
            .then(if (enabled) Modifier else Modifier.graphicsLayer { alpha = 0.45f }),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = left)
                .width(knobWidth)
                .height(knob)
                .clip(RoundedCornerShape(knob / 2))
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
