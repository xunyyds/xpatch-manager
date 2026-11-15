package io.xpatch.gradle

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "只做一致性校验，没有可缓存的输出")
abstract class VerifyStubDexTask : DefaultTask() {

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:Internal
    abstract val dexFile: RegularFileProperty

    @get:Internal
    abstract val checksumFile: RegularFileProperty

    @get:Input
    abstract val strict: Property<Boolean>

    @TaskAction
    fun verify() {
        val dex = dexFile.orNull?.asFile
        if (dex == null || !dex.isFile) {
            logger.warn("verifyStubDex: 没找到 assets/xp/stub.dex，跳过校验")
            return
        }

        val sourcesRoot = sourceDir.orNull?.asFile
        if (sourcesRoot == null || !sourcesRoot.isDirectory) {
            logger.warn("verifyStubDex: 没找到引导桩源码目录 manager/stub，跳过校验")
            return
        }

        val baseline = checksumFile.orNull?.asFile
        val recorded: Map<String, String> =
            if (baseline != null && baseline.isFile) readBaseline(baseline) else emptyMap()
        val expectedDex = recorded["dex"]
        val expectedSources = recorded["sources"]
        val actualDex = XpStubDex.sha256(dex)

        val problems = mutableListOf<String>()
        if (expectedDex != null && !expectedDex.equals(actualDex, ignoreCase = true)) {
            problems += "stub.dex 的 sha256 与基线记录不一致（可能被手工替换）：" +
                "当前 $actualDex，记录 $expectedDex"
        }
        if (expectedSources != null &&
            !expectedSources.equals(XpStubDex.sourceDigest(sourcesRoot), ignoreCase = true)
        ) {
            problems += "stub 源码已变更，请重新生成 dex（在 manager/ 下跑 gradle :app:buildStubDex）"
        }

        if (expectedDex == null || expectedSources == null) {
            val missing = XpStubDex.missingDescriptors(dex, sourcesRoot)
            if (missing.isNotEmpty()) {
                problems += "stub.dex 里缺少桩类 ${missing.joinToString("、")}：" +
                    "注入后会 ClassNotFoundException，请重新生成 dex（在 manager/ 下跑 gradle :app:buildStubDex）"
            } else {
                logger.lifecycle(
                    "verifyStubDex: 没有完整的 stub-dex.sha256 基线，已改按 dex 内容校验通过" +
                        "（${XpStubDex.expectedDescriptors(sourcesRoot).size} 个桩类齐全）；" +
                        "跑一次 :app:buildStubDex 可得到精确的哈希基线"
                )
            }
        }

        if (problems.isEmpty()) {
            if (expectedDex != null && expectedSources != null) {
                logger.lifecycle("verifyStubDex: stub.dex 与源码一致（sha256 $actualDex）")
            }
            return
        }
        report(problems)
    }

    private fun report(problems: List<String>) {
        val message = problems.joinToString("\n")
        if (strict.get()) {
            throw GradleException("verifyStubDex 校验失败：\n$message")
        }
        logger.warn("verifyStubDex 校验失败（仅提示，不阻塞构建）：\n$message")
    }

    private fun readBaseline(file: File): Map<String, String> {
        val result = mutableMapOf<String, String>()
        file.readLines(Charsets.UTF_8).forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val separator = trimmed.indexOf('=')
            if (separator > 0) {
                result[trimmed.substring(0, separator).trim()] = trimmed.substring(separator + 1).trim()
            } else if (trimmed.matches(Regex("""[0-9a-fA-F]{64}"""))) {
                result["dex"] = trimmed
            }
        }
        return result
    }
}
