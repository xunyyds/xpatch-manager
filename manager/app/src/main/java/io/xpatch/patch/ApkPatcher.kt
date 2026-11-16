package io.xpatch.patch

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import io.xpatch.patch.obfuscate.InjectLayout
import io.xpatch.patch.obfuscate.InjectedEntrySet
import io.xpatch.patch.obfuscate.Logical
import io.xpatch.patch.obfuscate.StubRewriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.ZipFile

class ApkPatcher(private val context: Context) {

    private val strippedBootDex = LinkedHashSet<String>()

    data class Options(
        val bypassLevel: Int = 3,
        val hookCore: Int = 0,
        val fileProvider: Boolean = false,
        val injectSignature: Boolean = true,
        val versionName: String? = null,
        val versionCode: Int? = null,
        val embedSource: Boolean = false,
        val sigMode: String = "java",
        val obfuscate: Boolean = false,
        val obfuscateKeepCrashPermission: Boolean = false,
    )

    data class Result(
        val output: File,
        val originalFactory: String?,
        val bootDexName: String,
        val config: String,
        val layout: InjectLayout? = null,
    )

    fun patch(
        sourceApk: File,
        outputApk: File,
        options: Options,
        onProgress: (Int) -> Unit = {},
        onLog: PatchLog = PatchLog.silent(),
    ): Result {
        val log = onLog
        log.debug("apk path: ${sourceApk.absolutePath}")
        log.step("解析原始 apk...")
        onProgress(10)

        var effectiveSource = sourceApk
        var entries: List<Entry>
        var manifest: ByteArray
        var originalManifest: ByteArray
        val originalFactory: String?
        val temps = ArrayList<File>()

        val parseStart = System.currentTimeMillis()
        RandomAccessFile(sourceApk, "r").use { source ->
            entries = readCentralDirectory(source, sourceApk.length())
        }
        log.milestone("清单 ${entries.size} 条目 · ${PatchLog.secs(System.currentTimeMillis() - parseStart)}")

        var strip = discoverLayout(sourceApk, entries, log)
        val outerLayout = strip.layout
        val injectedBefore = entries.any { strip.isInjected(it.name) || strip.isEmbeddedSource(it.name) }
        val beforeBootDex = entries.size
        strippedBootDex.clear()
        RandomAccessFile(sourceApk, "r").use { source ->
            entries = dropStaleBootDex(source, entries, log, strippedBootDex)
        }
        val hadInjected = injectedBefore || entries.size != beforeBootDex
        val ourStubFactories = buildSet {
            addAll(LEGACY_STUB_CLASSES)
            outerLayout?.factoryDashed?.takeIf { it.isNotBlank() }?.let { add(it) }
        }

        val recordedEntry = outerLayout?.entry(Logical.SOURCE_MANIFEST) ?: ORIGINAL_MANIFEST_ENTRY
        val recordedManifest = runCatching {
            RandomAccessFile(sourceApk, "r").use { src ->
                entries.firstOrNull { it.name == recordedEntry }?.let { readEntry(src, it) }
            }
        }.getOrNull()

        var depth = 0
        while (
            depth < MAX_STRIP_DEPTH &&
            (
                entries.any { strip.isInjected(it.name) || strip.isEmbeddedSource(it.name) } ||
                    oversizedNested(entries, effectiveSource.length()).isNotEmpty()
                )
        ) {
            val legacyFamily = if (strip.layout == null) legacyFamily(entries) else null
            val hasTrace = entries.any { strip.isInjected(it.name) || strip.isEmbeddedSource(it.name) }
            log.step(
                if (depth == 0) {
                    when {
                        strip.layout != null -> "检测到本框架混淆产物，先剥离再重新打包"
                        legacyFamily != null -> "检测到历史注入层，剥离后重新打包"
                        hasTrace -> "检测到源包已注入，剥离旧内核层"
                        else -> "检测到大体积内嵌包，尝试剥离原始包"
                    }
                } else {
                    "第 ${depth + 1} 层仍有注入痕迹，继续剥离"
                },
            )
            if (depth == 0 && legacyFamily != null) {
                log.step("源包可能由 $legacyFamily 旧签名签发，覆盖安装需选择对应签名或卸载后重装")
            }
            val stripped = try {
                stripInjected(effectiveSource, entries, strip, log, strip.sourceEntry)
            } catch (ambiguous: EmbeddedSource.AmbiguousException) {
                log.step("内嵌来源歧义，停止打包：${ambiguous.message}")
                throw ambiguous
            }
            val extracted = stripped.file
            if (extracted == null) {
                if (stripped.rejected.isNotEmpty()) {
                    log.step("内嵌来源候选全部未通过验证（共 ${stripped.candidates.size} 个候选）")
                }
                if (stripped.unproven.isNotEmpty() && options.embedSource) {
                    error(
                        "无法证明内嵌包是原始包，请改用原始 APK：外层有同包名内嵌包但内容对不上（" +
                            stripped.unproven.take(3).joinToString("，") + "）",
                    )
                }
                log.step("第 ${depth + 1} 层无法提取内嵌原始包，仅移除注入条目")
                entries = dropOverlappingNested(
                    effectiveSource,
                    entries.filter { !strip.isInjected(it.name) },
                )
                break
            }
            temps += extracted
            effectiveSource = extracted
            RandomAccessFile(effectiveSource, "r").use { source ->
                entries = readCentralDirectory(source, effectiveSource.length())
            }
            depth++
            if (depth < MAX_STRIP_DEPTH) {
                strip = discoverLayout(effectiveSource, entries, log)
            }
        }

        if (effectiveSource != sourceApk) {
            log.milestone("剥离 $depth 层 · 源 ${PatchLog.mb(effectiveSource.length())}")
        }
        RandomAccessFile(effectiveSource, "r").use { source ->
            entries = dropStaleBootDex(source, entries, log, strippedBootDex)
        }

        val inputManifest: ByteArray
        val inputFactory: String?
        RandomAccessFile(effectiveSource, "r").use { source ->
            val manifestEntry = entries.firstOrNull { it.name == MANIFEST }
                ?: error("APK 缺少 AndroidManifest.xml")
            inputManifest = readEntry(source, manifestEntry)
            val editor = AxmlEditor(inputManifest)
            val manifestPackage = editor.readAttribute("manifest", "package").orEmpty()
            originalManifest = pickOriginalManifest(
                recorded = if (effectiveSource == sourceApk) recordedManifest else null,
                input = inputManifest,
                expectedPackage = manifestPackage,
                log = log,
                ourStubs = ourStubFactories,
            )
            inputFactory = sanitizeFactory(
                editor.readApplicationAttribute(ATTR_FACTORY_NAME),
                log,
                ourStubFactories,
            )
        }

        val signedSource = effectiveSource
        val rebuilt = options.embedSource && hadInjected && effectiveSource == sourceApk
        if (rebuilt) {
            error(
                "输入是旧补丁产物，且外层没有可提取的内嵌原包：重建出来的安装包没有原始签名块，" +
                    "内嵌档位的原生层校验会把它当成篡改。请改用原始 APK（或先卸载重装原版）重新打包。",
            )
        }
        assertClean(entries, effectiveSource, log, strip, options.embedSource)
        val embeddedSourceLength = effectiveSource.length()

        if (entries.none { isBootDexName(it.name) }) {
            error(
                "宿主代码已丢失，拒绝打包：输入的补丁产物里没有任何宿主 dex，" +
                    "剥离旧注入层后没有可恢复的原始代码，继续打包只会得到无法启动的空壳。" +
                    "请改用原始 APK 重新打包。",
            )
        }

        val bootDexIndex = nextBootDexIndex(entries)
        val bootDexName = if (bootDexIndex <= 1) "classes.dex" else "classes$bootDexIndex.dex"

        val obfuscationSeed = if (options.obfuscate) SecureRandom().nextLong() else 0L
        val stubAsset = asset("xp/stub.dex")
        var stub = if (options.obfuscate) {
            StubRewriter.rewrite(
                dex = stubAsset,
                seed = obfuscationSeed,
                preferredFactory = outerLayout?.factoryDashed,
                preferredProvider = outerLayout?.providerDashed,
            )
        } else {
            StubRewriter.skipped(stubAsset, null)
        }
        val layout = if (!options.obfuscate) {
            null
        } else {
            runCatching {
                InjectLayout.generate(
                    entries = entries.map { it.name to it.uncompressedSize },
                    bootDex = bootDexName,
                    includeSource = options.embedSource,
                    factoryDashed = stub.factoryDashed,
                    providerDashed = null,
                    seed = obfuscationSeed,
                )
            }.mapCatching { generated ->
                if (hasAsset("xp/hook_core_x86_64.so") || generated.entry(Logical.HOOK_SO_X86_64) == null) {
                    generated
                } else {
                    generated.copy(entries = generated.entries - Logical.HOOK_SO_X86_64)
                }
            }.onFailure { log.step("生成注入布局失败（${it.message}），本次按不混淆打包") }.getOrNull()
        }
        if (options.obfuscate && layout == null) {
            stub = StubRewriter.skipped(stubAsset, "注入布局生成失败")
        }
        if (layout != null) {
            log.milestone("混淆布局 ${layout.dir} · 类名 ${layout.factoryDashed}")
            if (!stub.renamed) {
                log.step("类名混淆降级，清单仍指向 ${stub.factoryDashed}（${stub.reason}）")
            } else if (outerLayout != null) {
                log.debug("复用上一版类名：${layout.factoryDashed}")
            }
        } else if (options.obfuscate) {
            log.step("布局生成失败，本次按旧名字打包")
        }

        val manifestBase = inputManifest
        val editor = AxmlEditor(manifestBase)
        val manifestPackage = editor.readAttribute("manifest", "package").orEmpty()
        originalFactory = inputFactory ?: recoverFactory(originalManifest, log, ourStubFactories)

        val factoryName = layout?.factoryDashed ?: STUB_CLASS
        var patched = stripStaleManifestComponents(
            manifest = manifestBase,
            manifestPackage = manifestPackage,
            outerProvider = outerLayout?.providerDashed,
            keepFactory = factoryName,
            log = log,
        )
        patched = AxmlEditor(patched).setApplicationAttribute(ATTR_FACTORY_NAME, factoryName)
        if (options.fileProvider) {
            if (manifestPackage.isBlank()) {
                log.step("清单缺少 package 属性，跳过文件提供器注入")
            } else {
                log.step("正在注入文件提供器")
                val authority = "$manifestPackage.MTDataFilesProvider"
                val editorNow = AxmlEditor(patched)
                if (editorNow.hasProvider(MT_PROVIDER_CLASS, authority)) {
                    log.debug("文件提供器已存在，复用 authority=$authority")
                } else {
                    patched = editorNow.addDocumentsProvider(
                        MT_PROVIDER_CLASS,
                        authority,
                        MT_WAKEUP_CLASS,
                        "$manifestPackage.MTDataFilesWakeUp",
                    )
                    log.debug("已注入 MT 文件提供器 authority=$authority")
                }
            }
        }
        options.versionName?.takeIf { it.isNotBlank() }?.let { name ->
            patched = AxmlEditor(patched).setStringAttribute("manifest", "versionName", name)
        }
        options.versionCode?.let { code ->
            patched = AxmlEditor(patched).setIntAttribute("manifest", "versionCode", code)
        }
        manifest = patched
        assertManifestClean(manifest, factoryName, options, log)
        log.debug("工厂注入写入完成")
        manifest = if (options.obfuscate && !options.obfuscateKeepCrashPermission) {
            log.debug("跳过 $MODULE_CRASH_PERMISSION（混淆：模块崩溃时管理器收不到广播）")
            manifest
        } else {
            injectModuleCrashPermission(manifest, log)
        }
        if (options.versionName?.isNotBlank() == true || options.versionCode != null) {
            log.debug("自定义版本：" + listOfNotNull(
                options.versionName?.takeIf { it.isNotBlank() }?.let { "versionName=$it" },
                options.versionCode?.let { "versionCode=$it" },
            ).joinToString("，"))
        }
        onProgress(25)

        val packageInfo = packageInfoOf(signedSource)
        val recorded = if (hadInjected || sourceApk != effectiveSource) {
            readRecordedSigning(sourceApk, log)
        } else {
            null
        }
        assertSigningOrigin(signedSource, recorded, hadInjected, options.embedSource, options, log)
        val config = buildConfig(
            packageInfo,
            originalFactory,
            options,
            signedSource,
            layout,
            recordedSign = recorded?.first,
            recordedSigningDetails = recorded?.second,
        )

        val embeddedCrc = AtomicLong()
        val embeddedFailure = AtomicReference<Throwable?>()
        val embeddedReady = CountDownLatch(1)
        val crcWorker = Thread {
            try {
                embeddedCrc.set(crcFile(effectiveSource))
            } catch (error: Throwable) {
                embeddedFailure.set(error)
            } finally {
                embeddedReady.countDown()
            }
        }.apply {
            isDaemon = true
            name = "xpatch-crc"
            start()
        }

        try {
            writeArchive(
                sourceApk = effectiveSource,
                outputApk = outputApk,
                entries = entries,
                manifest = manifest,
                originalManifest = originalManifest,
                bootDexName = bootDexName,
                stubDex = stub.bytes,
                config = config,
                packageName = packageInfo?.packageName.orEmpty(),
                embeddedCrc = embeddedCrc,
                embeddedFailure = embeddedFailure,
                embeddedReady = embeddedReady,
                onProgress = onProgress,
                log = log,
                embedSource = options.embedSource,
                layout = layout,
            )
        } finally {
            embeddedReady.countDown()
            runCatching { crcWorker.join(2000) }
            temps.forEach { temp -> runCatching { temp.delete() } }
        }
        if (options.embedSource) {
            val overhead = outputApk.length() - embeddedSourceLength
            check(overhead <= MAX_EMBED_OVERHEAD) {
                "产物体积异常：比内嵌原包多出 $overhead B（上限 $MAX_EMBED_OVERHEAD B）。" +
                    "嵌套链接没有生效，条目被重复写入会导致产物接近翻倍，已拒绝输出。"
            }
            log.debug("内嵌开销 ${overhead} B · 产物 ${PatchLog.mb(outputApk.length())}")
        }

        return Result(
            output = outputApk,
            originalFactory = originalFactory,
            bootDexName = bootDexName,
            config = config,
            layout = layout,
        )
    }

