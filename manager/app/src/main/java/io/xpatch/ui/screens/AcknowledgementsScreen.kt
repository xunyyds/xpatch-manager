package io.xpatch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.xpatch.ui.theme.HitArea
import io.xpatch.ui.theme.LocalMiuix
import io.xpatch.ui.theme.PagePad
import io.xpatch.ui.theme.Radius
import io.xpatch.ui.theme.Space
import io.xpatch.ui.theme.xpShape
import io.xpatch.ui.util.rememberListBottomPadding
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AcknowledgementsScreen(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = PagePad.horizontal,
            end = PagePad.horizontal,
            top = Space.s,
            bottom = rememberListBottomPadding(false) + Space.l,
        ),
    ) {
        item { AcknowledgementsIntroCard() }
        item { Spacer(Modifier.height(Space.xl)) }
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(xpShape(Radius.card))
                    .background(ackContainer()),
            ) {
                AcknowledgementsData.entries.forEachIndexed { index, entry ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = Space.l),
                            color = ackDivider(),
                        )
                    }
                    AcknowledgementLine(entry) {
                        runCatching { uriHandler.openUri(entry.url) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(Space.xl)) }
        item { AcknowledgementsFooter(uriHandler) }
    }
}

@Composable
private fun AcknowledgementsIntroCard(modifier: Modifier = Modifier) {
    val miuix = LocalMiuix.current
    val container = if (miuix) MiuixTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primaryContainer
    val content = if (miuix) MiuixTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(xpShape(if (miuix) Radius.card else Radius.sheet))
            .background(container)
            .padding(Space.l),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Rounded.Favorite,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(Space.xl),
        )
        Spacer(Modifier.width(Space.m))
        Text(
            AcknowledgementsData.INTRO,
            style = if (miuix) MiuixTheme.textStyles.footnote1 else MaterialTheme.typography.bodyMedium,
            color = content,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AcknowledgementLine(entry: AcknowledgementEntry, onOpen: () -> Unit) {
    val miuix = LocalMiuix.current
    val primary = if (miuix) MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary
    val body = if (miuix) MiuixTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface
    val hint = if (miuix) MiuixTheme.colorScheme.onSurfaceVariantSummary else MaterialTheme.colorScheme.onSurfaceVariant
    val linked = entry.url.isNotEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = if (linked) ripple(color = primary) else null,
                enabled = linked,
                onClick = onOpen,
            )
            .heightIn(min = HitArea.minimum)
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = if (miuix) MiuixTheme.textStyles.body2 else MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (linked) primary else body,
            )
            Spacer(Modifier.height(Space.xs))
            Text(
                entry.meta,
                style = if (miuix) MiuixTheme.textStyles.footnote2 else MaterialTheme.typography.labelSmall,
                color = hint,
            )
        }
        if (linked) {
            Spacer(Modifier.width(Space.s))
            Icon(
                imageVector = Icons.Rounded.OpenInNew,
                contentDescription = null,
                tint = hint,
                modifier = Modifier.size(Space.l),
            )
        }
    }
}

@Composable
private fun AcknowledgementsFooter(uriHandler: androidx.compose.ui.platform.UriHandler) {
    val miuix = LocalMiuix.current
    val primary = if (miuix) MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary
    val hint = if (miuix) MiuixTheme.colorScheme.onSurfaceVariantSummary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text(
            AcknowledgementsData.CONTRIBUTORS_NOTE,
            style = if (miuix) MiuixTheme.textStyles.body2 else MaterialTheme.typography.bodyMedium,
            color = primary,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier
                .clip(xpShape(Radius.control))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = primary),
                ) {
                    runCatching { uriHandler.openUri(AcknowledgementsData.FULL_LIST_URL) }
                }
                .heightIn(min = HitArea.minimum)
                .padding(horizontal = Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "完整许可清单见仓库 ACKNOWLEDGEMENTS.md",
                style = if (miuix) MiuixTheme.textStyles.footnote2 else MaterialTheme.typography.labelSmall,
                color = hint,
            )
        }
    }
}

@Composable
private fun ackContainer() = if (LocalMiuix.current) {
    MiuixTheme.colorScheme.surface
} else {
    MaterialTheme.colorScheme.surfaceContainer
}

@Composable
private fun ackDivider() = if (LocalMiuix.current) {
    MiuixTheme.colorScheme.surfaceContainerHighest
} else {
    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
}
