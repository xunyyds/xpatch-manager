package io.xpatch.gradle

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

@DisableCachingByDefault(because = "只做发布资产断言，没有可缓存的输出")
abstract class CheckReleaseAssetsTask : DefaultTask() {

    @get:Internal
    abstract val assetsDir: DirectoryProperty

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeSourceDir: DirectoryProperty

    @get:Internal
    abstract val debugAssetsDir: DirectoryProperty

    @get:Internal
    abstract val nativeCoreBuildDir: DirectoryProperty

    @get:Internal
    abstract val hookCoreSo: RegularFileProperty

    @get:Internal
    abstract val hookCoreX86So: RegularFileProperty

    @get:Internal
    abstract val runtimeDex: RegularFileProperty

    @get:Internal
    abstract val stubDex: RegularFileProperty

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stubSourceDir: DirectoryProperty

    @get:Internal
    abstract val androidSdkDir: Property<File>

    @get:Internal
    abstract val nmPath: Property<String>

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun check() {
        val problems = mutableListOf<String>()
        val notes = mutableListOf<String>()

        val assets = assetsDir.get().asFile
        if (!assets.isDirectory) {
            throw GradleException("发布资产目录不存在：${assets.absolutePath}")
        }

        val nm = nmPath.orNull?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
        if (nm == null) {
            problems += "找不到 llvm-nm（设置 ANDROID_SDK_ROOT 或 sdk.dir），无法校验 .so 导出符号"
        } else {
            notes += "llvm-nm = ${nm.absolutePath}"
        }

        val soCases = listOf(
            hookCoreSo.get().asFile to File(nativeCoreBuildDir.get().asFile, "libhook_core.so"),
            hookCoreX86So.get().asFile to File(nativeCoreBuildDir.get().asFile, "libhook_core.so"),
        )
        val x86BuildDir = File(nativeCoreBuildDir.get().asFile.parentFile, "build-x86").let { File(it, "libhook_core.so") }
        val soSources = listOf(
            hookCoreSo.get().asFile to File(nativeCoreBuildDir.get().asFile, "libhook_core.so"),
            hookCoreX86So.get().asFile to x86BuildDir,
        )

        soCases.forEachIndexed { index, pair ->
            if (index != 0) return@forEachIndexed
            val so = pair.first
            if (!so.isFile) {
                problems += "缺少发布资产 ${so.name}：${so.absolutePath}"
                return@forEachIndexed
            }
            val symbolHint = soSources[index].second
            if (!symbolHint.isFile) {
                problems += "找不到原生构建产物 ${symbolHint.absolutePath}，无法证明 ${so.name} 来源"
            } else if (XpStubDex.sha256(symbolHint) != XpStubDex.sha256(so)) {
                problems += "${so.name} 与 ${symbolHint.absolutePath} 不一致：" +
                    "资产不是最近一次原生构建的产物（${XpStubDex.sha256(so).take(12)} ≠ " +
                    "${XpStubDex.sha256(symbolHint).take(12)}）"
            }
            if (nm != null) {
                val exports = XpNativeGate.readDefinedSymbols(nm, so, execOps)
                val missing = XpNativeGate.EXPECTED_HOOK_CORE_EXPORTS.filter { it !in exports }
                val extra = exports.filter { it !in XpNativeGate.EXPECTED_HOOK_CORE_EXPORTS }
                if (missing.isNotEmpty()) {
                    problems += "${so.name} 缺少导出：${missing.joinToString(", ")}"
                }
                if (extra.isNotEmpty()) {
                    problems += "${so.name} 存在非白名单导出：${extra.joinToString(", ")}"
                }
                if (exports.size != XpNativeGate.EXPECTED_HOOK_CORE_EXPORTS.size) {
                    problems += "${so.name} 导出数量 ${exports.size} ≠ 期望 " +
                        "${XpNativeGate.EXPECTED_HOOK_CORE_EXPORTS.size}"
                }
                notes += "${so.name}: ${exports.size} 个导出（${XpStubDex.sha256(so).take(12)}）"
            }
        }

        val x86 = hookCoreX86So.get().asFile
        if (x86.isFile) {
            if (!x86BuildDir.isFile) {
                problems += "找不到原生构建产物 ${x86BuildDir.absolutePath}，无法证明 ${x86.name} 来源"
            } else if (XpStubDex.sha256(x86BuildDir) != XpStubDex.sha256(x86)) {
                problems += "${x86.name} 与 ${x86BuildDir.absolutePath} 不一致：" +
                    "资产不是最近一次原生构建的产物"
            }
            if (nm != null) {
                val exports = XpNativeGate.readDefinedSymbols(nm, x86, execOps)
                val missing = XpNativeGate.EXPECTED_HOOK_CORE_EXPORTS.filter { it !in exports }
                val extra = exports.filter { it !in XpNativeGate.EXPECTED_HOOK_CORE_EXPORTS }
                if (missing.isNotEmpty()) {
                    problems += "${x86.name} 缺少导出：${missing.joinToString(", ")}"
                }
                if (extra.isNotEmpty()) {
                    problems += "${x86.name} 存在非白名单导出：${extra.joinToString(", ")}"
                }
                notes += "${x86.name}: ${exports.size} 个导出（${XpStubDex.sha256(x86).take(12)}）"
            }
        } else {
            problems += "缺少发布资产 ${x86.name}"
        }

        val debugDir = debugAssetsDir.get().asFile
        if (debugDir.isDirectory) {
            val debugSo = File(debugDir, "hook_core_x86_64.so")
            if (debugSo.isFile && x86.isFile && XpStubDex.sha256(debugSo) != XpStubDex.sha256(x86)) {
                notes += "debug 变体存在不同的 hook_core_x86_64.so，release 使用 main 资产，未受影响"
            }
        }

        val dex = runtimeDex.get().asFile
        if (!dex.isFile) {
            problems += "缺少发布资产 runtime.dex：${dex.absolutePath}"
        } else {
            val classes = XpNativeGate.readDexClassDescriptors(dex)
            val sourcesRoot = runtimeSourceDir.orNull?.asFile
            if (sourcesRoot == null || !sourcesRoot.isDirectory) {
                problems += "找不到内核源码目录 runtime/java，无法校验 runtime.dex 类集合"
            } else {
                val expected = XpNativeGate.expectedDescriptors(sourcesRoot)
                val missingClasses = expected.filter { it !in classes }
                if (missingClasses.isNotEmpty()) {
                    problems += "runtime.dex 缺少当前源码类（${missingClasses.size} 个）：" +
                        missingClasses.take(20).joinToString(", ") +
                        if (missingClasses.size > 20) " …" else ""
                }
                val required = XpNativeGate.REQUIRED_RUNTIME_DESCRIPTORS.filter { it !in classes }
                if (required.isNotEmpty()) {
                    problems += "runtime.dex 缺少必需类：${required.joinToString(", ")}"
                }
                notes += "runtime.dex: ${classes.size} 个 class_defs，" +
                    "源码类 ${expected.size} 个（${XpStubDex.sha256(dex).take(12)}）"
            }
        }

        val stub = stubDex.get().asFile
        if (!stub.isFile) {
            problems += "缺少发布资产 stub.dex：${stub.absolutePath}"
        } else {
            val stubClasses = XpNativeGate.readDexClassDescriptors(stub)
            val missingStub = XpNativeGate.REQUIRED_STUB_DESCRIPTORS.filter { it !in stubClasses }
            if (missingStub.isNotEmpty()) {
                problems += "stub.dex 缺少引导桩：${missingStub.joinToString(", ")}"
            }
            val stubSources = stubSourceDir.orNull?.asFile
            if (stubSources != null && stubSources.isDirectory) {
                val missingStubs = XpStubDex.missingDescriptors(stub, stubSources)
                if (missingStubs.isNotEmpty()) {
                    problems += "stub.dex 缺少 manager/stub 源码类：" + missingStubs.joinToString(", ")
                }
            }
            notes += "stub.dex: ${stubClasses.size} 个 class_defs（${XpStubDex.sha256(stub).take(12)}）"
        }

        notes.forEach { logger.lifecycle("checkReleaseAssets: $it") }
        if (problems.isNotEmpty()) {
            val message = problems.joinToString("\n")
            logger.error("checkReleaseAssets 校验失败：\n$message")
            throw GradleException("checkReleaseAssets 校验失败（${problems.size} 项）：\n$message")
        }
        logger.lifecycle("checkReleaseAssets: 发布资产与当前源码一致")
    }
}
