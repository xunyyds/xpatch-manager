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

class ApkPatcher(private val context: Context) {

    data class Options(
        val bypassLevel: Int = 3,
        val hookCore: Int = 0,
        val dynamicMode: Boolean = false,
        val fileProvider: Boolean = false,
        val injectSignature: Boolean = true,
        val versionName: String? = null,
        val versionCode: Int? = null,
        val embedSource: Boolean = false,
        val sigMode: String = "java",
        val entryHook: Boolean = false,
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
        onLog: (String) -> Unit = {},
    ): Result {
        onLog("读取目标应用清单")
        onProgress(10)

        var effectiveSource = sourceApk
        var entries: List<Entry>
        var manifest: ByteArray
        var originalManifest: ByteArray
        val originalFactory: String?
        var providerReady = false
        val temps = ArrayList<File>()

        RandomAccessFile(sourceApk, "r").use { source ->
            entries = readCentralDirectory(source, sourceApk.length())
        }
        onLog("解析完成，共 ${entries.size} 个条目")

        var strip = discoverLayout(sourceApk, entries, onLog)
        val outerLayout = strip.layout
        val injectedBefore = entries.any { strip.isInjected(it.name) || strip.isEmbeddedSource(it.name) }
        val beforeBootDex = entries.size
        RandomAccessFile(sourceApk, "r").use { source ->
            entries = dropStaleBootDex(source, entries, onLog)
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
            entries.any { strip.isInjected(it.name) || strip.isEmbeddedSource(it.name) }
        ) {
            val legacyFamily = if (strip.layout == null) legacyFamily(entries) else null
            onLog(
                if (depth == 0) {
                    when {
                        strip.layout != null -> "检测到本框架混淆产物，按布局剥离后重新打包"
                        legacyFamily != null -> "检测到历史注入层，剥离后重新打包"
                        else -> "检测到源包已注入，剥离旧内核层"
                    }
                } else {
                    "第 ${depth + 1} 层仍有注入痕迹，继续剥离"
                },
            )
            if (depth == 0 && legacyFamily != null) {
                onLog("源包可能由 $legacyFamily 旧签名签发，覆盖安装需在打包页选择对应签名，或卸载后重装")
            }
            val stripped = try {
                stripInjected(effectiveSource, entries, strip, onLog, strip.sourceEntry)
            } catch (ambiguous: EmbeddedSource.AmbiguousException) {
                onLog("内嵌来源歧义，停止打包：${ambiguous.message}")
                throw ambiguous
            }
            val extracted = stripped.file
            if (extracted == null) {
                if (stripped.rejected.isNotEmpty()) {
                    onLog("内嵌来源候选全部未通过验证（共 ${stripped.candidates.size} 个候选）")
                }
                if (stripped.unproven.isNotEmpty() && (options.dynamicMode || options.embedSource)) {
                    error(
                        "无法证明内嵌包是原始包，请改用原始 APK：外层有同包名内嵌包但内容对不上（" +
                            stripped.unproven.take(3).joinToString("，") + "）",
                    )
                }
                onLog("第 ${depth + 1} 层无法提取内嵌原始包，仅移除注入条目")
                entries = dropOverlappingNested(
                    effectiveSource,
                    entries.filter { !strip.isInjected(it.name) },
                    onLog,
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
                strip = discoverLayout(effectiveSource, entries, onLog)
            }
        }

        if (effectiveSource != sourceApk) {
            onLog("剥离完成，共 $depth 层，最终源 ${effectiveSource.length() / 1024 / 1024} MB")
        }
        RandomAccessFile(effectiveSource, "r").use { source ->
            entries = dropStaleBootDex(source, entries, onLog)
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
                onLog = onLog,
                ourStubs = ourStubFactories,
            )
            inputFactory = sanitizeFactory(
                editor.readApplicationAttribute(ATTR_FACTORY_NAME),
                onLog,
                ourStubFactories,
            )
        }

        val signedSource = effectiveSource
        val rebuilt = options.embedSource && hadInjected && effectiveSource == sourceApk
        if (rebuilt) {
            val clean = File(
                File(context.cacheDir, "patch").apply { mkdirs() },
                "clean-source.${System.nanoTime()}.apk",
            )
            onLog("内嵌来源：按剥离结果重建原始安装包（不含注入条目）")
            materializeCleanSource(effectiveSource, clean, entries, originalManifest)
            temps += clean
            effectiveSource = clean
            RandomAccessFile(effectiveSource, "r").use { source ->
                entries = readCentralDirectory(source, effectiveSource.length())
            }
            onLog("已重建干净原始安装包 ${clean.length() / 1024 / 1024} MB，共 ${entries.size} 个条目")
        }
        assertClean(entries, effectiveSource, onLog, strip, options.embedSource)

        if (options.dynamicMode && options.embedSource) {
            val before = entries.size
            entries = entries.filter { entry ->
                !isBootDexName(entry.name) &&
                    !entry.name.startsWith("META-INF/") &&
                    entry.name != "stamp-cert-sha256"
            }
            onLog("动态加载：保留 ${entries.size}/$before 个条目（清单/资源/assets/原生库/根目录资源），代码由原包提供")
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
                    providerDashed = if (options.entryHook && !options.dynamicMode) stub.providerDashed else null,
                    seed = obfuscationSeed,
                )
            }.mapCatching { generated ->
                if (hasAsset("xp/hook_core_x86_64.so") || generated.entry(Logical.HOOK_SO_X86_64) == null) {
                    generated
                } else {
                    generated.copy(entries = generated.entries - Logical.HOOK_SO_X86_64)
                }
            }.onFailure { onLog("生成注入布局失败（${it.message}），本次按不混淆打包") }.getOrNull()
        }
        if (options.obfuscate && layout == null) {
            stub = StubRewriter.skipped(stubAsset, "注入布局生成失败")
        }
        if (layout != null) {
            onLog("注入路径混淆：开（目录 ${layout.dir}，类名 ${layout.factoryDashed}）")
            if (!stub.renamed) {
                onLog("类名混淆已降级，清单仍指向 ${stub.factoryDashed}（${stub.reason}）")
            } else if (outerLayout != null) {
                onLog("复用上一版产物的类名：${layout.factoryDashed}")
            }
        } else if (!options.obfuscate) {
            onLog("注入路径混淆：关（沿用 xp/ 前缀与 io.xpatch.stub 类名）")
        } else {
            onLog("注入路径混淆：关（布局生成失败，本次按旧名字打包）")
        }
        val staleProvider = outerLayout?.providerDashed
        if (options.obfuscate && options.entryHook && !options.dynamicMode &&
            staleProvider != null && staleProvider != layout?.providerDashed
        ) {
            onLog("提示：输入清单里的旧 provider（$staleProvider）无法复用，可能残留指向旧类名的入口")
        }

        val manifestBase = if (rebuilt) originalManifest else inputManifest
        val editor = AxmlEditor(manifestBase)
        val manifestPackage = editor.readAttribute("manifest", "package").orEmpty()
        originalFactory = inputFactory ?: recoverFactory(originalManifest, onLog, ourStubFactories)

        val factoryName = layout?.factoryDashed ?: STUB_CLASS
        val providerName = layout?.providerDashed ?: XP_PROVIDER_CLASS
        var patched = editor.setApplicationAttribute(ATTR_FACTORY_NAME, factoryName)
        if (options.entryHook && !options.dynamicMode) {
            if (manifestPackage.isBlank()) {
                onLog("清单缺少 package 属性，无法生成 provider authority，仅保留工厂注入")
            } else {
                val authority = if (layout != null) {
                    "$manifestPackage.${layout.hex8}"
                } else {
                    "$manifestPackage.xpatch"
                }
                if (editor.hasProvider(providerName, authority)) {
                    onLog("清单已含 $providerName，复用既有 provider，不重复插入")
                }
                patched = AxmlEditor(patched).addProvider(providerName, authority)
                providerReady = true
            }
        }
        if (options.fileProvider) {
            if (manifestPackage.isBlank()) {
                onLog("清单缺少 package 属性，无法生成文件提供器 authority，跳过文件提供器注入")
            } else {
                val authority = "$manifestPackage.MTDataFilesProvider"
                val editorNow = AxmlEditor(patched)
                if (editorNow.hasProvider(MT_PROVIDER_CLASS, authority)) {
                    onLog("清单已含 $MT_PROVIDER_CLASS，复用既有文件提供器")
                } else {
                    patched = editorNow.addDocumentsProvider(
                        MT_PROVIDER_CLASS,
                        authority,
                        MT_WAKEUP_CLASS,
                        "$manifestPackage.MTDataFilesWakeUp",
                    )
                    onLog("已注入 MT 文件提供器：authority=$authority")
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
        onLog(if (providerReady) "已写入工厂注入（+ provider 备用入口）" else "已写入工厂注入")
        manifest = if (options.obfuscate && !options.obfuscateKeepCrashPermission) {
            onLog("注入路径混淆已开启：跳过 $MODULE_CRASH_PERMISSION，模块崩溃时管理器收不到熔断广播")
            manifest
        } else {
            injectModuleCrashPermission(manifest, onLog)
        }
        if (options.versionName?.isNotBlank() == true || options.versionCode != null) {
            onLog("自定义版本：" + listOfNotNull(
                options.versionName?.takeIf { it.isNotBlank() }?.let { "versionName=$it" },
                options.versionCode?.let { "versionCode=$it" },
            ).joinToString("，"))
        }
        onProgress(25)

        val packageInfo = packageInfoOf(signedSource)
        val config = buildConfig(packageInfo, originalFactory, options, signedSource, layout)

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
                onLog = onLog,
                embedSource = options.embedSource,
                layout = layout,
            )
        } finally {
            embeddedReady.countDown()
            runCatching { crcWorker.join(2000) }
            temps.forEach { temp -> runCatching { temp.delete() } }
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
        onLog: (String) -> Unit,
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
                    onLog("config $found 没有 res_cfg，按旧产物处理")
                }
            }
        }
        if (layout != null) {
            onLog("发现本框架混淆布局：目录 ${layout.dir}（config $found）")
        }
        return InjectedEntrySet(
            layout = layout,
            legacyInjected = { name -> isInjectedEntry(name) },
            legacyEmbeddedSource = { name -> embeddedSourceOf(name) },
        )
    }

    private fun sanitizeFactory(
        factory: String?,
        onLog: (String) -> Unit,
        ourStubs: Set<String> = LEGACY_STUB_CLASSES,
    ): String? {
        if (factory.isNullOrBlank()) return null
        if (factory in ourStubs) {
            onLog("源包工厂指向引导桩（$factory），已忽略以免自我委派")
            return null
        }
        return factory
    }

    private fun pickOriginalManifest(
        recorded: ByteArray?,
        input: ByteArray,
        expectedPackage: String,
        onLog: (String) -> Unit,
        ourStubs: Set<String> = LEGACY_STUB_CLASSES,
    ): ByteArray {
        if (recorded == null) return input
        val parsed = runCatching { AxmlEditor(recorded) }.getOrNull()
        val packageName = parsed?.let { runCatching { it.readAttribute("manifest", "package") }.getOrNull() }
        if (parsed == null || packageName == null) {
            onLog("xp/source.manifest 解析失败，退回输入清单作为原始清单")
            return input
        }
        if (expectedPackage.isNotBlank() && packageName != expectedPackage) {
            onLog("xp/source.manifest 的包名 $packageName 与输入清单不一致，退回输入清单")
            return input
        }
        val factory = runCatching { parsed.readApplicationAttribute(ATTR_FACTORY_NAME) }.getOrNull()
        if (factory != null && factory in ourStubs) {
            onLog("xp/source.manifest 已含引导桩工厂，更早的原始清单已不可恢复")
        } else {
            onLog("复用上一版记录的原始清单 xp/source.manifest")
        }
        return recorded
    }

    private fun recoverFactory(
        originalManifest: ByteArray,
        onLog: (String) -> Unit,
        ourStubs: Set<String> = LEGACY_STUB_CLASSES,
    ): String? {
        val factory = runCatching {
            AxmlEditor(originalManifest).readApplicationAttribute(ATTR_FACTORY_NAME)
        }.getOrNull()
        if (factory.isNullOrBlank() || factory in ourStubs) return null
        onLog("从原始清单恢复 appComponentFactory=$factory")
        return factory
    }

    private fun injectModuleCrashPermission(manifest: ByteArray, onLog: (String) -> Unit): ByteArray {
        val editor = AxmlEditor(manifest)
        return try {
            if (editor.hasUsesPermission(MODULE_CRASH_PERMISSION)) {
                onLog("清单已含 $MODULE_CRASH_PERMISSION，跳过权限注入")
                manifest
            } else {
                val patched = editor.addUsesPermission(MODULE_CRASH_PERMISSION)
                onLog("已写入权限声明 $MODULE_CRASH_PERMISSION")
                patched
            }
        } catch (error: Exception) {
            onLog("清单注入权限失败，模块保护广播可能不可达（${error.javaClass.simpleName}: ${error.message}）")
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
        onLog: (String) -> Unit,
    ): List<Entry> {
        val stale = entries.filter { entry ->
            if (!isBootDexName(entry.name)) return@filter false
            if (entry.compressedSize !in 1..MAX_BOOTDEX) return@filter false
            val body = runCatching { readRawEntry(source, entry) }.getOrNull() ?: return@filter false
            val text = String(body, Charsets.ISO_8859_1)
            text.contains("Lio/xpatch/stub/AppEntry;") ||
                text.contains("Lfpa/AppEntry;") ||
                text.contains("xp/core.dex")
        }
        if (stale.isEmpty()) return entries
        stale.forEach { onLog("移除旧引导桩 ${it.name}") }
        val names = stale.mapTo(HashSet()) { it.name }
        return entries.filter { it.name !in names }
    }

    private fun dropOverlappingNested(
        sourceApk: File,
        entries: List<Entry>,
        onLog: (String) -> Unit,
    ): List<Entry> {
        val outerSize = sourceApk.length()
        if (outerSize <= 0) return entries
        val oversized = entries.filter {
            it.name.endsWith(APK_SUFFIX, ignoreCase = true) &&
                it.uncompressedSize >= MIN_EMBEDDED &&
                it.compressedSize.toDouble() / outerSize >= NESTED_APK_COST_LIMIT
        }
        if (oversized.isEmpty()) return entries
        oversized.forEach {
            onLog(
                "移除未还原的内嵌包 ${it.name}，写入代价占外层 " +
                    "${"%.0f".format(it.compressedSize * 100.0 / outerSize)}%，避免产物翻倍",
            )
        }
        val names = oversized.mapTo(HashSet()) { it.name }
        return entries.filter { it.name !in names }
    }

    private fun assertClean(
        entries: List<Entry>,
        sourceApk: File,
        onLog: (String) -> Unit,
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
        val leftover = stripInjected(sourceApk, scope, strip, onLog, null)
        val found = leftover.file
        if (found != null) {
            val name = leftover.entry
            found.delete()
            error("源包内仍有可验证的内嵌原始包（$name），剥离未完成，拒绝打包以免产物翻倍")
        }
        val outerSize = sourceApk.length()
        if (outerSize <= 0) return
        val heavy = entries.filter {
            it.name.endsWith(APK_SUFFIX, ignoreCase = true) &&
                it.uncompressedSize >= MIN_EMBEDDED &&
                it.compressedSize.toDouble() / outerSize >= NESTED_APK_COST_LIMIT
        }
        require(heavy.isEmpty()) {
            "源包内嵌大体积 APK，产物必然接近翻倍：" +
                heavy.take(3).map { "${it.name} ${it.compressedSize / 1024 / 1024} MB" }
        }
    }

    private fun stripInjected(
        sourceApk: File,
        entries: List<Entry>,
        strip: InjectedEntrySet,
        onLog: (String) -> Unit,
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
                onLog = onLog,
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
        onLog: (String) -> Unit,
        embedSource: Boolean,
        layout: InjectLayout?,
    ) {
        fun nameOf(logical: Logical, legacy: String): String = layout?.entry(logical) ?: legacy
        val sourceEntryName = nameOf(Logical.SOURCE, SOURCE_ENTRY)

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
            if (embedSource) add(Added(sourceEntryName, STORED, null))
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
            onLog("注入条目时间戳跟随原包 ${timestampText(stamp)}")
        }
        val plan = carried.map { Plan.Carried(it) } + added.map { Plan.Injected(it) }

        val total = plan.size
        onLog("开始写入 $total 个条目（保留 ${carried.size}，注入 ${added.size}）")
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

        FileOutputStream(outputApk, false).buffered(BUFFER_SIZE).use { out ->
            var position = 0L

            RandomAccessFile(sourceApk, "r").use { source ->
                onLog("注入内核与引导桩 $bootDexName")

                for (step in plan) {
                    when (step) {
                        is Plan.Carried -> {
                            val written = writeCarried(source, step.entry, manifest, out, position)
                            position += written.first
                            records += written.second
                            advance()
                        }

                        is Plan.Injected -> {
                            val item = step.added
                            if (item.name == sourceEntryName) {
                                onLog("内嵌原始安装包 ${item.name}")
                                embeddedReady.await()
                                embeddedFailure.get()?.let { throw it }
                                val length = sourceApk.length()
                                val crc = embeddedCrc.get()
                                val entry = addedEntry(item.name, STORED, item.dosTime, item.dosDate)
                                val headerOffset = position
                                val header = localHeader(entry, headerOffset, crc, length, length)
                                out.write(header)
                                position += header.size
                                source.seek(0)
                                position += copyRange(source, length, out)
                                records += Record(entry, headerOffset, crc, length, length)
                                advance()
                                continue
                            }
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
        }
        onLog("写入完成，产物 ${outputApk.length() / 1024 / 1024} MB")
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

    private fun materializeCleanSource(
        sourceApk: File,
        outputApk: File,
        entries: List<Entry>,
        manifest: ByteArray,
    ) {
        outputApk.delete()
        val kept = entries.filterNot { isSignatureEntry(it.name) }
        val records = ArrayList<Record>(kept.size)
        FileOutputStream(outputApk, false).buffered(BUFFER_SIZE).use { out ->
            var position = 0L
            RandomAccessFile(sourceApk, "r").use { source ->
                for (entry in kept) {
                    val written = writeCarried(source, entry, manifest, out, position)
                    position += written.first
                    records += written.second
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
        }
        require(outputApk.length() > 0L) { "重建原始安装包失败：产物为空" }
    }

    private fun isSignatureEntry(name: String): Boolean {
        if (name == "stamp-cert-sha256") return true
        if (!name.startsWith("META-INF/")) return false
        val leaf = name.substringAfterLast('/').uppercase()
        return leaf == "MANIFEST.MF" || leaf.startsWith("SIG-") ||
            leaf.endsWith(".SF") || leaf.endsWith(".RSA") || leaf.endsWith(".DSA")
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
    ): ByteArray {
        require(compressed in 0..UINT32_MAX && uncompressed in 0..UINT32_MAX) { "条目超出 ZIP 格式上限" }
        val extra = alignedExtra(entry, headerOffset)
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

    private fun alignedExtra(entry: Entry, headerOffset: Long): ByteArray {
        val multiple = alignmentOf(entry).toLong()
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
        buffer.putShort((entry.flags and FLAG_DATA_DESCRIPTOR.inv()).toShort())
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

    private fun buildConfig(
        info: PackageInfo?,
        originalFactory: String?,
        options: Options,
        apk: File,
        layout: InjectLayout?,
    ): String {
        val json = JSONObject()
        json.put("hook_core", options.hookCore)
        json.put("app_component_name", originalFactory.orEmpty())
        json.put("sign", signaturesJson(info))
        json.put("sig_mode", normalizeSigMode(options.sigMode))
        json.put("entry_hook", options.entryHook && !options.dynamicMode)
        json.put("signing_details", signingDetailsJson(apk))
        json.put("ver", RUNTIME_VERSION)
        json.put("build_nonce", nonce())
        json.put("bypass_sign_type", options.bypassLevel)
        json.put("dynamic_mode", options.dynamicMode)
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
            json.put("scheme", signingSchemeVersion(signing))
            json.put("apk", certificatesJson(current))
            json.put("history", certificatesJson(if (history.isNullOrEmpty()) current else history))
            return json.toString()
        }
        val legacy = legacySignatures(apk)
        json.put("scheme", 1)
        json.put("apk", certificatesJson(legacy))
        json.put("history", certificatesJson(legacy))
        return json.toString()
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
    )

    private class Record(
        val entry: Entry,
        val offset: Long,
        val crc: Long,
        val compressed: Long,
        val uncompressed: Long,
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
        const val XP_PROVIDER_CLASS = "io.xpatch.stub.XpProvider"
        const val MT_PROVIDER_CLASS = "bin.mt.file.content.MTDataFilesProvider"
        const val MT_WAKEUP_CLASS = "bin.mt.file.content.MTDataFilesWakeUpActivity"

        const val MODULE_CRASH_PERMISSION = "io.xpatch.permission.MODULE_CRASH"
        const val ATTR_FACTORY_NAME = "appComponentFactory"
        const val RUNTIME_VERSION = 42

        val SIG_MODES = setOf("off", "java", "java_file", "deep")
        const val SIG_MODE_DEFAULT = "java"

        
        val LEGACY_STUB_CLASSES = setOf(
            "fpa.AppEntry",
            "xpt.stub.AppEntry",
            "io.xpatch.stub.AppEntry",
        )

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
        const val MAX_STRIP_DEPTH = 5
        const val NESTED_APK_COST_LIMIT = 0.25
        const val MAX_BOOTDEX = 2L * 1024 * 1024

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


