package io.xpatch.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object Space {
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

object Radius {
    val control: Dp = 12.dp
    val card: Dp = 16.dp
    val sheet: Dp = 24.dp
}

object Motion {
    const val quickMs: Int = 120
    const val normalMs: Int = 160
    const val containerMs: Int = 220
    const val containerExitMs: Int = 180
}
