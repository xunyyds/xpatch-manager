package io.xpatch.ui.screens

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.xpatch.BuildConfig
import io.xpatch.data.BackupArchive
import io.xpatch.data.LiquidGlassLevel
import io.xpatch.data.ManagerSettings
import io.xpatch.data.ModuleApiStore
import io.xpatch.data.PermissionCenter
import io.xpatch.data.SettingsStore
import io.xpatch.data.SoftwareSource
import io.xpatch.data.ThemeColor
import io.xpatch.data.UiStyle
import io.xpatch.data.XpUser
import io.xpatch.model.ModuleInfo
import io.xpatch.patch.PatchLog
import io.xpatch.patch.ShizukuBridge
import io.xpatch.ui.components.DialogAction
import io.xpatch.ui.components.RowDivider
import io.xpatch.ui.components.SettingGroup
import io.xpatch.ui.components.SettingItem
import io.xpatch.ui.components.SettingSelectRow
import io.xpatch.ui.components.SettingSwitchItem
import io.xpatch.ui.components.UserTargetDialog
import io.xpatch.ui.components.XPatchDialog
import io.xpatch.ui.components.appTextFieldColors
import io.xpatch.ui.components.userTargetText
import io.xpatch.ui.format.readableSize
import io.xpatch.ui.theme.LocalMiuix
import io.xpatch.ui.theme.ShapeTokens
import io.xpatch.ui.theme.seedOf
import io.xpatch.ui.theme.xpShape
import io.xpatch.ui.util.rememberListBottomPadding
import io.xpatch.ui.util.rememberPageScrollState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsScreen(
    settings: ManagerSettings,
    defaultSignatureName: String,
    exportLabel: String,
    exportLost: Boolean,
    shizukuReady: Boolean,
    shizukuAvailable: Boolean,
    onRequestShizuku: () -> Unit,
    onRecheckShizuku: () -> Unit,
    onOpenSignatures: () -> Unit,
    onThemeChange: (Int) -> Unit,
    onUiStyleChange: (UiStyle) -> Unit,
    onThemeColorChange: (ThemeColor) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onOledDarkChange: (Boolean) -> Unit,
    onShowSystemChange: (Boolean) -> Unit,
    onBottomBarChange: (Int) -> Unit,
    onLiquidGlassLevelChange: (LiquidGlassLevel) -> Unit,
    onLogLevelChange: (Int) -> Unit,
    onSoftwareSourceChange: (SoftwareSource) -> Unit,
    onPickExportDir: () -> Unit,
    onClearExportDir: () -> Unit,
    onBackup: (Set<BackupArchive.Scope>) -> Unit,
    onRestoreBackup: () -> Unit,
    onHistoryLimitChange: (Int) -> Unit,
    onDeleteArtifactChange: (Boolean) -> Unit,
    onSkipDeletePromptChange: (Boolean) -> Unit,
    onOpenDisclaimer: () -> Unit,
    onOpenAbout: () -> Unit,
    appearancePage: Boolean = false,
    onOpenAppearance: () -> Unit = {},
    users: List<XpUser> = emptyList(),
    onInstallUsersChange: (Set<Int>) -> Unit = {},
    onOpenUsers: () -> Unit = {},
) {
    val miuix = LocalMiuix.current
    var showLimitDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showThemeColorDialog by remember { mutableStateOf(false) }
    var showInstallUserDialog by remember { mutableStateOf(false) }
    val multiUser = users.size > 1
    val defaultInstallUsers = settings.installUsers.ifEmpty { setOf(io.xpatch.data.UserDirectory.currentUserId()) }
    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dynamicActive = dynamicSupported && settings.dynamicColor

    if (showThemeColorDialog) {
        ThemeColorDialog(
            current = settings.themeColor,
            onDismiss = { showThemeColorDialog = false },
            onSelect = { color ->
                showThemeColorDialog = false
                onThemeColorChange(color)
            },
        )
    }
    if (showPermissionDialog) {
        PermissionCenterDialog(onDismiss = { showPermissionDialog = false })
    }
    if (showLimitDialog) {
        HistoryLimitDialog(
            current = settings.patchHistoryLimit,
            onDismiss = { showLimitDialog = false },
            onConfirm = { limit ->
                showLimitDialog = false
                onHistoryLimitChange(limit)
            },
        )
    }
    if (showBackupDialog) {
        BackupScopeDialog(
            onDismiss = { showBackupDialog = false },
            onConfirm = { scopes ->
                showBackupDialog = false
                onBackup(scopes)
            },
        )
    }
    if (showInstallUserDialog) {
        UserTargetDialog(
            users = users,
            selected = defaultInstallUsers,
            onConfirm = { picked ->
                showInstallUserDialog = false
                onInstallUsersChange(picked)
            },
            onDismiss = { showInstallUserDialog = false },
        )
    }

    LazyColumn(
        state = rememberPageScrollState(),
        contentPadding = PaddingValues(
            top = 12.dp,
            bottom = rememberListBottomPadding(true),
        ),
        verticalArrangement = Arrangement.spacedBy(if (miuix) 0.dp else 16.dp),
    ) {
        if (miuix) item { Spacer(Modifier.size(12.dp)) }
        if (appearancePage) {
            item {
                SettingGroup("外观") {
                    SettingSelectRow(
                        title = "界面风格",
                        options = UiStyle.entries.map { it.label },
                        selectedIndex = UiStyle.entries.indexOf(settings.uiStyle),
                        onSelect = { index -> onUiStyleChange(UiStyle.entries[index]) },
                    )
                    SettingsRowDivider()
                    SettingSelectRow(
                        title = "主题",
                        options = listOf("跟随系统", "浅色", "深色"),
                        selectedIndex = settings.themeMode,
                        onSelect = onThemeChange,
                    )
                    if (miuix) {
                        SettingsRowDivider()
                        SettingItem(
                            title = "配色与主题色",
                            subtitle = "仅 Material 可用",
                            enabled = false,
                        )
                    } else {
                        SettingsRowDivider()
                        SettingItem(
                            title = "配色方案",
                            subtitle = if (dynamicActive) {
                                "动态配色生效中"
                            } else {
                                "选择主题强调色"
                            },
                            value = settings.themeColor.label,
                            arrow = !dynamicActive,
                            enabled = !dynamicActive,
                            onClick = if (dynamicActive) null else ({ showThemeColorDialog = true }),
                        )
                        SettingsRowDivider()
                        SettingSwitchItem(
                            title = "动态配色",
                            subtitle = if (dynamicSupported) {
                                "跟随系统壁纸生成配色"
                            } else {
                                "当前系统不支持（需 Android 12 及以上）"
                            },
                            checked = dynamicActive,
                            enabled = dynamicSupported,
                            onCheckedChange = onDynamicColorChange,
                        )
                        SettingsRowDivider()
                        SettingSwitchItem(
                            title = "OLED 优化",
                            subtitle = "深色模式下使用纯黑背景",
                            checked = settings.oledDark,
                            onCheckedChange = onOledDarkChange,
                        )
                    }
                    SettingsRowDivider()
                    SettingSelectRow(
                        title = "底栏样式",
                        options = listOf("标准", "液态"),
                        selectedIndex = settings.bottomBarStyle,
                        onSelect = onBottomBarChange,
                    )
                    if (settings.bottomBarStyle == SettingsStore.BOTTOM_BAR_LIQUID) {
                        SettingsRowDivider()
                        SettingSelectRow(
                            title = "液态底栏通透度",
                            options = LiquidGlassLevel.entries.map { it.label },
                            selectedIndex = settings.liquidGlassLevel.index,
                            onSelect = { index ->
                                onLiquidGlassLevelChange(LiquidGlassLevel.entries[index])
                            },
                        )
                    }
                }
            }
        } else {
            item {
                SettingGroup("外观") {
                    SettingItem(
                        title = "主题、配色与底栏",
                        subtitle = appearanceSummary(settings, miuix),
                        arrow = true,
                        onClick = onOpenAppearance,
                    )
                }
            }
            item {
                SettingGroup("权限") {
                    ShizukuSettingsRows(
                        ready = shizukuReady,
                        available = shizukuAvailable,
                        onRequest = onRequestShizuku,
                        onRecheck = onRecheckShizuku,
                    )
                    SettingItem(
                        title = "权限管理",
                        arrow = true,
                        onClick = { showPermissionDialog = true },
                    )
                }
            }
            item {
                SettingGroup("签名") {
                    SettingItem(
                        title = "签名管理",
                        value = defaultSignatureName,
                        arrow = true,
                        onClick = onOpenSignatures,
                    )
                }
            }
            item {
                SettingGroup("打包") {
                    SettingSelectRow(
                        title = "打包日志",
                        options = PatchLog.LEVEL_OPTIONS.map { it.second },
                        selectedIndex = settings.patchLogLevel,
                        onSelect = onLogLevelChange,
                    )
                    if (multiUser) {
                        SettingsRowDivider()
                        SettingItem(
                            title = "默认安装到",
                            value = userTargetText(defaultInstallUsers),
                            arrow = true,
                            onClick = { showInstallUserDialog = true },
                        )
                        SettingsRowDivider()
                        SettingItem(
                            title = "用户",
                            subtitle = users.joinToString("、") { it.label },
                            arrow = true,
                            onClick = onOpenUsers,
                        )
                    }
                }
            }
            item {
                SettingGroup("在线版本") {
                    SettingSelectRow(
                        title = "软件源",
                        options = SoftwareSource.entries.map { it.label },
                        selectedIndex = SoftwareSource.entries.indexOf(settings.softwareSource),
                        onSelect = { index -> onSoftwareSourceChange(SoftwareSource.entries[index]) },
                    )
                }
            }
            item {
                SettingGroup("作用域") {
                    SettingSwitchItem(
                        title = "显示系统应用",
                        checked = settings.showSystemApps,
                        onCheckedChange = onShowSystemChange,
                    )
                }
            }
            item {
                SettingGroup("打包历史") {
                    SettingItem(
                        title = "记录上限",
                        subtitle = "超出后自动清理最早的打包记录",
                        value = settings.patchHistoryLimit.toString() + " 条",
                        arrow = true,
                        onClick = { showLimitDialog = true },
                    )
                    SettingSwitchItem(
                        title = "删除记录时同时删除产物",
                        checked = settings.deleteArtifactOnRemove,
                        onCheckedChange = onDeleteArtifactChange,
                    )
                    SettingSwitchItem(
                        title = "删除时不再询问",
                        checked = settings.skipArtifactDeletePrompt,
                        onCheckedChange = onSkipDeletePromptChange,
                    )
                }
            }
            item {
                SettingGroup("备份与恢复") {
                    SettingItem(
                        title = "备份数据",
                        arrow = true,
                        onClick = { showBackupDialog = true },
                    )
                    SettingItem(
                        title = "从备份恢复",
                        arrow = true,
                        onClick = onRestoreBackup,
                    )
                    SettingItem(
                        title = "导出目录",
                        subtitle = if (exportLost) {
                            "权限已失效，请重新选择"
                        } else if (exportLabel.isBlank()) {
                            "打包完成时导出安装包的位置"
                        } else {
                            "打包完成时导出安装包的位置，长按可清除"
                        },
                        value = if (exportLabel.isBlank()) "未设置" else exportLabel,
                        arrow = true,
                        onClick = onPickExportDir,
                        onLongClick = if (exportLabel.isNotBlank()) onClearExportDir else null,
                    )
                }
            }
            item {
                SettingGroup("其他") {
                    SettingItem(
                        title = "免责声明",
                        arrow = true,
                        onClick = onOpenDisclaimer,
                    )
                    SettingItem(
                        title = "关于",
                        subtitle = "v" + BuildConfig.VERSION_NAME,
                        arrow = true,
                        onClick = onOpenAbout,
                    )
                }
            }
        }
        if (miuix) item { Spacer(Modifier.size(24.dp)) }
    }
}

