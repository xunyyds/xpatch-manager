package io.xpatch.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.xpatch.model.AppInfo
import io.xpatch.ui.components.EmptyState
import io.xpatch.ui.components.EntryRow
import io.xpatch.ui.components.GroupHeader
import io.xpatch.ui.components.PullRefreshBox
import io.xpatch.ui.components.RowDivider
import io.xpatch.ui.theme.LocalMiuix
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixDivider
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun HomeScreen(
    apps: List<AppInfo>,
    shizukuReady: Boolean,
    shizukuAvailable: Boolean,
    onRequestShizuku: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpen: (AppInfo) -> Unit,
) {
    val miuix = LocalMiuix.current
    Column(Modifier.fillMaxSize()) {
        ShizukuBanner(shizukuReady, shizukuAvailable, onRequestShizuku)
        PullRefreshBox(
            refreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                if (apps.isEmpty()) {
                    item {
                        EmptyState("还没有已打包的应用\n点右下角「添加应用」开始\n下拉可重新加载")
                    }
                } else {
                    item { GroupHeader("已打包 ${apps.size} 个应用") }
                    if (miuix) {
                        item {
                            MiuixCard(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 12.dp),
                            ) {
                                apps.forEachIndexed { index, app ->
                                    AppEntry(app, onOpen)
                                    if (index != apps.lastIndex) MiuixDivider()
                                }
                            }
                        }
                    } else {
                        items(apps, key = { it.packageName }) { app ->
                            AppEntry(app, onOpen)
                            RowDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppEntry(app: AppInfo, onOpen: (AppInfo) -> Unit) {
    EntryRow(
        icon = app.icon,
        title = "${app.label}(${app.hookCore ?: "未知"},${app.runtimeVersion})",
        subtitle = if (app.versionCode > 0) "${app.versionName}(${app.versionCode})" else app.versionName,
        caption = app.packageName,
        onClick = { onOpen(app) },
        trailing = {
            Text(
                "${app.enabledModules} 个模块",
                style = MaterialTheme.typography.labelMedium,
                color = if (LocalMiuix.current) MiuixTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primary,
            )
        },
    )
}

@Composable
internal fun ShizukuBanner(ready: Boolean, available: Boolean, onRequest: () -> Unit) {
    val text = when {
        ready -> "Shizuku 已授权"
        available -> "需要 Shizuku 授权 · 点此授权"
        else -> "未检测到 Shizuku · 使用系统安装器"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .let { if (!ready && available) it.clickable { onRequest() } else it }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
