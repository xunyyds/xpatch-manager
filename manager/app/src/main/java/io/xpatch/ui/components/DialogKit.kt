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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.xpatch.ui.theme.LocalMiuix
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    miuix: Boolean = LocalMiuix.current,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (miuix) {
        top.yukonga.miuix.kmp.window.WindowDialog(
            show = true,
            onDismissRequest = { if (dismissible) onDismiss() },
            title = title,
        ) {
            Column {
                content()
                if (actions.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    ) {
                        actions.forEach { action ->
                            top.yukonga.miuix.kmp.basic.TextButton(
                                text = action.label,
                                onClick = action.onClick,
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
    val configuration = LocalConfiguration.current
    val dialogMaxWidth = (configuration.screenWidthDp * 0.92f).roundToInt().coerceAtMost(480)
    val dialogMaxContentHeight = (configuration.screenHeightDp * 0.6f).roundToInt()

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
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 10.dp),
            )
            Column(
                Modifier
                    .heightIn(max = dialogMaxContentHeight.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            ) {
                content()
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End),
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
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
