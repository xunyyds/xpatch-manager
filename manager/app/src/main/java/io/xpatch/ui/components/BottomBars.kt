package io.xpatch.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import io.xpatch.data.LiquidGlassLevel
import io.xpatch.ui.screens.Tab
import io.xpatch.ui.theme.AnimationQuality
import io.xpatch.ui.theme.LocalAnimationQuality
import io.xpatch.ui.theme.LocalMiuix
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.NavigationBar as MiuixNavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem as MiuixNavigationBarItem
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun StandardBottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    if (LocalMiuix.current) {
        MiuixNavigationBar {
            Tab.entries.forEach { entry ->
                MiuixNavigationBarItem(
                    selected = current == entry,
                    onClick = { onSelect(entry) },
                    icon = entry.icon,
                    label = entry.label,
                )
            }
        }
        return
    }
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        Tab.entries.forEach { entry ->
            NavigationBarItem(
                selected = current == entry,
                onClick = { onSelect(entry) },
                icon = { Icon(entry.icon, contentDescription = entry.label) },
                label = { Text(entry.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}

@Composable
internal fun LiquidBar(
    current: Tab,
    backdrop: Backdrop,
    glassLevel: LiquidGlassLevel = LiquidGlassLevel.ENHANCED,
    onSelect: (Tab) -> Unit
) {
    val miuix = LocalMiuix.current
    val lowPower = LocalAnimationQuality.current == AnimationQuality.REDUCED
    val primary = if (miuix) MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary
    val idle = if (miuix) MiuixTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        LiquidBottomTabs(
            selectedTabIndex = { current.ordinal },
            onTabSelected = { index -> Tab.entries.getOrNull(index)?.let(onSelect) },
            backdrop = backdrop,
            tabsCount = Tab.entries.size,
            accentColor = primary,
            glassLevel = glassLevel,
            lowPower = lowPower,
            containerColor = if (LocalMiuix.current) {
                MiuixTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surface
            },
        ) {
            Tab.entries.forEach { entry ->
                val selected = current == entry
                LiquidBottomTab(onClick = { onSelect(entry) }) {
                    if (miuix) {
                        MiuixIcon(
                            entry.icon,
                            contentDescription = entry.label,
                            modifier = Modifier.size(22.dp),
                            tint = if (selected) primary else idle,
                        )
                        top.yukonga.miuix.kmp.basic.Text(
                            entry.label,
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) primary else idle,
                        )
                    } else {
                        Icon(
                            entry.icon,
                            contentDescription = entry.label,
                            modifier = Modifier.size(22.dp),
                            tint = if (selected) primary else idle,
                        )
                        Text(
                            entry.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) primary else idle,
                        )
                    }
                }
            }
        }
    }
}