    private fun discoverLayout(
        sourceApk: File,
        entries: List<Entry>,
        log: PatchLog,
    ): InjectedEntrySet {
        val found = runCatching {
            RandomAccessFile(sourceApk, "r").use { source ->
                InjectLayout.discoverConfigEntry(entries.map { it.name to it.uncompressedSize }) { name ->
                    val entry = entries.firstOrNull { it.name == name } ?: return@discoverConfigEntry null
                    runCatching { readRawEntry(source, entry) }.getOrNull()
                }
            }
        }.getOrNull()
        var layout: InjectLayout? = null
        if (found != null) {
            val entry = entries.firstOrNull { it.name == found }
            val bytes = entry?.let { target ->
                runCatching { RandomAccessFile(sourceApk, "r").use { readRawEntry(it, target) } }.getOrNull()
            }
            if (bytes != null) {
                layout = InjectLayout.fromConfig(String(bytes, Charsets.UTF_8))
                if (layout == null && found != InjectLayout.LEGACY_CONFIG_ENTRY) {
                    log.debug("config $found 无 res_cfg，按旧产物处理")
                }
            }
        }
        if (layout != null) {
            log.debug("发现上一版混淆布局：目录 ${layout.dir}（config $found）")
        }
        return InjectedEntrySet(
            layout = layout,
            legacyInjected = { name -> isInjectedEntry(name) },
            legacyEmbeddedSource = { name -> embeddedSourceOf(name) },
        )
    }

    private fun sanitizeFactory(
        factory: String?,
        log: PatchLog,
        ourStubs: Set<String> = LEGACY_STUB_CLASSES,
    ): String? {
        if (factory.isNullOrBlank()) return null
        if (factory in ourStubs) {
            log.debug("源包工厂指向引导桩（$factory），已忽略以免自我委派")
            return null
        }
        return factory
    }

    private fun pickOriginalManifest(
        recorded: ByteArray?,
        input: ByteArray,
        expectedPackage: String,
        log: PatchLog,
        ourStubs: Set<String> = LEGACY_STUB_CLASSES,
    ): ByteArray {
        if (recorded == null) return input
        val parsed = runCatching { AxmlEditor(recorded) }.getOrNull()
        val packageName = parsed?.let { runCatching { it.readAttribute("manifest", "package") }.getOrNull() }
        if (parsed == null || packageName == null) {
            log.debug("xp/source.manifest 解析失败，退回输入清单")
            return input
        }
        if (expectedPackage.isNotBlank() && packageName != expectedPackage) {
            log.debug("xp/source.manifest 包名 $packageName 不一致，退回输入清单")
            return input
        }
        val factory = runCatching { parsed.readApplicationAttribute(ATTR_FACTORY_NAME) }.getOrNull()
        if (factory != null && factory in ourStubs) {
            log.debug("xp/source.manifest 已含引导桩工厂，原始清单不可恢复")
        } else {
            log.debug("复用上一版原始清单 xp/source.manifest")
        }
        return recorded
    }

