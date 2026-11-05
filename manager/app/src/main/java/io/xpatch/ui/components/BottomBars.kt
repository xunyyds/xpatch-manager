package io.xpatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import io.xpatch.ui.screens.Tab
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
    NavigationBar(containerColor = Color.Transparent) {
        Tab.entries.forEach { entry ->
            NavigationBarItem(
                selected = current == entry,
                onClick = { onSelect(entry) },
                icon = { Icon(entry.icon, contentDescription = entry.label) },
                label = { Text(entry.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}

@Composable
internal fun MiuixBottomBar(current: Tab, onSelect: (Tab) -> Unit) {
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
}

@Composable
internal fun LiquidBar(current: Tab, backdrop: Backdrop, onSelect: (Tab) -> Unit) {
    val miuix = LocalMiuix.current
    val primary = if (miuix) MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary
    val idle = if (miuix) MiuixTheme.colorScheme.onSurfaceVariantSummary
    else MaterialTheme.colorScheme.onSurfaceVariant
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
        ) {
            Tab.entries.forEach { entry ->
                val selected = current == entry
                LiquidBottomTab(onClick = { onSelect(entry) }) {
                    MiuixIcon(
                        entry.icon,
                        contentDescription = entry.label,
                        modifier = Modifier.size(if (selected) 23.dp else 20.dp),
                        tint = if (selected) primary else idle,
                    )
                    top.yukonga.miuix.kmp.basic.Text(
                        entry.label,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) primary else idle,
                    )
                }
            }
        }
    }
}
