package io.xpatch.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.xpatch.ui.theme.LocalMiuix
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val SHEET_DISMISS_FRACTION = 0.22f

@Composable
internal fun BottomSheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val miuix = LocalMiuix.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var sheetHeight by remember { mutableFloatStateOf(0f) }
    var entered by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }

    fun settle() {
        dragging = false
        scope.launch {
            if (offset.value > sheetHeight * SHEET_DISMISS_FRACTION) {
                offset.animateTo(sheetHeight, tween(180, easing = FastOutSlowInEasing))
                onDismiss()
            } else {
                offset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (dragging) 0.36f else 0.28f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .graphicsLayer {
                        translationY = if (entered) offset.value else sheetHeight
                    }
                    .onGloballyPositioned { coords ->
                        val height = coords.size.height.toFloat()
                        if (height > 0f && !entered) {
                            sheetHeight = height
                            scope.launch {
                                offset.snapTo(height)
                                offset.animateTo(0f, tween(150, easing = FastOutSlowInEasing))
                                entered = true
                            }
                        }
                    }
                    .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                    .background(
                        if (miuix) MiuixTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.surface,
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { }
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 12.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .pointerInput(entered, sheetHeight) {
                            if (!entered) return@pointerInput
                            detectVerticalDragGestures(
                                onDragStart = { dragging = true },
                                onDragEnd = { settle() },
                                onDragCancel = { settle() },
                            ) { change, dragAmount ->
                                change.consume()
                                scope.launch {
                                    offset.snapTo(
                                        (offset.value + dragAmount)
                                            .coerceIn(0f, sheetHeight * 1.4f),
                                    )
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(38.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            ),
                    )
                }
                content()
            }
        }
    }
}

@Composable
internal fun SheetHeader(
    title: String,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            "重置",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    onReset()
                    onDismiss()
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

@Composable
internal fun SheetLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 5.dp),
    )
}
