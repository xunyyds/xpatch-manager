package io.xpatch.patch.obfuscate

import org.json.JSONObject
import java.security.SecureRandom

/**
 * 注入条目的"逻辑名"。
 *
 * 写入侧（本文件）用它取随机路径，读取侧（引导桩 `AppEntry`、内核 `XpLayout`）按同一张表反查，
 * 所以 key 与扩展名是**跨仓库契约**（docs/inject-path-obfuscation.md §4.1），三份实现必须一致。
 * 扩展名不是随便挑的：`.so` 走原生库的 16 KB 页对齐分支且名字像正常资产，`.bin`/`.dat`
 * 则在常见扫描器的"可疑后缀"名单之外。
 */
enum class Logical(val key: String, val ext: String) {
    CFG("cfg", "dat"),
    CORE("core", "bin"),
    HOOK_DEX("hook_dex", "bin"),
    HOOK_SO("hook_so", "so"),
    HOOK_SO_X86_64("hook_so_x86_64", "so"),
    DEX89("dex89", "bin"),
    DEX100("dex100", "bin"),
    DEX101("dex101", "bin"),
    DEX102("dex102", "bin"),
    FILE_PROVIDER("file_provider", "bin"),
    SOURCE("source", "dat"),
    SOURCE_MANIFEST("source_manifest", "dat"),
    PKG("pkg", "dat"),
}

/**
 * 一次打包的注入布局：目录、13（或 12）个随机条目名、引导桩 dex 名、以及改名后的引导桩类名。
 *
 * 不可变值对象：生成一次，写入侧和 config 序列化都读它，避免"写进 zip 的名字"和
 * "写进 config 的名字"各算一次随机数而错位（那会导致引导桩找不到内核，产物装完打不开）。
 */
