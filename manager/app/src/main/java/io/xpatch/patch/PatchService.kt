package io.xpatch.patch

import android.content.Context
import io.xpatch.data.HookCore
import io.xpatch.data.PatchRecord
import io.xpatch.data.SettingsStore
import io.xpatch.data.SignatureStore
import io.xpatch.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class PatchOptions(
    val hookCore: HookCore = HookCore.XPLANT,
    val bypassLevel: Int = 3,
    val injectSignature: Boolean = true,
    val fileProvider: Boolean = false,
    val dynamicMode: Boolean = false,
    val signatureProfile: SignatureStore.Profile? = null,
    val versionName: String? = null,
    val versionCode: Int? = null,
    val embedSource: Boolean = false,
    val forceOverwrite: Boolean = false,
    val sigMode: String = "java",
    val entryHook: Boolean = false,
    val obfuscate: Boolean = false,
    val obfuscateKeepCrashPermission: Boolean = false,
)

class PatchService(private val context: Context) {

    data class Outcome(
        val apk: File,
        val packageName: String,
        val label: String,
        val bootDex: String,
        val originalFactory: String?,
        val config: String,
        
        val forceOverwrite: Boolean = false,
    )

    suspend fun build(
        app: AppInfo,
        options: PatchOptions,
        onLog: (String) -> Unit = {},
        onProgress: (Int) -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        onLog("准备源安装包：${app.label}")
        onProgress(2)
        onLog("定位已安装应用的 APK")
        onProgress(5)
        buildFrom(copyInstalledApk(app), app.packageName, app.label, options, onLog, onProgress)
    }

    private fun copyInstalledApk(app: AppInfo): File {
        val currentPath = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(app.packageName, 0).sourceDir
        }.getOrNull()

        val path = sequenceOf(currentPath, app.apkPath)
            .filterNotNull()
            .firstOrNull { it.isNotBlank() && File(it).exists() }
            ?: error("找不到 ${app.label} 的安装包，请确认应用已安装")

