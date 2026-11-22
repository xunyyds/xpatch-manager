package io.xpatch.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.xpatch.data.DownloadCenter
import io.xpatch.data.OnlinePackageStore
import io.xpatch.data.SoftwareSource
import io.xpatch.ui.components.DialogAction
import io.xpatch.ui.components.appTextFieldColors
import io.xpatch.ui.components.XPatchDialog
import io.xpatch.ui.format.formatTime
import io.xpatch.ui.format.readableSize
import io.xpatch.ui.theme.HitArea
import io.xpatch.ui.theme.IconSize
import io.xpatch.ui.theme.PagePad
import io.xpatch.ui.theme.Radius
import io.xpatch.ui.theme.ShapeTokens
import io.xpatch.ui.theme.Space
import io.xpatch.ui.theme.adaptiveDialogListHeight
import io.xpatch.ui.theme.adaptiveLoadingHeight
import io.xpatch.ui.theme.xpShape

internal data class OnlinePrompt(
    val app: OnlinePackageStore.OnlineApp,
    val versions: List<OnlinePackageStore.OnlineVersion>,
    val loading: Boolean,
    val error: String?,
    val currentVersion: String = "",
    val latestVersion: String = "",
)

internal data class OnlineProgress(
    val app: OnlinePackageStore.OnlineApp,
    val version: OnlinePackageStore.OnlineVersion,
    val taskId: Long,
)

