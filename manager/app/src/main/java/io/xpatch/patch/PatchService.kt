package io.xpatch.patch

import android.content.Context
import io.xpatch.data.HookCore
import io.xpatch.data.SignatureStore
import io.xpatch.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class PatchOptions(
    val hookCore: HookCore = HookCore.XPLANT,
    val bypassLevel: Int = 1,
    val injectSignature: Boolean = true,
    val fileProvider: Boolean = true,
    val dynamicMode: Boolean = false,
    val signatureProfile: SignatureStore.Profile? = null,
    val versionName: String? = null,
    val versionCode: Int? = null,
    val embedSource: Boolean = false,
)

class PatchService(private val context: Context) {

    data class Outcome(
        val apk: File,
        val packageName: String,
        val label: String,
        val bootDex: String,
        val originalFactory: String?,
        val config: String,
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

    /** 从已注入的应用包中取出内嵌的原始 APK（更新核心用）。 */
    suspend fun extractOriginalApk(app: AppInfo): File? = withContext(Dispatchers.IO) {
        val installed = copyInstalledApk(app)
        runCatching {
            java.util.zip.ZipFile(installed).use { zip ->
                val entry = SOURCE_ENTRIES.firstNotNullOfOrNull { zip.getEntry(it) } ?: return@use null
                val dir = File(context.cacheDir, "input").apply { mkdirs() }
                val target = File(dir, "${app.packageName}-source.apk")
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target
            }
        }.getOrNull()
    }

    /** 定位已安装应用的 APK 路径，直接使用，不再整体复制。 */
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
        )
    }

    fun install(outcome: Outcome): Int =
        PatchInstaller(context).install(outcome.apk, outcome.packageName)

    fun installFromFile(apk: File, packageName: String): Int =
        PatchInstaller(context).install(apk, packageName)

    fun export(outcome: Outcome): File {
        val dir = outputDir().apply { mkdirs() }
        val target = File(dir, "${outcome.label}-${outcome.packageName}.apk")
        outcome.apk.copyTo(target, overwrite = true)
        return target
    }

    /**
     * 产物目录：优先 Android/data/<pkg>/files/输出（所有文件管理器和第三方 App
     * 都能直接读写），不可用时退回应用私有外部目录。
     */
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

    private companion object {
        // 顺序：本项目优先，其次历史命名空间，最后第三方补丁工具（NPatch / LSPatch）
        // 的内嵌原始包布局，保证「更新核心」从任意来源产物都能取到原始 APK。
        val SOURCE_ENTRIES = listOf(
            "xp/source.apk",
            "fpa/source.apk",
            "xp/o_app.apk",
            "assets/npatch/origin.apk",
            "lspatch/origin.apk",
            "assets/lspatch/origin.apk",
        )
    }
}


