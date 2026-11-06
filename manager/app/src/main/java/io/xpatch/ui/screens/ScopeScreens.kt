package io.xpatch.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.xpatch.model.AppInfo
import io.xpatch.model.ModuleInfo
import io.xpatch.ui.components.AppIcon
import io.xpatch.ui.components.EmptyState
import io.xpatch.ui.components.EntryRow
import io.xpatch.ui.components.SwitchableEntryRow
import io.xpatch.ui.components.GroupHeader
import io.xpatch.ui.components.RowDivider
import io.xpatch.ui.components.SortMenu
import io.xpatch.ui.components.SortOption
import io.xpatch.ui.components.StickySearchBar
import io.xpatch.ui.theme.LocalMiuix
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixDivider
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.Collator
import java.util.Locale

@Composable
internal fun ModuleScopeScreen(
    module: ModuleInfo,
    apps: List<AppInfo>,
    scopeOf: () -> Set<String>,
    onToggle: (AppInfo, Boolean) -> Unit,
) {
    val scope = scopeOf()
    val selected = scope.intersect(apps.map { it.packageName }.toSet())
    val miuix = LocalMiuix.current
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScopeHeader(module.label, module.packageName, "已选择 ${selected.size} 个应用") }
        item { GroupHeader("应用") }
        if (apps.isEmpty()) {
            item { EmptyState("没有可作用的应用") }
        } else if (miuix) {
            item {
                MiuixCard(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    apps.forEachIndexed { index, app ->
                        ScopeAppRow(app, app.packageName in scope, onToggle)
                        if (index != apps.lastIndex) MiuixDivider()
                    }
                }
            }
        } else {
            items(apps, key = { it.packageName }) { app ->
                ScopeAppRow(app, app.packageName in scope, onToggle)
                RowDivider()
            }
        }
    }
}

@Composable
private fun ScopeAppRow(
    app: AppInfo,
    checked: Boolean,
    onToggle: (AppInfo, Boolean) -> Unit,
) {
    SwitchableEntryRow(
        icon = app.icon,
        title = app.label,
        subtitle = app.packageName,
        checked = checked,
        onCheckedChange = { onToggle(app, it) },
    )
}

