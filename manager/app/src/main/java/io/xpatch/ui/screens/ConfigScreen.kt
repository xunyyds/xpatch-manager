package io.xpatch.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.xpatch.data.HookCore
import io.xpatch.data.PatchRecord
import io.xpatch.data.SettingsStore
import io.xpatch.data.SignatureStore
import io.xpatch.patch.PatchOptions
import io.xpatch.patch.ShizukuInstaller
import io.xpatch.ui.components.AppIcon
import io.xpatch.ui.components.Chip
import io.xpatch.ui.components.DialogAction
import io.xpatch.ui.components.SettingGroup
import io.xpatch.ui.components.XPatchDialog
import io.xpatch.ui.format.formatDateTime
import io.xpatch.ui.format.readableSize
import io.xpatch.ui.theme.LocalMiuix
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal const val NO_SIGN_ID = "__no_sign__"

private val NO_SIGN_PROFILE = SignatureStore.Profile(id = NO_SIGN_ID, name = "不签名", kind = SignatureStore.Kind.BUILTIN)

private data class BypassMode(val key: String, val name: String, val desc: String)

private val BYPASS_MODES = listOf(
    BypassMode("none", "None", "不进行任何处理。应用若校验签名，可能直接闪退。"),
    BypassMode("basic", "Basic", "处理 Java 层最常见的签名查询，覆盖大多数应用。"),
    BypassMode("high", "High", "在 Basic 基础上恢复入口工厂，并处理更严格的签名校验。"),
    BypassMode("extreme", "Extreme", "在 High 基础上接管 Binder/Parcel 中的签名信息，覆盖跨进程校验。"),
    BypassMode("seccomp", "Seccomp", "基于 FPA 方案改进的 native 文件重定向，处理 openat 读取原 APK 的校验。"),
    BypassMode("stealth", "Stealth", "在 Extreme 基础上隐藏入口 Hook，降低被检测概率。"),
)

private fun bypassModeKey(sigMode: String, bypass: Int): String = when {
    sigMode == SettingsStore.SIG_MODE_JAVA_FILE -> "extreme"
    sigMode == SettingsStore.SIG_MODE_DEEP -> "stealth"
    sigMode == SettingsStore.SIG_MODE_OFF -> "none"
    bypass == 1 -> "seccomp"
    bypass == 2 -> "high"
    else -> "basic"
}

private fun hookCoreDesc(core: HookCore): String = when (core) {
    HookCore.XPLANT -> "默认注入引擎，兼容性最好。"
    HookCore.NOINLINE -> "内联优化更保守，适合个别异常应用。"
    HookCore.PINE -> "备选引擎，适合特殊加固与兼容场景。"
}

