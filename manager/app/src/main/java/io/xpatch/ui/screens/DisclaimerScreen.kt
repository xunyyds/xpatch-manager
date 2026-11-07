package io.xpatch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.xpatch.ui.theme.LocalMiuix
import io.xpatch.ui.util.rememberListBottomPadding
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DisclaimerScreen(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 8.dp,
            bottom = rememberListBottomPadding(false) + 16.dp,
        ),
    ) {
        item { DisclaimerIntroCard() }
        item { Spacer(Modifier.height(28.dp)) }
        DisclaimerData.sections.forEachIndexed { index, section ->
            item(key = section.title) {
                DisclaimerSectionHeader(index + 1, section.title)
                Spacer(Modifier.height(14.dp))
                DisclaimerParagraphs(section)
                Spacer(Modifier.height(30.dp))
            }
        }
        item { DisclaimerFooter() }
    }
}

@Composable
internal fun DisclaimerIntroCard(modifier: Modifier = Modifier) {
    val miuix = LocalMiuix.current
    val container = if (miuix) MiuixTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primaryContainer
    val content = if (miuix) MiuixTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (miuix) 16.dp else 24.dp))
            .background(container)
            .padding(18.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Rounded.Gavel,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            DisclaimerData.INTRO,
            style = if (miuix) MiuixTheme.textStyles.footnote1 else MaterialTheme.typography.bodyMedium,
            color = content,
        )
    }
}

@Composable
internal fun DisclaimerSectionHeader(number: Int, title: String) {
    val miuix = LocalMiuix.current
    val container = if (miuix) MiuixTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primaryContainer
    val onContainer = if (miuix) MiuixTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
    val titleColor = if (miuix) MiuixTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(container),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number.toString(),
                style = if (miuix) MiuixTheme.textStyles.body2 else MaterialTheme.typography.labelMedium,
                color = onContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = if (miuix) MiuixTheme.textStyles.title3 else MaterialTheme.typography.titleMedium,
            color = titleColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun DisclaimerParagraphs(section: DisclaimerSection) {
    val miuix = LocalMiuix.current
    val bodyColor = if (miuix) MiuixTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        section.paragraphs.forEachIndexed { index, text ->
            val emphasized = section.highlight && index == section.paragraphs.lastIndex
            Text(
                text,
                style = if (miuix) MiuixTheme.textStyles.body2 else MaterialTheme.typography.bodyMedium,
                color = bodyColor,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
internal fun DisclaimerFooter() {
    val miuix = LocalMiuix.current
    val hint = if (miuix) MiuixTheme.colorScheme.onSurfaceVariantSummary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "XPatch " + DisclaimerData.versionLine(),
            style = if (miuix) MiuixTheme.textStyles.footnote2 else MaterialTheme.typography.labelSmall,
            color = hint,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "开发者邮箱 " + DisclaimerData.CONTACT,
            style = if (miuix) MiuixTheme.textStyles.footnote2 else MaterialTheme.typography.labelSmall,
            color = hint,
        )
    }
}
