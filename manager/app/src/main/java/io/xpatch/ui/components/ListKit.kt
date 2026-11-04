package io.xpatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import io.xpatch.ui.theme.LocalMiuix
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class SortOption(val id: String, val label: String)

@Composable
fun SortMenu(
    options: List<SortOption>,
    selectedId: String,
    reversed: Boolean,
    onSelect: (String) -> Unit,
    onReverse: () -> Unit,
    modifier: Modifier = Modifier,
    extra: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
    trigger: @Composable (open: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        trigger { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                val active = option.id == selectedId
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                option.label,
                                Modifier.weight(1f),
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            if (active) {
                                Text(
                                    if (reversed) "↓" else "↑",
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        if (active) onReverse() else onSelect(option.id)
                    },
                )
            }
            if (extra != null) {
                HorizontalDivider()
                extra { expanded = false }
            }
        }
    }
}

@Composable
fun StickySearchBar(
    background: Color,
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(background),
    ) {
        if (title != null) title()
        SearchField(query, onQueryChange, placeholder)
    }
}

@Composable
fun TrailingText(text: String) {
    if (LocalMiuix.current) {
        MiuixText(
            text,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    } else {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
