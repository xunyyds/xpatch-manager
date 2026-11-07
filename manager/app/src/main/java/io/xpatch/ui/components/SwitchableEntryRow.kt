package io.xpatch.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.xpatch.ui.theme.LocalMiuix
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SwitchableEntryRow(
    icon: Drawable?,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    endExtra: (@Composable () -> Unit)? = null,
    rowBackground: Color? = null,
    minHeight: Dp = 56.dp,
    onSwitchSettled: (() -> Unit)? = null,
) {
    val miuix = LocalMiuix.current
    val rowModifier = if (onClick != null) {
        modifier
            .fillMaxWidth()
            .clickable { onClick() }
    } else {
        modifier.fillMaxWidth()
    }
    Row(
        modifier = rowModifier
            .then(
                if (rowBackground != null) {
                    Modifier.background(rowBackground)
                } else {
                    Modifier
                },
            )
            .heightIn(min = minHeight)
            .padding(
                start = if (miuix) 16.dp else 20.dp,
                end = if (miuix) 16.dp else 20.dp,
                top = 10.dp,
                bottom = 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon, size = if (miuix) 42 else 46)
        Spacer(Modifier.width(if (miuix) 14.dp else 16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (miuix) {
                    MiuixText(
                        title,
                        fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                } else {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                badge?.invoke()
            }
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.padding(top = 1.dp))
                if (miuix) {
                    MiuixText(
                        subtitle,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (endExtra != null) {
            Spacer(Modifier.width(6.dp))
            endExtra()
        }
        Spacer(Modifier.width(8.dp))
        JellySwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            onSettled = onSwitchSettled,
        )
    }
}
