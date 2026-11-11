package io.xpatch.patch.obfuscate

import org.json.JSONObject
import java.security.SecureRandom

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

data class InjectLayout(
    val dir: String,
    val entries: Map<Logical, String>,
    val bootDex: String,
    val factoryDashed: String,
    val providerDashed: String?,
    val hex8: String,
    val runtimeClass: String = RUNTIME_CLASS,
    val seed: Long = 0L,
) {

    fun entry(logical: Logical): String? = entries[logical]

    val configEntry: String get() = entries[Logical.CFG] ?: LEGACY_CONFIG_ENTRY

    fun allNames(): Set<String> = buildSet {
        addAll(entries.values)
        if (bootDex.isNotBlank()) add(bootDex)
    }

    fun intoJson(json: JSONObject) {
        json.put("res_v", RES_VERSION)
        json.put("res_dir", dir)
        for ((logical, name) in entries) json.put("res_" + logical.key, name)
        json.put("res_boot_dex", bootDex)
        json.put("res_factory", factoryDashed)
        providerDashed?.takeIf { it.isNotBlank() }?.let { json.put("res_provider", it) }
        json.put("res_runtime_class", runtimeClass)
        json.put("res_hex8", hex8)
        json.put("res_enc", 0)
    }

    companion object {
        const val RUNTIME_CLASS = "io.xpatch.core.CoreEntry"

        const val LEGACY_CONFIG_ENTRY = "xp/config.json"

        const val RES_VERSION = 1

        const val MAX_CONFIG_BYTES = 64 * 1024

        private const val MAX_CONFIG_PROBES = 2048

        val CONFIG_EXTS = setOf("json", "dat", "bin", "txt", "lst", "")

        private const val MAX_SHAKE = 16

        private val HEX = "0123456789abcdef"

        fun generate(
            entries: List<Pair<String, Long>>,
            bootDex: String,
            includeSource: Boolean,
            factoryDashed: String,
            providerDashed: String?,
            seed: Long,
        ): InjectLayout {
            val random = SecureRandom()
            val taken = HashSet<String>()
            for ((name, _) in entries) {
                taken.add(name)
                val slash = name.indexOf('/')
                if (slash > 0) taken.add(name.substring(0, slash + 1))
            }
            val profile = HostProfile.of(entries)
            repeat(MAX_SHAKE) {
                val names = LinkedHashMap<Logical, String>()
                for (logical in Logical.values()) {
                    if (logical == Logical.SOURCE && !includeSource) continue
                    val name: String? = when (logical) {
                        Logical.HOOK_SO -> profile.soName(random, taken, "arm64-v8a", false)
                        Logical.HOOK_SO_X86_64 -> profile.soName(random, taken, "x86_64", true)
                        Logical.CFG, Logical.PKG, Logical.SOURCE_MANIFEST ->
                            profile.dataName(random, taken, logical.ext, false)
                        else -> profile.dataName(random, taken, logical.ext, true)
                    }
                    if (name == null) continue
                    if (name in taken) return@repeat
                    names[logical] = name
                    taken.add(name)
                }
                if (names.values.toSet().size != names.size) return@repeat
                val hex8 = buildString {
                    repeat(8) { append(HEX[random.nextInt(HEX.length)]) }
                }
                return InjectLayout(
                    dir = profile.dataDir,
                    entries = names,
                    bootDex = bootDex,
                    factoryDashed = factoryDashed,
                    providerDashed = providerDashed,
                    hex8 = hex8,
                    seed = seed,
                )
            }
            error("无法生成与输入包不冲突的条目名")
        }

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
                    ?: configEntry.substringBeforeLast('/', ""),
                entries = entries,
                bootDex = json.optString("res_boot_dex"),
                factoryDashed = json.optString("res_factory").takeIf { it.isNotBlank() }
                    ?: StubRewriter.DEFAULT_FACTORY,
                providerDashed = json.optString("res_provider").takeIf { it.isNotBlank() },
                hex8 = json.optString("res_hex8").takeIf { it.isNotBlank() } ?: "00000000",
                runtimeClass = json.optString("res_runtime_class").takeIf { it.isNotBlank() }
                    ?: RUNTIME_CLASS,
            )
        }

        fun discoverConfigEntry(
            entries: List<Pair<String, Long>>,
            read: (String) -> ByteArray?,
        ): String? {
            var budget = MAX_CONFIG_PROBES
            for (index in entries.indices.reversed()) {
                val (name, size) = entries[index]
                if (!looksLikeConfig(name, size)) continue
                if (budget-- <= 0) break
                val body = read(name) ?: continue
                if (isLayoutConfig(body)) return name
            }
            return entries.firstOrNull { it.first == LEGACY_CONFIG_ENTRY }?.first
        }

        fun looksLikeConfig(name: String, size: Long): Boolean {
            if (name.isBlank() || name.endsWith("/")) return false
            if (size <= 0L || size > MAX_CONFIG_BYTES) return false
            val dot = name.lastIndexOf('.')
            val slash = name.lastIndexOf('/')
            if (dot <= slash) return CONFIG_EXTS.contains("")
            val ext = name.substring(dot + 1).lowercase()
            return CONFIG_EXTS.contains(ext)
        }

        fun isLayoutConfig(raw: ByteArray?): Boolean {
            if (raw == null || raw.isEmpty() || raw.size > MAX_CONFIG_BYTES) return false
            val text = runCatching { String(raw, Charsets.UTF_8) }.getOrNull() ?: return false
            if (!text.trimStart().startsWith("{")) return false
            return text.contains("\"res_cfg\"")
        }
    }
}

class InjectedEntrySet(
    val layout: InjectLayout?,
    private val legacyInjected: (String) -> Boolean,
    private val legacyEmbeddedSource: (String) -> Boolean,
) {
    private val entryNames: Set<String> = layout?.entries?.values?.toSet().orEmpty()
    private val bootDex: String = layout?.bootDex.orEmpty()

    val configEntry: String = layout?.configEntry ?: InjectLayout.LEGACY_CONFIG_ENTRY

    val sourceEntry: String? = layout?.entry(Logical.SOURCE)

    fun isInjected(name: String): Boolean =
        name in entryNames || (bootDex.isNotEmpty() && name == bootDex) || legacyInjected(name)

    fun isLayoutEntry(name: String): Boolean = name in entryNames

    fun isEmbeddedSource(name: String): Boolean = name == sourceEntry || legacyEmbeddedSource(name)
}