internal data class AppScopeMeta(val installTime: String, val apkSize: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppScopeScreen(
    app: AppInfo,
    meta: AppScopeMeta?,
    modules: List<ModuleInfo>,
    apiOf: (ModuleInfo) -> Int,
    onApiChange: (ModuleInfo, Int) -> Unit,
    onLaunch: () -> Unit,
    onForceStop: () -> Unit,
    onRestart: () -> Unit,
    onDexopt: () -> Unit,
    onAppInfo: () -> Unit,
    onViewLogs: () -> Unit,
    onUpdateCore: () -> Unit,
    inScope: (ModuleInfo) -> Boolean,
    onToggle: (ModuleInfo, Boolean) -> Unit,
    onOpenModule: (ModuleInfo) -> Unit,
) {
    val miuix = LocalMiuix.current
    val background = if (miuix) MiuixTheme.colorScheme.background else MaterialTheme.colorScheme.background
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableIntStateOf(0) }
    var reversed by remember { mutableStateOf(false) }
    val sortOptions = remember {
        listOf(
            SortOption("0", "默认"),
            SortOption("1", "名称"),
            SortOption("2", "首字母"),
        )
    }
    val collator = remember { Collator.getInstance(Locale.CHINA) }
    val enabledFlags = modules.associate { it.packageName to inScope(it) }
    val recommendedFlags = modules.associate { it.packageName to (app.packageName in it.recommendedApps) }

    val listed = modules
        .filter { module ->
            query.isBlank() ||
                module.label.contains(query, ignoreCase = true) ||
                module.packageName.contains(query, ignoreCase = true)
        }
        .let { source ->
            when (sort) {
                1 -> source.sortedBy { it.label.lowercase() }
                2 -> source.sortedWith(compareBy(collator) { it.label })
                else -> source
            }
        }
        .let { if (reversed) it.asReversed() else it }
        .sortedWith(
            compareByDescending<ModuleInfo> { enabledFlags[it.packageName] == true }
                .thenByDescending { recommendedFlags[it.packageName] == true },
        )

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { GroupHeader("应用详情") }
        item {
            if (miuix) {
                MiuixCard(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    DetailRow("版本号", app.versionName.ifBlank { "未设置" } + "（" + app.versionCode + "）", Modifier.padding(horizontal = 16.dp))
                    DetailRow("安装时间", meta?.installTime ?: "未设置", Modifier.padding(horizontal = 16.dp))
                    DetailRow("大小", meta?.apkSize ?: "未设置", Modifier.padding(horizontal = 16.dp))
                    DetailRow("运行时", runtimeLabel(app), Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(12.dp))
                    MiuixDivider()
                    ActionRow("启动应用", onLaunch)
                    MiuixDivider()
                    ActionRow("强制停止", onForceStop, "正在停止 ")
                    MiuixDivider()
                    ActionRow("重启应用", onRestart, "强制停止后重新拉起")
                    MiuixDivider()
                    ActionRow("Dexopt 优化", onDexopt)
                    MiuixDivider()
                    ActionRow("打开系统应用信息页", onAppInfo)
                    MiuixDivider()
                    ActionRow("查看日志", onViewLogs)
                    MiuixDivider()
                    ActionRow("更新核心", onUpdateCore, "从内嵌原始包重新打包")
                }
            } else {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    DetailRow("版本号", app.versionName.ifBlank { "未设置" } + "（" + app.versionCode + "）")
                    DetailRow("安装时间", meta?.installTime ?: "未设置")
                    DetailRow("大小", meta?.apkSize ?: "未设置")
                    DetailRow("运行时", runtimeLabel(app))
                }
                ActionRow("启动应用", onLaunch)
                ActionRow("强制停止", onForceStop, "正在停止 ")
                ActionRow("重启应用", onRestart, "强制停止后重新拉起")
                ActionRow("Dexopt 优化", onDexopt)
                ActionRow("打开系统应用信息页", onAppInfo)
                ActionRow("查看日志", onViewLogs)
                ActionRow("更新核心", onUpdateCore, "从内嵌原始包重新打包")
            }
        }
        stickyHeader(key = "modules-header") {
            StickySearchBar(background, query, { query = it }, "搜索模块 / 包名") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { GroupHeader("全部模块 ${listed.size} 个") }
                    SortMenu(
                        options = sortOptions,
                        selectedId = sort.toString(),
                        reversed = reversed,
                        onSelect = { sort = it.toInt() },
                        onReverse = { reversed = !reversed },
                        trigger = { open ->
                            val primary = if (miuix) MiuixTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { open() }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.Sort,
                                    contentDescription = "排序",
                                    modifier = Modifier.size(16.dp),
                                    tint = primary,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    sortOptions[sort].label + if (reversed) " ↓" else " ↑",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = primary,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        },
                    )
                }
            }
        }
        if (modules.isEmpty()) {
            item { EmptyState("没有已安装的模块") }
        } else if (listed.isEmpty()) {
            item { EmptyState("没有匹配的模块") }
        } else if (miuix) {
            item {
                MiuixCard(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    listed.forEachIndexed { index, module ->
                        ScopeModuleRow(
                            module,
                            inScope(module),
                            apiOf(module) to { api -> onApiChange(module, api) },
                            recommended = recommendedFlags[module.packageName] == true,
                            onOpen = { onOpenModule(module) },
                        ) { onToggle(module, it) }
                        if (index != listed.lastIndex) MiuixDivider()
                    }
                }
            }
        } else {
            items(listed, key = { it.packageName }) { module ->
                ScopeModuleRow(
                    module,
                    inScope(module),
                    apiOf(module) to { api -> onApiChange(module, api) },
                    recommended = recommendedFlags[module.packageName] == true,
                    onOpen = { onOpenModule(module) },
                ) { onToggle(module, it) }
                RowDivider()
            }
        }
    }
}

@Composable
private fun ScopeModuleRow(
    module: ModuleInfo,
    checked: Boolean,
    api: Pair<Int, (Int) -> Unit>,
    recommended: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    SwitchableEntryRow(
        icon = module.icon,
        title = module.label,
        subtitle = module.packageName,
        checked = checked,
        onCheckedChange = onToggle,
        onClick = onOpen,
        badge = if (recommended) {
            { RecommendBadge() }
        } else {
            null
        },
        minHeight = 64.dp,
        endExtra = {
            ApiSelector(module, api.first) { api.second(it) }
        },
    )
}

@Composable
internal fun RecommendBadge() {
    val primary = if (LocalMiuix.current) MiuixTheme.colorScheme.primary
    else MaterialTheme.colorScheme.primary
    Text(
        "推荐",
        style = MaterialTheme.typography.labelSmall,
        color = primary,
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(primary.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private const val LATEST_RUNTIME_VERSION = 39

internal fun runtimeLabel(app: AppInfo): String {
    if (app.runtimeVersion <= 0) return "未打包"
    val core = app.hookCore ?: "未知"
    val version = "v" + app.runtimeVersion
    return if (app.runtimeVersion < LATEST_RUNTIME_VERSION) "$core $version（旧，建议重新打包）" else "$core $version"
}

@Composable
internal fun DetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (LocalMiuix.current) MiuixTheme.colorScheme.onSurfaceVariantSummary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ActionRow(title: String, onClick: () -> Unit, subtitle: String? = null) {
    if (LocalMiuix.current) {
        ArrowPreference(
            title = title,
            summary = subtitle,
            onClick = onClick,
        )
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}
@Composable
internal fun ScopeHeader(title: String, subtitle: String, countText: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            countText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
