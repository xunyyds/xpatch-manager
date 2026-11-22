package io.xpatch.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.Shapes
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.toPath
import kotlin.math.min

internal const val Smoothing: Float = 0.6f

private data class ShapeKey(
    val topStart: Float,
    val topEnd: Float,
    val bottomEnd: Float,
    val bottomStart: Float,
    val smoothing: Float,
)

private val shapeCache = HashMap<ShapeKey, SmoothCornerShape>()

internal fun xpShape(radius: Dp): CornerBasedShape {
    val key = ShapeKey(radius.value, radius.value, radius.value, radius.value, Smoothing)
    return shapeCache.getOrPut(key) {
        SmoothCornerShape(
            topStart = CornerSize(radius),
            topEnd = CornerSize(radius),
            bottomEnd = CornerSize(radius),
            bottomStart = CornerSize(radius),
        )
    }
}

internal fun xpShape(
    topStart: Dp = 0.dp,
    topEnd: Dp = 0.dp,
    bottomEnd: Dp = 0.dp,
    bottomStart: Dp = 0.dp,
): CornerBasedShape {
    val key = ShapeKey(topStart.value, topEnd.value, bottomEnd.value, bottomStart.value, Smoothing)
    return shapeCache.getOrPut(key) {
        SmoothCornerShape(
            topStart = CornerSize(topStart),
            topEnd = CornerSize(topEnd),
            bottomEnd = CornerSize(bottomEnd),
            bottomStart = CornerSize(bottomStart),
        )
    }
}

private data class PathKey(
    val width: Float,
    val height: Float,
    val ltr: Boolean,
)

internal class SmoothCornerShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
    private val smoothing: Float = Smoothing,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {

    private val pathCache = LinkedHashMap<PathKey, Path>(8, 0.75f, true)

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        if (size.width <= 0f || size.height <= 0f) return Outline.Generic(Path())
        val ltr = layoutDirection == LayoutDirection.Ltr
        val path = cachedPath(size, ltr, topStart, topEnd, bottomEnd, bottomStart)
        return Outline.Generic(path)
    }

    private fun cachedPath(
        size: Size,
        ltr: Boolean,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
    ): Path {
        val key = PathKey(size.width, size.height, ltr)
        pathCache[key]?.let { return it }
        if (pathCache.size >= 16) pathCache.remove(pathCache.keys.first())
        val path = buildPath(size, ltr, topStart, topEnd, bottomEnd, bottomStart)
        pathCache[key] = path
        return path
    }

    private fun buildPath(
        size: Size,
        ltr: Boolean,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
    ): Path {
        val topLeft = if (ltr) topStart else topEnd
        val topRight = if (ltr) topEnd else topStart
        val bottomLeft = if (ltr) bottomStart else bottomEnd
        val bottomRight = if (ltr) bottomEnd else bottomStart
        val limit = min(size.width, size.height) / 2f
        val polygon = RoundedPolygon.rectangle(
            width = size.width,
            height = size.height,
            rounding = CornerRounding.Unrounded,
            perVertexRounding = listOf(
                rounding(bottomRight, limit),
                rounding(bottomLeft, limit),
                rounding(topLeft, limit),
                rounding(topRight, limit),
            ),
            centerX = size.width / 2f,
            centerY = size.height / 2f,
        )
        return polygon.toPath().asComposePath()
    }

    private fun rounding(radius: Float, limit: Float): CornerRounding {
        if (radius <= 0f || limit <= 0f) return CornerRounding.Unrounded
        return CornerRounding(radius.coerceAtMost(limit), smoothing)
    }

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): CornerBasedShape = SmoothCornerShape(topStart, topEnd, bottomEnd, bottomStart, smoothing)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SmoothCornerShape) return false
        return topStart == other.topStart &&
            topEnd == other.topEnd &&
            bottomEnd == other.bottomEnd &&
            bottomStart == other.bottomStart &&
            smoothing == other.smoothing
    }

    override fun hashCode(): Int {
        var result = topStart.hashCode()
        result = 31 * result + topEnd.hashCode()
        result = 31 * result + bottomEnd.hashCode()
        result = 31 * result + bottomStart.hashCode()
        result = 31 * result + smoothing.hashCode()
        return result
    }
}

internal val XpShapes = Shapes(
    extraSmall = xpShape(4.dp),
    small = xpShape(8.dp),
    medium = xpShape(12.dp),
    large = xpShape(16.dp),
    extraLarge = xpShape(28.dp),
)

internal object ShapeTokens {
    val card: CornerBasedShape = xpShape(16.dp)
    val dialog: CornerBasedShape = xpShape(28.dp)
    val sheet: CornerBasedShape = xpShape(topStart = 28.dp, topEnd = 28.dp)
    val input: CornerBasedShape = xpShape(topStart = 4.dp, topEnd = 4.dp)
    val badge: CornerBasedShape = xpShape(16.dp)
}