package io.xpatch.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import io.xpatch.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EnvInfoCollector {

    fun collect(context: Context, extras: Map<String, String> = emptyMap()): String = buildString {
        appendLine("XPatch 环境信息（用于问题排查，可整份发给开发者）")
        appendLine("生成时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        appendLine()
        appendLine("== 应用 ==")
        appendLine("版本: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
        appendLine("包名: " + context.packageName)
        appendLine()
        appendLine("== 设备 ==")
        appendLine("品牌: " + Build.BRAND)
        appendLine("制造商: " + Build.MANUFACTURER)
        appendLine("型号: " + Build.MODEL)
        appendLine("设备: " + Build.DEVICE + " / " + Build.PRODUCT)
        appendLine()
        appendLine("== 系统 ==")
        appendLine("Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")")
        appendLine("安全补丁: " + Build.VERSION.SECURITY_PATCH)
        appendLine("指纹: " + Build.FINGERPRINT)
        appendLine("内核: " + (System.getProperty("os.version") ?: "未知"))
        appendLine("虚拟机: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"))
        appendLine("ABI: " + Build.SUPPORTED_ABIS.joinToString(", "))
        appendLine()
        appendLine("== 显示 ==")
        val metrics = context.resources.displayMetrics
        val configuration = context.resources.configuration
        appendLine("分辨率: " + metrics.widthPixels + "x" + metrics.heightPixels)
        appendLine("密度: " + metrics.densityDpi + "dpi (" + metrics.density + "x)")
        appendLine("字体缩放: " + configuration.fontScale)
        appendLine(
            "方向: " + if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                "横屏"
            } else {
                "竖屏"
            },
        )
        appendLine()
        appendLine("== 权限 ==")
        appendLine(
            "所有文件访问: " + if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
            ) {
                "已授权"
            } else {
                "未授权"
            },
        )
        appendLine(
            "安装未知应用: " + if (context.packageManager.canRequestPackageInstalls()) "已授权" else "未授权",
        )
        if (PermissionCenter.storageRuntimeNeeded()) {
            val read = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
            appendLine("存储读取(运行时): " + if (read) "已授权" else "未授权")
        }
        appendLine()
        appendLine("== 设置 ==")
        val settings = SettingsStore(context).load()
        appendLine("界面风格: " + settings.uiStyle.label)
        appendLine(
            "主题: " + when (settings.themeMode) {
                SettingsStore.THEME_LIGHT -> "浅色"
                SettingsStore.THEME_DARK -> "深色"
                else -> "跟随系统"
            },
        )
        appendLine("Hook 内核: " + settings.hookCore.label)
        appendLine("签名绕过等级: " + settings.signatureBypassLevel)
        appendLine("注入签名: " + onOff(settings.injectSignature))
        appendLine("动态模式: " + onOff(settings.dynamicMode))
        appendLine("文件提供器: " + onOff(settings.fileProvider))
        appendLine(
            "底栏样式: " + if (settings.bottomBarStyle == SettingsStore.BOTTOM_BAR_LIQUID) "液态" else "标准",
        )
        appendLine("打包历史上限: " + settings.patchHistoryLimit)
        appendLine()
        if (extras.isNotEmpty()) {
            appendLine("== 运行状态 ==")
            extras.forEach { (key, value) -> appendLine(key + ": " + value) }
            appendLine()
        }
        appendLine("== 崩溃 ==")
        val crash = CrashRecorder.lastCrashSummary(context)
        if (crash == null) {
            appendLine("无崩溃记录")
        } else {
            appendLine(crash)
        }
    }

    private fun onOff(value: Boolean): String = if (value) "开" else "关"
}
