package io.xpatch.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.xpatch.data.ModuleGuardStore
import io.xpatch.data.RestartCenter
import io.xpatch.data.UserDirectory
import io.xpatch.data.XpUser
import io.xpatch.model.AppInfo
import io.xpatch.ui.components.DialogAction
import io.xpatch.ui.components.EmptyState
import io.xpatch.ui.components.EntryRow
import io.xpatch.ui.components.GroupHeader
import io.xpatch.ui.components.ModuleGuardHintBanner
import io.xpatch.ui.components.PullRefreshBox
import io.xpatch.ui.components.RestartHintBanner
import io.xpatch.ui.components.RestartListRows
import io.xpatch.ui.components.RowDivider
import io.xpatch.ui.components.SettingItem
import io.xpatch.ui.components.UserTabs
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
    onAdd: () -> Unit,
    onOpen: (AppInfo) -> Unit,
    onOpenGuardLogs: (String, String) -> Unit,
    users: List<XpUser> = emptyList(),
    viewUser: Int = 0,
    onViewUserChange: (Int) -> Unit = {},
    hidden: List<AppInfo> = emptyList(),
    onInstallHidden: (AppInfo) -> Unit = {},
    moduleCountOf: (String) -> Int = { _ -> 0 },
) {
    val miuix = LocalMiuix.current
    val context = LocalContext.current
    val pendingRestarts by RestartCenter.pending.collectAsState()
    val bannerHidden by RestartCenter.bannerHidden.collectAsState()
    var showRestartList by remember { mutableStateOf(false) }
    val guardEvents by ModuleGuardStore.events.collectAsState()
    LaunchedEffect(Unit) { ModuleGuardStore.init(context) }

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
                                    RestartCenter.restartNow(context, pendingRestarts.first().packageName, pendingRestarts.first().userId)
                                } else {
                                    RestartCenter.restartAll(context)
                                }
                            },
                            onOpenList = { showRestartList = true },
                            onHide = { RestartCenter.hideBanner() },
                        )
                    }
                }
                if (guardEvents.isNotEmpty()) {
                    item {
                        ModuleGuardHintBanner(
                            events = guardEvents,
                            onView = { event -> onOpenGuardLogs(event.appPackage, event.modulePackage) },
                            onHide = { ModuleGuardStore.clear(context) },
                        )
                    }
                }
                if (apps.isEmpty() && hidden.isEmpty()) {
                    item {
                        EmptyState("还没有已打包的应用") {
                            HomeAddButton(onAdd)
                        }
                    }
                } else {
                    if (users.size > 1) {
                        item {
                            UserTabs(
                                users = users,
                                selected = viewUser,
                                onSelect = onViewUserChange,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            )
                        }
                    }
                    item {
                        GroupHeader("已打包 ${apps.size} 个应用 · ${UserDirectory.labelOf(viewUser)}")
                    }
                    if (apps.isNotEmpty() && miuix) {
                        item {
                            MiuixCard(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 12.dp),
                            ) {
                                apps.forEachIndexed { index, app ->
                                    AppEntry(app, moduleCountOf(app.packageName), onOpen)
                                    if (index != apps.lastIndex) MiuixDivider()
                                }
                            }
                        }
                    } else if (apps.isNotEmpty()) {
                        items(apps, key = { it.packageName }) { app ->
                            AppEntry(app, moduleCountOf(app.packageName), onOpen)
                            RowDivider()
                        }
                    }
                    if (hidden.isNotEmpty()) {
                        item { GroupHeader("未在此用户安装 ${hidden.size} 个") }
                        if (miuix) {
                            item {
                                MiuixCard(
                                    modifier = Modifier
                                        .padding(horizontal = 12.dp)
                                        .padding(bottom = 12.dp),
                                ) {
                                    hidden.forEachIndexed { index, app ->
                                        HiddenAppEntry(app, onInstallHidden)
                                        if (index != hidden.lastIndex) MiuixDivider()
                                    }
                                }
                            }
                        } else {
                            items(hidden, key = { "hidden:" + it.packageName }) { app ->
                                HiddenAppEntry(app, onInstallHidden)
                                RowDivider()
                            }
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
                    RestartCenter.restartNow(context, request.packageName, request.userId)
                }
            }
        }
    }
}

@Composable
private fun HomeAddButton(onClick: () -> Unit) {
    if (LocalMiuix.current) {
        top.yukonga.miuix.kmp.basic.Button(
            onClick = onClick,
            modifier = Modifier.height(44.dp),
        ) {
            Text("添加应用")
        }
        return
    }
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = Modifier.height(44.dp),
        shape = io.xpatch.ui.theme.xpShape(16.dp),
    ) {
        Text("添加应用")
    }
}

@Composable
private fun HiddenAppEntry(app: AppInfo, onInstall: (AppInfo) -> Unit) {
    EntryRow(
        icon = app.icon,
        title = app.label,
        subtitle = app.packageName,
        trailing = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                tonalElevation = 0.dp,
                onClick = { onInstall(app) },
            ) {
                Text(
                    "安装",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        },
    )
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
private fun AppEntry(app: AppInfo, moduleCount: Int, onOpen: (AppInfo) -> Unit) {
    EntryRow(
        icon = app.icon,
        title = "${app.label}(${app.hookCore ?: "未知"},${app.runtimeVersion})",
        subtitle = if (app.versionCode > 0) "${app.versionName}(${app.versionCode})" else app.versionName,
        caption = app.packageName,
        onClick = { onOpen(app) },
        trailing = {
            Text(
                "${moduleCount} 个模块",
                style = MaterialTheme.typography.labelMedium,
                color = if (LocalMiuix.current) MiuixTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primary,
            )
        },
    )
}
