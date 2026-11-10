package io.xpatch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import io.xpatch.ui.components.DialogAction
import io.xpatch.ui.components.SettingGroup
import io.xpatch.ui.components.XPatchDialog
import io.xpatch.ui.format.formatDateTime
import io.xpatch.ui.format.readableSize
import io.xpatch.ui.theme.LocalMiuix
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal const val NO_SIGN_ID = "__no_sign__"

private val NO_SIGN_PROFILE = SignatureStore.Profile(id = NO_SIGN_ID, name = "不签名", kind = SignatureStore.Kind.BUILTIN)

/**
 * 「过签方案」六档：每一项都直接对应最终写进 xp/config.json 的 (sig_mode, bypass_level)。
 * 描述按 XpInit.installSignBypass() 的真实分支写——旧版 high/extreme/stealth 把功劳记错了层
 * （Parcel hook 在 basic 就有、入口工厂与 bypass 无关、stealth 实际是新增清单 provider），这里逐条改正。
 * 档位不是单调强度阶梯：strict 比 seccomp 多了严格校验，seccomp 比 file 多了 native openat；
 * 最高档 extreme 才是严格校验 + 文件层 + dobby/inline 混合 native 的三层叠加。
 */
private data class BypassMode(
    val key: String,
    val name: String,
    val desc: String,
    val sigMode: String,
    val bypassLevel: Int,
)

private val BYPASS_MODES = listOf(
    BypassMode(
        key = "none",
        name = "None（关闭）",
        desc = "关闭签名伪装：不安装任何签名 hook，也不接管文件层。应用只要校验签名就会失败。",
        sigMode = SettingsStore.SIG_MODE_OFF,
        bypassLevel = 3,
    ),
    BypassMode(
        key = "basic",
        name = "Basic（Java 层）",
        desc = "Java 层签名伪装：Signature 比较、PackageInfo、checkSignatures 都返回原证书，覆盖大多数应用（默认档）。",
        sigMode = SettingsStore.SIG_MODE_JAVA,
        bypassLevel = 3,
    ),
    BypassMode(
        key = "strict",
        name = "Strict（+ 严格校验）",
        desc = "在 Basic 之上打开 hasSigningCertificate 严格校验（Android 11+ 自校验常用 API），应对更严格的签名查询。",
        sigMode = SettingsStore.SIG_MODE_JAVA,
        bypassLevel = 2,
    ),
    BypassMode(
        key = "file",
        name = "File（+ 文件层）",
        desc = "在 Strict 之上把代码/资源路径重定向到内嵌的原始安装包，处理按 APK 文件路径读签名的校验；必须内嵌原包，产物更大。",
        sigMode = SettingsStore.SIG_MODE_JAVA_FILE,
        bypassLevel = 2,
    ),
    BypassMode(
        key = "seccomp",
        name = "Seccomp（native 重定向）",
        desc = "在 Java 层之上用 native 层 openat 重定向替换签名块读取，并同时接管文件层；同样需要内嵌原包，但不含 hasSigningCertificate 严格校验。",
        sigMode = SettingsStore.SIG_MODE_JAVA,
        bypassLevel = 1,
    ),
    BypassMode(
        key = "extreme",
        name = "Extreme（最高档）",
        desc = "最高档：严格签名校验（hasSigningCertificate）+ 文件层路径重定向 + dobby/inline 混合 native 重定向三层叠加；必须内嵌原始安装包，产物最大。",
        sigMode = SettingsStore.SIG_MODE_DEEP,
        bypassLevel = 3,
    ),
)

/**
 * 反向映射：界面显示的档位只由 (sigMode, bypassLevel) 纯函数派生，不再另存 remember state。
 * 旧写法 `var bypassKey by remember { mutableStateOf(bypassModeKey(...)) }` 会让显示与真正下发的参数脱钩
 * （导入备份后出现过"显示 Stealth、实际 strict=false + 原生重定向"）。
 * 组合不属于六档（旧配置 sig_mode=deep、手改的备份）时返回 null，由界面显示「自定义」。
 */
private fun bypassModeKey(sigMode: String, bypassLevel: Int): String? =
    BYPASS_MODES.firstOrNull { it.sigMode == sigMode && it.bypassLevel == bypassLevel }?.key

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
    // 档位/入口 Hook 一变更就落盘 SettingsStore 并同步 MainScaffold 的 settings：
    // 只在本页攒 state、直到点「开始 Patch」才写，会出现"设置了却没保存"和 settings.copy 脱钩。
    onSignatureModeChange: (sigMode: String, bypassLevel: Int, entryHook: Boolean) -> Unit,
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
    var moreExpanded by remember { mutableStateOf(false) }
    // 派生值：界面显示的档位 = 即将写进 config.json 的 (sigMode, bypass)，两者不可能再脱钩。
    val bypassKey = bypassModeKey(sigMode, bypass)
    val bypassMode = BYPASS_MODES.firstOrNull { it.key == bypassKey }
    val bypassTitle = bypassMode?.name ?: "自定义"
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
                    ChoiceRow(hookCore.label) { showHookDialog = true }
                }
            }
            item {
                SettingGroup("过签方案") {
                    ChoiceRow(bypassTitle) { showBypassDialog = true }
                    if (bypassMode == null) {
                        // 非预设组合（旧 deep 配置或手改过的备份）：明说它不在六档内，避免被当成最近的一档。
                        NoteText("预设档位见「过签方案」列表，重新选择任意档位会把它改回预设组合。")
                    }
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
                        // 入口 Hook 不再是"隐藏入口/降低检测"：它只是在工厂注入之外**额外**插一个
                        // ContentProvider 备用入口（会新增清单组件），因此默认关、且与档位彻底解耦。
                        SwitchLine(
                            title = "入口 Hook",
                            subtitle = "实验：额外插入 ContentProvider 备用入口，仍保留工厂注入。",
                            checked = entryHook,
                            onChange = { on ->
                                entryHook = on
                                onSignatureModeChange(sigMode, bypass, on)
                            },
                        )
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
        // 选中值直接用派生出来的 bypassKey（非预设组合传空串，五档都不打勾）；
        // 选中后只改 (sigMode, bypassLevel) 两项，入口 Hook 不再被档位顺手重置成 true。
        ChoiceDialog(
            title = "过签方案",
            options = BYPASS_MODES.map { Triple(it.key, it.name, it.desc) },
            selected = bypassKey ?: "",
            onSelect = { key ->
                BYPASS_MODES.firstOrNull { it.key == key }?.let { mode ->
                    sigMode = mode.sigMode
                    bypass = mode.bypassLevel
                    // 需要内嵌的档位默认走 FPA 动态加载（产物只保留原包 + 运行层），
                    // 避免"原条目 + xp/source.apk"双份体积；可在「更多」里手动关掉。
                    dynamicMode = mode.key == "file" || mode.key == "seccomp" || mode.key == "extreme"
                    onSignatureModeChange(mode.sigMode, mode.bypassLevel, entryHook)
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
private fun ChoiceRow(value: String, subtitle: String = "", onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(value, style = MaterialTheme.typography.bodyMedium)
            // 描述只在选择弹窗里展示；折叠态只留档位名，避免一屏全是说明文字。
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
