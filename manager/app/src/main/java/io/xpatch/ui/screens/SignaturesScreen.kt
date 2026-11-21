package io.xpatch.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import io.xpatch.data.SignatureStore
import io.xpatch.ui.components.EmptyState
import io.xpatch.ui.components.GroupHeader
import io.xpatch.ui.theme.LocalMiuix
import io.xpatch.ui.theme.xpShape
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixDivider

@Composable
internal fun SignaturesScreen(
    profiles: List<SignatureStore.Profile>,
    defaultId: String,
    onSelect: (SignatureStore.Profile) -> Unit,
    onDelete: (SignatureStore.Profile) -> Unit,
    onOpenPicker: () -> Unit,
) {
    val miuix = LocalMiuix.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val builtin = profiles.filter { it.kind == SignatureStore.Kind.BUILTIN }
    val custom = profiles.filter { it.kind == SignatureStore.Kind.CUSTOM }

    val store = remember { SignatureStore(context) }
    var pickKeystore by remember { mutableStateOf(false) }
    var pickCert by remember { mutableStateOf(false) }
    var pendingKeyFile by remember { mutableStateOf<java.io.File?>(null) }
    var pendingCertFile by remember { mutableStateOf<java.io.File?>(null) }
    var importName by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var importBusy by remember { mutableStateOf(false) }

    fun tmpCopy(uri: android.net.Uri): java.io.File? = runCatching {
        val out = java.io.File(context.cacheDir, "sig_import_" + System.currentTimeMillis())
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        out
    }.getOrNull()

    val keystorePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        pickKeystore = false
        if (uri == null) return@rememberLauncherForActivityResult
        val f = tmpCopy(uri)
        if (f == null) {
            importError = "读取所选文件失败"
            return@rememberLauncherForActivityResult
        }
        pendingCertFile = null
        pendingKeyFile = null
        importName = f.nameWithoutExtension
        importPassword = ""
        importError = null
        pickKeystore = false
        pendingKeyFile = f
    }

    val certPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        pickCert = false
        if (uri == null) return@rememberLauncherForActivityResult
        val f = tmpCopy(uri)
        if (f == null) {
            importError = "读取证书文件失败"
            return@rememberLauncherForActivityResult
        }
        pendingCertFile = f
    }

    val keyPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val f = tmpCopy(uri)
        if (f == null) {
            importError = "读取私钥文件失败"
            return@rememberLauncherForActivityResult
        }
        pendingKeyFile = f
        if (pendingCertFile != null) {
            importName = pendingCertFile?.nameWithoutExtension ?: ""
            importPassword = ""
            importError = null
        }
    }

    fun doImport() {
        if (importBusy) return
        val name = importName.trim()
        if (name.isEmpty()) {
            importError = "请输入签名名称"
            return
        }
        importBusy = true
        importError = null
        val cert = pendingCertFile
        val key = pendingKeyFile
        val saved = if (cert != null && key != null) {
            runCatching { store.importKeystore(name, key, importPassword, cert, key) }
        } else if (key != null) {
            runCatching { store.importKeystore(name, key, importPassword, null, null) }
        } else {
            runCatching { store.importKeystore(name, java.io.File(""), importPassword, null, null) }
                .recoverCatching { throw IllegalStateException("缺少签名文件") }
        }
        importBusy = false
        saved.onSuccess { profile ->
            onSelect(profile)
            pendingKeyFile = null
            pendingCertFile = null
            importName = ""
            importPassword = ""
            importError = null
            pickKeystore = false
        }.onFailure { e ->
            importError = "导入失败：" + (e.message ?: "未知错误")
        }
    }

    fun dismissImport() {
        pickKeystore = false
        pendingKeyFile = null
        pendingCertFile = null
        importName = ""
        importPassword = ""
        importError = null
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f),
            state = io.xpatch.ui.util.rememberPageScrollState(),
            contentPadding = PaddingValues(bottom = io.xpatch.ui.util.rememberListBottomPadding(false)),
        ) {
            item { GroupHeader("内置签名（兼容 FPA/NP）") }
            if (miuix) {
                item {
                    SignatureCard(builtin) { profile ->
                        SignatureRow(profile, profile.id == defaultId, onSelect, null)
                    }
                }
            } else {
                items(builtin, key = { it.id }) { profile ->
                    SignatureRow(profile, profile.id == defaultId, onSelect, null)
                }
            }
            item { GroupHeader("自定义签名") }
            if (custom.isEmpty()) {
                item { EmptyState("还没有自定义签名") }
            } else if (miuix) {
                item {
                    SignatureCard(custom) { profile ->
                        SignatureRow(profile, profile.id == defaultId, onSelect) { onDelete(profile) }
                    }
                }
            } else {
                items(custom, key = { it.id }) { profile ->
                    SignatureRow(profile, profile.id == defaultId, onSelect) { onDelete(profile) }
                }
            }
        }
        if (pickKeystore && pendingKeyFile != null) {
            ImportFormCard(
                title = "导入签名文件",
                nameLabel = "签名名称",
                nameValue = importName,
                onNameChange = { importName = it },
                passwordLabel = "文件密码（无密码留空）",
                passwordValue = importPassword,
                onPasswordChange = { importPassword = it },
                hint = "格式：PKCS12 / JKS / BKS",
                error = importError,
                busy = importBusy,
                onConfirm = { doImport() },
                onDismiss = { dismissImport() },
            )
        }
        if (pendingCertFile != null && pendingKeyFile != null) {
            ImportFormCard(
                title = "导入证书与私钥",
                nameLabel = "签名名称",
                nameValue = importName,
                onNameChange = { importName = it },
                passwordLabel = "PKCS8 私钥密码（无密码留空）",
                passwordValue = importPassword,
                onPasswordChange = { importPassword = it },
                hint = "证书：" + (pendingCertFile?.name ?: "") + " · 私钥：" + (pendingKeyFile?.name ?: ""),
                error = importError,
                busy = importBusy,
                onConfirm = { doImport() },
                onDismiss = { dismissImport() },
            )
        }
        var menuOpen by remember { mutableStateOf(false) }
        Box {
            ExtendenAddButton(
                onClick = {
                    if (pendingCertFile != null && pendingKeyFile == null) {
                        pickCert = true
                        keyPicker.launch(arrayOf("*/*"))
                    } else {
                        menuOpen = true
                    }
                },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("导入 keystore 文件（p12 / jks / bks）") },
                    onClick = {
                        menuOpen = false
                        pendingCertFile = null
                        pendingKeyFile = null
                        pickKeystore = false
                        keystorePicker.launch(arrayOf("*/*"))
                    },
                )
                DropdownMenuItem(
                    text = { Text("导入证书 + 私钥（der / pem + pk8）") },
                    onClick = {
                        menuOpen = false
                        pendingCertFile = null
                        pendingKeyFile = null
                        pickCert = true
                        certPicker.launch(arrayOf("*/*"))
                    },
                )
            }
        }
    }
}

