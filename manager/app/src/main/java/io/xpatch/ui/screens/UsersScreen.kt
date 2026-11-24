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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.xpatch.data.UserDirectory
import io.xpatch.data.XpUser
import io.xpatch.ui.components.GroupHeader
import io.xpatch.ui.components.RowDivider
import io.xpatch.ui.theme.LocalMiuix
import io.xpatch.ui.util.rememberListBottomPadding
import io.xpatch.ui.util.rememberPageScrollState
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixDivider
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun UsersScreen(
    users: List<XpUser>,
    installedIn: Set<Int>,
    onInstallManager: (Int) -> Unit,
) {
    val miuix = LocalMiuix.current
    val current = remember { UserDirectory.currentUserId() }
    val channel = remember { UserDirectory.channelLabel() }
    LazyColumn(
        state = rememberPageScrollState(),
        contentPadding = PaddingValues(top = 4.dp, bottom = rememberListBottomPadding(true)),
    ) {
        item { GroupHeader("${users.size} 个用户") }
        if (miuix) {
            item {
                MiuixCard(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    users.forEachIndexed { index, user ->
                        UserRow(
                            user = user,
                            isCurrent = user.id == current,
                            hasManager = user.id in installedIn,
                            onClick = if (user.id in installedIn) null else ({ onInstallManager(user.id) }),
                        )
                        if (index != users.lastIndex) MiuixDivider()
                    }
                }
            }
        } else {
            items(users.size) { index ->
                val user = users[index]
                UserRow(
                    user = user,
                    isCurrent = user.id == current,
                    hasManager = user.id in installedIn,
                    onClick = if (user.id in installedIn) null else ({ onInstallManager(user.id) }),
                )
                RowDivider()
            }
        }
        item {
            Text(
                channel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun UserRow(
    user: XpUser,
    isCurrent: Boolean,
    hasManager: Boolean,
    onClick: (() -> Unit)?,
) {
    val miuix = LocalMiuix.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                start = if (miuix) 16.dp else 24.dp,
                end = if (miuix) 16.dp else 24.dp,
                top = if (miuix) 12.dp else 16.dp,
                bottom = if (miuix) 12.dp else 16.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(if (miuix) 42.dp else 46.dp)
                .clip(CircleShape)
                .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                avatarOf(user),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(if (miuix) 14.dp else 16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                user.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                user.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        when {
            isCurrent && hasManager -> Text(
                "当前",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            !hasManager -> Text(
                "安装到该用户",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            else -> Unit
        }
    }
}

private fun avatarOf(user: XpUser): String {
    val name = user.label.trim()
    if (name.isEmpty()) return "#"
    val first = name.first()
    return if (first.isLetterOrDigit()) first.uppercase() else "#"
}