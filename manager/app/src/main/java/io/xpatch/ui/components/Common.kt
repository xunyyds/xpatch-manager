package io.xpatch.ui.components

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.xpatch.ui.theme.LocalMiuix
import java.util.Collections
import java.util.WeakHashMap
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val iconCache = Collections.synchronizedMap(WeakHashMap<Drawable, MutableMap<Int, ImageBitmap>>())

@Composable
fun AppIcon(drawable: Drawable?, size: Int = 48) {
    val density = LocalDensity.current
    val px = with(density) { size.dp.roundToPx() }.coerceAtLeast(1)
    val bitmap = remember(drawable, px) {
        if (drawable == null) {
            null
        } else {
            iconCache.getOrPut(drawable) { mutableMapOf() }
                .getOrPut(px) { drawable.toBitmap(px, px, Bitmap.Config.ARGB_8888).asImageBitmap() }
        }
    }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(size.dp * 0.3f))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(size.dp),
                filterQuality = FilterQuality.High,
            )
        }
    }
}

@Composable
fun EntryRow(
    icon: Drawable?,
    title: String,
    subtitle: String,
    caption: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val miuix = LocalMiuix.current
    val base = Modifier.fillMaxWidth()
    val modifier = when {
        onClick != null && onLongClick != null ->
            base.combinedClickable(onClick = onClick, onLongClick = onLongClick)

        onClick != null -> base.clickable { onClick() }
        else -> base
    }
    Row(
        modifier = modifier.padding(
            start = if (miuix) 16.dp else 20.dp,
            end = if (miuix) 16.dp else 20.dp,
            top = 12.dp,
            bottom = 12.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon, size = if (miuix) 42 else 46)
        Spacer(Modifier.width(if (miuix) 14.dp else 16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            if (miuix) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MiuixText(
                        title,
                        fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    badge?.invoke()
                }
                Spacer(Modifier.height(2.dp))
                MiuixText(
                    subtitle,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (caption != null) {
                    MiuixText(
                        caption,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    badge?.invoke()
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (caption != null) {
                    Text(
                        caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val miuix = LocalMiuix.current
    val container = if (miuix) MiuixTheme.colorScheme.surfaceContainerHighest
    else MaterialTheme.colorScheme.surfaceContainerHighest
    val hint = if (miuix) MiuixTheme.colorScheme.onSurfaceVariantSummary
    else MaterialTheme.colorScheme.onSurfaceVariant
    val accent = if (miuix) MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary
    val textColor = if (miuix) MiuixTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .height(42.dp)
            .clip(RoundedCornerShape(if (miuix) 14.dp else 21.dp))
            .background(container)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = hint,
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = hint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                cursorBrush = SolidColor(accent),
            )
        }
        if (value.isNotEmpty()) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .clickable { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "清除搜索内容",
                    modifier = Modifier.size(16.dp),
                    tint = hint,
                )
            }
        }
    }
}

@Composable
fun GroupHeader(text: String) {
    if (LocalMiuix.current) {
        SmallTitle(text)
        return
    }
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    fill: Boolean = false,
) {
    if (LocalMiuix.current) {
        val container = if (selected) MiuixTheme.colorScheme.tertiaryContainer
        else MiuixTheme.colorScheme.surfaceContainerHigh
        val content = if (selected) MiuixTheme.colorScheme.onTertiaryContainer
        else MiuixTheme.colorScheme.onSurfaceVariantSummary
        Box(
            Modifier
                .then(if (fill) Modifier.fillMaxWidth() else Modifier)
                .clip(RoundedCornerShape(12.dp))
                .background(container)
                .clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            MiuixText(
                text,
                style = MiuixTheme.textStyles.body2,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        onClick = onClick,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun RowDivider(startPadding: Dp = 82.dp) {
    if (LocalMiuix.current) {
        MiuixDivider(
            modifier = Modifier.padding(start = startPadding),
        )
    } else {
        HorizontalDivider(
            modifier = Modifier.padding(start = startPadding),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
    }
}

@Composable
fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 72.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (LocalMiuix.current) {
            MiuixText(
                text,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun PulsingDots(
    modifier: Modifier = Modifier,
    dotSize: Dp = 7.dp,
    spacing: Dp = 5.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val transition = rememberInfiniteTransition(label = "dots")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1080, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val distance = ((phase.value - index) % 3f + 3f) % 3f
            val lift = (1f - distance).coerceIn(0f, 1f)
            val alpha = 0.24f + 0.76f * lift
            Box(
                Modifier
                    .size(dotSize)
                    .clip(RoundedCornerShape(dotSize))
                    .background(color.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
fun ThinProgressBar(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = color.copy(alpha = 0.16f),
    height: Dp = 2.dp,
) {
    val transition = rememberInfiniteTransition(label = "bar")
    val head = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "head",
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val radius = size.height / 2f
        drawRoundRect(
            color = trackColor,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
        )
        val barWidth = (size.width * 0.34f).coerceAtLeast(1f)
        val travel = size.width + barWidth
        val left = head.value * travel - barWidth
        val start = left.coerceAtLeast(0f)
        val end = (left + barWidth).coerceAtMost(size.width)
        if (end > start) {
            drawLine(
                color = color,
                start = Offset(start + radius, radius),
                end = Offset(end - radius, radius),
                strokeWidth = size.height,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
}

@Composable
fun BusyRow(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDots()
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun Spinner(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 2.dp,
    durationMillis: Int = 850,
) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "angle",
    )
    Canvas(modifier = modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        drawArc(
            color = color.copy(alpha = 0.18f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(width = stroke),
        )
        drawArc(
            color = color,
            startAngle = angle.value - 90f,
            sweepAngle = 96f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun RefreshCircle(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    Box(
        modifier = modifier
            .size(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Spinner(size = 20.dp, color = color, strokeWidth = 2.dp)
    }
}