data class InjectLayout(
    val dir: String,
    val entries: Map<Logical, String>,
    val bootDex: String,
    val factoryDashed: String,
    val providerDashed: String?,
    val runtimeClass: String = RUNTIME_CLASS,
    /** 供 §4.4 的条目顺序打散/时间戳采样使用；不进 config，剥离时也用不上。 */
    val seed: Long = 0L,
) {

    fun entry(logical: Logical): String? = entries[logical]

    /** config 自己那条：引导桩/内核只靠它发现布局，所以它一定在 entries 里。 */
    val configEntry: String get() = entries[Logical.CFG] ?: LEGACY_CONFIG_ENTRY

    /** 目录末段的 8 位十六进制：provider authority 的后缀也取它，一次打包只留一个随机源。 */
    val hex8: String get() = dir.substringAfterLast('/')

    /** 本次写入会占用的全部条目名（含引导桩）。生成时用来对照输入包，剥离时用来比对。 */
    fun allNames(): Set<String> = buildSet {
        addAll(entries.values)
        if (bootDex.isNotBlank()) add(bootDex)
    }

    /**
     * 写 §4.2 的扁平 `res_*` 键。
     *
     * 只在开关打开时调用：关掉时一个 `res_*` 都不能出现，否则旧产物不再字节等价（§4.6）。
     * `res_cfg` 自描述（便于人工解包排查），`res_runtime_class` 写出来是为了让引导桩源码里
     * 不再有 `io.xpatch` 字面量，二期改运行层类名时不用动桩。
     */
    fun intoJson(json: JSONObject) {
        json.put("res_v", RES_VERSION)
        json.put("res_dir", dir)
        for ((logical, name) in entries) json.put("res_" + logical.key, name)
        json.put("res_boot_dex", bootDex)
        json.put("res_factory", factoryDashed)
        // entryHook 才写：读侧把"缺失"当成"没有 provider"，多写一个不存在的类名反而会被自检当真。
        providerDashed?.takeIf { it.isNotBlank() }?.let { json.put("res_provider", it) }
        json.put("res_runtime_class", runtimeClass)
        // v1 恒 0；二期做载荷加密时置 1，读侧据它决定要不要在内存里解密。
        json.put("res_enc", 0)
    }

    companion object {
        /** 内核入口类名：v1 固定，写进 config 只为让引导桩源码不再出现 io.xpatch 字面量。 */
        const val RUNTIME_CLASS = "io.xpatch.core.CoreEntry"

        /** 旧产物的固定 config 路径，也是"发现失败"时的兜底（§3 发现机制）。 */
        const val LEGACY_CONFIG_ENTRY = "xp/config.json"

        const val RES_VERSION = 1

        /** 候选 config 的体积上限：真正的 config 是几 KB，超过 1 MB 的一定不是它（解压炸弹也一起挡掉）。 */
        const val MAX_CONFIG_BYTES = 1024 * 1024

        /**
         * 候选发现正则：manager / 引导桩 / 内核三份实现必须**逐字一致**（对方是 Java，照抄这行注释即可）。
         *
         * ```
         * ^assets/[0-9a-f]{8}/[0-9a-z]{10,14}\.(dat|bin|json)$
         * ```
         *
         * 名字形如 `assets/1a2b3c4d/k9m2p4q7r1t5.dat`：目录 8 位小写十六进制、文件名 10~14 位
         * 小写字母数字、扩展名限定 dat/bin/json。生成侧（[generate]）必须保证 config 条目落在
         * 这个形状里（stem 固定 12 位、扩展名取 [Logical.CFG] 的 `dat`），否则引导桩扫不到它。
         */
        val CANDIDATE_REGEX = Regex("^assets/[0-9a-f]{8}/[0-9a-z]{10,14}\\.(dat|bin|json)$")

        private const val DIR_PREFIX = "assets"
        private const val HEX8_LENGTH = 8

        /**
         * 文件名主体长度：选定 12 位是为了落在发现正则的 10~14 位窗口里（两侧各留 1 位余量，
         * 正则本身留在契约里给读侧兜底，写侧不要把窗口边缘用满）。
         */
        private const val STEM_LENGTH = 12

        /** 与输入包冲突时整体重摇的次数上限；超过就抛，上层降级成"不混淆"并打日志。 */
        private const val MAX_SHAKE = 8

        private const val STEM_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"
        private const val HEX_ALPHABET = "0123456789abcdef"

        /**
         * 摇一份布局。
         *
         * @param existingNames 输入 APK 现有条目名（含目录条目）。撞名必须整体重摇：
         *   局部重试会出现"目录随机、个别文件名沿用真实条目"的覆盖，直接吃掉原包资源。
         * @param includeSource 只有内嵌档位才把 `res_source` 算进布局；不写就不生成，避免
         *   config 里出现一个 zip 里不存在的路径。
         */
        fun generate(
            existingNames: Set<String>,
            bootDex: String,
            includeSource: Boolean,
            factoryDashed: String,
            providerDashed: String?,
            seed: Long = 0L,
        ): InjectLayout {
            val random = SecureRandom()
            repeat(MAX_SHAKE) {
                val dir = "$DIR_PREFIX/" + randomHex(random, HEX8_LENGTH)
                // 目录本身也可能撞上真实目录（例如加固包自己用了 assets/<hex8>/）：
                // 判重看前缀，不只看完整文件名。
                if (existingNames.any { it == dir || it.startsWith("$dir/") }) return@repeat
                val names = LinkedHashMap<Logical, String>()
                var conflict = false
                for (logical in Logical.values()) {
                    if (logical == Logical.SOURCE && !includeSource) continue
                    val name = "$dir/${randomStem(random)}.${logical.ext}"
                    if (name in existingNames) {
                        conflict = true
                        break
                    }
                    names[logical] = name
                }
                // 同一个目录里两次摇出同一个 stem 也算冲突（真撞了会导致后写的覆盖先写的）。
                if (conflict || names.values.toSet().size != names.size) return@repeat
                return InjectLayout(
                    dir = dir,
                    entries = names,
                    bootDex = bootDex,
                    factoryDashed = factoryDashed,
                    providerDashed = providerDashed,
                    seed = seed,
                )
            }
            error("连续 $MAX_SHAKE 次生成的资产目录与输入包条目冲突，放弃路径混淆")
        }

        /**
         * 从 config JSON 读回布局（二次打包剥离用）。
         *
         * 缺 `res_cfg` 即视为旧产物：`res_*` 缺失 = 全部用旧名字，这条约定让老产物零影响（§4.2）。
         */
        fun fromConfig(jsonText: String): InjectLayout? {
            val json = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
            val configEntry = json.optString("res_cfg").takeIf { it.isNotBlank() } ?: return null
            val entries = LinkedHashMap<Logical, String>()
            for (logical in Logical.values()) {
                if (logical == Logical.CFG) {
                    entries[logical] = configEntry
                    continue
                }
                val name = json.optString("res_" + logical.key).takeIf { it.isNotBlank() } ?: continue
                entries[logical] = name
            }
            return InjectLayout(
                dir = json.optString("res_dir").takeIf { it.isNotBlank() }
                    ?: configEntry.substringBeforeLast('/'),
                entries = entries,
                bootDex = json.optString("res_boot_dex"),
                // 改名降级的产物 res_factory 就是旧类名；这里缺失时同样回落到旧类名。
                factoryDashed = json.optString("res_factory").takeIf { it.isNotBlank() }
                    ?: StubRewriter.DEFAULT_FACTORY,
                providerDashed = json.optString("res_provider").takeIf { it.isNotBlank() },
                runtimeClass = json.optString("res_runtime_class").takeIf { it.isNotBlank() }
                    ?: RUNTIME_CLASS,
            )
        }

        /**
         * 在条目表里找出本框架的 config（剥离与排障都靠它）。
         *
         * 判定口径与引导桩/内核完全一致，缺一不可：
         * 1. **按中心目录（zip）顺序**逐个试，取第一个通过内容校验的——三份实现都不排序，
         *    否则同一份产物会被不同层挑出不同的候选（引导桩为了省类引用刻意不排序）；
         * 2. 名字匹配 [CANDIDATE_REGEX]；
         * 3. 解压后体积 ≤ 1 MB（声明体积已知且超限的直接跳过；`size <= 0` 表示未知，照读一次）；
         * 4. `trim` 后以 `{` 开头（是个 JSON 对象）；
         * 5. 含子串 `"res_cfg"`（我们的标记键）。
         * 全不中才回落到旧名字 `xp/config.json`（存在才返回）。
         *
         * 为什么不用"固定名 + 派生哈希"：派生哈希要求三份实现逐位一致，任何一处漂移都是
         * "装完打不开"级别的故障，而收益只是少扫一遍条目表——O(条目数) 一次，可忽略。
         *
         * 调用方必须传入**解析出来的中心目录原始顺序**（不是 HashMap/Set 去重后的顺序），
         * 否则"第一命中"的含义在层与层之间就不一样了。
         *
         * @param entries (条目名, 解压后大小)，大小 <= 0 表示未知（内核同口径：仍按上限读一次）
         * @param read 按名字取解压后的字节；返回 null 表示读不出来（继续看下一个候选）
         */
        fun discoverConfigEntry(
            entries: List<Pair<String, Long>>,
            read: (String) -> ByteArray?,
        ): String? {
            for ((name, size) in entries) {
                if (!CANDIDATE_REGEX.matches(name)) continue
                if (size > MAX_CONFIG_BYTES) continue
                val body = read(name) ?: continue
                if (body.isEmpty() || body.size > MAX_CONFIG_BYTES) continue
                val text = runCatching { String(body, Charsets.UTF_8) }.getOrNull() ?: continue
                if (!text.trimStart().startsWith("{")) continue
                if (!text.contains("\"res_cfg\"")) continue
                return name
            }
            return entries.firstOrNull { it.first == LEGACY_CONFIG_ENTRY }?.first
        }

        private fun randomHex(random: SecureRandom, length: Int): String {
            val text = StringBuilder(length)
            repeat(length) { text.append(HEX_ALPHABET[random.nextInt(HEX_ALPHABET.length)]) }
            return text.toString()
        }

        private fun randomStem(random: SecureRandom): String {
            val text = StringBuilder(STEM_LENGTH)
            repeat(STEM_LENGTH) { text.append(STEM_ALPHABET[random.nextInt(STEM_ALPHABET.length)]) }
            return text.toString()
        }
    }
}