@Composable
private fun ImportFormCard(
    title: String,
    nameLabel: String,
    nameValue: String,
    onNameChange: (String) -> Unit,
    passwordLabel: String,
    passwordValue: String,
    onPasswordChange: (String) -> Unit,
    hint: String,
    error: String?,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    MiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Text(nameLabel, style = MaterialTheme.typography.labelMedium)
            androidx.compose.material3.OutlinedTextField(
                value = nameValue,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Text(passwordLabel, style = MaterialTheme.typography.labelMedium)
            androidx.compose.material3.OutlinedTextField(
                value = passwordValue,
                onValueChange = onPasswordChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            )
            Spacer(Modifier.height(6.dp))
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(error, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                Button(onClick = onConfirm, enabled = !busy) {
                    Text(if (busy) "导入中…" else "导入")
                }
                Button(onClick = onDismiss, enabled = !busy) {
                    Text("取消")
                }
            }
        }
    }
}

@Composable
private fun SignatureCard(
    profiles: List<SignatureStore.Profile>,
    row: @Composable (SignatureStore.Profile) -> Unit,
) {
    MiuixCard(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        profiles.forEachIndexed { index, profile ->
            row(profile)
            if (index != profiles.lastIndex) MiuixDivider()
        }
    }
}

@Composable
internal fun SignatureRow(
    profile: SignatureStore.Profile,
    selected: Boolean,
    onSelect: (SignatureStore.Profile) -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onSelect(profile) }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                profile.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                if (profile.kind == SignatureStore.Kind.BUILTIN) "内置" else "自定义",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Text("默认", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (onDelete != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                "删除",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable { onDelete() },
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

@Composable
internal fun SignaturePicker(
    signatures: List<SignatureStore.Profile>,
    selectedId: String,
    onSelect: (SignatureStore.Profile) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = signatures.firstOrNull { it.id == selectedId }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (current == null) "不签名" else "使用「" + current.name + "」重新签名",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (current != null) {
                    Text(
                        if (current.kind == SignatureStore.Kind.BUILTIN) "内置证书" else "自定义证书",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text("更改", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("不签名") },
                onClick = {
                    expanded = false
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

@Composable
internal fun ExtendenAddButton(onClick: () -> Unit) {
    if (LocalMiuix.current) {
        MiuixButton(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(52.dp),
            cornerRadius = 16.dp,
        ) {
            Text("添加自定义签名", style = MaterialTheme.typography.titleMedium)
        }
        return
    }
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .height(52.dp),
        shape = xpShape(16.dp),
    ) {
        Text("添加自定义签名", style = MaterialTheme.typography.titleMedium)
    }
}
