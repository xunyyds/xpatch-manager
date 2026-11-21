package io.xpatch.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.UnevenRoundedRectangle

internal fun xpShape(radius: Dp): RoundedRectangle =
    RoundedRectangle(radius, RoundedCornerStyle.Continuous)

internal fun xpShape(
    topStart: Dp = 0.dp,
    topEnd: Dp = 0.dp,
    bottomEnd: Dp = 0.dp,
    bottomStart: Dp = 0.dp,
): UnevenRoundedRectangle =
    UnevenRoundedRectangle(topStart, topEnd, bottomEnd, bottomStart, RoundedCornerStyle.Continuous)

internal fun xpCornerShape(radius: Dp): CornerBasedShape = ContinuousCornerShape(
    topStart = CornerSize(radius),
    topEnd = CornerSize(radius),
    bottomEnd = CornerSize(radius),
    bottomStart = CornerSize(radius),
)

internal class ContinuousCornerShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {
    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline = UnevenRoundedRectangle(
        topStart.dp,
        topEnd.dp,
        bottomEnd.dp,
        bottomStart.dp,
        RoundedCornerStyle.Continuous,
    ).createOutline(size, layoutDirection, Density(1f, 1f))

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): CornerBasedShape = ContinuousCornerShape(topStart, topEnd, bottomEnd, bottomStart)
}