private fun appearanceSummary(settings: ManagerSettings, miuix: Boolean): String {
    val themeLabel = when (settings.themeMode) {
        SettingsStore.THEME_LIGHT -> "浅色"
        SettingsStore.THEME_DARK -> "深色"
        else -> "跟随系统"
    }
    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dynamicActive = dynamicSupported && settings.dynamicColor
    return buildString {
        append(settings.uiStyle.label)
        append(" · ")
        append(themeLabel)
        if (!miuix) {
            append(" · ")
            append(if (dynamicActive) "动态配色" else "配色 " + settings.themeColor.label)
        }
    }
}

@Composable
private fun SettingsRowDivider() {
    if (!LocalMiuix.current) RowDivider(startPadding = 16.dp)
}

@Composable
internal fun ApiSelector(module: ModuleInfo, current: Int, onChange: (Int) -> Unit) {
    var expanded by remember(module.packageName, module.availableApiLevels) { mutableStateOf(false) }
    val levels = ModuleApiStore.supportedLevels(module.availableApiLevels)
    val selected = ModuleApiStore.resolveApi(current, module.availableApiLevels, module.autoApiLevel)
    val miuix = LocalMiuix.current
    val primary = if (miuix) MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary
    val surface = if (miuix) MiuixTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer
    val onSurface = if (miuix) MiuixTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface
    Box {
        TextButton(
            onClick = { expanded = true },
            enabled = levels.isNotEmpty(),
            shape = xpShape(10.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                if (levels.isEmpty()) "API 未识别" else "API $selected",
                style = MaterialTheme.typography.labelMedium,
                color = if (levels.isEmpty()) onSurface.copy(alpha = 0.38f) else primary,
            )
            if (levels.isNotEmpty()) {
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = "选择 API", tint = primary, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = surface,
            shape = xpShape(16.dp),
        ) {
            levels.forEach { level ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (level == levels.first()) "API $level · 最高" else "API $level",
                            color = if (level == selected) primary else onSurface,
                            fontWeight = if (level == selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    trailingIcon = {
                        if (level == selected) {
                            Icon(Icons.Rounded.Check, contentDescription = "已选择", tint = primary, modifier = Modifier.size(20.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        onChange(level)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeColorDialog(
    current: ThemeColor,
    onDismiss: () -> Unit,
    onSelect: (ThemeColor) -> Unit,
) {
    XPatchDialog(
        onDismiss = onDismiss,
        title = "配色方案",
        actions = listOf(DialogAction("取消", onClick = onDismiss)),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ThemeColor.entries.forEach { color ->
                val selected = color == current
                Column(
                    modifier = Modifier
                        .clip(xpShape(12.dp))
                        .clickable { onSelect(color) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(xpShape(22.dp))
                            .background(seedOf(color))
                            .then(
                                if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, xpShape(22.dp))
                                else Modifier,
                            ),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        color.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryLimitDialog(
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val miuix = LocalMiuix.current
    var text by remember { mutableStateOf(current.toString()) }
    val parsed = text.trim().toIntOrNull()
    val valid = parsed != null && parsed in 1..500
    XPatchDialog(
        onDismiss = onDismiss,
        title = "记录上限",
        actions = listOf(
            DialogAction("取消", onClick = onDismiss),
            DialogAction("确定") { onConfirm(if (valid) parsed!! else 50) },
        ),
    ) {
        Text("超出上限时会自动清理最早的打包记录", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        if (miuix) {
            MiuixTextField(
                value = text,
                onValueChange = { text = limitDigits(it) },
                modifier = Modifier.fillMaxWidth(),
                label = "1 - 500",
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        } else {
            TextField(
                value = text,
                onValueChange = { text = limitDigits(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("1 - 500") },
                shape = ShapeTokens.input,
                colors = appTextFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (valid) {
                "将保留最近 " + parsed + " 条记录"
            } else {
                "请输入 1 - 500 之间的整数，留空或非法值将回退为 50"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BackupScopeDialog(
    onDismiss: () -> Unit,
    onConfirm: (Set<BackupArchive.Scope>) -> Unit,
) {
    val context = LocalContext.current
    val archive = remember { BackupArchive(context) }
    var selected by remember { mutableStateOf(BackupArchive.Scope.default) }
    var usage by remember { mutableStateOf(emptyMap<BackupArchive.Scope, BackupArchive.Usage>()) }
    var scanning by remember { mutableStateOf(true) }
    LaunchedEffect(selected) {
        scanning = true
        usage = withContext(Dispatchers.IO) { archive.usage(selected) }
        scanning = false
    }
    val total = selected.sumOf { usage[it]?.bytes ?: 0L }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    XPatchDialog(
        onDismiss = onDismiss,
        title = "备份范围",
        actions = listOf(
            DialogAction("取消", onClick = onDismiss),
            DialogAction("开始备份") { if (selected.isNotEmpty()) onConfirm(selected) },
        ),
    ) {
        Text(
            "备份会写入一个 ZIP 文件，恢复时覆盖对应的当前数据。",
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { selected = BackupArchive.Scope.entries.toSet() }) {
                Text("全选", style = MaterialTheme.typography.labelLarge)
            }
            TextButton(onClick = { selected = emptySet() }) {
                Text("全不选", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (scanning) "统计中…" else readableSize(total),
                style = MaterialTheme.typography.labelMedium,
                color = secondary,
            )
        }
        BackupArchive.Scope.entries.forEach { scope ->
            val checked = scope in selected
            val info = usage[scope]
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(xpShape(10.dp))
                    .clickable {
                        selected = toggleBackupScope(selected, scope)
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(scope.title, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        buildString {
                            append(scope.detail)
                            if (info != null && info.files > 0) {
                                append(" · ")
                                append(readableSize(info.bytes))
                                append(" · ")
                                append(info.files)
                                append(" 个文件")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = secondary,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (selected.isEmpty()) {
                "至少选择一项才能备份"
            } else {
                "已选 " + selected.size + " 项 · 预计 " + readableSize(total)
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (selected.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PermissionCenterDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val items = remember {
        PermissionCenter.required(
            context,
            shizukuAvailable = ShizukuBridge.isAvailable(),
            shizukuGranted = ShizukuBridge.hasPermission(),
        )
    }
    XPatchDialog(
        onDismiss = onDismiss,
        title = "权限管理",
        actions = listOf(DialogAction("知道了", onClick = onDismiss)),
    ) {
        items.forEach { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        item.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.granted) {
                    Text(
                        "已授权",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    TextButton(onClick = { openPermission(context, item) }) {
                        Text(
                            when (item.kind) {
                                PermissionCenter.Kind.RUNTIME -> "申请"
                                PermissionCenter.Kind.SHIZUKU -> "去授权"
                                else -> "去设置"
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "拒绝后如需再次授权，可前往系统设置统一开启，应用内不再逐项弹窗。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { PermissionCenter.openAppDetails(context) }) {
            Text("前往系统设置")
        }
    }
}

private fun openPermission(context: Context, item: PermissionCenter.Item) {
    when (item.kind) {
        PermissionCenter.Kind.RUNTIME -> PermissionCenter.openAppDetails(context)

        PermissionCenter.Kind.SHIZUKU -> if (ShizukuBridge.isAvailable()) {
            ShizukuBridge.requestPermission { }
        } else {
            Toast.makeText(context, "未检测到 Shizuku", Toast.LENGTH_SHORT).show()
        }

        else -> when (item.id) {
            PermissionCenter.Id.ALL_FILES -> PermissionCenter.openAllFiles(context)
            PermissionCenter.Id.INSTALL -> PermissionCenter.openInstall(context)
            else -> PermissionCenter.openAppDetails(context)
        }
    }
}

private fun toggleBackupScope(
    current: Set<BackupArchive.Scope>,
    scope: BackupArchive.Scope,
): Set<BackupArchive.Scope> {
    if (scope in current) {
        val next = current - scope
        return if (scope == BackupArchive.Scope.HISTORY) next - BackupArchive.Scope.ARTIFACTS else next
    }
    return if (scope == BackupArchive.Scope.ARTIFACTS) {
        current + scope + BackupArchive.Scope.HISTORY
    } else {
        current + scope
    }
}

private fun limitDigits(input: String): String = input.filter { it.isDigit() }.take(3)