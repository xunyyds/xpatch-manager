package io.xpatch.patch.obfuscate

import java.security.SecureRandom

class HostProfile private constructor(
    val dataDir: String,
    val dataTemplates: List<Pair<String, String>>,
    val libDir: String,
    val libTemplates: List<Pair<String, String>>,
    val abiDirs: Set<String>,
    val neutral: Boolean,
) {

    fun dataName(random: SecureRandom, taken: Set<String>, preferredExt: String, binary: Boolean): String {
        return sibling(random, taken, dataDir, dataTemplates, preferredExt, binary, "")
    }

    fun soName(random: SecureRandom, taken: Set<String>, abi: String, required: Boolean): String? {
        if (required && abi.isNotEmpty() && abi !in abiDirs) return null
        val dir = if (abi.isNotEmpty() && abi in abiDirs) "lib/$abi/" else libDir
        if (dir.isEmpty() || libTemplates.isEmpty()) {
            return sibling(random, taken, dataDir, dataTemplates, "so", true, "")
        }
        return sibling(random, taken, dir, libTemplates, "so", true, "lib")
    }

    private fun sibling(
        random: SecureRandom,
        taken: Set<String>,
        dir: String,
        templates: List<Pair<String, String>>,
        preferredExt: String,
        binary: Boolean,
        prefix: String,
    ): String {
        repeat(128) {
            val template = if (neutral || templates.isEmpty()) {
                "" to preferredExt
            } else {
                templates[random.nextInt(templates.size)]
            }
            val stem = template.first.removePrefix(prefix)
            val ext = chooseExt(preferredExt, template.second, binary)
            val tail = buildString {
                val length = if (neutral) NEUTRAL_STEM_LENGTH else 2 + random.nextInt(3)
                repeat(length) { append(STEM_TAIL[random.nextInt(STEM_TAIL.length)]) }
            }
            val body = if (neutral) tail else stem + tail
            val name = dir + prefix + body + (if (ext.isEmpty()) "" else "." + ext)
            if (name !in taken) return name
        }
        return dir + prefix + "x" + random.nextInt(Int.MAX_VALUE).toString(36) + "." + preferredExt
    }

    private fun chooseExt(preferredExt: String, templateExt: String, binary: Boolean): String {
        if (preferredExt == templateExt) return templateExt
        if (preferredExt == "so") return "so"
        if (binary) {
            return if (templateExt in BINARY_EXTS) templateExt else preferredExt
        }
        return if (templateExt in TEXT_EXTS) templateExt else preferredExt
    }

    companion object {

        private const val STEM_TAIL = "abcdefghijklmnopqrstuvwxyz0123456789"

        private const val NEUTRAL_STEM_LENGTH = 10

        private const val MIN_DIR_FILES = 3

        private const val MAX_TEMPLATES = 32

        private const val MIN_TEMPLATE_POOL = 2

        private val MEDIA_EXTS = setOf(
            "png", "webp", "jpg", "jpeg", "gif", "bmp", "svg", "ttf", "otf", "woff", "woff2",
            "mp3", "mp4", "aac", "wav", "ogg", "m4a", "html", "htm", "xml", "arsc",
        )

        private val TEXT_EXTS = setOf("json", "txt", "lst", "properties", "")

        private val BINARY_EXTS = setOf("bin", "dat", "zip", "apk", "pak", "bytes", "db", "br", "")

        private val STRUCTURAL_STEMS = setOf(
            "androidmanifest", "resources", "stamp-cert-sha256", "manifest", "index",
        )

        private val NEUTRAL_DIRS = listOf(
            "assets/data/", "assets/cache/", "assets/config/", "assets/dexopt/", "assets/res/", "assets/payload/",
        )

        fun of(entries: List<Pair<String, Long>>): HostProfile {
            val byDir = HashMap<String, MutableList<String>>()
            for ((name, _) in entries) {
                if (name.isBlank() || name.endsWith("/")) continue
                val slash = name.lastIndexOf('/')
                if (slash <= 0) continue
                val dir = name.substring(0, slash + 1)
                val base = name.substring(slash + 1)
                if (base.isEmpty()) continue
                byDir.getOrPut(dir) { ArrayList() }.add(base)
            }
            val abiDirs = byDir.keys
                .filter { it.startsWith("lib/") && it.count { ch -> ch == '/' } == 2 }
                .map { it.removePrefix("lib/").removeSuffix("/") }
                .toSet()
            val libDir = byDir.keys
                .filter { it.startsWith("lib/") && it.count { ch -> ch == '/' } == 2 }
                .maxByOrNull { byDir[it]?.size ?: 0 }
                ?.takeIf { (byDir[it]?.size ?: 0) >= MIN_DIR_FILES }
                .orEmpty()
            val libTemplates = templates(byDir[libDir].orEmpty(), true)
            val dataCandidates = byDir.entries
                .filter { it.key != libDir && !it.key.startsWith("lib/") }
                .map { entry -> entry.key to usableFiles(entry.value) }
                .filter { it.second.size >= MIN_DIR_FILES }
                .filter { it.second.size >= MIN_TEMPLATE_POOL }
                .sortedByDescending { it.second.size }
                .take(64)
            val pick = if (dataCandidates.isEmpty()) {
                null
            } else {
                dataCandidates[(Math.abs(dataCandidates.hashCode()) % dataCandidates.size)]
            }
            if (pick == null) {
                val dir = NEUTRAL_DIRS[0]
                return HostProfile(dir, emptyList(), libDir, libTemplates, abiDirs, true)
            }
            return HostProfile(pick.first, templates(pick.second, false), libDir, libTemplates, abiDirs, false)
        }

        private fun usableFiles(files: List<String>): List<String> {
            return files.filter { !isMedia(it) && !isStructural(it) }
        }

        private fun templates(files: List<String>, library: Boolean): List<Pair<String, String>> {
            val result = ArrayList<Pair<String, String>>()
            for (base in files) {
                if (library && !base.endsWith(".so")) continue
                if (isMedia(base) || isStructural(base)) continue
                val dot = base.lastIndexOf('.')
                val ext = if (dot > 0) base.substring(dot + 1).lowercase() else ""
                val stem = if (dot > 0) base.substring(0, dot) else base
                if (stem.isEmpty() || stem.length > 24) continue
                val pair = stem to ext
                if (result.size < MAX_TEMPLATES && !result.contains(pair)) result.add(pair)
            }
            return result
        }

        private fun isStructural(fileName: String): Boolean {
            val dot = fileName.lastIndexOf('.')
            val stem = if (dot > 0) fileName.substring(0, dot) else fileName
            val lower = stem.lowercase()
            if (lower in STRUCTURAL_STEMS) return true
            return lower.startsWith("classes") && lower.removePrefix("classes").all { it.isDigit() }
        }

        private fun isMedia(fileName: String): Boolean {
            val dot = fileName.lastIndexOf('.')
            if (dot <= 0) return false
            return fileName.substring(dot + 1).lowercase() in MEDIA_EXTS
        }
    }
}

