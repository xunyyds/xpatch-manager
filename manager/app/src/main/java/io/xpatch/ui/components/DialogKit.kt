package io.xpatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.xpatch.ui.theme.DialogMetrics
import io.xpatch.ui.theme.LocalMiuix
import io.xpatch.ui.theme.PagePad
import io.xpatch.ui.theme.Radius
import io.xpatch.ui.theme.Space
import kotlin.math.roundToInt

internal data class DialogAction(
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
internal fun XPatchDialog(
    onDismiss: () -> Unit,
    title: String,
    actions: List<DialogAction>,
    dismissible: Boolean = true,
    fixedContentHeight: Dp? = null,
    scrollableContent: Boolean = true,
    miuix: Boolean = LocalMiuix.current,
    content: @Composable ColumnScope.() -> Unit,
) {
    val configuration = LocalConfiguration.current
    val miuixMaxContentHeight = (configuration.screenHeightDp * DialogMetrics.MIUIX_FRACTION)
        .roundToInt().coerceAtLeast(DialogMetrics.MIUIX_MIN)
    val contentHeightModifier = if (fixedContentHeight != null) {
        Modifier.height(fixedContentHeight.coerceAtMost(miuixMaxContentHeight.dp))
    } else {
        Modifier.heightIn(max = miuixMaxContentHeight.dp)
    }
    val scrollModifier = if (scrollableContent) {
        Modifier.verticalScroll(rememberScrollState())
    } else {
        Modifier
    }
    if (miuix) {
        top.yukonga.miuix.kmp.window.WindowDialog(
            show = true,
            onDismissRequest = { if (dismissible) onDismiss() },
            title = title,
        ) {
            Column {
                Column(
                    contentHeightModifier.then(scrollModifier),
                ) {
                    content()
                }
                if (actions.isNotEmpty()) {
                    Spacer(Modifier.height(Space.l))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End),
                    ) {
                        actions.forEach { action ->
                            top.yukonga.miuix.kmp.basic.TextButton(
                                text = action.label,
                                onClick = action.onClick,
                                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(
                                    color = if (action.destructive) {
                                        MaterialTheme.colorScheme.errorContainer
                                    } else {
                                        MaterialTheme.colorScheme.primaryContainer
                                    },
                                    textColor = if (action.destructive) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
        return
    }
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val dialogMaxWidth = (configuration.screenWidthDp * DialogMetrics.WIDTH_FRACTION)
        .roundToInt().coerceAtMost(DialogMetrics.WIDTH_MAX)
    val dialogMaxContentHeight = (configuration.screenHeightDp * DialogMetrics.CONTENT_FRACTION)
        .roundToInt()

    Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Column(
            Modifier
                .widthIn(max = dialogMaxWidth.dp)
                .fillMaxWidth()
                .padding(horizontal = PagePad.horizontal)
                .clip(RoundedCornerShape(Radius.dialog))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(
                    start = Space.xl,
                    end = Space.xl,
                    top = Space.xl,
                    bottom = Space.m,
                ),
            )
            Column(
                if (fixedContentHeight != null) {
                    Modifier
                        .height(fixedContentHeight.coerceAtMost(dialogMaxContentHeight.dp))
                        .then(scrollModifier)
                } else {
                    Modifier
                        .heightIn(max = dialogMaxContentHeight.dp)
                        .then(scrollModifier)
                }.padding(horizontal = Space.xl),
            ) {
                content()
            }
            Spacer(Modifier.height(Space.xs))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.s, vertical = Space.s),
                horizontalArrangement = Arrangement.spacedBy(Space.xxs, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEach { action ->
                    DialogButton(
                        label = action.label,
                        color = if (action.destructive) error else primary,
                        onClick = action.onClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(Radius.control))
            .clickable { onClick() }
            .padding(horizontal = Space.m, vertical = Space.m),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}
