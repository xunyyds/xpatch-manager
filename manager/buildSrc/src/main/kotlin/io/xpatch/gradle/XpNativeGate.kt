package io.xpatch.gradle

import java.io.ByteArrayOutputStream
import java.io.File
import org.gradle.api.GradleException
import org.gradle.process.ExecOperations

object XpNativeGate {

    val EXPECTED_HOOK_CORE_EXPORTS: List<String> = listOf(
        "JNI_OnLoad",
        "Java_io_xpatch_core_NativeCalls_enable_1io_1redirect",
        "Java_io_xpatch_core_NativeCalls_enable_1io_1redirect_1dobby",
        "Java_io_xpatch_core_NativeCalls_enable_1io_1redirect_1mix",
        "Java_io_xpatch_core_NativeCalls_enable_1io_1redirect_1plt",
        "Java_io_xpatch_core_entry_NativeHookBridge_deopt",
        "Java_io_xpatch_core_entry_NativeHookBridge_disableProfileSaver",
        "Java_io_xpatch_core_entry_NativeHookBridge_hookMethod",
        "Java_io_xpatch_core_entry_NativeHookBridge_nativeInvoke",
    )

    val REQUIRED_RUNTIME_DESCRIPTORS: List<String> = listOf(
        "Lio/xpatch/core/CoreEntry;",
        "Lio/xpatch/core/XpInit;",
        "Lio/xpatch/core/NativeLoader;",
        "Lio/xpatch/core/XpLayout;",
        "Lio/xpatch/core/signbypass/LevelPltSignBypass;",
        "Lio/xpatch/core/signbypass/PMSignBypass;",
        "Lio/xpatch/core/signbypass/SignatureFacade;",
        "Lio/xpatch/core/signbypass/FileLayerHooks;",
        "Lio/xpatch/core/module/ModuleLoader;",
        "Lio/xpatch/core/module/LocalStoreModuleProvider;",
    )

    val REQUIRED_STUB_DESCRIPTORS: List<String> = listOf(
        "Lio/xpatch/stub/AppEntry;",
        "Lio/xpatch/stub/XpProvider;",
    )

    fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    fun resolveNm(sdkDir: File?): File? {
        if (sdkDir == null) return null
        val executable = if (isWindows()) "llvm-nm.exe" else "llvm-nm"
        val ndkRoot = File(sdkDir, "ndk")
        if (ndkRoot.isDirectory) {
            val found = ndkRoot.listFiles { file -> file.isDirectory }
                ?.sortedWith(Comparator { a, b -> XpStubDex.compareVersion(b.name, a.name) })
                ?.asSequence()
                ?.map { ndk ->
                    if (isWindows()) {
                        File(ndk, "toolchains/llvm/prebuilt/windows-x86_64/bin/$executable")
                    } else {
                        File(ndk, "toolchains/llvm/prebuilt/linux-x86_64/bin/$executable")
                    }
                }
                ?.firstOrNull { it.isFile }
            if (found != null) return found
        }
        val pathDirs = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
        return pathDirs.asSequence()
            .filter { it.isNotBlank() }
            .map { File(it, executable) }
            .firstOrNull { it.isFile }
    }

