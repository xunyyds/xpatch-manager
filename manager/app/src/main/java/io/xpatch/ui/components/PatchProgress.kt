package io.xpatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.xpatch.ui.theme.LocalMiuix
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class PatchProgress(
    val running: Boolean,
    val progress: Int,
    val logs: List<String>,
    val title: String,
)

@Composable
internal fun PatchProgressDialog(
    state: PatchProgress,
    failed: String?,
    resultPath: String?,
    onExport: () -> Unit,
    onOpenWith: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val done = !state.running
    val miuix = LocalMiuix.current
    val surface = if (miuix) MiuixTheme.colorScheme.surfaceContainer
    else MaterialTheme.colorScheme.surface
    val onSurface = if (miuix) MiuixTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface
    val secondary = if (miuix) MiuixTheme.colorScheme.onSurfaceVariantSummary
    else MaterialTheme.colorScheme.onSurfaceVariant
    val primary = if (miuix) MiuixTheme.colorScheme.primary
    else MaterialTheme.colorScheme.primary
    val error = if (miuix) MiuixTheme.colorScheme.error
    else MaterialTheme.colorScheme.error

    Dialog(
        onDismissRequest = { if (done) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = done,
            dismissOnClickOutside = done,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(surface)
                .padding(18.dp),
        ) {
            PatchHeader(state, done, failed, onSurface, secondary, primary)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0, 100) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = if (failed != null) error else primary,
                trackColor = secondary.copy(alpha = 0.2f),
            )
            Spacer(Modifier.height(12.dp))
            TypewriterLogs(state.logs, secondary, primary)
            if (done) {
                Spacer(Modifier.height(14.dp))
                if (failed == null) {
                    if (!resultPath.isNullOrBlank()) {
                        Text(
                            resultPath,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = secondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(secondary.copy(alpha = 0.08f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    ResultActions(
                        primary = primary,
                        onExport = onExport,
                        onOpenWith = onOpenWith,
                        onInstall = onInstall,
                    )
                } else {
                    Text(
                        failed,
                        style = MaterialTheme.typography.bodySmall,
                        color = error,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ActionLabel("知道了", primary, onClick = onDismiss)
                    }
                }
            }
        }
    }
}

@Composable
private fun PatchHeader(
    state: PatchProgress,
    done: Boolean,
    failed: String?,
    onSurface: Color,
    secondary: Color,
    primary: Color,
) {
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.running) {
        if (state.running) {
            startedAt = System.currentTimeMillis()
            while (true) {
                elapsed = System.currentTimeMillis() - startedAt
                delay(200)
            }
        }
    }
    val header = when {
        failed != null -> "打包失败"
        done -> "打包完成"
        else -> state.title
    }
    val accent = when {
        failed != null -> MaterialTheme.colorScheme.error
        else -> primary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            header,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            formatElapsed(elapsed),
            style = MaterialTheme.typography.labelMedium,
            color = secondary,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            state.progress.coerceIn(0, 100).toString() + "%",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
    }
}

@Composable
private fun TypewriterLogs(
    logs: List<String>,
    secondary: Color,
    primary: Color,
) {
    val listState = rememberLazyListState()
    var shown by remember { mutableIntStateOf(0) }
    var autoFollow by remember { mutableStateOf(true) }

    LaunchedEffect(logs.size) {
        if (logs.size < shown) {
            shown = 0
            autoFollow = true
        }
        while (shown < logs.size) {
            shown += 1
            delay(TYPE_INTERVAL_MS)
        }
    }

    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount == 0 || last >= info.totalItemsCount - 1
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to atBottom }
            .distinctUntilChanged()
            .collect { (scrolling, bottom) ->
                if (scrolling && !bottom) autoFollow = false
            }
    }

    LaunchedEffect(atBottom) {
        if (atBottom) autoFollow = true
    }

    LaunchedEffect(shown, autoFollow) {
        if (autoFollow && shown > 0) {
            listState.scrollToItem(shown - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height(188.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(secondary.copy(alpha = 0.08f)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        itemsIndexed(logs.take(shown)) { index, line ->
            val fresh = index == shown - 1
            Text(
                line,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = if (fresh) primary else secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ResultActions(
    primary: Color,
    onExport: () -> Unit,
    onOpenWith: () -> Unit,
    onInstall: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionLabel("导出", primary, onClick = onExport)
        ActionLabel("打开方式", primary, onClick = onOpenWith)
        ActionLabel("安装", primary, onClick = onInstall)
    }
}

@Composable
private fun ActionLabel(
    text: String,
    color: Color,
    onClick: () -> Unit,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

private fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return if (m > 0) "${m}分${s.toString().padStart(2, '0')}秒" else "$s 秒"
}

private const val TYPE_INTERVAL_MS = 80L
