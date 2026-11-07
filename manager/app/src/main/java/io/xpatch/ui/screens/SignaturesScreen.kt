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
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val builtin = profiles.filter { it.kind == SignatureStore.Kind.BUILTIN }
    val custom = profiles.filter { it.kind == SignatureStore.Kind.CUSTOM }
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
        ExtendenAddButton(onOpenPicker)
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
        shape = RoundedCornerShape(16.dp),
    ) {
        Text("添加自定义签名", style = MaterialTheme.typography.titleMedium)
    }
}
