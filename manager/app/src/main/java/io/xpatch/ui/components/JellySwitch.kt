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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.xpatch.ui.theme.AnimationQuality
import io.xpatch.ui.theme.LocalAnimationQuality
import io.xpatch.ui.theme.LocalMiuix
import io.xpatch.ui.theme.SwitchKnob
import io.xpatch.ui.theme.SwitchTrackOff
import io.xpatch.ui.theme.SwitchTrackOn
import io.xpatch.ui.theme.xpShape

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
    val margin = 6.dp
    val knob = trackHeight - margin * 2
    val travel = trackWidth - knob - margin * 2

    val reduced = LocalAnimationQuality.current == AnimationQuality.REDUCED
    val knobX = remember { Animatable(if (checked) 1f else 0f) }
    val stretch = remember { Animatable(0f) }
    val trackColor = remember { Animatable(if (checked) 1f else 0f) }
    val settledCallback = rememberUpdatedState(onSettled)
    var animateOnSettle by remember { mutableStateOf(false) }

    LaunchedEffect(checked, reduced) {
        val target = if (checked) 1f else 0f
        if (reduced) {
            knobX.snapTo(target)
        } else {
            knobX.animateTo(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                    visibilityThreshold = 0.001f,
                ),
            )
        }
    }
    LaunchedEffect(checked, reduced) {
        if (!reduced) {
            stretch.animateTo(
                targetValue = 1f,
                animationSpec = spring(stiffness = Spring.StiffnessHigh, dampingRatio = 1f),
            )
            stretch.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
    }
    LaunchedEffect(checked, reduced) {
        val target = if (checked) 1f else 0f
        if (reduced) {
            trackColor.snapTo(target)
        } else {
            trackColor.animateTo(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        }
        if (animateOnSettle) settledCallback.value?.invoke()
    }

    val miuix = LocalMiuix.current
    val offColor = if (miuix) SwitchTrackOff else MaterialTheme.colorScheme.surfaceContainerHighest
    val onColor = if (miuix) SwitchTrackOn else MaterialTheme.colorScheme.primary
    val knobColor = if (miuix) SwitchKnob else MaterialTheme.colorScheme.onPrimary
    val interaction = remember { MutableInteractionSource() }

    val progress = knobX.value
    val edgeFade = (progress * (1f - progress) * 4f).coerceIn(0f, 1f)
    val stretchFactor = 1f + stretch.value * 0.5f * edgeFade
    val knobWidth = knob * stretchFactor
    val rawLeft = margin + travel * progress - (knobWidth - knob) / 2
    val maxLeft = trackWidth - margin - knobWidth
    val left = rawLeft.coerceIn(margin, maxLeft)

    Box(
        modifier = modifier
            .width(trackWidth)
            .height(trackHeight)
            .clip(xpShape(trackHeight / 2))
            .background(lerpColor(offColor, onColor, trackColor.value))
            .toggleable(
                value = checked,
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                role = Role.Switch,
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
                .clip(xpShape(knob / 2))
                .background(knobColor),
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
