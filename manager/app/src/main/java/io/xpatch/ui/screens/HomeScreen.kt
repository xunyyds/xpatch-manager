package io.xpatch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.xpatch.data.RestartCenter
import io.xpatch.model.AppInfo
import io.xpatch.ui.components.DialogAction
import io.xpatch.ui.components.EmptyState
import io.xpatch.ui.components.EntryRow
import io.xpatch.ui.components.GroupHeader
import io.xpatch.ui.components.PullRefreshBox
import io.xpatch.ui.components.RestartHintBanner
import io.xpatch.ui.components.RestartListRows
import io.xpatch.ui.components.RowDivider
import io.xpatch.ui.components.SettingItem
import io.xpatch.ui.components.XPatchDialog
import io.xpatch.ui.theme.LocalMiuix
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixDivider
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun HomeScreen(
    apps: List<AppInfo>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpen: (AppInfo) -> Unit,
) {
    val miuix = LocalMiuix.current
    val context = LocalContext.current
    val pendingRestarts by RestartCenter.pending.collectAsState()
    val bannerHidden by RestartCenter.bannerHidden.collectAsState()
    var showRestartList by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        PullRefreshBox(
            refreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = io.xpatch.ui.util.rememberPageScrollState(),
                contentPadding = PaddingValues(bottom = io.xpatch.ui.util.rememberListBottomPadding(true)),
            ) {
                if (pendingRestarts.isNotEmpty() && !bannerHidden) {
                    item {
                        RestartHintBanner(
                            requests = pendingRestarts,
                            onRestart = {
                                if (pendingRestarts.size == 1) {
                                    RestartCenter.restartNow(context, pendingRestarts.first().packageName)
                                } else {
                                    RestartCenter.restartAll(context)
                                }
                            },
                            onOpenList = { showRestartList = true },
                            onHide = { RestartCenter.hideBanner() },
                        )
                    }
                }
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

        if (showRestartList) {
            XPatchDialog(
                onDismiss = { showRestartList = false },
                title = "待重启的应用",
                actions = listOf(
                    DialogAction("全部重启") {
                        RestartCenter.restartAll(context)
                        showRestartList = false
                    },
                    DialogAction("关闭") { showRestartList = false },
                ),
            ) {
                RestartListRows(pendingRestarts) { request ->
                    RestartCenter.restartNow(context, request.packageName)
                }
            }
        }
    }
}

@Composable
internal fun ShizukuSettingsRows(
    ready: Boolean,
    available: Boolean,
    onRequest: () -> Unit,
    onRecheck: () -> Unit,
) {
    SettingItem(
        title = "Shizuku 服务",
        subtitle = "检测服务状态；授权与其它权限统一在「权限管理」中处理",
        value = when {
            ready -> "可用"
            available -> "未授权"
            else -> "不可用"
        },
        arrow = true,
        onClick = if (ready || available) onRecheck else onRequest,
    )
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

