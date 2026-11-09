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
        onLog("签名校验：" + SettingsStore.sigModeLabel(options.sigMode))
        if (options.entryHook) {
            onLog("入口 Hook 已开启（实验）：不改清单，需重新打包后生效")
        }
        val result = ApkPatcher(context).patch(
            source,
            unsigned,
            ApkPatcher.Options(
                bypassLevel = options.bypassLevel,
                hookCore = options.hookCore.index,
                dynamicMode = options.dynamicMode,
                fileProvider = options.fileProvider,
                injectSignature = options.injectSignature,
                versionName = options.versionName,
                versionCode = options.versionCode,
                embedSource = options.embedSource,
                sigMode = options.sigMode,
                entryHook = options.entryHook,
            ),
            onProgress = onProgress,
            onLog = onLog,
        )

        val profile = if (options.injectSignature) {
            options.signatureProfile ?: SignatureStore(context).defaultProfile()
        } else {
            null
        }
        if (profile != null) {
            onLog("重打包完成，开始签名")
            onProgress(85)
            ApkSigner(context).sign(unsigned, signed, profile)
            onLog("已用「${profile.name}」完成 V2+V3 签名")
            onProgress(92)
        } else {
            onLog("跳过签名，产物保持原签名")
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
