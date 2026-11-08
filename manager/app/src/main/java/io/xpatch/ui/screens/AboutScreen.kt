package io.xpatch.ui.screens

import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.xpatch.BuildConfig
import io.xpatch.data.DownloadCenter
import io.xpatch.data.UpdateChecker
import io.xpatch.patch.InstallHelper
import io.xpatch.ui.components.AppIcon
import io.xpatch.ui.components.DialogAction
import io.xpatch.ui.components.RowDivider
import io.xpatch.ui.components.SettingGroup
import io.xpatch.ui.components.SettingItem
import io.xpatch.ui.components.XPatchDialog
import io.xpatch.ui.components.MarkdownView
import io.xpatch.ui.components.rememberMarkdownBlocks
import io.xpatch.ui.format.readableSize
import io.xpatch.ui.theme.LocalMiuix
import io.xpatch.ui.util.rememberListBottomPadding
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AboutScreen(
    onOpenDisclaimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var updateState by remember { mutableStateOf<UpdateChecker.CheckResult?>(null) }
    var downloadTaskId by remember { mutableStateOf<Long?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }
    val downloadTasks by DownloadCenter.tasks.collectAsState()
    val downloadTask = downloadTaskId?.let { downloadTasks[it] }
    val downloading = downloadTask != null && !downloadTask.done && downloadTask.failed == null

    val appIcon = remember {
        runCatching { context.packageManager.getApplicationIcon(context.packageName) }.getOrNull() as? Drawable
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
        item { Spacer(Modifier.height(20.dp)) }
        item {
            SettingGroup("版本") {
                SettingItem(
                    title = "检查更新",
                    value = updateShortStatus(checking, updateState),
                    arrow = !checking,
                    onClick = { checkUpdate() },
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
                    "使用前请阅读并理解全部条款",
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
                    "内核与致谢",
                    "FPA 内核与第三方组件清单",
                    arrow = true,
                    onClick = { runCatching { uriHandler.openUri(UpdateChecker.REPO_URL + "/blob/main/ACKNOWLEDGEMENTS.md") } },
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
private fun AboutHeader(appIcon: Drawable?) {    val miuix = LocalMiuix.current
    val primary = if (miuix) MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary
    val hint = if (miuix) MiuixTheme.colorScheme.onSurfaceVariantSummary else MaterialTheme.colorScheme.onSurfaceVariant
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
            style = if (miuix) MiuixTheme.textStyles.headline1 else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AboutPill("v" + BuildConfig.VERSION_NAME, primary)
            AboutPill("MIT", primary)
            AboutPill("免 Root", primary)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "免 Root 的 Android 模块加载框架管理器",
            style = if (miuix) MiuixTheme.textStyles.footnote1 else MaterialTheme.typography.bodySmall,
            color = hint,
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
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
