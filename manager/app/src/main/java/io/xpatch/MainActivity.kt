package io.xpatch

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.xpatch.data.EventLog
import io.xpatch.data.ScopeFileWriter
import io.xpatch.data.SettingsStore
import io.xpatch.ui.components.DialogAction
import io.xpatch.ui.components.XPatchDialog
import io.xpatch.ui.screens.MainScaffold
import io.xpatch.ui.theme.LocalMiuix
import io.xpatch.ui.theme.XPatchTheme

class MainActivity : ComponentActivity() {

    private var storageGranted by mutableStateOf(true)

    private var scopeSyncThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settingsStore = SettingsStore(this)
        val initialSettings = settingsStore.load()
        setContent {
            var themeMode by remember { mutableIntStateOf(initialSettings.themeMode) }
            var uiStyle by remember { mutableStateOf(initialSettings.uiStyle) }
            XPatchTheme(themeMode = themeMode, uiStyle = uiStyle) {
                MainScaffold(
                    onThemeModeChange = { mode ->
                        settingsStore.setThemeMode(mode)
                        themeMode = mode
                    },
                    onUiStyleChange = { style ->
                        settingsStore.setUiStyle(style)
                        uiStyle = style
                    },
                )
                StorageAccessGate(
                    granted = { storageGranted },
                    onOpenSettings = { openAllFilesSettings() },
                    onDismiss = { storageGranted = true },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        storageGranted = granted
        if (granted) {
            val running = scopeSyncThread
            if (running == null || !running.isAlive) {
                scopeSyncThread = Thread {
                    runCatching {
                        Thread.sleep(3000)
                        ScopeFileWriter(applicationContext).syncAll { message ->
                            runCatching { EventLog(applicationContext).add(message) }
                        }
                    }
                }.also { it.start() }
            }
        }
    }

    private fun openAllFilesSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }
}

@Composable
private fun StorageAccessGate(
    granted: () -> Boolean,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!granted()) {
        AllFilesAccessDialog(onOpenSettings = onOpenSettings, onDismiss = onDismiss)
    }
}

@Composable
private fun AllFilesAccessDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    XPatchDialog(
        onDismiss = onDismiss,
        title = "需要所有文件访问权限",
        actions = listOf(
            DialogAction("稍后", onClick = onDismiss),
            DialogAction("前往授权", onClick = onOpenSettings),
        ),
        miuix = LocalMiuix.current,
    ) {
        Text(
            "XPatch 需要该权限把模块作用域写入目标应用的 Android/media 目录，否则注入后的应用读不到作用域。请先在系统设置里授权。",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
