package io.xpatch.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.xpatch.data.PermissionCenter
import io.xpatch.patch.ShizukuBridge
import io.xpatch.ui.theme.LocalMiuix
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun OnboardingScreen(
    onAcceptDisclaimer: () -> Unit,
    onComplete: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val miuix = LocalMiuix.current
    var step by remember { mutableIntStateOf(0) }
    var deniedRuntime by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val items = remember(refreshTick) {
        PermissionCenter.required(
            context,
            shizukuAvailable = ShizukuBridge.isAvailable(),
            shizukuGranted = ShizukuBridge.hasPermission(),
        )
    }
    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { granted -> !granted }) deniedRuntime = true
        refreshTick++
    }

    fun handleItem(item: PermissionCenter.Item) {
        when (item.kind) {
            PermissionCenter.Kind.RUNTIME ->
                runtimeLauncher.launch(PermissionCenter.storageRuntimePermissions())

            PermissionCenter.Kind.SHIZUKU -> if (ShizukuBridge.isAvailable()) {
                ShizukuBridge.requestPermission { refreshTick++ }
            } else {
                Toast.makeText(context, "未检测到 Shizuku，可先跳过", Toast.LENGTH_SHORT).show()
            }

            else -> when (item.id) {
                PermissionCenter.Id.ALL_FILES -> PermissionCenter.openAllFiles(context)
                PermissionCenter.Id.INSTALL -> PermissionCenter.openInstall(context)
                else -> PermissionCenter.openAppDetails(context)
            }
        }
    }

    val background = if (miuix) MiuixTheme.colorScheme.background else MaterialTheme.colorScheme.background
    Column(
        Modifier
            .fillMaxSize()
            .background(background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Text(
            if (step == 0) "欢迎使用 XPatch" else "所需权限",
            style = if (miuix) MiuixTheme.textStyles.headline1 else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (step == 0) {
                "开始使用前，请先阅读并同意免责声明"
            } else {
                "以下权限用于核心功能，你也可以先跳过，之后在系统设置里统一开启"
            },
            style = if (miuix) MiuixTheme.textStyles.footnote1 else MaterialTheme.typography.bodySmall,
            color = if (miuix) MiuixTheme.colorScheme.onSurfaceVariantSummary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Box(Modifier.weight(1f)) {
            if (step == 0) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    DisclaimerIntroCard()
                    Spacer(Modifier.height(24.dp))
                    DisclaimerData.sections.forEachIndexed { index, section ->
                        DisclaimerSectionHeader(index + 1, section.title)
                        Spacer(Modifier.height(12.dp))
                        DisclaimerParagraphs(section)
                        Spacer(Modifier.height(24.dp))
                    }
                    DisclaimerFooter()
                    Spacer(Modifier.height(12.dp))
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items.forEach { item ->
                        PermissionRow(
                            item = item,
                            onAction = { handleItem(item) },
                        )
                    }
                    if (deniedRuntime) {
                        Text(
                            "你拒绝了部分权限，应用内不会再反复弹窗；需要时请前往系统设置统一开启。",
                            style = if (miuix) MiuixTheme.textStyles.footnote1 else MaterialTheme.typography.bodySmall,
                            color = if (miuix) MiuixTheme.colorScheme.error else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step == 0) {
                OnboardingButton("不同意并退出", primary = false, Modifier.weight(1f), onClick = onExit)
                OnboardingButton("同意并继续", primary = true, Modifier.weight(1f)) {
                    onAcceptDisclaimer()
                    step = 1
                }
            } else {
                OnboardingButton("跳过", primary = false, Modifier.weight(1f), onClick = onComplete)
                OnboardingButton("完成", primary = true, Modifier.weight(1f), onClick = onComplete)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PermissionRow(
    item: PermissionCenter.Item,
    onAction: () -> Unit,
) {
    val miuix = LocalMiuix.current
    val card = if (miuix) MiuixTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainer
    val granted = item.granted
    val grantedColor = if (miuix) MiuixTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.tertiaryContainer
    val grantedOn = if (miuix) MiuixTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onTertiaryContainer
    val hintColor = if (miuix) MiuixTheme.colorScheme.onSurfaceVariantSummary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (miuix) 16.dp else 20.dp))
            .background(card)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = if (miuix) MiuixTheme.textStyles.body1 else MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                item.reason,
                style = if (miuix) MiuixTheme.textStyles.footnote1 else MaterialTheme.typography.bodySmall,
                color = if (miuix) MiuixTheme.colorScheme.onSurfaceVariantSummary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (granted) grantedColor else Color.Transparent)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                if (granted) "已授权" else "未授权",
                style = if (miuix) MiuixTheme.textStyles.footnote2 else MaterialTheme.typography.labelSmall,
                color = if (granted) grantedOn else hintColor,
            )
        }
        if (!granted) {
            Spacer(Modifier.width(10.dp))
            OnboardingTextAction(
                label = when (item.kind) {
                    PermissionCenter.Kind.RUNTIME -> "申请"
                    PermissionCenter.Kind.SHIZUKU -> "去授权"
                    else -> "去设置"
                },
                onClick = onAction,
            )
        }
    }
}

@Composable
private fun OnboardingTextAction(label: String, onClick: () -> Unit) {
    val miuix = LocalMiuix.current
    val color = if (miuix) MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary
    androidx.compose.material3.TextButton(onClick = onClick) {
        Text(label, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun OnboardingButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (LocalMiuix.current) {
        if (primary) {
            MiuixButton(onClick = onClick, modifier = modifier.height(48.dp)) {
                MiuixText(label, style = MiuixTheme.textStyles.button)
            }
        } else {
            MiuixTextButton(text = label, onClick = onClick, modifier = modifier.height(48.dp))
        }
        return
    }
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier.height(48.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(48.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(label)
        }
    }
}
