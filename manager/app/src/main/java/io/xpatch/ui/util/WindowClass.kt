package io.xpatch.ui.util

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WindowClass { COMPACT, MEDIUM, EXPANDED }

@Composable
fun currentWindowClass(): WindowClass {
    val width = LocalConfiguration.current.screenWidthDp
    return when {
        width >= 840 -> WindowClass.EXPANDED
        width >= 600 -> WindowClass.MEDIUM
        else -> WindowClass.COMPACT
    }
}

@Composable
fun AdaptiveWidth(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 640.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val windowClass = currentWindowClass()
    if (windowClass == WindowClass.COMPACT) {
        Box(modifier = modifier, content = content)
    } else {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(modifier = Modifier.widthIn(max = maxWidth), content = content)
        }
    }
}

@Composable
fun rememberListBottomPadding(withBottomBar: Boolean): Dp {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return bottom + if (withBottomBar) 96.dp else 24.dp
}
