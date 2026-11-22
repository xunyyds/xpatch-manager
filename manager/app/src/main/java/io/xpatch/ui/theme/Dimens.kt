package io.xpatch.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

object Space {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

object PagePad {
    val horizontal: Dp = 20.dp
    val container: Dp = 16.dp
}

object RowMetrics {
    val icon: Dp = 44.dp
    val singleLine: Dp = 56.dp
    val twoLine: Dp = 72.dp
}

object HitArea {
    val minimum: Dp = 48.dp
    val icon: Dp = 20.dp
    val searchHeight: Dp = 48.dp
}

object IconSize {
    val tiny: Dp = 14.dp
    val small: Dp = 16.dp
    val compact: Dp = 18.dp
    val normal: Dp = 22.dp
    val stroke: Dp = 2.dp
}

object Radius {
    val chip: Dp = 6.dp
    val control: Dp = 12.dp
    val card: Dp = 16.dp
}

object DialogMetrics {
    const val WIDTH_FRACTION: Float = 0.92f
    const val WIDTH_MAX: Int = 480
    const val CONTENT_FRACTION: Float = 0.6f
    const val MIUIX_FRACTION: Float = 0.5f
    const val MIUIX_MIN: Int = 240
    const val LIST_FRACTION: Float = 0.62f
    const val LOADING_FRACTION: Float = 0.18f
}

@Composable
fun rememberScreenHeightDp(): Int = LocalConfiguration.current.screenHeightDp

@Composable
fun rememberScreenWidthDp(): Int = LocalConfiguration.current.screenWidthDp

@Composable
fun adaptiveDialogWidth(): Dp {
    val width = LocalConfiguration.current.screenWidthDp
    return (width * DialogMetrics.WIDTH_FRACTION).toInt()
        .coerceAtMost(DialogMetrics.WIDTH_MAX)
        .dp
}

@Composable
fun adaptiveDialogContentHeight(fraction: Float = DialogMetrics.CONTENT_FRACTION): Dp {
    val height = LocalConfiguration.current.screenHeightDp
    return (height * fraction).toInt().dp
}

@Composable
fun adaptiveDialogListHeight(): Dp = adaptiveDialogContentHeight(DialogMetrics.LIST_FRACTION)

@Composable
fun adaptiveLoadingHeight(): Dp = adaptiveDialogContentHeight(DialogMetrics.LOADING_FRACTION)

object Motion {
    const val quickMs: Int = 120
    const val normalMs: Int = 160
    const val containerMs: Int = 220
    const val containerExitMs: Int = 180

    val fadeIn = tween<Float>(durationMillis = normalMs, easing = FastOutSlowInEasing)
    val fadeOut = tween<Float>(durationMillis = quickMs, easing = FastOutSlowInEasing)
    val control = tween<Float>(durationMillis = normalMs, easing = FastOutSlowInEasing)
    val spatialEnter = tween<IntOffset>(durationMillis = containerMs, easing = FastOutSlowInEasing)
    val spatialExit = tween<IntOffset>(durationMillis = containerExitMs, easing = FastOutSlowInEasing)
}