    fun readDefinedSymbols(nm: File, so: File, execOps: ExecOperations): List<String> {
        val out = ByteArrayOutputStream()
        val result = execOps.exec { spec ->
            spec.isIgnoreExitValue = true
            spec.commandLine(nm.absolutePath, "-D", "--defined-only", so.absolutePath)
            spec.standardOutput = out
            spec.errorOutput = out
        }
        if (result.exitValue != 0) {
            throw GradleException(
                "llvm-nm 读取失败（exit=${result.exitValue}）：${so.absolutePath}\n" +
                    XpStubDex.tail(out.toString(Charsets.UTF_8.name()))
            )
        }
        return out.toString(Charsets.UTF_8.name())
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line -> line.split(Regex("""\s+""")).last() }
            .distinct()
            .sorted()
            .toList()
    }

    private fun readU4(data: ByteArray, offset: Int): Long {
        return (data[offset].toLong() and 0xff) or
            ((data[offset + 1].toLong() and 0xff) shl 8) or
            ((data[offset + 2].toLong() and 0xff) shl 16) or
            ((data[offset + 3].toLong() and 0xff) shl 24)
    }

    private fun readDexString(data: ByteArray, offset: Int): String? {
        var pos = offset
        var utf16Size = 0L
        var shift = 0
        while (pos < data.size && shift <= 28) {
            val byte = data[pos].toInt() and 0xff
            pos++
            utf16Size = utf16Size or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
        }
        if (utf16Size <= 0L || utf16Size > 65535L) return null
        val builder = StringBuilder()
        var guard = 0
        while (pos < data.size && guard < 4096) {
            val byte = data[pos].toInt() and 0xff
            pos++
            if (byte == 0) break
            when {
                byte < 0x80 -> builder.append(byte.toChar())
                byte and 0xe0 == 0xc0 -> {
                    if (pos >= data.size) break
                    val next = data[pos].toInt() and 0xff
                    pos++
                    builder.append((((byte and 0x1f) shl 6) or (next and 0x3f)).toChar())
                }
                byte and 0xf0 == 0xe0 -> {
                    if (pos + 1 >= data.size) break
                    val b2 = data[pos].toInt() and 0xff
                    val b3 = data[pos + 1].toInt() and 0xff
                    pos += 2
                    builder.append((((byte and 0x0f) shl 12) or ((b2 and 0x3f) shl 6) or (b3 and 0x3f)).toChar())
                }
                else -> {
                    pos += 3
                    builder.append('\uFFFD')
                }
            }
            guard++
        }
        return builder.toString()
    }

    fun readDexClassDescriptors(dex: File): List<String> {
        val data = dex.readBytes()
        if (data.size < 0x70) throw GradleException("dex 头长度不足：${dex.absolutePath}")
        if (data[0] != 0x64.toByte() || data[1] != 0x65.toByte() ||
            data[2] != 0x78.toByte() || data[3] != 0x0a.toByte()
        ) {
            throw GradleException("不是合法的 dex（magic 不是 dex\\n）：${dex.absolutePath}")
        }
        val stringIdsSize = readU4(data, 0x38)
        val stringIdsOff = readU4(data, 0x3c).toInt()
        val typeIdsSize = readU4(data, 0x40)
        val typeIdsOff = readU4(data, 0x44).toInt()
        val classDefsSize = readU4(data, 0x60)
        val classDefsOff = readU4(data, 0x64).toInt()
        if (classDefsOff + classDefsSize * 32 > data.size) {
            throw GradleException("class_defs 越界：${dex.absolutePath}")
        }
        val result = LinkedHashSet<String>()
        for (index in 0 until classDefsSize.toInt()) {
            val defOff = classDefsOff + index * 32
            val classIdx = readU4(data, defOff)
            if (classIdx >= typeIdsSize) continue
            val descriptorIdx = readU4(data, typeIdsOff + classIdx.toInt() * 4)
            if (descriptorIdx >= stringIdsSize) continue
            val stringOff = readU4(data, stringIdsOff + descriptorIdx.toInt() * 4).toInt()
            if (stringOff <= 0 || stringOff >= data.size) continue
            val descriptor = readDexString(data, stringOff) ?: continue
            result.add(descriptor)
        }
        return result.toList()
    }

    private val packagePattern = Regex("""^\s*package\s+([\w.]+)\s*;""", RegexOption.MULTILINE)

    fun expectedDescriptors(sourcesRoot: File): List<String> {
        if (!sourcesRoot.isDirectory) return emptyList()
        val stubRoot = File(sourcesRoot, "stubs").absolutePath
        return sourcesRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".java") }
            .filter { !it.absolutePath.startsWith(stubRoot, ignoreCase = true) }
            .map { file ->
                val text = file.readText(Charsets.UTF_8)
                val pkg = packagePattern.find(text)?.groupValues?.get(1).orEmpty()
                val simpleName = file.name.removeSuffix(".java")
                if (pkg.isEmpty()) "L$simpleName;" else "L${pkg.replace('.', '/')}/$simpleName;"
            }
            .distinct()
            .sorted()
            .toList()
    }
}
