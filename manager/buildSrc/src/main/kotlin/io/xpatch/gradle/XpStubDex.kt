package io.xpatch.gradle

import java.io.File
import java.security.MessageDigest
import java.util.Properties

object XpStubDex {

    private val versionPattern = Regex("""(\d+(?:\.\d+)*)""")

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    fun versionKey(name: String): List<Int> =
        versionPattern.find(name)?.value
            ?.split('.')
            ?.mapNotNull { it.toIntOrNull() }
            ?: emptyList()

    fun compareVersion(a: String, b: String): Int {
        val left = versionKey(a)
        val right = versionKey(b)
        for (index in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrElse(index) { 0 }
            val r = right.getOrElse(index) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    fun localProperty(file: File, key: String): String? {
        if (!file.isFile) return null
        return try {
            val properties = Properties()
            file.inputStream().use { properties.load(it) }
            properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (error: Exception) {
            null
        }
    }

    fun resolveAndroidJar(sdkDir: File?, compileSdk: Int): File? {
        if (sdkDir == null) return null
        val platforms = File(sdkDir, "platforms")
        if (!platforms.isDirectory) return null
        val jars = platforms.listFiles { file -> file.isDirectory }
            ?.sortedWith(Comparator<File> { a, b -> compareVersion(b.name, a.name) })
            ?.map { File(it, "android.jar") }
            ?.filter { it.isFile }
            .orEmpty()
        return jars.firstOrNull { versionKey(it.parentFile.name).firstOrNull() == compileSdk }
            ?: jars.firstOrNull()
    }

    fun resolveD8(sdkDir: File?, buildToolsVersion: String): File? {
        if (sdkDir == null) return null
        val root = File(sdkDir, "build-tools")
        if (!root.isDirectory) return null
        val executable = if (isWindows()) "d8.bat" else "d8"
        File(File(root, buildToolsVersion), executable).takeIf { it.isFile }?.let { return it }
        return root.listFiles { file -> file.isDirectory }
            ?.sortedWith(Comparator<File> { a, b -> compareVersion(b.name, a.name) })
            ?.asSequence()
            ?.map { File(it, executable) }
            ?.firstOrNull { it.isFile }
    }

    fun resolveJdkTool(tool: String): File? {
        val executable = if (isWindows()) "$tool.exe" else tool
        val candidates = mutableListOf<File>()
        val javaHome = System.getProperty("java.home")?.takeIf { it.isNotBlank() }
        if (javaHome != null) {
            candidates += File(javaHome, "bin/$executable")
            File(javaHome).parentFile?.let { candidates += File(it, "bin/$executable") }
        }
        System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }
            ?.let { candidates += File(it, "bin/$executable") }
        return candidates.firstOrNull { it.isFile }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    fun sourceDigest(dir: File): String {
        val base = dir.absoluteFile
        if (!base.isDirectory) return sha256OfText("missing")
        val files = base.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".java") }
            .sortedBy { it.relativeTo(base).invariantSeparatorsPath }
            .toList()
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { file ->
            val path = file.relativeTo(base).invariantSeparatorsPath
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(file.readBytes())
            digest.update(0.toByte())
        }
        return hex(digest.digest())
    }

    fun sha256OfText(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(text.toByteArray(Charsets.UTF_8))
        return hex(digest.digest())
    }

    private val packagePattern = Regex("""^\s*package\s+([\w.]+)\s*;""", RegexOption.MULTILINE)

    fun packageOf(file: File): String =
        packagePattern.find(file.readText(Charsets.UTF_8))?.groupValues?.get(1).orEmpty()

    fun expectedDescriptors(sourcesRoot: File): List<String> =
        sourcesRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".java") }
            .map { file ->
                val pkg = packageOf(file)
                val simpleName = file.name.removeSuffix(".java")
                if (pkg.isEmpty()) "L$simpleName;" else "L${pkg.replace('.', '/')}/$simpleName;"
            }
            .sorted()
            .toList()

    fun missingDescriptors(dex: File, sourcesRoot: File): List<String> {
        val bytes = dex.readBytes()
        return expectedDescriptors(sourcesRoot).filter { descriptor ->
            !containsBytes(bytes, descriptor.toByteArray(Charsets.UTF_8))
        }
    }

    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        val last = haystack.size - needle.size
        var start = 0
        while (start <= last) {
            if (haystack[start] == needle[0]) {
                var offset = 1
                while (offset < needle.size && haystack[start + offset] == needle[offset]) offset++
                if (offset == needle.size) return true
            }
            start++
        }
        return false
    }

    fun tail(text: String, limit: Int = 1600): String =
        if (text.length <= limit) text else "…" + text.takeLast(limit)
}