    private fun assertManifestClean(
        manifest: ByteArray,
        factoryName: String,
        options: Options,
        log: PatchLog,
    ) {
        val editor = runCatching { AxmlEditor(manifest) }.getOrElse { error ->
            log.debug("清单断言跳过（无法解析注入后的清单）：${error.message}")
            return
        }
        val factory = runCatching { editor.readApplicationAttribute(ATTR_FACTORY_NAME) }.getOrNull()
        check(factory == factoryName) {
            "注入后的清单 appComponentFactory=$factory，与期望的 $factoryName 不一致"
        }
        check(runCatching { editor.hasResolvedAttribute("application", ATTR_FACTORY_NAME) }.getOrDefault(false)) {
            "注入后的清单 appComponentFactory 没有资源 ID（resource map 缺项），平台会忽略该属性，" +
                "工厂注入等于没写。宿主原本没有该属性时必须补 resource map。"
        }
        val staleProviderClasses = LEGACY_PROVIDER_CLASSES
        val providerClasses = runCatching {
            editor.childAttributeValues("application", "provider", ATTR_NAME)
        }.getOrDefault(emptyList())
        val leaked = providerClasses.filter { it in staleProviderClasses }
        check(leaked.isEmpty()) {
            "注入后的清单仍含指向已剥离类的 provider：$leaked（会导致启动 ClassNotFoundException）"
        }
        val activities = runCatching {
            editor.childAttributeValues("application", "activity", ATTR_NAME)
        }.getOrDefault(emptyList())
        if (options.fileProvider && activities.none { it == MT_WAKEUP_CLASS }) {
            log.debug("清单断言：未找到文件提供器 activity $MT_WAKEUP_CLASS")
        }
    }

    private fun stripStaleManifestComponents(
        manifest: ByteArray,
        manifestPackage: String,
        outerProvider: String?,
        keepFactory: String,
        log: PatchLog,
    ): ByteArray {
        var result = manifest
        val authorities = buildList {
            if (manifestPackage.isNotBlank()) {
                add("$manifestPackage.xpatch")
                add("$manifestPackage.MTDataFilesProvider")
            }
        }
        val providerCandidates = buildList {
            addAll(LEGACY_PROVIDER_CLASSES)
            outerProvider?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        for (authorityPrefix in authorities) {
            for (candidate in blankClassPatterns(providerCandidates)) {
                val tried = runCatching {
                    AxmlEditor(result).removeChildElement(
                        "application",
                        "provider",
                        ATTR_NAME,
                        candidate,
                    )
                }.getOrElse { error ->
                    log.step("清理旧 provider 失败（$authorityPrefix / $candidate）：${error.message}")
                    result
                }
                if (!tried.contentEquals(result)) {
                    log.step("已从清单移除残留 provider：$candidate（authority 前缀 $authorityPrefix）")
                    result = tried
                }
            }
        }
        val activityCandidates = buildList {
            add(MT_WAKEUP_CLASS)
            add(LEGACY_WAKEUP_CLASS)
        }
        for (candidate in activityCandidates) {
            val tried = runCatching {
                AxmlEditor(result).removeChildElement(
                    "application",
                    "activity",
                    ATTR_NAME,
                    candidate,
                )
            }.getOrElse { error ->
                log.step("清理旧 activity 失败（$candidate）：${error.message}")
                result
            }
            if (!tried.contentEquals(result)) {
                log.step("已从清单移除残留 activity：$candidate")
                result = tried
            }
        }
        val currentFactory = runCatching {
            AxmlEditor(result).readApplicationAttribute(ATTR_FACTORY_NAME)
        }.getOrNull()
        if (currentFactory != null && currentFactory != keepFactory &&
            currentFactory in LEGACY_STUB_CLASSES
        ) {
            val cleared = runCatching {
                AxmlEditor(result).removeAttribute("application", ATTR_FACTORY_NAME)
            }.getOrElse { error ->
                log.step("清理旧 appComponentFactory 失败：${error.message}")
                result
            }
            if (!cleared.contentEquals(result)) {
                log.step("已从清单移除旧工厂声明：$currentFactory")
                result = cleared
            }
        }
        return result
    }

    private fun blankClassPatterns(candidates: Iterable<String>): List<String> =
        candidates.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun recoverFactory(
        originalManifest: ByteArray,
        log: PatchLog,
        ourStubs: Set<String> = LEGACY_STUB_CLASSES,
    ): String? {
        val factory = runCatching {
            AxmlEditor(originalManifest).readApplicationAttribute(ATTR_FACTORY_NAME)
        }.getOrNull()
        if (factory.isNullOrBlank() || factory in ourStubs) return null
        log.debug("从原始清单恢复 appComponentFactory=$factory")
        return factory
    }

    private fun injectModuleCrashPermission(manifest: ByteArray, log: PatchLog): ByteArray {
        val editor = AxmlEditor(manifest)
        return try {
            if (editor.hasUsesPermission(MODULE_CRASH_PERMISSION)) {
                log.debug("清单已含 $MODULE_CRASH_PERMISSION，跳过权限注入")
                manifest
            } else {
                val patched = editor.addUsesPermission(MODULE_CRASH_PERMISSION)
                log.debug("已写入权限声明 $MODULE_CRASH_PERMISSION")
                patched
            }
        } catch (error: Exception) {
            log.step("权限注入失败，模块保护广播可能不可达（${error.javaClass.simpleName}: ${error.message}）")
            manifest
        }
    }

    private fun readRawEntry(source: RandomAccessFile, entry: Entry): ByteArray {
        require(entry.uncompressedSize in 1..MAX_BOOTDEX) { "条目体积异常" }
        val raw = ByteArray(entry.compressedSize.toInt())
        source.seek(entry.dataStart)
        source.readFully(raw)
        return when (entry.method) {
            STORED -> raw
            DEFLATED -> {
                val inflater = Inflater(true)
                try {
                    inflater.setInput(raw)
                    val out = ByteArray(entry.uncompressedSize.toInt())
                    var offset = 0
                    while (!inflater.finished() && offset < out.size) {
                        val produced = inflater.inflate(out, offset, out.size - offset)
                        if (produced <= 0) break
                        offset += produced
                    }
                    if (offset == out.size) out else out.copyOf(offset)
                } finally {
                    inflater.end()
                }
            }

            else -> error("不支持的压缩方式")
        }
    }

    private fun dropStaleBootDex(
        source: RandomAccessFile,
        entries: List<Entry>,
        log: PatchLog,
        removed: MutableSet<String>?,
    ): List<Entry> {
        val stale = entries.filter { entry ->
            if (!isBootDexName(entry.name)) return@filter false
            if (entry.compressedSize !in 1..MAX_BOOTDEX) return@filter false
            val body = runCatching { readRawEntry(source, entry) }.getOrNull() ?: return@filter false
            val verdict = classifyBootDex(body)
            if (verdict == BootDexVerdict.NOT_STALE) return@filter false
            val reason = bootDexReason(body)
            if (reason != null) log.debug("${entry.name} 判定为旧引导桩：$reason")
            verdict == BootDexVerdict.STALE
        }
        if (stale.isEmpty()) return entries
        stale.forEach { log.step("移除旧引导桩 ${it.name}") }
        val names = stale.mapTo(HashSet()) { it.name }
        removed?.addAll(names)
        return entries.filter { it.name !in names }
    }

    private fun bootDexReason(body: ByteArray): String? {
        val defined = runCatching { dexDefinedClassDescriptors(body) }.getOrNull()
        if (defined.isNullOrEmpty()) return null
        val ourStubs = defined.filter { it in STUB_CLASS_DESCRIPTORS }
        if (ourStubs.isNotEmpty()) return "class_defs 含引导桩 ${ourStubs.take(2)}"
        if (defined.size == 1 && isFactorySubclass(body, defined[0])) {
            return "class_defs 只有 1 个类，且继承 AppComponentFactory（混淆桩）：${defined[0]}"
        }
        val markers = listOf("xp/core.dex", "xp/source.manifest", "xp/config.json", "res_boot_dex")
        val text = String(body, Charsets.ISO_8859_1)
        if (defined.size == 1 && markers.any { text.contains(it) }) {
            return "class_defs 只有 1 个类，且引用框架注入条目（混淆桩）：${defined[0]}"
        }
        return null
    }

    private fun classifyBootDex(body: ByteArray): BootDexVerdict {
        val defined = runCatching { dexDefinedClassDescriptors(body) }.getOrNull() ?: return BootDexVerdict.NOT_STALE
        if (defined.isEmpty()) return BootDexVerdict.NOT_STALE
        if (defined.any { it in STUB_CLASS_DESCRIPTORS }) return BootDexVerdict.STALE
        if (defined.size != 1) return BootDexVerdict.NOT_STALE
        if (isFactorySubclass(body, defined[0])) return BootDexVerdict.STALE
        val text = String(body, Charsets.ISO_8859_1)
        val markers = listOf("xp/core.dex", "xp/source.manifest", "xp/config.json", "res_boot_dex")
        return if (markers.any { text.contains(it) }) BootDexVerdict.STALE else BootDexVerdict.NOT_STALE
    }

    private fun isFactorySubclass(body: ByteArray, descriptor: String): Boolean {
        val text = String(body, Charsets.ISO_8859_1)
        if (!text.contains("Landroid/app/AppComponentFactory;")) return false
        return text.contains(descriptor)
    }

    private fun dexDefinedClassDescriptors(dex: ByteArray): List<String> {
        if (dex.size < 0x70) return emptyList()
        if (dex[0] != 0x64.toByte() || dex[1] != 0x65.toByte() ||
            dex[2] != 0x78.toByte() || dex[3] != 0x0A.toByte()
        ) {
            return emptyList()
        }
        val stringIdsSize = readLe32(dex, 0x38)
        val stringIdsOff = readLe32(dex, 0x3C)
        val typeIdsSize = readLe32(dex, 0x40)
        val typeIdsOff = readLe32(dex, 0x44)
        val classDefsSize = readLe32(dex, 0x60)
        val classDefsOff = readLe32(dex, 0x64)
        if (classDefsSize <= 0 || classDefsOff <= 0) return emptyList()
        if (classDefsOff.toLong() + classDefsSize.toLong() * 32L > dex.size) return emptyList()
        val result = ArrayList<String>(classDefsSize)
        for (index in 0 until classDefsSize) {
            val defOff = classDefsOff + index * 32
            if (defOff + 32 > dex.size) break
            val classIdx = readLe32(dex, defOff)
            if (classIdx < 0 || classIdx >= typeIdsSize) continue
            val typeOff = typeIdsOff + classIdx * 4
            if (typeOff + 4 > dex.size) continue
            val descriptorIdx = readLe32(dex, typeOff)
            if (descriptorIdx < 0 || descriptorIdx >= stringIdsSize) continue
            val stringOff = stringIdsOff + descriptorIdx * 4
            if (stringOff + 4 > dex.size) continue
            val offset = readLe32(dex, stringOff)
            if (offset <= 0 || offset >= dex.size) continue
            val descriptor = readMutf8String(dex, offset)
            if (descriptor != null) result.add(descriptor)
        }
        return result
    }

    private fun readLe32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)

