package io.xpatch.patch

import android.content.Context
import io.xpatch.data.ExportStore
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
    val signatureProfile: SignatureStore.Profile? = null,
    val versionName: String? = null,
    val versionCode: Int? = null,
    val embedSource: Boolean = false,
    val forceOverwrite: Boolean = false,
    val sigMode: String = "java",
    val obfuscate: Boolean = false,
    val obfuscateKeepCrashPermission: Boolean = false,
    val logLevel: Int = PatchLog.LEVEL_NORMAL,
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

    data class ExportResult(val path: String, val name: String, val uri: String, val size: Long)

    suspend fun build(
        app: AppInfo,
        options: PatchOptions,
        onLog: (String) -> Unit = {},
        onProgress: (Int) -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        onProgress(2)
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

    private fun rejectIfNoArm64(source: File, log: PatchLog) {
        val abis = runCatching {
            java.util.zip.ZipFile(source).use { zip ->
                val found = LinkedHashSet<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    if (!name.startsWith("lib/")) continue
                    val rest = name.substring(4)
                    val slash = rest.indexOf('/')
                    if (slash <= 0) continue
                    found.add(rest.substring(0, slash))
                }
                found
            }
        }.getOrNull() ?: return
        if (abis.isEmpty()) return
        if (abis.any { it == "arm64-v8a" || it == "x86_64" }) return
        log.step("宿主只有 32 位原生库：${abis.joinToString("，")}")
        error(
            "不支持只有 32 位原生库的宿主：内核只提供 arm64-v8a 与 x86_64 两份 hook_core。" +
                "在该宿主上打包会成功但 hook 全部失效（System.load 失败）。请改用 64 位版本的安装包。",
        )
    }

    private fun rejectSplit(source: File, packageName: String, log: PatchLog) {
        val info = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(packageName, 0)
        }.getOrNull() ?: return
        val sourcePath = runCatching { info.sourceDir }.getOrNull()
        if (sourcePath == null || File(sourcePath).canonicalPath != source.canonicalFile.path) {
            return
        }
        val splits = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 21) info.splitSourceDirs else null
        }.getOrNull()
        if (splits.isNullOrEmpty()) return
        log.step("检测到 split 安装包（共 ${splits.size + 1} 个 APK）：${splits.take(3).joinToString("，")}")
        error(
            "不支持对 split 安装包打包：当前只处理单 APK 宿主。" +
                "内嵌原包只有 base 会缺 ABI split 的原生库与密度/语言资源，装完必然 UnsatisfiedLinkError 或 ResourcesNotFoundException。" +
                "请改用原始的完整 APK 文件（从安装包来源获取）后再试。",
        )
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
        val log = PatchLog.of(onLog, options.logLevel)
        require(source.exists()) { "找不到源 APK：${source.absolutePath}" }
        log.debug("apk path: ${source.absolutePath}")
        log.debug(
            "过签模式：${SettingsStore.sigModeLabel(options.sigMode)} · " +
                "档位：${BypassTier.of(options.sigMode, options.bypassLevel).label}",
        )
        rejectSplit(source, packageName, log)
        rejectIfNoArm64(source, log)
        log.milestone("源 ${source.name} · ${PatchLog.mb(source.length())}")

        val workDir = File(context.cacheDir, "patch").apply { mkdirs() }
        val unsigned = File(workDir, "$packageName-unsigned.apk")
        val signed = File(workDir, "$packageName-xpatch.apk")
        unsigned.delete()
        signed.delete()
        val tier = BypassTier.of(options.sigMode, options.bypassLevel)
        val embedSource = options.embedSource || tier.needsSource
        val effectiveEmbed = embedSource
        if (effectiveEmbed) {
            log.milestone("内嵌原包（嵌套链接，宿主条目共享原包字节，不翻倍）")
        }
        val result = try {
            ApkPatcher(context).patch(
                source,
                unsigned,
                ApkPatcher.Options(
                    bypassLevel = options.bypassLevel,
                    hookCore = options.hookCore.index,
                    fileProvider = options.fileProvider,
                    injectSignature = options.injectSignature,
                    versionName = options.versionName,
                    versionCode = options.versionCode,
                    embedSource = effectiveEmbed,
                    sigMode = options.sigMode,
                    obfuscate = options.obfuscate,
                    obfuscateKeepCrashPermission = options.obfuscateKeepCrashPermission,
                ),
                onProgress = onProgress,
                onLog = log,
            )
        } catch (error: Throwable) {
            unsigned.delete()
            signed.delete()
            throw error
        }
        result.layout?.let { layout -> log.step("布局 ${layout.dir} · config ${layout.configEntry}") }
        log.debug("未签名产物 ${unsigned.length()} B")

        val profile = if (options.injectSignature) {
            options.signatureProfile ?: SignatureStore(context).defaultProfile()
        } else {
            null
        }
        if (profile != null && profile.kind != SignatureStore.Kind.BUILTIN) {
            log.step("非内置签名档：模块保护通知可能无法送达管理器（signature 权限不跨证书授予）")
        }
        try {
            if (profile != null) {
                onProgress(85)
                val signStart = System.currentTimeMillis()
                ApkSigner(context).sign(unsigned, signed, profile)
                log.milestone(
                    "签名 V2+V3（${profile.name}）· ${PatchLog.secs(System.currentTimeMillis() - signStart)}",
                )
                log.debug("签名产物 ${signed.length()} B")
                onProgress(92)
            } else {
                log.debug("未签名产物通常无法安装；如需安装请选择签名")
                onProgress(92)
                if (!unsigned.renameTo(signed)) {
                    unsigned.copyTo(signed, overwrite = true)
                    unsigned.delete()
                }
            }
        } catch (error: Throwable) {
            unsigned.delete()
            signed.delete()
            throw error
        }
        unsigned.delete()

        onProgress(95)
        val delivered = File(outputDir().apply { mkdirs() }, "$label-$packageName.apk")
        val copied = runCatching { signed.copyTo(delivered, overwrite = true) }
        if (copied.isSuccess) {
            log.milestone("完成。输出 APK：${delivered.absolutePath}")
        } else {
            log.step("产物另存失败，保留缓存：${signed.absolutePath}")
        }

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

    suspend fun export(outcome: Outcome): ExportResult = withContext(Dispatchers.IO) {
        val fileName = "${outcome.label}-${outcome.packageName}.apk"
        val chosen = ExportStore(context).savedTarget()
        if (chosen != null) {
            val saved = runCatching {
                ExportStore(context).save(outcome.apk, fileName, chosen.uri)
            }.getOrNull()
            if (saved != null) {
                return@withContext ExportResult(
                    path = saved.path + "/" + saved.name,
                    name = saved.name,
                    uri = saved.uri,
                    size = saved.size,
                )
            }
        }
        val dir = outputDir().apply { mkdirs() }
        val target = uniqueTarget(dir, fileName)
        if (target.absolutePath != outcome.apk.absolutePath) {
            outcome.apk.copyTo(target, overwrite = false)
        }
        ExportResult(path = target.absolutePath, name = target.name, uri = "", size = target.length())
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