        return File(path)
    }

    suspend fun buildFromFile(
        apk: File,
        label: String,
        options: PatchOptions,
        onLog: (String) -> Unit = {},
        onProgress: (Int) -> Unit = {},
    ): Outcome {
        val packageName = context.packageManager
            .getPackageArchiveInfo(apk.absolutePath, 0)
            ?.packageName
            ?: error("无法读取 APK 信息")
        onLog("读取外部安装包：${apk.name}，${apk.length() / 1024 / 1024} MB")
        onProgress(5)
        return buildFrom(apk, packageName, label, options, onLog, onProgress)
    }

    private suspend fun buildFrom(
        source: File,
        packageName: String,
        label: String,
        options: PatchOptions,
        onLog: (String) -> Unit = {},
        onProgress: (Int) -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        require(source.exists()) { "找不到源 APK：${source.absolutePath}" }
        onLog("源文件 ${source.name}，${source.length() / 1024 / 1024} MB")

        val workDir = File(context.cacheDir, "patch").apply { mkdirs() }
        val unsigned = File(workDir, "$packageName-unsigned.apk")
        val signed = File(workDir, "$packageName-xpatch.apk")
        unsigned.delete()
        signed.delete()
        onLog("过签模式：" + SettingsStore.sigModeLabel(options.sigMode))
        val sigMode = options.sigMode.trim().lowercase()
        val embedSource = options.embedSource ||
            (sigMode != SettingsStore.SIG_MODE_JAVA && sigMode != SettingsStore.SIG_MODE_OFF) ||
            (options.bypassLevel == 1 && sigMode != SettingsStore.SIG_MODE_OFF)
        val dynamicMode = options.dynamicMode || embedSource
        val effectiveEmbed = embedSource || dynamicMode
        if (dynamicMode) {
            onLog("动态加载已开启（FPA 方式）：产物只保留原包 + 运行层，避免体积翻倍")
        }
        if (effectiveEmbed) {
            onLog(
                if (options.embedSource || dynamicMode) {
                    "已内嵌原始安装包（动态加载/按设置）"
                } else {
                    "已内嵌原始安装包（文件层需要）"
                },
            )
        }
        if (options.entryHook && !dynamicMode) {
            onLog("入口 Hook 已开启（实验）：额外插入 io.xpatch.stub.XpProvider 备用入口，工厂注入保持不变")
        } else if (options.entryHook) {
            onLog("动态加载：provider 备用入口已关闭（切换后 stub 不在原包里），仅保留工厂注入")
        }
        if (options.obfuscate) {
            onLog("注入路径混淆已开启：条目名与清单类名随机化，产物里不再出现 xp/ 条目名与 io.xpatch.stub")
            if (options.obfuscateKeepCrashPermission) {
                onLog("保留模块熔断权限：清单里会留下 io.xpatch.permission.MODULE_CRASH")
            } else {
                onLog("熔断权限未注入（路径混淆默认丢弃 io.xpatch.permission.MODULE_CRASH）：模块崩溃时管理器收不到广播")
            }
        } else {
            onLog("注入路径混淆已关闭：沿用 xp/ 固定条目名与 io.xpatch.stub 类名")
        }
        val result = ApkPatcher(context).patch(
            source,
            unsigned,
            ApkPatcher.Options(
                bypassLevel = options.bypassLevel,
                hookCore = options.hookCore.index,
                dynamicMode = dynamicMode,
                fileProvider = options.fileProvider,
                injectSignature = options.injectSignature,
                versionName = options.versionName,
                versionCode = options.versionCode,
                embedSource = effectiveEmbed,
                sigMode = options.sigMode,
                entryHook = options.entryHook,
                obfuscate = options.obfuscate,
                obfuscateKeepCrashPermission = options.obfuscateKeepCrashPermission,
            ),
            onProgress = onProgress,
            onLog = onLog,
        )
        result.layout?.let { layout -> onLog("产物布局：${layout.dir}（config ${layout.configEntry}）") }

        val profile = if (options.injectSignature) {
            options.signatureProfile ?: SignatureStore(context).defaultProfile()
        } else {
            null
        }
        if (profile != null && profile.kind != SignatureStore.Kind.BUILTIN) {
            onLog("提示：非内置签名档下，模块保护通知可能无法送达管理器（signature 权限不跨证书授予）")
        }
        if (profile != null) {
            onLog("重打包完成，开始签名")
            onProgress(85)
            ApkSigner(context).sign(unsigned, signed, profile)
            onLog("已用「${profile.name}」完成 V2+V3 签名")
            onProgress(92)
        } else {
            onLog("未签名产物通常无法安装；如需安装请选择签名")
            onProgress(92)
            unsigned.copyTo(signed, overwrite = true)
        }
        unsigned.delete()

        onProgress(95)
        val delivered = File(outputDir().apply { mkdirs() }, "$label-$packageName.apk")
        runCatching { signed.copyTo(delivered, overwrite = true) }
            .onSuccess { onLog("产物已保存：${delivered.absolutePath}") }
            .onFailure { onLog("产物另存失败，保留缓存：${signed.absolutePath}") }

        Outcome(
            apk = if (delivered.exists()) delivered else signed,
            packageName = packageName,
            label = label,
            bootDex = result.bootDexName,
            originalFactory = result.originalFactory,
            config = result.config,
            forceOverwrite = options.forceOverwrite,
        )
    }

    fun install(outcome: Outcome): Int =
        PatchInstaller(context).install(outcome.apk, outcome.packageName)

    fun installFromFile(apk: File, packageName: String): Int =
        PatchInstaller(context).install(apk, packageName)

    fun export(outcome: Outcome): File {
        val dir = outputDir().apply { mkdirs() }
        val target = uniqueTarget(dir, "${outcome.label}-${outcome.packageName}.apk")
        if (target.absolutePath != outcome.apk.absolutePath) {
            outcome.apk.copyTo(target, overwrite = false)
        }
        return target
    }

    fun outcomeOf(record: PatchRecord): Outcome = Outcome(
        apk = File(record.apkPath),
        packageName = record.packageName,
        label = record.label,
        bootDex = record.bootDex,
        originalFactory = record.originalFactory,
        config = record.config,
        forceOverwrite = record.forceOverwrite,
    )

    private fun uniqueTarget(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "$stem($index)$ext")
            index++
        }
        return candidate
    }

    fun outputDir(): File {
        val preferred = File(
            android.os.Environment.getExternalStorageDirectory(),
            "Android/data/${context.packageName}/files/输出",
        )
        if (runCatching { preferred.mkdirs(); preferred.isDirectory && preferred.canWrite() }.getOrDefault(false)) {
            return preferred
        }
        return File(context.getExternalFilesDir(null), "输出")
    }

    private companion object
}