/**
 * 一份"本框架注入条目"名单：剥离、保留过滤、`assertClean` 全部走这里。
 *
 * 以前每个地方各写一遍 `startsWith("xp/")`，加一层新名字时漏掉任何一处就是"体积翻倍"或
 * "把旧注入当原包再注一层"。发现自家混淆产物后（[layout] 非空），`res_*` 全表一起算注入条目；
 * 找不到布局时等价于今天的行为，老产物逻辑一个字节都不变。
 */
class InjectedEntrySet(
    val layout: InjectLayout?,
    private val legacyInjected: (String) -> Boolean,
    private val legacyEmbeddedSource: (String) -> Boolean,
) {
    private val entryNames: Set<String> = layout?.entries?.values?.toSet().orEmpty()
    private val bootDex: String = layout?.bootDex.orEmpty()

    val configEntry: String = layout?.configEntry ?: InjectLayout.LEGACY_CONFIG_ENTRY

    /** 内嵌原包条目名；旧产物是 `xp/source.apk`。 */
    val sourceEntry: String? = layout?.entry(Logical.SOURCE)

    fun isInjected(name: String): Boolean =
        name in entryNames || (bootDex.isNotEmpty() && name == bootDex) || legacyInjected(name)

    /**
     * 只认布局里的"数据条目"名。引导桩是 `classesN.dex`，可能与原包真实 dex 同名，
     * 所以它不算在内——否则"内嵌原包时拒绝重复注入"的闸门会对合法原包误报。
     */
    fun isLayoutEntry(name: String): Boolean = name in entryNames

    fun isEmbeddedSource(name: String): Boolean = name == sourceEntry || legacyEmbeddedSource(name)
}
