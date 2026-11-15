package io.xpatch.gradle

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

@DisableCachingByDefault(because = "写回源码树里的 assets，且依赖本机的 SDK 工具链")
abstract class BuildStubDexTask : DefaultTask() {

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:InputFile
    @get:Optional
    abstract val androidJar: RegularFileProperty

    @get:OutputFile
    abstract val dexFile: RegularFileProperty

    @get:OutputFile
    abstract val checksumFile: RegularFileProperty

    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    @get:Internal
    abstract val workDir: DirectoryProperty

    @get:Input
    abstract val autoBuild: Property<Boolean>

    @get:Input
    abstract val javaVersion: Property<String>

    @get:Input
    abstract val minApi: Property<Int>

    @get:Input
    @get:Optional
    abstract val javacPath: Property<String>

    @get:Input
    @get:Optional
    abstract val d8Path: Property<String>

    @get:Internal
    abstract val javaHome: Property<String>

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun build() {
        val dex = dexFile.get().asFile

        if (!autoBuild.get()) {
            logger.lifecycle("buildStubDex: xp.stub.autoBuild=false，跳过生成，沿用已入库的 ${dex.name}")
            return
        }

        val jar = androidJar.orNull?.asFile
        val javac = javacPath.orNull?.let(::File)
        val d8 = d8Path.orNull?.let(::File)
        val sourcesRoot = sourceDir.orNull?.asFile
        val missing = buildList {
            if (sourcesRoot == null || !sourcesRoot.isDirectory) add("manager/stub 源码目录")
            if (jar == null || !jar.isFile) add("android.jar")
            if (javac == null || !javac.isFile) add("javac")
            if (d8 == null || !d8.isFile) add("d8")
        }
        if (missing.isNotEmpty()) {
            logger.warn(
                "buildStubDex: 找不到 ${missing.joinToString("、")}，跳过引导桩编译，" +
                    "沿用已入库的 ${dex.name}。请检查 local.properties 的 sdk.dir 与 JDK，" +
                    "或显式关闭：-Pxp.stub.autoBuild=false"
            )
            return
        }
        val jarFile = jar ?: return
        val javacFile = javac ?: return
        val d8File = d8 ?: return
        val sourceRoot = sourcesRoot ?: return

        val work = workDir.get().asFile
        val classes = File(work, "classes")
        val dexOut = File(work, "dex")
        val stamp = stampFile.get().asFile
        stamp.delete()
        try {
            classes.deleteRecursively()
            dexOut.deleteRecursively()
            classes.mkdirs()
            dexOut.mkdirs()

            val sources = sourceRoot.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".java") }
                .sortedBy { it.path }
                .toList()
            if (sources.isEmpty()) {
                logger.warn("buildStubDex: $sourceRoot 下没有 .java，跳过")
                return
            }

            val env = buildMap {
                javaHome.orNull?.takeIf { it.isNotBlank() }?.let { put("JAVA_HOME", it) }
            }

            runTool(
                listOf(
                    javacFile.absolutePath,
                    "-encoding", "UTF-8",
                    "-nowarn",
                    "-g:lines",
                    "-source", javaVersion.get(),
                    "-target", javaVersion.get(),
                    "-classpath", jarFile.absolutePath,
                    "-d", classes.absolutePath,
                ) + sources.map { it.absolutePath },
                env, work, "javac",
            )

            val classFiles = classes.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") }
                .sortedBy { it.path }
                .toList()
            if (classFiles.isEmpty()) {
                throw GradleException("buildStubDex: javac 退出码 0 但没有产出任何 .class（$classes）")
            }

            runTool(
                listOf(
                    d8File.absolutePath,
                    "--release",
                    "--min-api", minApi.get().toString(),
                    "--lib", jarFile.absolutePath,
                    "--output", dexOut.absolutePath,
                ) + classFiles.map { it.absolutePath },
                env, work, "d8",
            )

            val produced = File(dexOut, "classes.dex")
            if (!produced.isFile || produced.length() == 0L) {
                throw GradleException("buildStubDex: d8 退出码 0 但没有产出 classes.dex（$dexOut）")
            }

            val parent = dex.parentFile
                ?: throw GradleException("buildStubDex: assets/xp 目录不存在：${dex.absolutePath}")
            parent.mkdirs()
            val previousHash = if (dex.isFile) XpStubDex.sha256(dex) else null
            val temp = File(parent, dex.name + ".tmp")
            produced.copyTo(temp, overwrite = true)
            val replaced = runCatching {
                Files.move(
                    temp.toPath(),
                    dex.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.isSuccess
            if (!replaced) {
                temp.copyTo(dex, overwrite = true)
                temp.delete()
            }

            val checksum = checksumFile.get().asFile
            checksum.parentFile?.mkdirs()
            val dexHash = XpStubDex.sha256(dex)
            checksum.writeText(
                buildString {
                    appendLine("# 由 :app:buildStubDex 生成，请勿手工编辑")
                    appendLine("# dex      = stub.dex 自身哈希")
                    appendLine("# sources  = manager/stub 全部 .java 的路径+内容哈希")
                    appendLine("dex=$dexHash")
                    appendLine("sources=${XpStubDex.sourceDigest(sourceRoot)}")
                    appendLine("java=${javaVersion.get()}")
                    appendLine("minApi=${minApi.get()}")
                },
                Charsets.UTF_8,
            )

            if (previousHash != null && previousHash != dexHash) {
                logger.lifecycle(
                    "buildStubDex: stub.dex 内容已变化（$previousHash → $dexHash），" +
                        "确认无误后提交 assets/xp/stub.dex"
                )
            }
            stamp.parentFile?.mkdirs()
            stamp.writeText(dexHash, Charsets.UTF_8)
            logger.lifecycle(
                "buildStubDex: 已生成 ${dex.absolutePath}（${dex.length()} 字节，" +
                    "${sources.size} 个源文件，javac ${javaVersion.get()} / min-api ${minApi.get()}）"
            )
        } catch (error: GradleException) {
            throw error
        } catch (error: Exception) {
            throw GradleException(
                "buildStubDex: 生成引导桩 dex 失败，不再沿用已入库的 ${dex.name}：${error.message}",
                error,
            )
        }
    }

    private fun runTool(
        command: List<String>,
        environment: Map<String, String>,
        workingDirectory: File,
        label: String,
    ) {
        val output = ByteArrayOutputStream()
        val result = execOps.exec { spec ->
            spec.isIgnoreExitValue = true
            spec.commandLine(*command.toTypedArray())
            spec.workingDir = workingDirectory
            if (environment.isNotEmpty()) spec.environment(environment)
            spec.standardOutput = output
            spec.errorOutput = output
        }
        if (result.exitValue != 0) {
            val text = XpStubDex.tail(output.toString(Charsets.UTF_8.name()))
            throw GradleException("buildStubDex: $label 退出码 ${result.exitValue}\n$text")
        }
    }
}