@Composable
internal fun ConfigScreen(
    target: PatchTarget,
    defaults: io.xpatch.data.ManagerSettings,
    signatures: List<SignatureStore.Profile>,
    defaultSignatureId: String,
    busy: Boolean,
    onStart: (PatchOptions) -> Unit,
    onPickSignature: (SignatureStore.Profile) -> Unit,
    onForceOverwriteChange: (Boolean) -> Unit,
    forceOverwriteReady: Boolean,
    forceOverwriteBlockReason: String,
    findReusable: (PatchOptions) -> PatchRecord?,
    onReuse: (PatchRecord) -> Unit,
) {
    var hookCore by remember { mutableStateOf(defaults.hookCore) }
    var bypass by remember { mutableStateOf(defaults.signatureBypassLevel) }
    var injectSignature by remember { mutableStateOf(defaults.injectSignature) }
    var fileProvider by remember { mutableStateOf(defaults.fileProvider) }
    var dynamicMode by remember { mutableStateOf(defaults.dynamicMode) }
    var forceOverwrite by remember { mutableStateOf(defaults.forceOverwrite) }
    var sigMode by remember { mutableStateOf(defaults.sigMode) }
    var entryHook by remember { mutableStateOf(defaults.entryHook) }
    var forceOverwriteError by remember { mutableStateOf<String?>(null) }
    var showHookDialog by remember { mutableStateOf(false) }
    var showBypassDialog by remember { mutableStateOf(false) }
    var bypassKey by remember { mutableStateOf(bypassModeKey(sigMode, bypass)) }
    var moreExpanded by remember { mutableStateOf(false) }
    val forceOverwriteNote = when {
        forceOverwrite && !forceOverwriteReady -> forceOverwriteBlockReason
        forceOverwrite -> "已启用：签名固定为 XPatch，安装时先保留数据卸载旧包"
        else -> "用于覆盖安装其他工具签名的应用；关闭时按所选签名正常安装"
    }
    val installedName = when (target) {
        is PatchTarget.Installed -> target.app.versionName
        is PatchTarget.FromFile -> target.versionName
    }.orEmpty()
    val installedCode = when (target) {
        is PatchTarget.Installed -> target.app.versionCode
        is PatchTarget.FromFile -> target.versionCode
    } ?: 0
    var versionName by remember { mutableStateOf("") }
    var versionCode by remember { mutableStateOf("") }

    val name = when (target) {
        is PatchTarget.Installed -> target.app.label
        is PatchTarget.FromFile -> target.label
    }
    val packageName = when (target) {
        is PatchTarget.Installed -> target.app.packageName
        is PatchTarget.FromFile -> target.packageName ?: target.file.name
    }
    val icon = when (target) {
        is PatchTarget.Installed -> target.app.icon
        is PatchTarget.FromFile -> target.icon
    }

    val selectedProfile = if (forceOverwrite) {
        signatures.firstOrNull { it.id == SignatureStore.BUILTIN_XPATCH }
    } else if (injectSignature) {
        signatures.firstOrNull { it.id == defaultSignatureId }
    } else {
        null
    }
    val options = PatchOptions(
        hookCore = hookCore,
        bypassLevel = bypass,
        injectSignature = if (forceOverwrite) true else injectSignature,
        fileProvider = fileProvider,
        dynamicMode = dynamicMode,
        signatureProfile = selectedProfile,
        forceOverwrite = forceOverwrite,
        versionName = versionName.trim().ifBlank { null },
        versionCode = versionCode.trim().toIntOrNull(),
        sigMode = sigMode,
        entryHook = entryHook,
    )
    val reusable = findReusable(options)
    var reuseConfirm by remember { mutableStateOf<PatchRecord?>(null) }

    Column(Modifier.fillMaxSize()) {
        AppHeaderCard(icon, name, packageName)
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            item {
                SettingGroup("Hook 核心") {
                    ChoiceRow(hookCore.label, hookCoreDesc(hookCore)) { showHookDialog = true }
                }
            }
            item {
                val mode = BYPASS_MODES.firstOrNull { it.key == bypassKey } ?: BYPASS_MODES[1]
                SettingGroup("过签方案") {
                    ChoiceRow(mode.name, mode.desc) { showBypassDialog = true }
                }
            }
            item {
                SettingGroup("签名") {
                    SignatureRow(
                        signatures = signatures,
                        selectedId = if (injectSignature) defaultSignatureId else NO_SIGN_ID,
                        enabled = !forceOverwrite,
                        onSelect = { profile ->
                            if (profile.id == NO_SIGN_ID) {
                                injectSignature = false
                            } else {
                                injectSignature = true
                                onPickSignature(profile)
                            }
                        },
                    )
                    NoteText("选择同样的签名证书可以覆盖安装。")
                }
            }
            item {
                MoreHeader(moreExpanded) { moreExpanded = !moreExpanded }
            }
            if (moreExpanded) {
            item {
                SettingGroup("版本信息") {
                    VersionRow(
                        title = "版本名",
                        placeholder = if (installedName.isBlank()) "保持原值" else installedName,
                        value = versionName,
                        onValueChange = { versionName = it },
                    )
                    VersionRow(
                        title = "版本号",
                        placeholder = if (installedCode > 0) installedCode.toString() else "保持原值",
                        value = versionCode,
                        onValueChange = { versionCode = it.filter { ch -> ch.isDigit() } },
                    )
                    NoteText("留空则沿用当前应用的真实版本信息。")
                }
            }
            item {
                SettingGroup("打包选项") {
                    SwitchLine(
                        title = "文件提供器",
                        subtitle = "提供给系统的文件访问组件，可在无 Root 权限时管理 data 目录文件（来自 MT 管理器）",
                        checked = fileProvider,
                        onChange = { fileProvider = it },
                    )
                    SwitchLine(
                        title = "动态模式",
                        subtitle = "内核动态接管类加载",
                        checked = dynamicMode,
                        onChange = { dynamicMode = it },
                    )
                }
            }
            item {
                SettingGroup("实验性") {
                    SwitchLine(
                        title = "强行覆盖安装",
                        subtitle = "签名不一致时保留数据卸载再安装，需要 Shizuku；开启后固定使用 XPatch 签名",
                        checked = forceOverwrite,
                        onChange = { on ->
                            
                            if (on && !ShizukuInstaller.forceOverwriteReady()) {
                                
                                forceOverwriteError =
                                    ShizukuInstaller.forceOverwriteBlockReason().ifBlank {
                                        "当前环境不支持强行覆盖安装"
                                    }
                            } else {
                                forceOverwrite = on
                                onForceOverwriteChange(on)
                            }
                        },
                    )
                    NoteText(forceOverwriteNote)
                }
            }
            }
            item { NoteText("点「返回」会保留打包结果，不必重新打包。") }
        }
        if (reusable != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "已打包过相同配置",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        formatDateTime(reusable.time) + " · " + readableSize(reusable.apkSize) + " · " + reusable.optionSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { onReuse(reusable) }) {
                    Text("直接复用")
                }
            }
        }
        StartButton(busy) {
            if (reusable != null) {
                reuseConfirm = reusable
            } else {
                onStart(options)
            }
        }
    }

    if (showHookDialog) {
        ChoiceDialog(
            title = "Hook 核心",
            options = HookCore.entries.map { Triple(it, it.label, hookCoreDesc(it)) },
            selected = hookCore,
            onSelect = { hookCore = it },
            onDismiss = { showHookDialog = false },
        )
    }
    if (showBypassDialog) {
        ChoiceDialog(
            title = "过签方案",
            options = BYPASS_MODES.map { Triple(it, it.name, it.desc) },
            selected = BYPASS_MODES.firstOrNull { it.key == bypassKey } ?: BYPASS_MODES[1],
            onSelect = { mode ->
                bypassKey = mode.key
                entryHook = mode.key == "stealth"
                when (mode.key) {
                    "none" -> { sigMode = SettingsStore.SIG_MODE_OFF; bypass = 3 }
                    "basic" -> { sigMode = SettingsStore.SIG_MODE_JAVA; bypass = 3 }
                    "high" -> { sigMode = SettingsStore.SIG_MODE_JAVA; bypass = 2 }
                    "extreme" -> { sigMode = SettingsStore.SIG_MODE_JAVA_FILE; bypass = 2 }
                    "seccomp" -> { sigMode = SettingsStore.SIG_MODE_JAVA; bypass = 1 }
                    "stealth" -> { sigMode = SettingsStore.SIG_MODE_DEEP; bypass = 2 }
                }
            },
            onDismiss = { showBypassDialog = false },
        )
    }

    forceOverwriteError?.let { reason ->
        XPatchDialog(
            onDismiss = { forceOverwriteError = null },
            title = "无法开启强行覆盖安装",
            actions = listOf(
                DialogAction("知道了") { forceOverwriteError = null },
            ),
        ) {
            Column {
                Text(reason, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "该功能依赖 Shizuku 完成保留数据的卸载与重装，请先安装并授权 Shizuku 后再开启。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    reuseConfirm?.let { record ->
        XPatchDialog(
            onDismiss = { reuseConfirm = null },
            title = "已打包过相同配置",
            actions = listOf(
                DialogAction("取消") { reuseConfirm = null },
                DialogAction("重新打包") {
                    reuseConfirm = null
                    onStart(options)
                },
                DialogAction("复用产物") {
                    reuseConfirm = null
                    onReuse(record)
                },
            ),
        ) {
            Column {
                Text(
                    "检测到同配置的打包产物，可直接复用，省去重新打包的时间。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    formatDateTime(record.time) + " · " + readableSize(record.apkSize) + " · " + record.optionSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    record.apkPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AppHeaderCard(icon: android.graphics.drawable.Drawable?, name: String, packageName: String) {
    if (LocalMiuix.current) {
        MiuixCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp, bottom = 4.dp),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(icon, size = 46)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    MiuixText(
                        name,
                        style = MiuixTheme.textStyles.title3,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    MiuixText(
                        "包名：$packageName",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        return
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(icon, size = 46)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "包名：$packageName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (value, label) ->
            Box(Modifier.weight(1f)) {
                Chip(
                    text = label,
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    fill = true,
                )
            }
        }
    }
}

@Composable
private fun SignatureRow(
    signatures: List<SignatureStore.Profile>,
    selectedId: String,
    enabled: Boolean,
    onSelect: (SignatureStore.Profile) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedIsNone = selectedId == NO_SIGN_ID
    val current = signatures.firstOrNull { it.id == selectedId }
    val miuix = LocalMiuix.current
    val primary = if (miuix) MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {            Column(Modifier.weight(1f)) {
                if (miuix) {
                    MiuixText(
                        when {
                            selectedIsNone -> "不签名"
                            current != null -> "使用「${current.name}」重新签名"
                            else -> "不签名"
                        },
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    MiuixText(
                        if (selectedIsNone) "产物保持原签名" else "内置证书",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    Text(
                        when {
                            selectedIsNone -> "不签名"
                            current != null -> "使用「${current.name}」重新签名"
                            else -> "不签名"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        if (selectedIsNone) "产物保持原签名" else "内置证书",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box {
                Text(
                    "更改",
                    style = MaterialTheme.typography.labelMedium,
                    color = primary,
                    modifier = Modifier.clickable { expanded = true },
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    DropdownMenuItem(
                        text = { Text("不签名") },
                        onClick = {
                            expanded = false
                            onSelect(NO_SIGN_PROFILE)
                        },
                    )
                    signatures.forEach { profile ->
                        DropdownMenuItem(
                            text = { Text(profile.name) },
                            onClick = {
                                expanded = false
                                onSelect(profile)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchLine(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        io.xpatch.ui.components.JellySwitch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun VersionRow(
    title: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val miuix = LocalMiuix.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (miuix) {
            MiuixText(
                title,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.width(76.dp),
            )
            MiuixTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = placeholder,
                useLabelAsPlaceholder = true,
            )
        } else {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.width(76.dp),
            )
            androidx.compose.material3.OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
                shape = RoundedCornerShape(12.dp),
            )
        }
    }
}


@Composable
private fun MoreHeader(expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "更多",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (expanded) "收起" else "展开",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChoiceRow(value: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(value, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Triple<T, String, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    XPatchDialog(
        onDismiss = onDismiss,
        title = title,
        actions = listOf(DialogAction("取消") { onDismiss() }),
    ) {
        Column {
            options.forEach { (value, name, desc) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(value); onDismiss() }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (value == selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(
                            desc,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (value == selected) {
                        Text(
                            "✓",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteText(text: String) {
    if (LocalMiuix.current) {
        MiuixText(
            text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 2.dp, bottom = 12.dp),
        )
    } else {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun StartButton(busy: Boolean, onClick: () -> Unit) {
    if (LocalMiuix.current) {
        MiuixButton(
            onClick = onClick,
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .height(52.dp),
        ) {
            MiuixText(
                if (busy) "正在打包…" else "开始 Patch",
                style = MiuixTheme.textStyles.button,
            )
        }
        return
    }
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .height(52.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(if (busy) "正在打包…" else "开始 Patch", style = MaterialTheme.typography.titleMedium)
    }
}
