package io.xpatch.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.PullToRefresh

@Composable
internal fun PullRefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    PullToRefresh(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        content = content,
    )
}
