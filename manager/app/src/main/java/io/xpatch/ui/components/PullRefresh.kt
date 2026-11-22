package io.xpatch.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.xpatch.ui.theme.LocalMiuix
import top.yukonga.miuix.kmp.basic.PullToRefresh

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PullRefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (LocalMiuix.current) {
        PullToRefresh(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = modifier,
            refreshTexts = PULL_REFRESH_TEXTS,
            content = content,
        )
        return
    }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier,
    ) {
        content()
    }
}

private val PULL_REFRESH_TEXTS = listOf(
    "下拉刷新",
    "松开刷新",
    "正在刷新…",
    "刷新完成",
)