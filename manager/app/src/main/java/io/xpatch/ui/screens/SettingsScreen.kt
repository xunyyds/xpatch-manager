package io.xpatch.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.xpatch.BuildConfig
import io.xpatch.data.ManagerSettings
import io.xpatch.data.ModuleApiStore
import io.xpatch.data.PermissionCenter
import io.xpatch.data.UiStyle
import io.xpatch.model.ModuleInfo
import io.xpatch.patch.ShizukuBridge
import io.xpatch.ui.components.DialogAction
import io.xpatch.ui.components.SettingChoiceRow
import io.xpatch.ui.components.SettingGroup
import io.xpatch.ui.components.SettingItem
import io.xpatch.ui.components.SettingSwitchItem
import io.xpatch.ui.components.XPatchDialog
import io.xpatch.ui.theme.LocalMiuix
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsScreen(
    settings: ManagerSettings,
    defaultSignatureName: String,
    exportLabel: String,
    shizukuReady: Boolean,
    shizukuAvailable: Boolean,
    onRequestShizuku: () -> Unit,
    onRecheckShizuku: () -> Unit,
    onOpenSignatures: () -> Unit,
    onThemeChange: (Int) -> Unit,
    onUiStyleChange: (UiStyle) -> Unit,
    onShowSystemChange: (Boolean) -> Unit,
    onBottomBarChange: (Int) -> Unit,
    onPickExportDir: () -> Unit,
    onClearExportDir: () -> Unit,
    onExportConfig: () -> Unit,
    onImportConfig: () -> Unit,
    onHistoryLimitChange: (Int) -> Unit,
    onDeleteArtifactChange: (Boolean) -> Unit,
    onSkipDeletePromptChange: (Boolean) -> Unit,
    onOpenDisclaimer: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val miuix = LocalMiuix.current
    var showLimitDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
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
    LazyColumn(
        state = io.xpatch.ui.util.rememberPageScrollState(),
        contentPadding = PaddingValues(top = 12.dp, bottom = io.xpatch.ui.util.rememberListBottomPadding(true)),
        verticalArrangement = Arrangement.spacedBy(if (miuix) 0.dp else 14.dp),
    ) {
        if (!miuix) {
            item {
                SettingGroup("外观") {
                    SettingChoiceRow(
                        "界面风格",
                        UiStyle.entries.map { it.label },
                        UiStyle.entries.indexOf(settings.uiStyle),
                    ) { index -> onUiStyleChange(UiStyle.entries[index]) }
                    SettingChoiceRow(
                        "主题",
                        listOf("跟随系统", "浅色", "深色"),
                        settings.themeMode,
                        onThemeChange,
                    )
                    SettingChoiceRow(
                        "底栏样式",
                        listOf("标准", "液态"),
                        settings.bottomBarStyle,
                        onBottomBarChange,
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
                    SettingItem("权限管理", "查看与统一管理所需权限", arrow = true, onClick = {
                        showPermissionDialog = true
                    })
                }
            }
            item {
                SettingGroup("签名") {
                    SettingItem(
                        "签名管理",
                        "打包产物使用的证书，可添加自定义签名",
                        defaultSignatureName,
                        arrow = true,
                        onClick = onOpenSignatures,
                    )
                }
            }
            item {
                SettingGroup("作用域") {
                    SettingSwitchItem(
                        "显示系统应用",
                        "作用域列表中包含系统应用",
                        settings.showSystemApps,
                        onShowSystemChange,
                    )
                }
            }
            item {
                SettingGroup("打包历史") {
                    SettingItem(
                        "记录上限",
                        "超出后自动清理最早的打包记录",
                        settings.patchHistoryLimit.toString() + " 条",
                        arrow = true,
                        onClick = { showLimitDialog = true },
                    )
                    SettingSwitchItem(
                        "删除记录时同时删除产物",
                        "删除历史记录时一并删除 APK 产物文件",
                        settings.deleteArtifactOnRemove,
                        onDeleteArtifactChange,
                    )
                    SettingSwitchItem(
                        "删除时不再询问",
                        "删除记录时按当前策略直接执行，不再弹窗确认",
                        settings.skipArtifactDeletePrompt,
                        onSkipDeletePromptChange,
                    )
                }
            }
            item {
                SettingGroup("备份与恢复") {
                    SettingItem("导出配置", "作用域、签名与设置保存为 JSON", "JSON", arrow = true) {
                        onExportConfig()
                    }
                    SettingItem("导入配置", "从 JSON 恢复全部配置", "JSON", arrow = true) {
                        onImportConfig()
                    }
                    SettingItem(
                        "导出目录",
                        if (exportLabel.isBlank()) {
                            "打包产物的默认保存位置"
                        } else {
                            "打包产物的默认保存位置 长按可清除"
                        },
                        if (exportLabel.isBlank()) "未设置" else exportLabel,
                        arrow = true,
                        onClick = onPickExportDir,
                        onLongClick = if (exportLabel.isNotBlank()) onClearExportDir else null,
                    )
                }
            }
            item { MoreSection(onOpenDisclaimer = onOpenDisclaimer, onOpenAbout = onOpenAbout) }
        } else {
            item { Spacer(Modifier.size(12.dp)) }
            item {
                SettingGroup("外观") {
                    MiuixSpinnerRow(
                        title = "界面风格",
                        options = UiStyle.entries.map { it.label },
                        selectedIndex = UiStyle.entries.indexOf(settings.uiStyle),
                        onSelect = { index -> onUiStyleChange(UiStyle.entries[index]) },
                    )
                    MiuixSpinnerRow(
                        title = "主题",
                        options = listOf("跟随系统", "浅色", "深色"),
                        selectedIndex = settings.themeMode,
                        onSelect = onThemeChange,
                    )
                    MiuixSpinnerRow(
                        title = "底栏样式",
                        options = listOf("标准", "液态"),
                        selectedIndex = settings.bottomBarStyle,
                        onSelect = onBottomBarChange,
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
                    SettingItem("权限管理", "查看与统一管理所需权限", arrow = true, onClick = {
                        showPermissionDialog = true
                    })
                }
            }
            item {
                SettingGroup("签名") {
                    SettingItem(
                        "签名管理",
                        "打包产物使用的证书，可添加自定义签名",
                        defaultSignatureName,
                        arrow = true,
                        onClick = onOpenSignatures,
                    )
                }
            }
            item {
                SettingGroup("作用域") {
                    SettingSwitchItem(
                        "显示系统应用",
                        "作用域列表中包含系统应用",
                        settings.showSystemApps,
                        onShowSystemChange,
                    )
                }
            }
            item {
                SettingGroup("打包历史") {
                    SettingItem(
                        "记录上限",
                        "超出后自动清理最早的打包记录",
                        settings.patchHistoryLimit.toString() + " 条",
                        arrow = true,
                        onClick = { showLimitDialog = true },
                    )
                    SettingSwitchItem(
                        "删除记录时同时删除产物",
                        "删除历史记录时一并删除 APK 产物文件",
                        settings.deleteArtifactOnRemove,
                        onDeleteArtifactChange,
                    )
                    SettingSwitchItem(
                        "删除时不再询问",
                        "删除记录时按当前策略直接执行，不再弹窗确认",
                        settings.skipArtifactDeletePrompt,
                        onSkipDeletePromptChange,
                    )
                }
            }
            item {
                SettingGroup("备份与恢复") {
                    SettingItem("导出配置", "作用域、签名与设置保存为 JSON", onClick = onExportConfig)
                    SettingItem("导入配置", "从 JSON 恢复全部配置", onClick = onImportConfig)
                    SettingItem(
                        "导出目录",
                        if (exportLabel.isBlank()) {
                            "打包产物的默认保存位置"
                        } else {
                            "打包产物的默认保存位置 长按可清除"
                        },
                        if (exportLabel.isBlank()) "未设置" else exportLabel,
                        arrow = true,
                        onClick = onPickExportDir,
                        onLongClick = if (exportLabel.isNotBlank()) onClearExportDir else null,
                    )
                }
            }
            item { MoreSection(onOpenDisclaimer = onOpenDisclaimer, onOpenAbout = onOpenAbout) }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun MiuixSpinnerRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    WindowSpinnerPreference(
        title = title,
        items = options.map { DropdownItem(title = it) },
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { index -> if (index != selectedIndex) onSelect(index) },
    )
}

@Composable
private fun MoreSection(
    onOpenDisclaimer: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val versionSummary = "XPatch v" + BuildConfig.VERSION_NAME + " · 免 Root LSP 管理器"
    if (LocalMiuix.current) {
        top.yukonga.miuix.kmp.basic.SmallTitle("其他")
        top.yukonga.miuix.kmp.basic.Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        ) {
            top.yukonga.miuix.kmp.basic.BasicComponent(
                title = "免责声明",
                summary = "使用前请阅读并理解全部条款",
                interactionSource = remember { MutableInteractionSource() },
                modifier = Modifier.clickable { onOpenDisclaimer() },
            )
            top.yukonga.miuix.kmp.basic.BasicComponent(
                title = "关于",
                summary = versionSummary,
                interactionSource = remember { MutableInteractionSource() },
                modifier = Modifier.clickable { onOpenAbout() },
            )
        }
        return
    }
    SettingGroup("其他") {
        SettingItem("免责声明", "使用前请阅读并理解全部条款", arrow = true, onClick = {
            onOpenDisclaimer()
        })
        SettingItem("关于", versionSummary, arrow = true, onClick = {
            onOpenAbout()
        })
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

@Composable
internal fun ApiSelector(module: ModuleInfo, current: Int, onChange: (Int) -> Unit) {
    var expanded by remember(module.packageName) { mutableStateOf(false) }
    val levels = ModuleApiStore.ALL_LEVELS
    val selected = ModuleApiStore.resolveApi(current, module.availableApiLevels, module.autoApiLevel)
    val miuix = LocalMiuix.current
    val primary = if (miuix) MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary
    val surface = if (miuix) MiuixTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer
    val onSurface = if (miuix) MiuixTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface
    Box {
        TextButton(
            onClick = { expanded = true },
            enabled = true,
            shape = RoundedCornerShape(10.dp),
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
            shape = RoundedCornerShape(16.dp),
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
        Spacer(Modifier.height(10.dp))
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
            OutlinedTextField(
                value = text,
                onValueChange = { text = limitDigits(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("1 - 500") },
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Spacer(Modifier.height(6.dp))
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

private fun limitDigits(input: String): String = input.filter { it.isDigit() }.take(3)
