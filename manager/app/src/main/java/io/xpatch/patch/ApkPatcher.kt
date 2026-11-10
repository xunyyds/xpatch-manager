package io.xpatch.patch

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
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
    )

    data class Result(
        val output: File,
        val originalFactory: String?,
        val bootDexName: String,
        val config: String,
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

        // 上一版产物里留存的原始清单。重复打包时输入清单可能已经被我们改写过，
        // 直接把它写回 xp/source.manifest 会让"重建原包"带着 io.xpatch.stub 的痕迹（审查 P2-17），
        // 所以在剥离前先把这份正确值读出来备用。
        val recordedManifest = runCatching {
            RandomAccessFile(sourceApk, "r").use { src ->
                entries.firstOrNull { it.name == ORIGINAL_MANIFEST_ENTRY }?.let { readEntry(src, it) }
            }
        }.getOrNull()

        var depth = 0
        while (
            depth < MAX_STRIP_DEPTH &&
            entries.any { isInjectedEntry(it.name) || embeddedSourceOf(it.name) }
        ) {
            val legacyFamily = legacyFamily(entries)
            onLog(
                if (depth == 0) {
                    if (legacyFamily != null) "检测到历史注入层，剥离后重新打包"
                    else "检测到源包已注入，剥离旧内核层"
                } else {
                    "第 ${depth + 1} 层仍有注入痕迹，继续剥离"
                },
            )
            if (depth == 0 && legacyFamily != null) {
                onLog("源包可能由 $legacyFamily 旧签名签发，覆盖安装需在打包页选择对应签名，或卸载后重装")
            }
            val stripped = stripInjected(effectiveSource, entries, onLog)
            if (stripped == null) {
                onLog("第 ${depth + 1} 层无法提取内嵌原始包，仅移除注入条目")
                entries = dropOverlappingNested(
                    effectiveSource,
                    entries.filter { !isInjectedEntry(it.name) },
                    onLog,
                )
                break
            }
            temps += stripped
            effectiveSource = stripped
            RandomAccessFile(effectiveSource, "r").use { source ->
                entries = readCentralDirectory(source, effectiveSource.length())
            }
            depth++
        }

        // 兜底闸门：要内嵌原包时，最终源自身还带 xp/source.apk 说明上面的剥离没真正
        // 还原到原始包；此时再写一份 source.apk 就是把上一代补丁包嵌套进来，体积逐次翻倍。
        // 宁可让这次打包失败并报错，也不能生成一个会无限膨胀的产物。
        if (options.embedSource) {
            val stillInjected = runCatching {
                java.util.zip.ZipFile(effectiveSource).use { zip ->
                    java.util.Collections.list(zip.entries()).any { entry ->
                        INJECTED_PREFIXES.any { entry.name.startsWith(it) }
                    }
                }
            }.getOrDefault(false)
            check(!stillInjected) {
                "源包内仍含 XPatch 注入条目，无法还原原始安装包；拒绝内嵌以避免产物逐次翻倍。" +
                    "请改用原始 APK 作为输入，或先卸载后重新选择安装包。"
            }
        }

        if (effectiveSource != sourceApk) {
            onLog("剥离完成，共 $depth 层，最终源 ${effectiveSource.length() / 1024 / 1024} MB")
        }
        RandomAccessFile(effectiveSource, "r").use { source ->
            entries = dropStaleBootDex(source, entries, onLog)
        }
        assertClean(entries, effectiveSource, onLog)

        // FPA 动态加载：代码由 xp/source.apk 在运行时提供；除 classes*.dex 和签名文件外，
        // 其余条目全部保留。不能只保留 res/assets/lib：MT 管理器这类加固包把资源 XML
        // 放在 APK 根目录（如 ܽۨ֫.xml），只按 res/ 前缀过滤会直接 Resources$NotFoundException。
        if (options.dynamicMode && options.embedSource) {
            val before = entries.size
            entries = entries.filter { entry ->
                !isBootDexName(entry.name) &&
                    !entry.name.startsWith("META-INF/") &&
                    entry.name != "stamp-cert-sha256"
            }
            onLog("动态加载：保留 ${entries.size}/$before 个条目（清单/资源/assets/原生库/根目录资源），代码由原包提供")
        }

        RandomAccessFile(effectiveSource, "r").use { source ->
            val manifestEntry = entries.firstOrNull { it.name == MANIFEST }
                ?: error("APK 缺少 AndroidManifest.xml")
            val inputManifest = readEntry(source, manifestEntry)
            val editor = AxmlEditor(inputManifest)
            val manifestPackage = editor.readAttribute("manifest", "package").orEmpty()
            // 编辑永远基于"输入清单"；写回 xp/source.manifest 的才是"原始清单"，两者不能混用。
            originalManifest = pickOriginalManifest(
                recorded = if (effectiveSource == sourceApk) recordedManifest else null,
                input = inputManifest,
                expectedPackage = manifestPackage,
                onLog = onLog,
            )
            val inputFactory = sanitizeFactory(editor.readApplicationAttribute(ATTR_FACTORY_NAME), onLog)
            originalFactory = inputFactory ?: recoverFactory(originalManifest, onLog)

            // 工厂注入始终写入：它在每个进程的 instantiateClassLoader 阶段就生效，既早于
            // Application.attachBaseContext，也天然覆盖多进程；provider 只是 entryHook 的备用入口
            // （审查 P2-1/P2-16：provider.onCreate 晚于 attachBaseContext，且只覆盖主进程）。
            var patched = editor.setApplicationAttribute(ATTR_FACTORY_NAME, STUB_CLASS)
            if (options.entryHook && !options.dynamicMode) {
                if (manifestPackage.isBlank()) {
                    onLog("清单缺少 package 属性，无法生成 provider authority，仅保留工厂注入")
                } else {
                    val authority = "$manifestPackage.xpatch"
                    if (editor.hasProvider(XP_PROVIDER_CLASS, authority)) {
                        onLog("清单已含 $XP_PROVIDER_CLASS，复用既有 provider，不重复插入")
                    }
                    patched = AxmlEditor(patched).addProvider(XP_PROVIDER_CLASS, authority)
                    providerReady = true
                }
            }
            options.versionName?.takeIf { it.isNotBlank() }?.let { name ->
                patched = AxmlEditor(patched).setStringAttribute("manifest", "versionName", name)
            }
            options.versionCode?.let { code ->
                patched = AxmlEditor(patched).setIntAttribute("manifest", "versionCode", code)
            }
            manifest = patched
        }
        onLog(if (providerReady) "已写入工厂注入（+ provider 备用入口）" else "已写入工厂注入")
        // 最后一步再补权限声明：它只影响"模块熔断广播能否送达管理器"，失败时保留其它注入
        // （工厂 / provider / 版本），理由见 injectModuleCrashPermission 的注释。
        manifest = injectModuleCrashPermission(manifest, onLog)
        if (options.versionName?.isNotBlank() == true || options.versionCode != null) {
            onLog("自定义版本：" + listOfNotNull(
                options.versionName?.takeIf { it.isNotBlank() }?.let { "versionName=$it" },
                options.versionCode?.let { "versionCode=$it" },
            ).joinToString("，"))
        }
        onProgress(25)

        val bootDexIndex = nextBootDexIndex(entries)
        val bootDexName = if (bootDexIndex <= 1) "classes.dex" else "classes$bootDexIndex.dex"
        val packageInfo = packageInfoOf(effectiveSource)
        val config = buildConfig(packageInfo, originalFactory, options, effectiveSource)

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
                config = config,
                packageName = packageInfo?.packageName.orEmpty(),
                embeddedCrc = embeddedCrc,
                embeddedFailure = embeddedFailure,
                embeddedReady = embeddedReady,
                onProgress = onProgress,
                onLog = onLog,
                embedSource = options.embedSource,
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
        )
    }

    private fun sanitizeFactory(factory: String?, onLog: (String) -> Unit): String? {
        if (factory.isNullOrBlank()) return null
        if (factory == STUB_CLASS || factory in LEGACY_STUB_CLASSES) {
            onLog("源包工厂指向引导桩（$factory），已忽略以免自我委派")
            return null
        }
        return factory
    }

    /**
     * 选出要写回 `xp/source.manifest` 的"原始清单"。
     *
     * 不变量：这个条目描述的是**未注入**的清单，否则文件层重建出的"原包"会带着
     * `io.xpatch.stub.AppEntry` / 我们的 provider，任何清单级自校验都会命中（审查 P2-17）。
     * 因此优先复用上一版记录的值；只有它缺失或解析失败时才退回输入清单。
     */
    private fun pickOriginalManifest(
        recorded: ByteArray?,
        input: ByteArray,
        expectedPackage: String,
        onLog: (String) -> Unit,
    ): ByteArray {
        if (recorded == null) return input
        val parsed = runCatching { AxmlEditor(recorded) }.getOrNull()
        val packageName = parsed?.let { runCatching { it.readAttribute("manifest", "package") }.getOrNull() }
        if (parsed == null || packageName == null) {
            onLog("xp/source.manifest 解析失败，退回输入清单作为原始清单")
            return input
        }
        // 记录值优先，但包名必须与输入清单一致，否则宁可用退化值也不写错包。
        if (expectedPackage.isNotBlank() && packageName != expectedPackage) {
            onLog("xp/source.manifest 的包名 $packageName 与输入清单不一致，退回输入清单")
            return input
        }
        val factory = runCatching { parsed.readApplicationAttribute(ATTR_FACTORY_NAME) }.getOrNull()
        if (factory != null && (factory == STUB_CLASS || factory in LEGACY_STUB_CLASSES)) {
            onLog("xp/source.manifest 已含引导桩工厂，更早的原始清单已不可恢复")
        } else {
            onLog("复用上一版记录的原始清单 xp/source.manifest")
        }
        return recorded
    }

    /**
     * 输入清单的 appComponentFactory 若是上一版写入的引导桩，[sanitizeFactory] 会丢弃它；
     * 此时从原始清单把真实工厂名找回来，避免 config 的 app_component_name 退化成空串。
     */
    private fun recoverFactory(originalManifest: ByteArray, onLog: (String) -> Unit): String? {
        val factory = runCatching {
            AxmlEditor(originalManifest).readApplicationAttribute(ATTR_FACTORY_NAME)
        }.getOrNull()
        if (factory.isNullOrBlank() || factory == STUB_CLASS || factory in LEGACY_STUB_CLASSES) return null
        onLog("从原始清单恢复 appComponentFactory=$factory")
        return factory
    }

    /**
     * 往目标清单注入模块保护权限（[MODULE_CRASH_PERMISSION]）。
     *
     * 为什么需要：W5 的 `ModuleCrashReceiver` 声明了 signature 级 `android:permission`，
     * 而 `Context.sendBroadcast(intent, permission)` 校验的是**发送方是否持有该权限**。
     * 被注入的应用自己不在清单里声明 `<uses-permission>` 就拿不到授予，广播会被系统丢弃，
     * 模块熔断完全失效——且这条链路失效是静默的（系统丢广播，运行时没有任何报错）。
     *
     * 不变量：注入失败**不中断打包**。缺这条声明只降级"模块熔断通知"这一条链路，
     * 清单里其它注入（工厂 / provider / 版本）仍然有效；把整个打包 fail 掉会让用户
     * 连"已安装但收不到熔断事件"这个可诊断状态都拿不到。代价必须是可见的日志，
     * 所以失败分支明确写出后果，而不是只写"跳过"。
     */
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

    private fun assertClean(entries: List<Entry>, sourceApk: File, onLog: (String) -> Unit) {
        val leaked = entries.filter { isInjectedEntry(it.name) }
        require(leaked.isEmpty()) {
            "源包仍含注入条目，拒绝打包以免体积翻倍：${leaked.take(5).map { it.name }}"
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

    private fun stripInjected(        sourceApk: File,
        entries: List<Entry>,
        onLog: (String) -> Unit,
    ): File? = runCatching {
        val outerSize = sourceApk.length()

        // xp/source.apk 是明确的内嵌原始包标记：优先且无条件下选它。
        // 不能只靠 score/100KB 阈值——小应用的原包可能只有几十 KB，被阈值挡掉后
        // 就会退回“把整个已补丁 APK 再内嵌一次”，每打一次包体积翻一倍。
        val preferred = entries.firstOrNull {
            it.name == SOURCE_ENTRY && it.uncompressedSize > 0
        }
        val candidates = entries
            .filter { it.name.endsWith(APK_SUFFIX, ignoreCase = true) }
            .filter { it.name == SOURCE_ENTRY || it.uncompressedSize >= MIN_EMBEDDED }
            .mapNotNull { entry ->
                val score = scoreEmbedded(entry, outerSize)
                if (score < MIN_EMBEDDED_SCORE) null else entry to score
            }
            .sortedByDescending { it.second }

        val chosen = preferred?.let { it to Int.MAX_VALUE }
            ?: candidates.firstOrNull()
            ?: return@runCatching null
        val embedded = chosen.first
        val target = File(
            sourceApk.parentFile,
            "${sourceApk.nameWithoutExtension}.${System.nanoTime()}.base.apk",
        )
        target.delete()

        RandomAccessFile(sourceApk, "r").use { source ->
            if (embedded.method == STORED) {
                source.seek(embedded.dataStart)
                FileOutputStream(target, false).buffered(BUFFER_SIZE).use { out ->
                    copyRange(source, embedded.compressedSize, out)
                }
            } else {
                target.outputStream().buffered(BUFFER_SIZE).use { out ->
                    source.seek(embedded.dataStart)
                    val chunk = ByteArray(DEFLATE_CHUNK)
                    val inflater = java.util.zip.Inflater(true)
                    try {
                        var remaining = embedded.compressedSize
                        while (remaining > 0) {
                            val read = minOf(remaining, chunk.size.toLong()).toInt()
                            source.readFully(chunk, 0, read)
                            remaining -= read
                            inflater.setInput(chunk, 0, read)
                            while (!inflater.needsInput()) {
                                val produced = inflater.inflate(chunk)
                                if (produced > 0) out.write(chunk, 0, produced)
                                if (inflater.finished()) break
                            }
                        }
                    } finally {
                        inflater.end()
                    }
                }
            }
        }

        if (embedded.name != SOURCE_ENTRY && target.length() < MIN_EMBEDDED) {
            target.delete()
            return@runCatching null
        }

        val valid = runCatching {
            java.util.zip.ZipFile(target).use { zip ->
                zip.getEntry(MANIFEST) != null &&
                    zip.entries().asSequence().any {
                        it.name.startsWith("classes") && it.name.endsWith(".dex")
                    }
            }
        }.getOrDefault(false)

        if (!valid) {
            target.delete()
            onLog("内嵌包校验失败，按普通包处理")
            return@runCatching null
        }

        val outerPackage = packageInfoOf(sourceApk)?.packageName
        val embeddedPackage = packageInfoOf(target)?.packageName
        if (outerPackage != null && embeddedPackage != null && embeddedPackage != outerPackage) {
            target.delete()
            onLog("内嵌包包名不匹配 $embeddedPackage != $outerPackage，按普通包处理")
            return@runCatching null
        }

        onLog("已提取内嵌原始包 ${embedded.name} ${target.length() / 1024 / 1024} MB")
        target
    }.getOrNull()

    private fun scoreEmbedded(
        entry: Entry,
        outerSize: Long,
    ): Int {
        var score = 0
        if (entry.name in KNOWN_LAYOUTS) {
            score += 60
        } else if (INJECTED_PREFIXES.any { entry.name.startsWith(it) }) {
            score += 25
        }
        val lowered = entry.name.lowercase()
        if (SOURCE_HINTS.any { lowered.contains(it) }) score += 20

        if (outerSize > 0) {
            val ratio = entry.uncompressedSize.toDouble() / outerSize
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
        config: String,
        packageName: String,
        embeddedCrc: AtomicLong,
        embeddedFailure: AtomicReference<Throwable?>,
        embeddedReady: CountDownLatch,
        onProgress: (Int) -> Unit,
        onLog: (String) -> Unit,
        embedSource: Boolean,
    ) {
        val added = buildList {
            add(Added(ORIGINAL_MANIFEST_ENTRY, DEFLATED) { originalManifest })
            add(Added(bootDexName, DEFLATED) { asset("xp/stub.dex") })
            add(Added(CORE_ENTRY, DEFLATED) { asset("xp/runtime.dex") })
            add(Added(HOOK_DEX_ENTRY, DEFLATED) { asset("xp/hook_core.dex") })
            add(Added(HOOK_SO_ENTRY, STORED) { asset("xp/hook_core.so") })
            // x86_64 模拟器 / 平板的备用原生库。loader 按 Build.SUPPORTED_ABIS 优先找
            // xp/hook_core_<abi>.so，找不到才回退上面的通用 arm64 资产；两个都注入，
            // 单包约 +1.15 MB，仍比修复前的单个 1.9 MB 未 strip 资产小。
            add(Added("xp/hook_core_x86_64.so", STORED) { asset("xp/hook_core_x86_64.so") })
            add(Added("xp/xp89.dex", DEFLATED) { asset("xp/xp89.dex") })
            add(Added("xp/xp100.dex", DEFLATED) { asset("xp/xp100.dex") })
            add(Added("xp/xp101.dex", DEFLATED) { asset("xp/xp101.dex") })
            add(Added("xp/xp102.dex", DEFLATED) { asset("xp/xp102.dex") })
            if (embedSource) add(Added(SOURCE_ENTRY, STORED, null))
            add(Added(CONFIG_ENTRY, DEFLATED) { config.toByteArray() })
            add(Added("xp/pkg", DEFLATED) { packageName.toByteArray() })
            add(Added("xp/file_provider", DEFLATED) { asset("xp/file_provider.dex") })
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

        val total = carried.size + added.size
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
                for (entry in carried) {
                    val replaced = entry.name == MANIFEST
                    val data = if (replaced) manifest else null
                    val payload = if (data != null) compress(data, entry.method) else null
                    val crc = if (data != null) crc32(data) else entry.crc
                    val compressed = payload?.size?.toLong() ?: entry.compressedSize
                    val uncompressed = data?.size?.toLong() ?: entry.uncompressedSize
                    val headerOffset = position
                    val header = localHeader(
                        entry = entry,
                        headerOffset = headerOffset,
                        crc = crc,
                        compressed = compressed,
                        uncompressed = uncompressed,
                    )
                    out.write(header)
                    position += header.size
                    if (payload != null) {
                        out.write(payload)
                        position += payload.size
                    } else {
                        source.seek(entry.dataStart)
                        position += copyRange(source, entry.compressedSize, out)
                    }
                    records += Record(entry, headerOffset, crc, compressed, uncompressed)
                    advance()
                }

                onLog("注入内核与引导桩 $bootDexName")

                for (item in added) {
                    if (item.name == SOURCE_ENTRY) {
                        onLog("内嵌原始安装包 xp/source.apk")
                        embeddedReady.await()
                        embeddedFailure.get()?.let { throw it }
                        val length = sourceApk.length()
                        val crc = embeddedCrc.get()
                        val entry = addedEntry(item.name, STORED)
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
                    val entry = addedEntry(item.name, item.method)
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

    private fun addedEntry(name: String, method: Int): Entry = Entry(
        name = name,
        nameBytes = name.toByteArray(Charsets.UTF_8),
        extra = EMPTY,
        comment = EMPTY,
        versionMadeBy = HOST_SYSTEM,
        versionNeeded = VERSION_NEEDED,
        flags = FLAG_UTF8,
        method = method,
        dosTime = 0,
        dosDate = DOS_DATE_EPOCH,
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
        // 动态加载产物会丢掉原 classes*.dex：此时必须把引导桩写成 classes.dex，
        // 否则只有 classes2.dex，ART 不会加载它，应用启动即 ClassNotFoundException。
        return if (!found) 1 else max + 1
    }

    private fun buildConfig(
        info: PackageInfo?,
        originalFactory: String?,
        options: Options,
        apk: File,
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
        // 运行时用它决定崩溃广播发给谁；管理器的包名不一定是 io.xpatch（可能有渠道后缀）。
        json.put("manager_package", context.packageName)
        json.put("real_version_code", info?.longVersionCode ?: 0L)
        json.put("module_paths", JSONArray())
        return json.toString()
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

    private class Added(
        val name: String,
        val method: Int,
        val data: (() -> ByteArray)?,
    )

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

        /** W5 的 ModuleCrashReceiver 是 signature 级权限的接收端；发送方（本产物）必须自己声明。 */
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

        val KNOWN_LAYOUTS = setOf(
            "fpa/source.apk",
            "xp/source.apk",
            "assets/npatch/origin.apk",
            "lspatch/origin.apk",
            "assets/lspatch/origin.apk",
        )

        val SOURCE_HINTS = listOf("source", "origin", "original", "base", "raw")

        const val APK_SUFFIX = ".apk"
        const val MIN_EMBEDDED = 100L * 1024
        const val MIN_EMBEDDED_SCORE = 45
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

        fun embeddedSourceOf(name: String): Boolean =
            name == SOURCE_ENTRY ||
                name == "fpa/source.apk" ||
                name == "xp/o_app.apk" ||
                name == "assets/npatch/origin.apk" ||
                name == "lspatch/origin.apk" ||
                name == "assets/lspatch/origin.apk"

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
