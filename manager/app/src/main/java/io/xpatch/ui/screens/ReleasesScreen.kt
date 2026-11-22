package io.xpatch.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.xpatch.BuildConfig
import io.xpatch.data.UpdateChecker
import io.xpatch.ui.components.EmptyState
import io.xpatch.ui.components.MarkdownView
import io.xpatch.ui.components.PullRefreshBox
import io.xpatch.ui.components.Spinner
import io.xpatch.ui.components.rememberMarkdownBlocks
import io.xpatch.ui.theme.LocalMiuix
import io.xpatch.ui.util.rememberListBottomPadding
import io.xpatch.ui.util.rememberPageScrollState
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixDivider
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ReleasesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val miuix = LocalMiuix.current
    var releases by remember { mutableStateOf<List<UpdateChecker.ReleaseInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        error = null
        runCatching { UpdateChecker.history(context, force = reloadKey > 0) }
            .onSuccess { releases = it }
            .onFailure {
                releases = emptyList()
                error = it.message ?: "获取更新日志失败"
            }
        loading = false
    }

    PullRefreshBox(
        refreshing = loading && releases.isNotEmpty(),
        onRefresh = { reloadKey++ },
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = rememberPageScrollState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = rememberListBottomPadding(false) + 24.dp,
            ),
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (miuix) 0.dp else 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        if (miuix) {
                            MiuixText(
                                "发行版本",
                                style = MiuixTheme.textStyles.title3,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            MiuixText(
                                "当前版本 v" + BuildConfig.VERSION_NAME + " · 数据来自 GitHub Releases",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        } else {
                            Text(
                                "发行版本",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "当前版本 v" + BuildConfig.VERSION_NAME + " · 数据来自 GitHub Releases",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (loading && releases.isNotEmpty()) Spinner(size = 18.dp)
                }
            }
            when {
                loading && releases.isEmpty() -> item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = if (miuix) 0.dp else 8.dp, vertical = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spinner()
                        Spacer(Modifier.width(12.dp))
                        if (miuix) {
                            MiuixText(
                                "正在获取更新日志…",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        } else {
                            Text(
                                "正在获取更新日志…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                error != null -> item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = if (miuix) 0.dp else 8.dp, vertical = 24.dp),
                    ) {
                        if (miuix) {
                            MiuixText(
                                error.orEmpty(),
                                style = MiuixTheme.textStyles.body2,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(4.dp))
                            MiuixTextButton(text = "重新获取", onClick = { reloadKey++ })
                        } else {
                            Text(
                                error.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = { reloadKey++ }) { Text("重新获取") }
                        }
                    }
                }

                releases.isEmpty() -> item { EmptyState("暂时没有可用的版本记录") }

                else -> itemsIndexed(releases, key = { _, release -> release.tagName }) { index, release ->
                    ReleaseEntry(
                        release = release,
                        onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
                    )
                    if (index != releases.lastIndex) {
                        if (miuix) {
                            MiuixDivider(modifier = Modifier.padding(start = 8.dp))
                        } else {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseEntry(
    release: UpdateChecker.ReleaseInfo,
    onOpenUrl: (String) -> Unit,
) {
    val miuix = LocalMiuix.current
    var expanded by remember(release.tagName) { mutableStateOf(false) }
    val blocks = rememberMarkdownBlocks(release.notes)
    val preview = remember(release.notes) {
        release.notes.lineSequence()
            .filter { it.isNotBlank() }
            .take(3)
            .joinToString(" ") { it.trimStart('#', ' ', '-', '*') }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = if (miuix) 0.dp else 8.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (miuix) {
                MiuixText(
                    "v" + release.version,
                    style = MiuixTheme.textStyles.title3,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            } else {
                Text(
                    "v" + release.version,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (release.prerelease) {
                Spacer(Modifier.width(8.dp))
                if (miuix) {
                    MiuixText(
                        "测试版",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MiuixTheme.colorScheme.tertiaryContainer)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                } else {
                    Text(
                        "测试版",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (release.publishedAt.isNotBlank()) {
                if (miuix) {
                    MiuixText(
                        release.publishedAt.take(10),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    Text(
                        release.publishedAt.take(10),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            release.notes.isBlank() -> {
                if (miuix) {
                    MiuixText(
                        "该版本没有发布说明",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    Text(
                        "该版本没有发布说明",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            expanded -> {
                if (miuix) {
                    MiuixText(
                        release.notes,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    MiuixTextButton(text = "收起", onClick = { expanded = false })
                } else {
                    MarkdownView(blocks = blocks)
                    TextButton(onClick = { expanded = false }) { Text("收起") }
                }
            }

            else -> {
                if (miuix) {
                    MiuixText(
                        preview,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MiuixTextButton(text = "展开全文", onClick = { expanded = true })
                } else {
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { expanded = true }) { Text("展开全文") }
                }
            }
        }
        if (release.htmlUrl.isNotBlank()) {
            if (miuix) {
                MiuixTextButton(text = "在 GitHub 查看", onClick = { onOpenUrl(release.htmlUrl) })
            } else {
                TextButton(onClick = { onOpenUrl(release.htmlUrl) }) { Text("在 GitHub 查看") }
            }
        }
    }
}