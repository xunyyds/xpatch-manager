package io.xpatch.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import io.xpatch.data.UserDirectory
import io.xpatch.data.XpUser

internal fun userTargetText(selected: Set<Int>): String {
    if (selected.isEmpty()) return UserDirectory.labelOf(UserDirectory.currentUserId())
    return selected.sorted().joinToString("、") { UserDirectory.labelOf(it) }
}

@Composable
internal fun UserTabs(
    users: List<XpUser>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (users.size <= 1) return
    val index = users.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    SegmentedControl(
        options = users.map { "${it.label}（${it.id}）" },
        selectedIndex = index,
        modifier = modifier.fillMaxWidth(),
        onSelect = { users.getOrNull(it)?.let { user -> onSelect(user.id) } },
    )
}

@Composable
internal fun UserChip(
    users: List<XpUser>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    if (users.size <= 1) return
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            tonalElevation = 0.dp,
            onClick = { expanded = true },
        ) {
            Row(
                Modifier.padding(start = 14.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    UserDirectory.labelOf(selected),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.padding(start = 2.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            users.forEach { user ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                user.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (user.id == selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                user.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingIcon = if (user.id == selected) {
                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelect(user.id)
                    },
                )
            }
        }
    }
}

@Composable
internal fun UserTargetRows(
    users: List<XpUser>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
) {
    val current = UserDirectory.currentUserId()
    users.forEach { user ->
        val checked = user.id in selected
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onToggle(user.id) }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    user.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    user.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (user.id == current) {
                Text(
                    "当前",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (checked) {
                Text(
                    "✓",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
internal fun UserTargetDialog(
    users: List<XpUser>,
    selected: Set<Int>,
    onConfirm: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    var working by remember(selected) { mutableStateOf(selected) }
    val current = UserDirectory.currentUserId()
    val needsManager = working.any { it != current }
    XPatchDialog(
        onDismiss = onDismiss,
        title = "安装到",
        actions = listOf(
            DialogAction("取消") { onDismiss() },
            DialogAction("完成") { onConfirm(working) },
        ),
    ) {
        Column {
            UserTargetRows(users, working) { id ->
                working = if (id in working && working.size > 1) working - id else working + id
            }
            if (needsManager) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "将同时把管理器安装到目标用户，用于该用户的模块通信能力。" +
                        "你仍可在主管理器里完成全部管理操作。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}