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
import io.xpatch.data.SignatureStore
import io.xpatch.patch.PatchOptions
import io.xpatch.ui.components.AppIcon
import io.xpatch.ui.components.Chip
import io.xpatch.ui.components.SettingGroup
import io.xpatch.ui.theme.LocalMiuix
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal const val NO_SIGN_ID = "__no_sign__"

private val NO_SIGN_PROFILE = SignatureStore.Profile(id = NO_SIGN_ID, name = "不签名", kind = SignatureStore.Kind.BUILTIN)

@Composable
internal fun ConfigScreen(
    target: PatchTarget,
    defaults: io.xpatch.data.ManagerSettings,
    signatures: List<SignatureStore.Profile>,
    defaultSignatureId: String,
    busy: Boolean,
    onStart: (PatchOptions) -> Unit,
    onPickSignature: (SignatureStore.Profile) -> Unit,
) {
    var hookCore by remember { mutableStateOf(defaults.hookCore) }
    var bypass by remember { mutableStateOf(defaults.signatureBypassLevel) }
    var injectSignature by remember { mutableStateOf(defaults.injectSignature) }
    var fileProvider by remember { mutableStateOf(defaults.fileProvider) }
    var dynamicMode by remember { mutableStateOf(defaults.dynamicMode) }
    val installedName = (target as? PatchTarget.Installed)?.app?.versionName.orEmpty()
    val installedCode = (target as? PatchTarget.Installed)?.app?.versionCode ?: 0
    var versionName by remember { mutableStateOf("") }
    var versionCode by remember { mutableStateOf("") }

    val name = when (target) {
        is PatchTarget.Installed -> target.app.label
        is PatchTarget.FromFile -> target.label
    }
    val packageName = when (target) {
        is PatchTarget.Installed -> target.app.packageName
        is PatchTarget.FromFile -> target.file.name
    }
    val icon = (target as? PatchTarget.Installed)?.app?.icon

    Column(Modifier.fillMaxSize()) {
        AppHeaderCard(icon, name, packageName)
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            item {
                SettingGroup("Hook 核心") {
                    ChipRow(
                        options = HookCore.entries.map { it to it.label },
                        selected = hookCore,
                        onSelect = { hookCore = it },
                    )
                }
            }
            item {
                SettingGroup("过签方案") {
                    ChipRow(
                        options = listOf(
                            1 to "seccomp",
                            2 to "hook",
                            3 to "none",
                        ),
                        selected = bypass,
                        onSelect = { bypass = it },
                    )
                }
            }
            item {
                SettingGroup("签名") {
                    SignatureRow(
                        signatures = signatures,
                        selectedId = if (injectSignature) defaultSignatureId else NO_SIGN_ID,
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
                        title = "File Provider",
                        subtitle = "为模块提供文件访问入口",
                        checked = fileProvider,
                        onChange = { fileProvider = it },
                    )
                    SwitchLine(
                        title = "动态模式",
                        subtitle = "运行时动态接管类加载",
                        checked = dynamicMode,
                        onChange = { dynamicMode = it },
                    )
                }
            }
            item { NoteText("点「返回」会保留打包结果，不必重新打包。") }
        }
        StartButton(busy) {
            val selectedProfile = if (injectSignature) {
                signatures.firstOrNull { it.id == defaultSignatureId }
            } else {
                null
            }
            onStart(
                PatchOptions(
                    hookCore = hookCore,
                    bypassLevel = bypass,
                    injectSignature = injectSignature,
                    fileProvider = fileProvider,
                    dynamicMode = dynamicMode,
                    signatureProfile = selectedProfile,
                    versionName = versionName.trim().ifBlank { null },
                    versionCode = versionCode.trim().toIntOrNull(),
                ),
            )
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
                .clickable { expanded = true }
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
    if (LocalMiuix.current) {
        SwitchPreference(
            checked = checked,
            onCheckedChange = onChange,
            title = title,
            summary = subtitle,
            insideMargin = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        )
        return
    }
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
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
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