@Composable
internal fun OnlinePromptDialog(
    prompt: OnlinePrompt,
    cached: List<OnlinePackageStore.OnlineEntry>,
    source: SoftwareSource,
    onSourceChange: (SoftwareSource) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onUse: (OnlinePackageStore.OnlineVersion, Boolean) -> Unit,
) {
    var selected by remember(prompt.app.packageName) {
        mutableStateOf<OnlinePackageStore.OnlineVersion?>(null)
    }
    var query by remember(prompt.app.packageName) { mutableStateOf("") }
    var sourceMenu by remember(prompt.app.packageName) { mutableStateOf(false) }
    val filtered = remember(prompt.versions, query) {
        if (query.isBlank()) {
            prompt.versions
        } else {
            val q = query.trim()
            prompt.versions.filter {
                it.version.contains(q, true) || it.versionCode.contains(q) || it.sizeText.contains(q, true)
            }
        }
    }
    val hasList = !prompt.loading && prompt.error == null && prompt.versions.isNotEmpty()
    XPatchDialog(
        onDismiss = onDismiss,
        title = "在线版本",
        fixedContentHeight = if (hasList) adaptiveDialogListHeight() else null,
        scrollableContent = !hasList,
        actions = listOf(
            DialogAction("取消", onClick = onDismiss),
            DialogAction("仅下载", onClick = { selected?.let { onUse(it, true) } }),
            DialogAction("下载并打包", onClick = { selected?.let { onUse(it, false) } }),
        ),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "当前 " + prompt.currentVersion.ifBlank { "—" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "  ·  最新 ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                prompt.latestVersion.ifBlank { "—" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            Box {
                Row(
                    Modifier
                        .clip(xpShape(Radius.chip))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .clickable { sourceMenu = true }
                        .padding(horizontal = Space.xs, vertical = Space.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        prompt.app.source.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        Icons.Rounded.ArrowDropDown,
                        contentDescription = "切换软件源",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                DropdownMenu(
                    expanded = sourceMenu,
                    onDismissRequest = { sourceMenu = false },
                ) {
                    SoftwareSource.entries.forEach { entry ->
                        val active = entry == source
                        DropdownMenuItem(
                            text = {
                                Text(
                                    entry.label,
                                    color = if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            trailingIcon = {
                                if (active) {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                            onClick = {
                                sourceMenu = false
                                onSourceChange(entry)
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.s))
        when {
            prompt.loading -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(adaptiveLoadingHeight()),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            prompt.error != null -> {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        prompt.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(Space.s))
                    androidx.compose.material3.TextButton(onClick = onRetry) {
                        Text("重试")
                    }
                }
            }

            prompt.versions.isEmpty() -> {
                Text(
                    "没有找到可用的历史版本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                Column(Modifier.fillMaxSize()) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = {
                            Text(
                                "搜索版本号",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.labelMedium,
                        shape = ShapeTokens.input,
                colors = appTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        filtered.size.toString() + " 个版本" +
                            if (query.isNotBlank()) " · 已筛选" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Space.xxs))
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                    items(
                        filtered.size,
                        key = { filtered[it].versionCode },
                    ) { index ->
                        val version = filtered[index]
                        if (index > 0) {
                            androidx.compose.material3.HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
                        }
                        val isSelected = selected?.versionCode == version.versionCode
                        val cachedEntry = cached.firstOrNull { it.versionCode == version.versionCode }
                        val isLatest = version.version == prompt.latestVersion
                        val isCurrent = prompt.currentVersion.isNotBlank() && version.version == prompt.currentVersion
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(xpShape(Radius.control))
                                .background(
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .clickable { selected = version }
                                .padding(horizontal = Space.s, vertical = Space.s),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "v" + version.version,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (isLatest) {
                                        Spacer(Modifier.width(Space.xs))
                                        VersionBadge("最新版", MaterialTheme.colorScheme.primary)
                                    }
                                    if (isCurrent) {
                                        Spacer(Modifier.width(Space.xs))
                                        VersionBadge("当前版本", MaterialTheme.colorScheme.tertiary)
                                    }
                                }
                                val meta = mutableListOf<String>()
                                if (version.releaseText.isNotBlank()) meta.add(version.releaseText)
                                if (version.sizeText.isNotBlank()) meta.add(version.sizeText)
                                if (cachedEntry != null) meta.add("已下载")
                                Text(
                                    meta.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (version.summary.isNotBlank()) {
                                    Text(
                                        version.summary,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
internal fun OriginalsPanel(
    entries: List<OnlinePackageStore.OnlineEntry>,
    totalSize: Long,
    onOpen: (OnlinePackageStore.OnlineEntry) -> Unit,
    onDelete: (OnlinePackageStore.OnlineEntry) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PagePad.horizontal, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                entries.size.toString() + " 个原包 · " + readableSize(totalSize),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.m),
        ) {
            OnlineDownloadEntries(
                entries = entries,
                onOpen = onOpen,
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun VersionBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(xpShape(Radius.chip))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = Space.xs, vertical = Space.xxs),
    )
}

@Composable
internal fun OnlineNoticeDialog(
    title: String,
    message: String,
    rawError: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    XPatchDialog(
        onDismiss = onDismiss,
        title = title,
        actions = listOf(
            DialogAction("关闭", onClick = onDismiss),
            DialogAction("重试", onClick = onRetry),
        ),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(Space.m))
        Text("原始信息", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Space.xs))
        TextField(
            value = rawError,
            onValueChange = {},
            readOnly = true,
            maxLines = 5,
            shape = ShapeTokens.input,
                colors = appTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun OnlineProgressDialog(
    progress: OnlineProgress,
    task: DownloadCenter.Task?,
    onHide: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onFailedLink: (String) -> Unit,
    onDone: () -> Unit,
) {
    val done = task?.done == true
    val failed = task?.failed
    var autoDone by remember(progress.taskId) { mutableStateOf(false) }
    LaunchedEffect(done) {
        if (done && !autoDone) {
            autoDone = true
            kotlinx.coroutines.delay(700)
            onDone()
        }
    }
    XPatchDialog(
        onDismiss = onHide,
        title = "下载原包",
        dismissible = true,
        actions = buildList {
            if (failed != null) {
                add(DialogAction("复制链接", onClick = { onFailedLink(progress.version.downloadUrl) }))
                add(DialogAction("重试", onClick = onRetry))
            }
            add(DialogAction(if (done) "完成" else "隐藏", onClick = if (done) onDone else onHide))
        },
    ) {
        Text(
            progress.app.name + " v" + progress.version.version,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            listOfNotNull(
                progress.version.releaseText.takeIf { it.isNotBlank() },
                progress.version.sizeText.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.m))
        when {
            done -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(IconSize.normal),
                    )
                    Spacer(Modifier.width(Space.s))
                    Text(
                        "下载完成，即将继续",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            failed != null -> {
                Column {
                    Text(
                        failed,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        "可以复制原始链接用浏览器下载，或点击重试。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (progress.version.source == SoftwareSource.WANDOUJIA) {
                        Text(
                            "下载源可能受限，可在设置中更换。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            else -> {
                val percent = task?.percent ?: 0
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    if (percent > 0) percent.toString() + "%" else "正在准备下载…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.s))
                androidx.compose.material3.TextButton(onClick = onCancel) {
                    Text("取消下载")
                }
            }
        }
    }
}

@Composable
internal fun OnlineFailedLinkDialog(
    url: String,
    detailUrl: String = "",
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val browserUrl = detailUrl
    val usableUrl = url.ifBlank { browserUrl }
    XPatchDialog(
        onDismiss = onDismiss,
        title = "下载链接",
        actions = buildList {
            add(DialogAction("取消", onClick = onDismiss))
            if (browserUrl.isNotBlank()) {
                add(
                    DialogAction(
                        "浏览器打开",
                        onClick = {
                            val opened = runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(browserUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.isSuccess
                            if (!opened) {
                                android.widget.Toast.makeText(context, "无法打开浏览器，请复制链接手动打开", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                    ),
                )
            }
        },
    ) {
        Text(
            when {
                url.isBlank() && browserUrl.isBlank() -> "暂时没有可用的下载链接。"
                url.isBlank() -> "暂时没有可用的下载链接，可打开详情页手动下载。"
                else -> "自动下载失败，可复制链接或打开详情页手动下载。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.m))
        TextField(
            value = usableUrl,
            onValueChange = {},
            readOnly = true,
            maxLines = 4,
            shape = ShapeTokens.input,
                colors = appTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Space.xs))
        androidx.compose.material3.TextButton(
            onClick = {
                clipboard.setText(AnnotatedString(usableUrl))
                copied = true
            },
        ) {
            Text(if (copied) "已复制" else "复制链接")
        }
    }
}

@Composable
internal fun OnlineStatusCapsule(
    percent: Int?,
    state: OnlineCapsuleState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = when (state) {
        OnlineCapsuleState.CHECKING -> MaterialTheme.colorScheme.secondaryContainer
        OnlineCapsuleState.FOUND -> MaterialTheme.colorScheme.primaryContainer
        OnlineCapsuleState.DOWNLOADING -> MaterialTheme.colorScheme.primaryContainer
        OnlineCapsuleState.DONE -> MaterialTheme.colorScheme.tertiaryContainer
        OnlineCapsuleState.HIDDEN -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (state) {
        OnlineCapsuleState.CHECKING -> MaterialTheme.colorScheme.onSecondaryContainer
        OnlineCapsuleState.FOUND -> MaterialTheme.colorScheme.onPrimaryContainer
        OnlineCapsuleState.DOWNLOADING -> MaterialTheme.colorScheme.onPrimaryContainer
        OnlineCapsuleState.DONE -> MaterialTheme.colorScheme.onTertiaryContainer
        OnlineCapsuleState.HIDDEN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier
            .clip(CircleShape)
            .background(container)
            .clickable { onClick() }
            .padding(horizontal = Space.m, vertical = Space.s)
            .animateContentSize(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state == OnlineCapsuleState.DOWNLOADING) {
                CircularProgressIndicator(
                    progress = { (percent ?: 0) / 100f },
                    modifier = Modifier.size(IconSize.small),
                    strokeWidth = IconSize.stroke,
                )
                Spacer(Modifier.width(Space.s))
            }
            AnimatedContent(state) { target ->
                when (target) {
                    OnlineCapsuleState.CHECKING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(IconSize.tiny), strokeWidth = IconSize.stroke)
                        Spacer(Modifier.width(Space.xs))
                        Text("查找在线版本…", style = MaterialTheme.typography.labelMedium, color = content)
                    }

                    OnlineCapsuleState.FOUND -> Text(
                        "发现在线版本 · 点此查看",
                        style = MaterialTheme.typography.labelMedium,
                        color = content,
                    )

                    OnlineCapsuleState.DOWNLOADING -> Text(
                        if (percent != null) "下载原包 $percent%" else "下载原包…",
                        style = MaterialTheme.typography.labelMedium,
                        color = content,
                    )

                    OnlineCapsuleState.DONE -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(IconSize.tiny), tint = content)
                        Spacer(Modifier.width(Space.xs))
                        Text("原包已就绪 · 点此打包", style = MaterialTheme.typography.labelMedium, color = content)
                    }

                    OnlineCapsuleState.HIDDEN -> Text(
                        "在线版本",
                        style = MaterialTheme.typography.labelMedium,
                        color = content,
                    )
                }
            }
        }
    }
}

internal enum class OnlineCapsuleState {
    CHECKING,
    FOUND,
    DOWNLOADING,
    DONE,
    HIDDEN,
}

@Composable
internal fun OnlineDownloadEntries(
    entries: List<OnlinePackageStore.OnlineEntry>,
    onOpen: (OnlinePackageStore.OnlineEntry) -> Unit,
    onDelete: (OnlinePackageStore.OnlineEntry) -> Unit,
) {
    if (entries.isEmpty()) {
        Text(
            "还没有下载的原包\n选中应用后可下载在线版本",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(Space.xl),
        )
        return
    }
    Column(Modifier.fillMaxWidth()) {
        entries.forEach { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(xpShape(Radius.control))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable { onOpen(entry) }
                    .padding(horizontal = Space.m, vertical = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.name.ifBlank { entry.packageName },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            "v" + entry.version,
                            readableSize(entry.size),
                            formatTime(entry.time).takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(Space.s))
                androidx.compose.material3.IconButton(onClick = { onDelete(entry) }) {
                    Icon(Icons.Rounded.Close, contentDescription = "删除", modifier = Modifier.size(IconSize.compact))
                }
            }
            Spacer(Modifier.height(Space.s))
        }
    }
}