    private fun readMutf8String(data: ByteArray, offset: Int): String? {
        var pos = offset
        var utf16Size = 0L
        var shift = 0
        while (pos < data.size && shift <= 28) {
            val byte = data[pos].toInt() and 0xFF
            pos++
            utf16Size = utf16Size or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
        }
        if (utf16Size <= 0L || utf16Size > 65535L) return null
        val builder = StringBuilder()
        var guard = 0
        while (pos < data.size && guard < 4096) {
            val byte = data[pos].toInt() and 0xFF
            pos++
            if (byte == 0) break
            when {
                byte < 0x80 -> builder.append(byte.toChar())
                byte and 0xE0 == 0xC0 -> {
                    if (pos >= data.size) return null
                    val next = data[pos].toInt() and 0xFF
                    pos++
                    builder.append((((byte and 0x1F) shl 6) or (next and 0x3F)).toChar())
                }

                byte and 0xF0 == 0xE0 -> {
                    if (pos + 1 >= data.size) return null
                    val b2 = data[pos].toInt() and 0xFF
                    val b3 = data[pos + 1].toInt() and 0xFF
                    pos += 2
                    builder.append((((byte and 0x0F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F)).toChar())
                }

                else -> return null
            }
            guard++
        }
        return builder.toString()
    }

    private enum class BootDexVerdict { STALE, NOT_STALE }

    private fun oversizedNested(entries: List<Entry>, outerSize: Long): List<Entry> {
        if (outerSize <= 0) return emptyList()
        return entries.filter {
            it.name.endsWith(APK_SUFFIX, ignoreCase = true) &&
                it.uncompressedSize >= MIN_EMBEDDED &&
                it.compressedSize.toDouble() / outerSize >= NESTED_APK_COST_LIMIT
        }
    }

    private fun dropOverlappingNested(
        sourceApk: File,
        entries: List<Entry>,
    ): List<Entry> {
        val oversized = oversizedNested(entries, sourceApk.length())
        if (oversized.isEmpty()) return entries
        error(
            "源包自带大体积 .apk 资产（" + oversized.take(3).map { it.name } +
                "），尝试剥离后仍无法还原原始包，内嵌会让产物接近翻倍。请改用不含大体积内嵌 APK 的原始安装包。",
        )
    }

    private fun assertClean(
        entries: List<Entry>,
        sourceApk: File,
        log: PatchLog,
        strip: InjectedEntrySet,
        embed: Boolean,
    ) {
        val leaked = entries.filter { strip.isInjected(it.name) }
        require(leaked.isEmpty()) {
            "源包仍含注入条目，拒绝打包以免体积翻倍：${leaked.take(5).map { it.name }}"
        }
        val physical = if (embed) {
            runCatching {
                RandomAccessFile(sourceApk, "r").use { source ->
                    readCentralDirectory(source, sourceApk.length())
                }
            }.getOrNull()
        } else {
            null
        }
        val scope = physical ?: entries
        val remaining = scope.filter { strip.isInjected(it.name) }
        require(remaining.isEmpty()) {
            "源包内仍含 XPatch 注入条目，无法还原原始安装包；拒绝内嵌以避免产物逐次翻倍：" +
                remaining.take(5).map { it.name } +
                "。请改用原始 APK 作为输入，或先卸载后重新选择安装包。"
        }
        if (embed) {
            val stale = scope.filter { strip.isEmbeddedSource(it.name) || it.name == SOURCE_ENTRY }
            require(stale.isEmpty()) {
                "源包内已有内嵌来源条目，重复内嵌会让体积翻倍：${stale.take(3).map { it.name }}"
            }
        }
        val leftover = stripInjected(sourceApk, scope, strip, log, null)
        val found = leftover.file
        if (found != null) {
            val name = leftover.entry
            found.delete()
            error("源包内仍有可验证的内嵌原始包（$name），剥离未完成，拒绝打包以免产物翻倍")
        }
        val outerSize = sourceApk.length()
        if (outerSize <= 0) return
        val heavy = oversizedNested(entries, outerSize)
        if (heavy.isNotEmpty()) {
            val detail = heavy.take(3).joinToString("，") {
                "${it.name} ${it.compressedSize} B"
            }
            log.step("源包内嵌大体积 APK，尝试剥离后仍无法还原原始包：$detail")
            error("源包内嵌大体积 APK，剥离失败且内嵌后产物必然接近翻倍：$detail。请改用不含大体积内嵌 APK 的原始安装包。")
        }
    }

    private fun stripInjected(
        sourceApk: File,
        entries: List<Entry>,
        strip: InjectedEntrySet,
        log: PatchLog,
        preferredName: String? = null,
    ): EmbeddedSource.Outcome {
        val outerPackage = packageInfoOf(sourceApk)?.packageName.orEmpty()
        return RandomAccessFile(sourceApk, "r").use { source ->
            EmbeddedSource(
                source = source,
                entries = entries.map {
                    EmbeddedSource.Entry(
                        name = it.name,
                        method = it.method,
                        crc = it.crc,
                        compressedSize = it.compressedSize,
                        uncompressedSize = it.uncompressedSize,
                        dataStart = it.dataStart,
                    )
                },
                outerPackage = outerPackage,
                injected = { name -> strip.isInjected(name) },
                preferred = preferredSourceNames(preferredName),
                packageNameOf = { file -> packageInfoOf(file)?.packageName },
                outDir = File(context.cacheDir, "patch"),
                score = { entry -> scoreEmbedded(entry.name, entry.uncompressedSize, sourceApk.length()) },
                strippedBootDex = strippedBootDex.toSet(),
                onLog = log.asStepSink(),
            ).locate()
        }
    }

    private fun scoreEmbedded(
        name: String,
        uncompressedSize: Long,
        outerSize: Long,
    ): Int {
        var score = 0
        if (name in KNOWN_LAYOUTS) {
            score += 60
        } else if (INJECTED_PREFIXES.any { name.startsWith(it) }) {
            score += 25
        }
        val lowered = name.lowercase()
        if (SOURCE_HINTS.any { lowered.contains(it) }) score += 20

        if (outerSize > 0) {
            val ratio = uncompressedSize.toDouble() / outerSize
            score += when {
                ratio in 0.45..1.02 -> 25
                ratio < 0.1 -> -40
                ratio > 1.1 -> -60
                else -> 0
            }
        }
        return score
    }

    private fun writeArchive(
        sourceApk: File,
        outputApk: File,
        entries: List<Entry>,
        manifest: ByteArray,
        originalManifest: ByteArray,
        bootDexName: String,
        stubDex: ByteArray,
        config: String,
        packageName: String,
        embeddedCrc: AtomicLong,
        embeddedFailure: AtomicReference<Throwable?>,
        embeddedReady: CountDownLatch,
        onProgress: (Int) -> Unit,
        log: PatchLog,
        embedSource: Boolean,
        layout: InjectLayout?,
    ) {
        fun nameOf(logical: Logical, legacy: String): String = layout?.entry(logical) ?: legacy
        val sourceEntryName = nameOf(Logical.SOURCE, SOURCE_ENTRY)
        if (layout != null) {
            val leaked = Logical.values()
                .map { nameOf(it, it.key) }
                .filter { it.startsWith(LEGACY_DIR) }
            check(leaked.isEmpty()) {
                "混淆档的注入条目仍使用 xp/ 原名：$leaked，" +
                    "宿主静态检查能直接看到修补痕迹。请检查 InjectLayout 的条目命名回退路径。"
            }
        }

        val added = buildList {
            add(Added(nameOf(Logical.SOURCE_MANIFEST, ORIGINAL_MANIFEST_ENTRY), DEFLATED) { originalManifest })
            add(Added(bootDexName, DEFLATED) { stubDex })
            add(Added(nameOf(Logical.CORE, CORE_ENTRY), DEFLATED) { asset("xp/runtime.dex") })
            add(Added(nameOf(Logical.HOOK_DEX, HOOK_DEX_ENTRY), DEFLATED) { asset("xp/hook_core.dex") })
            add(Added(nameOf(Logical.HOOK_SO, HOOK_SO_ENTRY), STORED) { asset("xp/hook_core.so") })
            if (hasAsset("xp/hook_core_x86_64.so") &&
                (layout == null || layout.entry(Logical.HOOK_SO_X86_64) != null)
            ) {
                add(
                    Added(nameOf(Logical.HOOK_SO_X86_64, "xp/hook_core_x86_64.so"), STORED) {
                        asset("xp/hook_core_x86_64.so")
                    },
                )
            }
            add(Added(nameOf(Logical.DEX89, "xp/xp89.dex"), DEFLATED) { asset("xp/xp89.dex") })
            add(Added(nameOf(Logical.DEX100, "xp/xp100.dex"), DEFLATED) { asset("xp/xp100.dex") })
            add(Added(nameOf(Logical.DEX101, "xp/xp101.dex"), DEFLATED) { asset("xp/xp101.dex") })
            add(Added(nameOf(Logical.DEX102, "xp/xp102.dex"), DEFLATED) { asset("xp/xp102.dex") })
            add(Added(nameOf(Logical.CFG, CONFIG_ENTRY), DEFLATED) { config.toByteArray() })
            add(Added(nameOf(Logical.PKG, "xp/pkg"), DEFLATED) { packageName.toByteArray() })
            add(Added(nameOf(Logical.FILE_PROVIDER, "xp/file_provider"), DEFLATED) { asset("xp/file_provider.dex") })
        }

        val addedNames = added.mapTo(HashSet()) { it.name }
        val carried = entries.filter { entry ->
            when {
                entry.name == MANIFEST -> true
                entry.name in addedNames -> false
                isInjectedEntry(entry.name) -> false
                else -> true
            }
        }

        if (layout != null) {
            val stamp = modeTimestamp(carried)
            added.forEach {
                it.dosTime = stamp.first
                it.dosDate = stamp.second
            }
            log.step("注入条目时间戳跟随原包 ${timestampText(stamp)}")
        }
        val plan = carried.map { Plan.Carried(it) } + added.map { Plan.Injected(it) }

        val hostStamp = if (layout != null) modeTimestamp(carried) else 0 to DOS_DATE_EPOCH
        val total = plan.size + if (embedSource) 1 else 0
        log.milestone("写入 $total（复用 ${carried.size} + 注入 ${added.size}${if (embedSource) " + 内嵌原包" else ""}）")
        val writeStart = System.currentTimeMillis()
        var done = 0
        var reported = 25

        fun advance() {
            done++
            val percent = 25 + done * (DONE_PROGRESS - 25) / total
            if (percent != reported) {
                reported = percent
                onProgress(percent)
            }
        }

        outputApk.delete()
        val records = ArrayList<Record>(total)
        var finalPosition = -1L

        try {
            FileOutputStream(outputApk, false).buffered(BUFFER_SIZE).use { out ->
                var position = 0L

                RandomAccessFile(sourceApk, "r").use { source ->
                    log.step("内核 + 引导桩 $bootDexName")

                    var hostDataOffset = -1L
                    if (embedSource) {
                        embeddedReady.await()
                        embeddedFailure.get()?.let { throw it }
                        val length = sourceApk.length()
                        val crc = embeddedCrc.get()
                        val hostEntry = addedEntry(sourceEntryName, STORED, hostStamp.first, hostStamp.second)
                        val headerOffset = position
                        val header = localHeader(hostEntry, headerOffset, crc, length, length, PAGE_ALIGNMENT)
                        out.write(header)
                        position += header.size
                        hostDataOffset = position
                        source.seek(0)
                        position += copyRange(source, length, out)
                        records += Record(hostEntry, headerOffset, crc, length, length)
                        log.step("内嵌原包 · ${PatchLog.mb(length)}（嵌套链接，宿主条目共享原包字节，不翻倍）")
                        advance()
                    }

                    for (step in plan) {
                        when (step) {
                            is Plan.Carried -> {
                                val entry = step.entry
                                if (embedSource && entry.name != MANIFEST) {
                                    records += Record(
                                        entry,
                                        hostDataOffset + entry.headerOffset,
                                        entry.crc,
                                        entry.compressedSize,
                                        entry.uncompressedSize,
                                        virtual = true,
                                    )
                                    advance()
                                    continue
                                }
                                val written = writeCarried(source, entry, manifest, out, position)
                                position += written.first
                                records += written.second
                                advance()
                            }

                            is Plan.Injected -> {
                                val item = step.added
                                val entry = addedEntry(item.name, item.method, item.dosTime, item.dosDate)
                                val data = requireNotNull(item.data).invoke()
                                val payload = compress(data, item.method)
                                val crc = crc32(data)
                                val headerOffset = position
                                val header = localHeader(
                                    entry = entry,
                                    headerOffset = headerOffset,
                                    crc = crc,
                                    compressed = payload.size.toLong(),
                                    uncompressed = data.size.toLong(),
                                )
                                out.write(header)
                                position += header.size
                                out.write(payload)
                                position += payload.size
                                records += Record(entry, headerOffset, crc, payload.size.toLong(), data.size.toLong())
                                advance()
                            }
                        }
                    }
                }

                val centralOffset = position
                var centralSize = 0L
                for (record in records) {
                    val bytes = centralRecord(record)
                    out.write(bytes)
                    centralSize += bytes.size
                }
                endOfCentralDirectory(out, records.size, centralOffset, centralSize)
                finalPosition = centralOffset + centralSize + END_OF_CENTRAL_SIZE
            }
        } catch (error: Throwable) {
            outputApk.delete()
            log.step("写包失败，已删除不完整产物：${outputApk.name}（${error.message}）")
            throw error
        }
        log.milestone(
            "写包 ${PatchLog.mb(outputApk.length())} · ${PatchLog.secs(System.currentTimeMillis() - writeStart)}",
        )
        log.debug("写包位置 $finalPosition · 文件长度 ${outputApk.length()} · 复用 ${carried.size} 条目")
    }

    private fun writeCarried(
        source: RandomAccessFile,
        entry: Entry,
        manifest: ByteArray,
        out: OutputStream,
        headerOffset: Long,
    ): Pair<Long, Record> {
        val data = if (entry.name == MANIFEST) manifest else null
        val payload = if (data != null) compress(data, entry.method) else null
        val crc = if (data != null) crc32(data) else entry.crc
        val compressed = payload?.size?.toLong() ?: entry.compressedSize
        val uncompressed = data?.size?.toLong() ?: entry.uncompressedSize
        val header = localHeader(entry, headerOffset, crc, compressed, uncompressed)
        out.write(header)
        var written = header.size.toLong()
        if (payload != null) {
            out.write(payload)
            written += payload.size
        } else {
            source.seek(entry.dataStart)
            written += copyRange(source, entry.compressedSize, out)
        }
        return written to Record(entry, headerOffset, crc, compressed, uncompressed)
    }

    private fun modeTimestamp(carried: List<Entry>): Pair<Int, Int> {
        if (carried.isEmpty()) return 0 to DOS_DATE_EPOCH
        val counts = HashMap<Long, Int>()
        for (entry in carried) {
            val key = (entry.dosDate.toLong() shl 16) or entry.dosTime.toLong()
            counts[key] = (counts[key] ?: 0) + 1
        }
        var bestKey = -1L
        var bestCount = 0
        for (entry in carried) {
            val key = (entry.dosDate.toLong() shl 16) or entry.dosTime.toLong()
            val count = counts.getValue(key)
            if (count > bestCount) {
                bestCount = count
                bestKey = key
            }
        }
        return (bestKey and 0xFFFF).toInt() to (bestKey shr 16).toInt()
    }

    private fun timestampText(stamp: Pair<Int, Int>): String {
        val date = stamp.second
        val time = stamp.first
        val year = 1980 + ((date shr 9) and 0x7F)
        val month = (date shr 5) and 0x0F
        val day = date and 0x1F
        val hour = (time shr 11) and 0x1F
        val minute = (time shr 5) and 0x3F
        val second = (time and 0x1F) * 2
        return "%04d-%02d-%02d %02d:%02d:%02d".format(year, month, day, hour, minute, second)
    }

    private fun addedEntry(name: String, method: Int, dosTime: Int = 0, dosDate: Int = DOS_DATE_EPOCH): Entry = Entry(
        name = name,
        nameBytes = name.toByteArray(Charsets.UTF_8),
        extra = EMPTY,
        comment = EMPTY,
        versionMadeBy = HOST_SYSTEM,
        versionNeeded = VERSION_NEEDED,
        flags = FLAG_UTF8,
        method = method,
        dosTime = dosTime,
        dosDate = dosDate,
        crc = 0L,
        compressedSize = 0L,
        uncompressedSize = 0L,
        internalAttrs = 0,
        externalAttrs = 0L,
        dataStart = 0L,
    )

    private fun localHeader(
        entry: Entry,
        headerOffset: Long,
        crc: Long,
        compressed: Long,
        uncompressed: Long,
        alignmentOverride: Int = 0,
    ): ByteArray {
        require(compressed in 0..UINT32_MAX && uncompressed in 0..UINT32_MAX) { "条目超出 ZIP 格式上限" }
        val extra = alignedExtra(entry, headerOffset, alignmentOverride)
        val buffer = ByteBuffer.allocate(LOCAL_HEADER_SIZE + entry.nameBytes.size + extra.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(LOCAL_HEADER_SIG)
        buffer.putShort(entry.versionNeeded.toShort())
        buffer.putShort((entry.flags and FLAG_DATA_DESCRIPTOR.inv()).toShort())
        buffer.putShort(entry.method.toShort())
        buffer.putShort(entry.dosTime.toShort())
        buffer.putShort(entry.dosDate.toShort())
        buffer.putInt(crc.toInt())
        buffer.putInt(compressed.toInt())
        buffer.putInt(uncompressed.toInt())
        buffer.putShort(entry.nameBytes.size.toShort())
        buffer.putShort(extra.size.toShort())
        buffer.put(entry.nameBytes)
        buffer.put(extra)
        return buffer.array()
    }

    private fun alignedExtra(entry: Entry, headerOffset: Long, alignmentOverride: Int = 0): ByteArray {
        val multiple = (if (alignmentOverride > 0) alignmentOverride else alignmentOf(entry)).toLong()
        if (multiple <= 1L) return entry.extra
        val prefix = headerOffset + LOCAL_HEADER_SIZE + entry.nameBytes.size
        if ((prefix + entry.extra.size) % multiple == 0L) return entry.extra
        var total = entry.extra.size.toLong() + ALIGN_FIELD_OVERHEAD
        while ((prefix + total) % multiple != 0L) total++
        if (total - entry.extra.size - 4 > MAX_EXTRA_FIELD) return entry.extra
        val fieldSize = (total - entry.extra.size - 4).toInt()
        val extra = ByteArray(total.toInt())
        System.arraycopy(entry.extra, 0, extra, 0, entry.extra.size)
        var index = entry.extra.size
        extra[index++] = (ALIGN_ID and 0xFF).toByte()
        extra[index++] = ((ALIGN_ID shr 8) and 0xFF).toByte()
        extra[index++] = (fieldSize and 0xFF).toByte()
        extra[index++] = ((fieldSize shr 8) and 0xFF).toByte()
        extra[index++] = (multiple.toInt() and 0xFF).toByte()
        extra[index++] = ((multiple.toInt() shr 8) and 0xFF).toByte()
        return extra
    }

    private fun alignmentOf(entry: Entry): Int = when {
        entry.method != STORED -> 1
        entry.name.endsWith(".so") -> PAGE_ALIGNMENT
        else -> DATA_ALIGNMENT
    }

    private fun centralRecord(record: Record): ByteArray {
        val entry = record.entry
        val buffer = ByteBuffer
            .allocate(CENTRAL_HEADER_SIZE + entry.nameBytes.size + entry.extra.size + entry.comment.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(CENTRAL_HEADER_SIG)
        buffer.putShort(entry.versionMadeBy.toShort())
        buffer.putShort(entry.versionNeeded.toShort())
        val flags = if (record.virtual) entry.flags else entry.flags and FLAG_DATA_DESCRIPTOR.inv()
        buffer.putShort(flags.toShort())
        buffer.putShort(entry.method.toShort())
        buffer.putShort(entry.dosTime.toShort())
        buffer.putShort(entry.dosDate.toShort())
        buffer.putInt(record.crc.toInt())
        buffer.putInt(record.compressed.toInt())
        buffer.putInt(record.uncompressed.toInt())
        buffer.putShort(entry.nameBytes.size.toShort())
        buffer.putShort(entry.extra.size.toShort())
        buffer.putShort(entry.comment.size.toShort())
        buffer.putShort(0)
        buffer.putShort(entry.internalAttrs.toShort())
        buffer.putInt(entry.externalAttrs.toInt())
        buffer.putInt(record.offset.toInt())
        buffer.put(entry.nameBytes)
        buffer.put(entry.extra)
        buffer.put(entry.comment)
        return buffer.array()
    }

    private fun endOfCentralDirectory(out: OutputStream, count: Int, centralOffset: Long, centralSize: Long) {
        require(count in 0..UINT16_MAX && centralOffset in 0..UINT32_MAX && centralSize in 0..UINT32_MAX) {
            "APK 超出 ZIP 格式上限"
        }
        val buffer = ByteBuffer.allocate(END_OF_CENTRAL_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(END_OF_CENTRAL_SIG)
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.putShort(count.toShort())
        buffer.putShort(count.toShort())
        buffer.putInt(centralSize.toInt())
        buffer.putInt(centralOffset.toInt())
        buffer.putShort(0)
        out.write(buffer.array())
    }

    private fun readCentralDirectory(source: RandomAccessFile, length: Long): List<Entry> {
        require(length >= END_OF_CENTRAL_SIZE) { "不是有效的 APK 文件" }
        val endOffset = findEndOfCentralDirectory(source, length)
        val end = ByteArray(END_OF_CENTRAL_SIZE)
        source.seek(endOffset)
        source.readFully(end)
        val centralSize = readUInt32(end, 12)
        val centralOffset = readUInt32(end, 16)
        require(centralSize != UINT32_MAX && centralOffset != UINT32_MAX) { "不支持 ZIP64 的安装包" }
        require(centralSize in 1..MAX_CENTRAL_SIZE) { "中央目录大小异常" }
        require(centralOffset + centralSize <= length) { "中央目录偏移异常" }

        val directory = ByteArray(centralSize.toInt())
        source.seek(centralOffset)
        source.readFully(directory)
        val buffer = ByteBuffer.wrap(directory).order(ByteOrder.LITTLE_ENDIAN)

        val entries = ArrayList<Entry>()
        while (buffer.remaining() >= CENTRAL_HEADER_SIZE && buffer.int == CENTRAL_HEADER_SIG) {
            val versionMadeBy = buffer.short.toInt() and 0xFFFF
            val versionNeeded = buffer.short.toInt() and 0xFFFF
            val flags = buffer.short.toInt() and 0xFFFF
            val method = buffer.short.toInt() and 0xFFFF
            val dosTime = buffer.short.toInt() and 0xFFFF
            val dosDate = buffer.short.toInt() and 0xFFFF
            val crc = buffer.int.toLong() and UINT32_MAX
            val compressed = buffer.int.toLong() and UINT32_MAX
            val uncompressed = buffer.int.toLong() and UINT32_MAX
            val nameLength = buffer.short.toInt() and 0xFFFF
            val extraLength = buffer.short.toInt() and 0xFFFF
            val commentLength = buffer.short.toInt() and 0xFFFF
            buffer.short
            val internalAttrs = buffer.short.toInt() and 0xFFFF
            val externalAttrs = buffer.int.toLong() and UINT32_MAX
            val localOffset = buffer.int.toLong() and UINT32_MAX
            require(buffer.remaining() >= nameLength + extraLength + commentLength) { "中央目录结构损坏" }
            val nameBytes = ByteArray(nameLength).also { buffer.get(it) }
            val extra = ByteArray(extraLength).also { buffer.get(it) }
            val comment = ByteArray(commentLength).also { buffer.get(it) }
            val name = String(nameBytes, if (flags and FLAG_UTF8 != 0) Charsets.UTF_8 else Charsets.ISO_8859_1)
            require(localOffset + LOCAL_HEADER_SIZE <= length) { "条目偏移异常" }
            source.seek(localOffset)
            val header = ByteArray(LOCAL_HEADER_SIZE)
            source.readFully(header)
            require(readInt(header, 0) == LOCAL_HEADER_SIG) { "条目头结构损坏" }
            val dataStart = localOffset + LOCAL_HEADER_SIZE +
                (readShort(header, 26).toLong()) + (readShort(header, 28).toLong())
            entries += Entry(
                name = name,
                nameBytes = nameBytes,
                extra = extra,
                comment = comment,
                versionMadeBy = versionMadeBy,
                versionNeeded = versionNeeded,
                flags = flags,
                method = method,
                dosTime = dosTime,
                dosDate = dosDate,
                crc = crc,
                compressedSize = compressed,
                uncompressedSize = uncompressed,
                internalAttrs = internalAttrs,
                externalAttrs = externalAttrs,
                dataStart = dataStart,
                headerOffset = localOffset,
            )
        }
        require(entries.isNotEmpty()) { "APK 内没有任何条目" }
        return entries
    }

    private fun findEndOfCentralDirectory(source: RandomAccessFile, length: Long): Long {
        val window = minOf(length, SEARCH_WINDOW.toLong()).toInt()
        val buffer = ByteArray(window)
        source.seek(length - window)
        source.readFully(buffer)
        for (index in window - END_OF_CENTRAL_SIZE downTo 0) {
            if (readInt(buffer, index) != END_OF_CENTRAL_SIG) continue
            val comment = (buffer[index + 20].toInt() and 0xFF) or
                ((buffer[index + 21].toInt() and 0xFF) shl 8)
            if (index + END_OF_CENTRAL_SIZE + comment == window) return length - window + index
        }
        error("不是有效的 APK 文件")
    }

    private fun readEntry(source: RandomAccessFile, entry: Entry): ByteArray {
        require(entry.compressedSize in 0..MAX_MANIFEST) { "AndroidManifest.xml 体积异常" }
        val raw = ByteArray(entry.compressedSize.toInt())
        source.seek(entry.dataStart)
        source.readFully(raw)
        return when (entry.method) {
            STORED -> raw
            DEFLATED -> inflate(raw, entry.uncompressedSize)
            else -> error("不支持的压缩方式")
        }
    }

    private fun inflate(raw: ByteArray, size: Long): ByteArray {
        require(size in 0..MAX_MANIFEST) { "AndroidManifest.xml 体积异常" }
        val inflater = Inflater(true)
        try {
            inflater.setInput(raw)
            val out = ByteArray(size.toInt())
            var offset = 0
            while (!inflater.finished() && offset < out.size) {
                val produced = inflater.inflate(out, offset, out.size - offset)
                if (produced <= 0) break
                offset += produced
            }
            return if (offset == out.size) out else out.copyOf(offset)
        } finally {
            inflater.end()
        }
    }

    private fun compress(data: ByteArray, method: Int): ByteArray = when (method) {
        STORED -> data
        DEFLATED -> deflate(data)
        else -> error("不支持的压缩方式")
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size.coerceAtLeast(DEFLATE_CHUNK))
            val buffer = ByteArray(DEFLATE_CHUNK)
            while (!deflater.finished()) {
                val produced = deflater.deflate(buffer)
                if (produced <= 0) break
                out.write(buffer, 0, produced)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun copyRange(source: RandomAccessFile, length: Long, out: OutputStream): Long {
        if (length <= 0L) return 0L
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = length
        while (remaining > 0L) {
            val read = source.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read <= 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        require(remaining == 0L) { "APK 数据不完整" }
        return length
    }

    private fun crcFile(file: File): Long {
        val crc = CRC32()
        val buffer = ByteArray(CRC_BUFFER)
        file.inputStream().buffered(CRC_BUFFER).use { input ->
            var read = input.read(buffer)
            while (read > 0) {
                crc.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return crc.value
    }

    private fun asset(name: String): ByteArray = context.assets.open(name).use { it.readBytes() }

    private fun hasAsset(name: String): Boolean = try {
        context.assets.open(name).use { }
        true
    } catch (ignored: Throwable) {
        false
    }

    private fun nextBootDexIndex(entries: List<Entry>): Int {
        var max = 0
        var found = false
        for (entry in entries) {
            val name = entry.name
            if (name == "classes.dex") {
                found = true
                max = maxOf(max, 1)
            } else if (name.startsWith("classes") && name.endsWith(".dex")) {
                val index = name.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: continue
                found = true
                max = maxOf(max, index)
            }
        }
        return if (!found) 1 else max + 1
    }

    private fun readRecordedSigning(apk: File, log: PatchLog): Pair<String, String>? {
        val names = listOf(
            CONFIG_ENTRY,
            LEGACY_DIR + "config.json",
            "fpa/config.json",
            "npatch/config.json",
            "assets/npatch/config.json",
            "lspatch/config.json",
            "assets/lspatch/config.json",
        )
        return runCatching {
            ZipFile(apk).use { zip ->
                for (name in names) {
                    val entry = zip.getEntry(name) ?: continue
                    if (entry.size <= 0 || entry.size > MAX_RECORDED_CONFIG) continue
                    val text = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
                    val json = JSONObject(text)
                    val sign = json.optString("sign", "")
                    val details = json.optString("signing_details", "")
                    if (sign.isBlank() && details.isBlank()) continue
                    log.debug("从旧产物 $name 读取原签名作为回退（sign=${sign.length}B，details=${details.length}B）")
                    return@use sign to details
                }
                null
            }
        }.onFailure { log.debug("读取旧产物签名失败：${it.message}") }.getOrNull()
    }

    private fun assertSigningOrigin(
        source: File,
        recorded: Pair<String, String>?,
        hadInjected: Boolean,
        embedSource: Boolean,
        options: Options,
        log: PatchLog,
    ) {
        if (!hadInjected || embedSource) return
        if (recorded != null) return
        val own = runCatching { signaturesJson(packageInfoOf(source)) }.getOrNull()
        if (own.isNullOrBlank() || own == "[]") return
        log.step(
            "输入是上一代补丁产物、没有内嵌原包、也读不到旧 config 里的原签名。" +
                "新产物的过签会拿 XPatch 证书当原证书，等于关掉签名伪装。",
        )
        error(
            "无法确认原始签名：输入是旧补丁产物（无内嵌原包），且旧产物里没有可用的 sign/signing_details。" +
                "请改用原始 APK 重新打包；否则产物里的签名伪装会指向 XPatch 自己的证书而失效。",
        )
    }

    private fun buildConfig(
        info: PackageInfo?,
        originalFactory: String?,
        options: Options,
        apk: File,
        layout: InjectLayout?,
        recordedSign: String? = null,
        recordedSigningDetails: String? = null,
    ): String {
        val json = JSONObject()
        json.put("hook_core", options.hookCore)
        json.put("app_component_name", originalFactory.orEmpty())
        json.put("sign", recordedSign ?: signaturesJson(info))
        json.put("sig_mode", normalizeSigMode(options.sigMode))
        json.put("signing_details", recordedSigningDetails ?: signingDetailsJson(apk))
        json.put("ver", RUNTIME_VERSION)
        json.put("build_nonce", nonce())
        json.put("bypass_sign_type", options.bypassLevel)
        json.put("dynamic_mode", false)
        json.put("file_provider", options.fileProvider)
        json.put("manager_package", context.packageName)
        json.put("real_version_code", info?.longVersionCode ?: 0L)
        json.put("module_paths", JSONArray())
        layout?.intoJson(json)
        return json.toString().replace("\\/", "/")
    }

    private fun signaturesJson(info: PackageInfo?): String {
        val array = JSONArray()
        val signs = info?.signatures ?: return array.toString()
        for (sign in signs) {
            array.put(sign.toByteArray().toHex())
        }
        return array.toString()
    }

    fun signingDetailsJson(apk: File): String {
        val json = JSONObject()
        @Suppress("NewApi")
        val signing = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfoOf(apk, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo
            } else {
                null
            }
        }.getOrNull()
        if (signing != null) {
            @Suppress("NewApi")
            val apkCerts = runCatching { signing.apkContentsSigners }.getOrNull()
            @Suppress("NewApi")
            val history = runCatching { signing.signingCertificateHistory }.getOrNull()
            val current = if (apkCerts.isNullOrEmpty()) legacySignatures(apk) else apkCerts
            val chain = if (history.isNullOrEmpty()) current else history
            val rotating = current != null && chain != null &&
                current.size == 1 && chain.isNotEmpty() &&
                chain[0].toByteArray().contentEquals(current[0].toByteArray()) &&
                chain.size == 1
            val stale = if (rotating) current else chain
            json.put("scheme", signingSchemeVersion(signing))
            json.put("apk", certificatesJson(current))
            json.put("history", certificatesJson(stale))
            json.put("oldest", certificatesJson(oldestCertificate(current, chain)))
            return json.toString()
        }
        val legacy = legacySignatures(apk)
        json.put("scheme", 1)
        json.put("apk", certificatesJson(legacy))
        json.put("history", certificatesJson(legacy))
        json.put("oldest", certificatesJson(legacy))
        return json.toString()
    }

    @Suppress("NewApi")
    private fun oldestCertificate(
        current: Array<Signature>?,
        chain: Array<Signature>?,
    ): Array<Signature>? {
        if (chain.isNullOrEmpty()) return current
        if (current == null || current.isEmpty()) return chain
        if (chain.size < 2) return current
        val sameAsCurrent = chain[0].toByteArray().contentEquals(current[0].toByteArray())
        return if (sameAsCurrent) arrayOf(chain[chain.size - 1]) else arrayOf(chain[0])
    }

    @Suppress("NewApi")
    private fun signingSchemeVersion(info: SigningInfo): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return 1
        val value = runCatching { info.schemeVersion }.getOrDefault(0)
        return if (value > 0) value else 3
    }

    private fun legacySignatures(apk: File): Array<Signature>? =
        runCatching { packageInfoOf(apk)?.signatures }.getOrNull()

    private fun certificatesJson(signatures: Array<Signature>?): String {
        val array = JSONArray()
        signatures?.forEach { array.put(it.toByteArray().toHex()) }
        return array.toString()
    }

    private fun normalizeSigMode(mode: String): String {
        val value = mode.trim().lowercase()
        return if (value in SIG_MODES) value else SIG_MODE_DEFAULT
    }

    private fun packageInfoOf(apk: File): PackageInfo? {
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNATURES
        return packageInfoOf(apk, flags)
    }

    private fun packageInfoOf(apk: File, flags: Int): PackageInfo? = runCatching {
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
    }.getOrNull()

    private fun nonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    private fun ByteArray.toHex(): String {
        val text = StringBuilder(size * 2)
        for (byte in this) {
            text.append(HEX[(byte.toInt() shr 4) and 0x0F])
            text.append(HEX[byte.toInt() and 0x0F])
        }
        return text.toString()
    }

    private fun crc32(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }

    private fun readInt(buffer: ByteArray, offset: Int): Int =
        (buffer[offset].toInt() and 0xFF) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 3].toInt() and 0xFF) shl 24)

    private fun readUInt32(buffer: ByteArray, offset: Int): Long = readInt(buffer, offset).toLong() and UINT32_MAX

    private fun readShort(buffer: ByteArray, offset: Int): Int =
        (buffer[offset].toInt() and 0xFF) or ((buffer[offset + 1].toInt() and 0xFF) shl 8)

    private class Entry(
        val name: String,
        val nameBytes: ByteArray,
        val extra: ByteArray,
        val comment: ByteArray,
        val versionMadeBy: Int,
        val versionNeeded: Int,
        val flags: Int,
        val method: Int,
        val dosTime: Int,
        val dosDate: Int,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val internalAttrs: Int,
        val externalAttrs: Long,
        val dataStart: Long,
        val headerOffset: Long = 0L,
    )

    private class Record(
        val entry: Entry,
        val offset: Long,
        val crc: Long,
        val compressed: Long,
        val uncompressed: Long,
        val virtual: Boolean = false,
    )

    private sealed class Plan {
        class Carried(val entry: Entry) : Plan()
        class Injected(val added: Added) : Plan()
    }

    private class Added(
        val name: String,
        val method: Int,
        val data: (() -> ByteArray)?,
    ) {
        var dosTime: Int = 0
        var dosDate: Int = DOS_DATE_EPOCH
    }

    private companion object {
        const val MANIFEST = "AndroidManifest.xml"
        const val SOURCE_ENTRY = "xp/source.apk"
        const val ORIGINAL_MANIFEST_ENTRY = "xp/source.manifest"
        const val CORE_ENTRY = "xp/core.dex"
        const val CONFIG_ENTRY = "xp/config.json"
        const val HOOK_DEX_ENTRY = "xp/hook_core.dex"
        const val HOOK_SO_ENTRY = "xp/hook_core.so"
        const val STUB_CLASS = "io.xpatch.stub.AppEntry"
        const val MT_PROVIDER_CLASS = "bin.mt.file.content.MTDataFilesProvider"
        const val MT_WAKEUP_CLASS = "bin.mt.file.content.MTDataFilesWakeUpActivity"

        const val MODULE_CRASH_PERMISSION = "io.xpatch.permission.MODULE_CRASH"
        const val ATTR_FACTORY_NAME = "appComponentFactory"
        const val ATTR_NAME = "name"
        const val RUNTIME_VERSION = 42

        val SIG_MODES = setOf("off", "java", "java_file", "deep")
        const val SIG_MODE_DEFAULT = "java"

        
        val LEGACY_STUB_CLASSES = setOf(
            "fpa.AppEntry",
            "xpt.stub.AppEntry",
            "io.xpatch.stub.AppEntry",
        )

        val STUB_CLASS_DESCRIPTORS = setOf(
            "Lfpa/AppEntry;",
            "Lxpt/stub/AppEntry;",
            "Lio/xpatch/stub/AppEntry;",
        )

        val LEGACY_PROVIDER_CLASSES = setOf(
            "fpa.XpProvider",
            "xpt.stub.XpProvider",
            "io.xpatch.stub.XpProvider",
        )

        const val LEGACY_WAKEUP_CLASS = "bin.mt.file.content.MTDataFilesWakeUpActivity"

        val INJECTED_NAMES = setOf(
            "xp/core.dex",
            "xp/hook_core.dex",
            "xp/hook_core.so",
            "xp/hook_core_x86_64.so",
            "xp/xp89.dex",
            "xp/xp100.dex",
            "xp/xp101.dex",
            "xp/xp102.dex",
            "xp/source.apk",
            "xp/o_app.apk",
            ORIGINAL_MANIFEST_ENTRY,
            "xp/config.json",
            "xp/pkg",
            "xp/fp",
            "xp/file_provider",
            LEGACY_DIR + "config.json",
            LEGACY_DIR + "core.dex",
        )

        const val LEGACY_DIR = "xpt/"

        val LEGACY_PREFIXES = listOf(
            "fpa/",
            LEGACY_DIR,
            "npatch/",
            "assets/npatch/",
            "lspatch/",
            "assets/lspatch/",
        )

        private fun legacyFamily(entries: List<Entry>): String? = when {
            entries.any { it.name.startsWith("fpa/") } -> "FPA"
            entries.any { it.name.startsWith(LEGACY_DIR) } -> "XPT"
            entries.any {
                it.name.startsWith("npatch/") || it.name.startsWith("assets/npatch/")
            } -> "NPatch"
            entries.any {
                it.name.startsWith("lspatch/") || it.name.startsWith("assets/lspatch/")
            } -> "LSPatch"
            else -> null
        }

        val LEGACY_NAMES = setOf(
            "assets/xposed_init",
            "META-INF/xposed/module.prop",
            "META-INF/xposed/java_init.list",
        )

        val INJECTED_PREFIXES = listOf(
            "xp/",
            "fpa/",
            LEGACY_DIR,
            "assets/npatch/",
            "lspatch/",
            "assets/lspatch/",
        )

        val SOURCE_NAMES = listOf(
            "xp/source.apk",
            "fpa/source.apk",
            "xp/o_app.apk",
            "assets/npatch/origin.apk",
            "lspatch/origin.apk",
            "assets/lspatch/origin.apk",
        )

        val KNOWN_LAYOUTS = SOURCE_NAMES.toSet()

        val SOURCE_HINTS = listOf("source", "origin", "original", "base", "raw")

        const val APK_SUFFIX = ".apk"
        const val MIN_EMBEDDED = 100L * 1024
        const val MAX_EMBED_OVERHEAD = 32L * 1024 * 1024
        const val MAX_STRIP_DEPTH = 5
        const val NESTED_APK_COST_LIMIT = 0.25
        const val MAX_BOOTDEX = 2L * 1024 * 1024
        const val MAX_RECORDED_CONFIG = 256L * 1024

        fun isInjectedEntry(name: String): Boolean =
            name in INJECTED_NAMES ||
                LEGACY_PREFIXES.any { name.startsWith(it) } ||
                name in LEGACY_NAMES

        fun isBootDexName(name: String): Boolean =
            name == "classes.dex" ||
                (name.startsWith("classes") && name.endsWith(".dex") &&
                    name.removePrefix("classes").removeSuffix(".dex").toIntOrNull() != null)

        fun preferredSourceNames(preferred: String?): List<String> {
            val names = LinkedHashSet<String>()
            preferred?.trim()?.takeIf { it.isNotEmpty() }?.let { names += it }
            names += SOURCE_NAMES
            return names.toList()
        }

        fun embeddedSourceOf(name: String): Boolean = name in SOURCE_NAMES

        const val STORED = 0
        const val DEFLATED = 8

        const val LOCAL_HEADER_SIG = 0x04034b50
        const val CENTRAL_HEADER_SIG = 0x02014b50
        const val END_OF_CENTRAL_SIG = 0x06054b50

        const val LOCAL_HEADER_SIZE = 30
        const val CENTRAL_HEADER_SIZE = 46
        const val END_OF_CENTRAL_SIZE = 22

        const val FLAG_DATA_DESCRIPTOR = 0x0008
        const val FLAG_UTF8 = 0x0800
        const val HOST_SYSTEM = 20
        const val VERSION_NEEDED = 20
        const val DOS_DATE_EPOCH = 0x0021

        const val ALIGN_ID = 0xd935
        const val ALIGN_FIELD_OVERHEAD = 6
        const val DATA_ALIGNMENT = 4
        const val PAGE_ALIGNMENT = 16 * 1024
        const val MAX_EXTRA_FIELD = 0xFFFF

        const val UINT32_MAX = 0xFFFFFFFFL
        const val UINT16_MAX = 0xFFFFL

        const val BUFFER_SIZE = 256 * 1024
        const val CRC_BUFFER = 256 * 1024
        const val DEFLATE_CHUNK = 16 * 1024
        const val SEARCH_WINDOW = 0xFFFF + END_OF_CENTRAL_SIZE
        const val MAX_CENTRAL_SIZE = 64L * 1024 * 1024
        const val MAX_MANIFEST = 32L * 1024 * 1024
        const val DONE_PROGRESS = 55

        val EMPTY = ByteArray(0)
        val HEX = "0123456789abcdef".toCharArray()
    }
}


