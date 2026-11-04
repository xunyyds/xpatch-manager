package io.xpatch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.xpatch.BuildConfig
import io.xpatch.data.ManagerSettings
import io.xpatch.data.ModuleApiStore
import io.xpatch.data.UiStyle
import io.xpatch.model.ModuleInfo
import io.xpatch.ui.components.SettingAside
import io.xpatch.ui.components.SettingChoiceRow
import io.xpatch.ui.components.SettingGroup
import io.xpatch.ui.components.SettingItem
import io.xpatch.ui.components.SettingSwitchItem
import io.xpatch.ui.theme.LocalMiuix
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsScreen(
    settings: ManagerSettings,
    defaultSignatureName: String,
    exportLabel: String,
    onOpenSignatures: () -> Unit,
    onThemeChange: (Int) -> Unit,
    onUiStyleChange: (UiStyle) -> Unit,
    onShowSystemChange: (Boolean) -> Unit,
    onBottomBarChange: (Int) -> Unit,
    onPickExportDir: () -> Unit,
    onClearExportDir: () -> Unit,
    onExportConfig: () -> Unit,
    onImportConfig: () -> Unit,
) {
    val miuix = LocalMiuix.current
    LazyColumn(
        contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
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
                    SettingAside("打包所用的 Hook 核心、签名绕过与动态模式等，在「Patch 配置」页按应用单独选择。")
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
                        "打包产物的默认保存位置",
                        if (exportLabel.isBlank()) "未设置" else exportLabel,
                        arrow = true,
                        onClick = onPickExportDir,
                        onLongClick = if (exportLabel.isNotBlank()) onClearExportDir else null,
                    )
                    SettingAside(
                        if (exportLabel.isBlank()) {
                            "选一个文件夹作为导出位置，之后会记住它，可随时更改。"
                        } else {
                            "当前导出目录：" + exportLabel + "，长按可清除。"
                        },
                    )
                }
            }
            item { AboutSection() }
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
                SettingAside("打包所用的 Hook 核心、签名绕过与动态模式等，在「Patch 配置」页按应用单独选择。")
            }
            item {
                SettingGroup("备份与恢复") {
                    SettingItem("导出配置", "作用域、签名与设置保存为 JSON", onClick = onExportConfig)
                    SettingItem("导入配置", "从 JSON 恢复全部配置", onClick = onImportConfig)
                    SettingItem(
                        "导出目录",
                        "打包产物的默认保存位置",
                        if (exportLabel.isBlank()) "未设置" else exportLabel,
                        arrow = true,
                        onClick = onPickExportDir,
                        onLongClick = if (exportLabel.isNotBlank()) onClearExportDir else null,
                    )
                    SettingAside(
                        if (exportLabel.isBlank()) {
                            "选一个文件夹作为导出位置，之后会记住它，可随时更改。"
                        } else {
                            "当前导出目录：" + exportLabel + "，长按可清除。"
                        },
                    )
                }
            }
            item { AboutSection() }
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
private fun AboutSection() {
    val miuix = LocalMiuix.current
    val secondary = if (miuix) MiuixTheme.colorScheme.onSurfaceVariantSummary
    else MaterialTheme.colorScheme.onSurfaceVariant
    val primary = if (miuix) MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary
    if (miuix) {
        top.yukonga.miuix.kmp.basic.SmallTitle("关于")
        top.yukonga.miuix.kmp.basic.Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        ) {
            top.yukonga.miuix.kmp.basic.BasicComponent(
                title = "XPatch",
                summary = "免 Root LSP 管理器 · v" + BuildConfig.VERSION_NAME,
            )
        }
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 20.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("XPatch", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("免 Root LSP 管理器", style = MaterialTheme.typography.bodySmall, color = secondary)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AboutPill("v" + BuildConfig.VERSION_NAME, primary)
            AboutPill("Android 17", primary)
            AboutPill("免 Root", primary)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "免 Root 的 LSP 模块管理器，兼容 Android 17。",
            style = MaterialTheme.typography.labelSmall,
            color = secondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AboutPill(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
internal fun ApiSelector(module: ModuleInfo, current: Int, onChange: (Int) -> Unit) {
    var expanded by remember(module.packageName, module.availableApiLevels) { mutableStateOf(false) }
    val levels = ModuleApiStore.supportedLevels(module.availableApiLevels)
    val selected = ModuleApiStore.resolveApi(current, levels, module.autoApiLevel)
    val miuix = LocalMiuix.current
    val primary = if (miuix) MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary
    val surface = if (miuix) MiuixTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer
    val onSurface = if (miuix) MiuixTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface
    Box {
        TextButton(
            onClick = { expanded = true },
            enabled = levels.isNotEmpty(),
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
