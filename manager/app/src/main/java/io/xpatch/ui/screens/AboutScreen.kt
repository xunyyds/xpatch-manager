package io.xpatch.ui.screens

import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.xpatch.BuildConfig
import io.xpatch.data.DownloadCenter
import io.xpatch.data.SettingsStore
import io.xpatch.data.UpdateChecker
import io.xpatch.patch.InstallHelper
import io.xpatch.ui.components.AppIcon
import io.xpatch.ui.components.DialogAction
import io.xpatch.ui.components.MarkdownView
import io.xpatch.ui.components.RowDivider
import io.xpatch.ui.components.SettingGroup
import io.xpatch.ui.components.SettingItem
import io.xpatch.ui.components.SettingSwitchItem
import io.xpatch.ui.components.XPatchDialog
import io.xpatch.ui.components.rememberMarkdownBlocks
import io.xpatch.ui.format.readableSize
import io.xpatch.ui.theme.LocalMiuix
import io.xpatch.ui.theme.ShapeTokens
import io.xpatch.ui.theme.xpShape
import io.xpatch.ui.util.rememberListBottomPadding
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AboutScreen(
    onOpenDisclaimer: () -> Unit,
    onOpenAcknowledgements: () -> Unit,
    onOpenReleases: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }
    var receiveBeta by remember { mutableStateOf(settingsStore.load().receiveBeta) }
    var checking by remember { mutableStateOf(false) }
    var updateState by remember { mutableStateOf<UpdateChecker.CheckResult?>(null) }
    var downloadTaskId by remember { mutableStateOf<Long?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var showBetaConfirm by remember { mutableStateOf(false) }
    val downloadTasks by DownloadCenter.tasks.collectAsState()
    val downloadTask = downloadTaskId?.let { downloadTasks[it] }
    val downloading = downloadTask != null && !downloadTask.done && downloadTask.failed == null

    val appIcon = remember {
        runCatching { context.packageManager.getApplicationIcon(context.packageName) }.getOrNull() as? Drawable
    }
    val miuix = LocalMiuix.current
    val status = updateShortStatus(checking, updateState)

    fun applyReceiveBeta(enabled: Boolean) {
        receiveBeta = enabled
        settingsStore.setReceiveBeta(enabled)
        updateState = null
    }

    fun checkUpdate() {
        if (checking || downloading) return
        checking = true
        updateState = null
        scope.launch {
            val result = runCatching {
                withTimeoutOrNull(25_000) { UpdateChecker.check(context, force = true) }
            }.getOrNull()
            checking = false
            updateState = result ?: UpdateChecker.CheckResult.Failed("检查超时，请稍后重试")
            if (result is UpdateChecker.CheckResult.UpToDate) {
                Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
            } else if (result is UpdateChecker.CheckResult.Failed) {
                Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun startDownload(release: UpdateChecker.ReleaseInfo) {
        if (downloading) return
        if (!InstallHelper.canInstall(context)) {
            Toast.makeText(context, "请先授予「安装未知应用」权限", Toast.LENGTH_SHORT).show()
            InstallHelper.openInstallPermission(context)
            return
        }
        downloadTaskId = DownloadCenter.startUpdate(context, release)
        Toast.makeText(context, "已开始后台下载，可在通知里暂停或取消", Toast.LENGTH_SHORT).show()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = rememberListBottomPadding(false) + 24.dp,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { AboutHeader(appIcon) }
        item { Spacer(Modifier.height(if (miuix) 20.dp else 8.dp)) }
        if (!miuix) {
            item {
                AboutSection("项目") {
                    AboutLinkRow(
                        icon = Icons.Rounded.Home,
                        title = "项目主页",
                        subtitle = "github.com/xunyyds/xpatch-manager",
                        external = true,
                    ) {
                        runCatching { uriHandler.openUri(UpdateChecker.REPO_URL) }
                    }
                    RowDivider(startPadding = 52.dp)
                    AboutLinkRow(
                        icon = Icons.Rounded.People,
                        title = "用户交流群",
                        subtitle = "卡拉彼丘交流群",
                        external = true,
                    ) {
                        runCatching { uriHandler.openUri("https://qm.qq.com/q/fLiqpUojq8") }
                    }
                    RowDivider(startPadding = 52.dp)
                    AboutLinkRow(
                        icon = Icons.Rounded.Email,
                        title = "反馈邮箱",
                        subtitle = DisclaimerData.CONTACT,
                        external = true,
                    ) {
                        runCatching { uriHandler.openUri("mailto:" + DisclaimerData.CONTACT) }
                    }
                }
            }
            item {
                AboutSection("开源") {
                    AboutLinkRow(
                        icon = Icons.Rounded.Favorite,
                        title = "特别鸣谢",
                        subtitle = "致谢项目",
                    ) { onOpenAcknowledgements() }
                    RowDivider(startPadding = 52.dp)
                    AboutLinkRow(
                        icon = Icons.Rounded.Gavel,
                        title = "开源许可",
                        subtitle = "MIT 与第三方许可",
                        external = true,
                    ) {
                        runCatching { uriHandler.openUri(UpdateChecker.REPO_URL + "/blob/main/LICENSE") }
                    }
                    RowDivider(startPadding = 52.dp)
                    AboutLinkRow(
                        icon = Icons.Rounded.Description,
                        title = "免责声明",
                    ) { onOpenDisclaimer() }
                }
            }
            item {
                AboutSection("版本") {
                    AboutLinkRow(
                        icon = Icons.Rounded.Public,
                        title = "检查更新",
                        trailing = status,
                        onClick = { checkUpdate() },
                    )
                    RowDivider(startPadding = 52.dp)
                    AboutLinkRow(
                        icon = Icons.Rounded.History,
                        title = "更新日志",
                    ) { onOpenReleases() }
                    RowDivider(startPadding = 52.dp)
                    AboutSwitchRow(
                        icon = Icons.Rounded.Notifications,
                        title = "接收测试版更新",
                        checked = receiveBeta,
                    ) { enabled ->
                        if (enabled && !receiveBeta) {
                            showBetaConfirm = true
                        } else {
                            applyReceiveBeta(enabled)
                        }
                    }
                    (updateState as? UpdateChecker.CheckResult.Available)?.let { available ->
                        RowDivider(startPadding = 52.dp)
                        AboutLinkRow(
                            icon = Icons.Rounded.Download,
                            title = "下载并安装 v" + available.release.version,
                            subtitle = if (downloading) {
                                "正在下载 " + (downloadTask?.percent ?: 0) + "% · 可在通知中暂停或取消"
                            } else {
                                "共 " + readableSize(available.release.apkSize) + " · 来自 GitHub Releases"
                            },
                            onClick = { startDownload(available.release) },
                        )
                    }
                }
            }
        } else {
            item {
                SettingGroup("版本") {
                    SettingItem(
                        title = "检查更新",
                        value = status,
                        arrow = !checking,
                        onClick = { checkUpdate() },
                    )
                    RowDivider(startPadding = 16.dp)
                    SettingItem(
                        title = "更新日志",
                        arrow = true,
                        onClick = onOpenReleases,
                    )
                    RowDivider(startPadding = 16.dp)
                    SettingSwitchItem(
                        title = "接收测试版更新",
                        checked = receiveBeta,
                        onCheckedChange = { enabled ->
                            if (enabled && !receiveBeta) {
                                showBetaConfirm = true
                            } else {
                                applyReceiveBeta(enabled)
                            }
                        },
                    )
                    (updateState as? UpdateChecker.CheckResult.Available)?.let { available ->
                        RowDivider(startPadding = 16.dp)
                        SettingItem(
                            title = "下载并安装 v" + available.release.version,
                            subtitle = if (downloading) {
                                "正在下载 " + (downloadTask?.percent ?: 0) + "% · 可在通知中暂停或取消"
                            } else {
                                "共 " + readableSize(available.release.apkSize) + " · 来自 GitHub Releases"
                            },
                            arrow = !downloading,
                            onClick = { startDownload(available.release) },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(14.dp)) }
            item {
                SettingGroup("更多") {
                    SettingItem(
                        "免责声明",
                        arrow = true,
                        onClick = { onOpenDisclaimer() },
                    )
                    RowDivider(startPadding = 16.dp)
                    SettingItem(
                        "项目主页",
                        "github.com/xunyyds/xpatch-manager",
                        arrow = true,
                        onClick = { runCatching { uriHandler.openUri(UpdateChecker.REPO_URL) } },
                    )
                    RowDivider(startPadding = 16.dp)
                    SettingItem(
                        "特别鸣谢",
                        "致谢项目",
                        arrow = true,
                        onClick = { onOpenAcknowledgements() },
                    )
                    RowDivider(startPadding = 16.dp)
                    SettingItem(
                        "开源许可",
                        "MIT 与第三方许可",
                        arrow = true,
                        onClick = { runCatching { uriHandler.openUri(UpdateChecker.REPO_URL + "/blob/main/LICENSE") } },
                    )
                    RowDivider(startPadding = 16.dp)
                    SettingItem(
                        "用户交流群",
                        "卡拉彼丘交流群",
                        arrow = true,
                        onClick = { runCatching { uriHandler.openUri("https://qm.qq.com/q/fLiqpUojq8") } },
                    )
                    RowDivider(startPadding = 16.dp)
                    SettingItem(
                        "反馈邮箱",
                        DisclaimerData.CONTACT,
                        arrow = true,
                        onClick = { runCatching { uriHandler.openUri("mailto:" + DisclaimerData.CONTACT) } },
                    )
                }
            }
        }
    }

    val available = updateState as? UpdateChecker.CheckResult.Available
    if (available != null && !downloading && updateError == null) {
        XPatchDialog(
            onDismiss = { updateState = null },
            title = "发现新版本 v" + available.release.version,
            actions = listOf(
                DialogAction("稍后再说", onClick = { updateState = null }),
                DialogAction("下载并安装") { startDownload(available.release) },
            ),
        ) {
            Text(
                "当前 v" + BuildConfig.VERSION_NAME + " → 最新 v" + available.release.version,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (available.release.notes.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                val blocks = rememberMarkdownBlocks(available.release.notes)
                if (blocks.isNotEmpty()) {
                    Column(
                        Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        MarkdownView(blocks = blocks)
                    }
                }
            }
        }
    }

    updateError?.let { message ->
        XPatchDialog(
            onDismiss = { updateError = null },
            title = "更新失败",
            actions = listOf(DialogAction("知道了", onClick = { updateError = null })),
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (showBetaConfirm) {
        XPatchDialog(
            onDismiss = { showBetaConfirm = false },
            title = "接收测试版更新",
            actions = listOf(
                DialogAction("取消", onClick = { showBetaConfirm = false }),
                DialogAction("启用") {
                    showBetaConfirm = false
                    applyReceiveBeta(true)
                },
            ),
        ) {
            Text("测试版不稳定，请谨慎更新。", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun updateShortStatus(
    checking: Boolean,
    state: UpdateChecker.CheckResult?,
): String? = when {
    checking -> "检查中"
    state is UpdateChecker.CheckResult.Available -> "v" + state.release.version
    state is UpdateChecker.CheckResult.UpToDate -> "已是最新"
    state is UpdateChecker.CheckResult.Failed -> "失败"
    else -> null
}

@Composable
private fun AboutHeader(appIcon: Drawable?) {
    val miuix = LocalMiuix.current
    if (miuix) {
        val primary = MiuixTheme.colorScheme.primary
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(appIcon, size = 96)
            Spacer(Modifier.height(16.dp))
            Text(
                "XPatch",
                style = MiuixTheme.textStyles.headline1,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AboutPill("v" + BuildConfig.VERSION_NAME, primary)
                AboutPill("MIT", primary)
            }
            Spacer(Modifier.height(12.dp))
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(appIcon, size = 72)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "XPatch",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "v" + BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AboutSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun AboutLinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    external: Boolean = false,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(xpShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AboutIconBadge(icon)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            if (external) Icons.Rounded.Language else Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AboutSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(xpShape(12.dp))
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 8.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AboutIconBadge(icon)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun AboutIconBadge(icon: ImageVector) {
    Box(
        Modifier
            .size(36.dp)
            .clip(ShapeTokens.badge)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun AboutPill(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier
            .clip(xpShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}