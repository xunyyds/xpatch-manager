package io.xpatch.patch

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.Inflater
import java.util.zip.ZipFile

class EmbeddedSource(
    private val source: RandomAccessFile,
    private val entries: List<Entry>,
    private val outerPackage: String,
    private val injected: (String) -> Boolean,
    private val preferred: List<String>,
    private val packageNameOf: (File) -> String?,
    private val outDir: File,
    private val score: (Entry) -> Int = { 0 },
    private val onLog: (String) -> Unit = {},
) {

    data class Entry(
        val name: String,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val dataStart: Long,
    )

    data class Outcome(
        val file: File?,
        val entry: String,
        val candidates: List<String>,
        val rejected: List<String>,
        val unproven: List<String>,
    )

    class AmbiguousException(names: List<String>) : IllegalStateException(
        "内嵌原始包存在多个等体积候选，无法唯一确认：" + names.joinToString("，"),
    )

    private class Verified(val entry: Entry, val file: File, val hits: Int)

    private class Verdict(val hits: Int, val reason: String?)

    private val unproven = ArrayList<String>()

    private val preferredNames = preferred.toHashSet()

    fun locate(): Outcome {
        val candidates = candidates()
        if (candidates.isEmpty()) return Outcome(null, "", emptyList(), emptyList(), emptyList())
        onLog("内嵌来源：发现 ${candidates.size} 个候选（${candidates.take(4).joinToString("，") { it.name }}）")
        val rejected = ArrayList<String>()
        val verified = ArrayList<Verified>()
        val scanned = HashSet<String>()
        val skipped = candidates.filter { !isPlausible(it) }
        if (skipped.isNotEmpty()) {
            onLog(
                "内嵌来源：跳过体积过小的候选 " +
                    skipped.take(3).joinToString("，") { "${it.name}（${ratioText(it.uncompressedSize)}%）" },
            )
        }
        verifyAll(candidates.filter { isPlausible(it) }, verified, rejected, scanned)
        if (verified.isEmpty()) {
            val recorded = candidates.filter { it.name in preferredNames && it.name !in scanned }
            if (recorded.isNotEmpty()) {
                onLog("内嵌来源：${recorded.map { it.name }} 按记录名继续验证")
                verifyAll(recorded, verified, rejected, scanned)
            }
        }
        if (verified.isEmpty()) {
            if (rejected.isNotEmpty()) {
                onLog("内嵌来源：候选全部淘汰（${rejected.take(3).joinToString("；")}）")
            }
            return Outcome(null, "", candidates.map { it.name }, rejected, unproven.toList())
        }
        return choose(verified, candidates, rejected)
    }

    private fun candidates(): List<Entry> {
        val found = LinkedHashMap<String, Entry>()
        for (name in preferred) {
            val entry = entries.firstOrNull { it.name == name && it.uncompressedSize > 0 } ?: continue
            found[entry.name] = entry
        }
        entries
            .filter { it.name !in found }
            .filter { it.name.endsWith(APK_SUFFIX, ignoreCase = true) && it.uncompressedSize >= MIN_CANDIDATE }
            .sortedByDescending { score(it) }
            .forEach { found[it.name] = it }
        for (entry in entries) {
            if (entry.name in found || entry.uncompressedSize < MIN_CANDIDATE) continue
            if (!startsWithZipMagic(entry)) continue
            found[entry.name] = entry
        }
        return found.values.toList()
    }

    private fun verifyAll(
        targets: List<Entry>,
        verified: MutableList<Verified>,
        rejected: MutableList<String>,
        scanned: MutableSet<String>,
    ) {
        for (entry in targets) {
            if (!scanned.add(entry.name)) continue
            val file = verify(entry, rejected) ?: continue
            verified += file
        }
    }

    private fun verify(entry: Entry, rejected: MutableList<String>): Verified? {
        outDir.mkdirs()
        val target = File(outDir, "strip.${System.nanoTime()}.${entries.size}.base.apk")
        target.delete()
        try {
            extract(entry, target)
        } catch (error: Exception) {
            target.delete()
            rejected += describe(entry, "解包失败 ${error.javaClass.simpleName}: ${error.message}")
            return null
        }
        val installable = runCatching {
            ZipFile(target).use { zip ->
                zip.getEntry(MANIFEST) != null &&
                    zip.entries().asSequence().any { it.name.startsWith("classes") && it.name.endsWith(".dex") }
            }
        }.getOrDefault(false)
        if (!installable) {
            target.delete()
            rejected += describe(entry, "不是安装包（缺少 AndroidManifest.xml 或 classes*.dex）")
            return null
        }
        val innerPackage = runCatching { packageNameOf(target) }.getOrNull().orEmpty()
        if (outerPackage.isBlank() || innerPackage != outerPackage) {
            target.delete()
            rejected += describe(entry, "包名 $innerPackage 与外层 $outerPackage 不一致")
            return null
        }
        val verdict = verifyEntries(target, entry)
        if (verdict.reason != null) {
            target.delete()
            rejected += describe(entry, verdict.reason)
            unproven += entry.name
            return null
        }
        return Verified(entry, target, verdict.hits)
    }

    private fun verifyEntries(target: File, candidate: Entry): Verdict {
        val sizes = HashMap<String, Long>(entries.size * 2)
        val crcs = HashMap<String, Long>(entries.size * 2)
        runCatching {
            ZipFile(target).use { zip ->
                zip.entries().asSequence().forEach { item ->
                    sizes[item.name] = item.size
                    crcs[item.name] = item.crc
                }
            }
        }.onFailure {
            return Verdict(0, "内嵌包中央目录不可读 ${it.javaClass.simpleName}: ${it.message}")
        }
        var hits = 0
        var missing = 0
        var skipped = 0
        val samples = ArrayList<String>(3)
        for (entry in entries) {
            if (excluded(entry.name, candidate)) continue
            val size = sizes[entry.name]
            val crc = crcs[entry.name]
            if (size == null || crc == null) {
                if (isDexEntry(entry.name)) {
                    skipped++
                    continue
                }
                if (missing < 3) samples += entry.name
                missing++
                continue
            }
            if (size != entry.uncompressedSize || crc != entry.crc) {
                if (missing < 3) samples += entry.name
                missing++
                continue
            }
            hits++
        }
        if (skipped > 0) {
            onLog("内嵌来源：${candidate.name} 跳过内嵌包不存在的引导桩 $skipped 个（classes*.dex）")
        }
        if (missing == 0) return Verdict(hits, null)
        return Verdict(hits, "与外层有 $missing 个条目不一致（如 ${samples.joinToString("，")}）")
    }

    private fun choose(verified: List<Verified>, candidates: List<Entry>, rejected: List<String>): Outcome {
        val best = verified.maxByOrNull { it.entry.uncompressedSize }!!
        val twins = verified.filter {
            it !== best && it.hits == best.hits && it.entry.uncompressedSize == best.entry.uncompressedSize
        }
        if (twins.isNotEmpty()) {
            verified.forEach { runCatching { it.file.delete() } }
            throw AmbiguousException(listOf(best.entry.name) + twins.map { it.entry.name })
        }
        verified.forEach { candidate -> if (candidate !== best) runCatching { candidate.file.delete() } }
        onLog(
            "内嵌来源：采用 ${best.entry.name}（${best.entry.uncompressedSize / 1024 / 1024} MB，" +
                "V3 命中 ${best.hits} 条，体积比 ${ratioText(best.entry.uncompressedSize)}%）",
        )
        return Outcome(best.file, best.entry.name, candidates.map { it.name }, rejected, unproven.toList())
    }

    private fun excluded(name: String, candidate: Entry): Boolean =
        name == candidate.name ||
            name == MANIFEST ||
            name == STAMP_CERT ||
            name.startsWith(META_INF) ||
            injected(name)

    private fun isPlausible(entry: Entry): Boolean =
        entry.uncompressedSize * 2 >= source.length() ||
            entry.name in preferredNames ||
            entry.name.endsWith(APK_SUFFIX, ignoreCase = true)

    private fun isDexEntry(name: String): Boolean =
        name.startsWith("classes") && name.endsWith(".dex")

    private fun startsWithZipMagic(entry: Entry): Boolean {
        val head = runCatching { prefix(entry, ZIP_MAGIC_SIZE) }.getOrNull() ?: return false
        if (head.size != ZIP_MAGIC_SIZE) return false
        return head[0] == ZIP_MAGIC[0] && head[1] == ZIP_MAGIC[1] &&
            head[2] == ZIP_MAGIC[2] && head[3] == ZIP_MAGIC[3]
    }

    private fun prefix(entry: Entry, bytes: Int): ByteArray? {
        if (entry.compressedSize <= 0L || entry.method != STORED && entry.method != DEFLATED) return null
        source.seek(entry.dataStart)
        if (entry.method == STORED) {
            val out = ByteArray(minOf(entry.compressedSize, bytes.toLong()).toInt())
            source.readFully(out)
            return out
        }
        val out = ByteArray(bytes)
        var filled = 0
        var remaining = entry.compressedSize
        val chunk = ByteArray(PREFIX_CHUNK)
        val inflater = Inflater(true)
        try {
            while (filled < bytes && remaining > 0L) {
                val read = minOf(remaining, chunk.size.toLong()).toInt()
                source.readFully(chunk, 0, read)
                remaining -= read
                inflater.setInput(chunk, 0, read)
                while (filled < bytes && !inflater.needsInput() && !inflater.finished()) {
                    val produced = inflater.inflate(out, filled, bytes - filled)
                    if (produced <= 0) break
                    filled += produced
                }
            }
        } finally {
            inflater.end()
        }
        return if (filled == bytes) out else out.copyOf(filled)
    }

    private fun extract(entry: Entry, target: File) {
        when (entry.method) {
            STORED -> {
                source.seek(entry.dataStart)
                FileOutputStream(target, false).buffered(BUFFER_SIZE).use { out -> copy(entry.compressedSize, out) }
            }

            DEFLATED -> FileOutputStream(target, false).buffered(BUFFER_SIZE).use { out ->
                source.seek(entry.dataStart)
                val chunk = ByteArray(INFLATE_CHUNK)
                val inflater = Inflater(true)
                try {
                    var remaining = entry.compressedSize
                    while (remaining > 0L && !inflater.finished()) {
                        val read = minOf(remaining, chunk.size.toLong()).toInt()
                        source.readFully(chunk, 0, read)
                        remaining -= read
                        inflater.setInput(chunk, 0, read)
                        while (!inflater.needsInput() && !inflater.finished()) {
                            val produced = inflater.inflate(chunk)
                            if (produced <= 0) break
                            out.write(chunk, 0, produced)
                        }
                    }
                } finally {
                    inflater.end()
                }
            }

            else -> error("不支持的内嵌包压缩方式 ${entry.method}")
        }
        require(target.length() == entry.uncompressedSize) {
            "解包体积异常 ${target.length()} != ${entry.uncompressedSize}"
        }
    }

    private fun copy(length: Long, out: OutputStream) {
        if (length <= 0L) return
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = length
        while (remaining > 0L) {
            val read = source.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read <= 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        require(remaining == 0L) { "内嵌包数据不完整" }
    }

    private fun describe(entry: Entry, reason: String): String = "${entry.name}（$reason）"

    private fun ratioText(size: Long): String =
        "%.1f".format(size * 100.0 / source.length().coerceAtLeast(1L))

    private companion object {
        const val MANIFEST = "AndroidManifest.xml"
        const val STAMP_CERT = "stamp-cert-sha256"
        const val META_INF = "META-INF/"
        const val APK_SUFFIX = ".apk"
        const val MIN_CANDIDATE = 64L * 1024
        const val STORED = 0
        const val DEFLATED = 8
        const val ZIP_MAGIC_SIZE = 4
        const val BUFFER_SIZE = 256 * 1024
        const val INFLATE_CHUNK = 16 * 1024
        const val PREFIX_CHUNK = 16 * 1024

        val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    }
}
