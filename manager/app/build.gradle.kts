import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.security.MessageDigest
import javax.inject.Inject
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

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val xpCompileSdk = 37
val xpBuildToolsVersion = "37.0.0"

android {
    namespace = "io.xpatch"
    compileSdk = xpCompileSdk
    buildToolsVersion = xpBuildToolsVersion

    defaultConfig {
        applicationId = "io.xpatch"
        minSdk = 29
        targetSdk = 37
        versionCode = 8
        versionName = "0.1.7"
    }

    signingConfigs {
        create("release") {
            storeFile = file("src/main/assets/xp/key.p12")
            storePassword = "12345678"
            keyAlias = "xpatch"
            keyPassword = "12345678"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        dex {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.compose.ui:ui:1.12.1")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.runtime:runtime:1.12.1")
    implementation("androidx.compose.animation:animation:1.12.1")
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("com.android.tools.build:apksig:8.7.2")
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("io.github.kyant0:shapes:1.2.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4") {
        exclude(group = "org.jetbrains.compose.material3", module = "material3-window-size-class")
    }
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4") {
        exclude(group = "org.jetbrains.compose.material3", module = "material3-window-size-class")
    }
}

configurations.all {
    resolutionStrategy {
        force("androidx.compose.material3:material3:1.5.0-beta02")
        force("androidx.compose.material3:material3-window-size-class:1.5.0-beta02")
    }
}

val xpStubSdkDir: File? = run {
    val candidates = listOfNotNull(
        providers.gradleProperty("xp.stub.sdkDir").orNull,
        XpStubDex.localProperty(rootProject.file("local.properties"), "sdk.dir"),
        System.getenv("ANDROID_SDK_ROOT"),
        System.getenv("ANDROID_HOME"),
    )
    candidates.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { File(it) }
        .firstOrNull { it.isDirectory }
}

val xpStubAndroidJar: File? = XpStubDex.resolveAndroidJar(xpStubSdkDir, xpCompileSdk)
val xpStubD8: File? = XpStubDex.resolveD8(xpStubSdkDir, xpBuildToolsVersion)
val xpStubJavac: File? = XpStubDex.resolveJdkTool("javac")

val xpStubAutoBuild: Boolean = providers.gradleProperty("xp.stub.autoBuild").orNull
    ?.trim()?.lowercase()
    ?.let { it != "false" && it != "off" && it != "0" && it != "no" }
    ?: true

val xpStubVerifyStrict: Boolean = providers.gradleProperty("xp.stub.verifyStrict").orNull
    ?.trim()?.lowercase()
    ?.let { it == "true" || it == "on" || it == "1" || it == "yes" }
    ?: true

val xpStubJavaVersion: String = providers.gradleProperty("xp.stub.javaVersion").orNull
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?: "11"

val xpStubMinApi: Int = providers.gradleProperty("xp.stub.minApi").orNull
    ?.trim()?.toIntOrNull()
    ?: 29

val xpStubSourceDir: File = rootProject.file("stub")
val xpStubDexFile: File = file("src/main/assets/xp/stub.dex")
val xpStubChecksumFile = layout.buildDirectory.file("stub-dex.sha256")

val buildStubDexTask = tasks.register<BuildStubDexTask>("buildStubDex") {
    group = "xp"
    description = "编译 manager/stub 的引导桩源码并用 d8 生成 assets/xp/stub.dex"
    if (xpStubSourceDir.isDirectory) sourceDir.fileValue(xpStubSourceDir)
    dexFile.set(xpStubDexFile)
    checksumFile.set(xpStubChecksumFile)
    stampFile.set(layout.buildDirectory.file("stub-dex.ok"))
    workDir.set(layout.buildDirectory.dir("stub-dex"))
    autoBuild.set(xpStubAutoBuild)
    javaVersion.set(xpStubJavaVersion)
    minApi.set(xpStubMinApi)
    javaHome.set(System.getProperty("java.home") ?: "")
    xpStubJavac?.let { javacPath.set(it.absolutePath) }
    xpStubD8?.let { d8Path.set(it.absolutePath) }
    xpStubAndroidJar?.let { androidJar.set(it) }
}

val verifyStubDexTask = tasks.register<VerifyStubDexTask>("verifyStubDex") {
    group = "xp"
    description = "校验 assets/xp/stub.dex 确实由 manager/stub 当前源码生成（默认严格，挂 check 与 assembleRelease）"
    if (xpStubSourceDir.isDirectory) sourceDir.fileValue(xpStubSourceDir)
    dexFile.set(xpStubDexFile)
    checksumFile.set(xpStubChecksumFile)
    strict.set(xpStubVerifyStrict)
    dependsOn(buildStubDexTask)
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(buildStubDexTask)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(buildStubDexTask)
}
tasks.matching { it.name == "check" || it.name == "assembleRelease" || it.name == "bundleRelease" }
    .configureEach {
        dependsOn(verifyStubDexTask)
    }

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
        val result = execOps.exec {
            isIgnoreExitValue = true
            commandLine(*command.toTypedArray())
            workingDir = workingDirectory
            if (environment.isNotEmpty()) environment(environment)
            standardOutput = output
            errorOutput = output
        }
        if (result.exitValue != 0) {
            val text = XpStubDex.tail(output.toString(Charsets.UTF_8.name()))
            throw GradleException("buildStubDex: $label 退出码 ${result.exitValue}\n$text")
        }
    }
}

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

    fun readDefinedSymbols(nm: File, so: File, execOps: org.gradle.process.ExecOperations): List<String> {
        val out = ByteArrayOutputStream()
        val result = execOps.exec {
            isIgnoreExitValue = true
            commandLine(nm.absolutePath, "-D", "--defined-only", so.absolutePath)
            standardOutput = out
            errorOutput = out
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
    abstract val fpaCoreBuildDir: DirectoryProperty

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
            hookCoreSo.get().asFile to File(fpaCoreBuildDir.get().asFile, "libhook_core.so"),
            hookCoreX86So.get().asFile to File(fpaCoreBuildDir.get().asFile, "libhook_core.so"),
        )
        val x86BuildDir = File(fpaCoreBuildDir.get().asFile.parentFile, "build-x86").let { File(it, "libhook_core.so") }
        val soSources = listOf(
            hookCoreSo.get().asFile to File(fpaCoreBuildDir.get().asFile, "libhook_core.so"),
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

object XpVersionGate {
    val MANAGER_PATTERN = Regex("""const\s+val\s+RUNTIME_VERSION\s*(?::\s*Int\s*)?=\s*(\d+)""")

    val RUNTIME_PATTERN = Regex("""static\s+(?:final\s+)?int\s+RUNTIME_VERSION\s*=\s*(\d+)\s*;""")

    val LATEST_PATTERN = Regex("""const\s+val\s+LATEST_RUNTIME_VERSION\s*(?::\s*Int\s*)?=\s*(\d+)""")

    fun readConstant(file: File, pattern: Regex, label: String): Int {
        if (!file.isFile) {
            throw GradleException("版本门禁失败：找不到 $label 的源码文件：${file.absolutePath}")
        }
        val values = pattern.findAll(file.readText(Charsets.UTF_8))
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .distinct()
            .toList()
        return when {
            values.isEmpty() -> throw GradleException(
                "版本门禁失败：${file.absolutePath} 里找不到 $label 的常量声明" +
                    "（期望形如 `" + pattern.pattern + "`）"
            )
            values.size > 1 -> throw GradleException(
                "版本门禁失败：$label 在 ${file.absolutePath} 里有多个不同取值：" +
                    values.joinToString("、")
            )
            else -> values.single()
        }
    }
}

@DisableCachingByDefault(because = "只做源码文本断言，没有可缓存的输出")
abstract class CheckRuntimeVersionTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val patcherFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val coreEntryFile: RegularFileProperty

    @get:Internal
    abstract val latestLabelFile: RegularFileProperty

    @TaskAction
    fun check() {
        val manager = XpVersionGate.readConstant(
            patcherFile.get().asFile,
            XpVersionGate.MANAGER_PATTERN,
            "ApkPatcher.RUNTIME_VERSION",
        )
        val runtime = XpVersionGate.readConstant(
            coreEntryFile.get().asFile,
            XpVersionGate.RUNTIME_PATTERN,
            "CoreEntry.RUNTIME_VERSION",
        )
        if (manager != runtime) {
            throw GradleException(
                "版本门禁失败：管理器 ApkPatcher.RUNTIME_VERSION=$manager，" +
                    "内核 CoreEntry.RUNTIME_VERSION=$runtime。\n" +
                    "管理器把这个值写进 xp/config.json 的 ver，内核用它判断补丁是不是当前内核生成的；" +
                    "两边不一致时，新打的补丁会被当成旧内核产物。升级内核版本请同时改这两处常量。"
            )
        }

        val labelFile = latestLabelFile.orNull?.asFile
        if (labelFile != null && labelFile.isFile) {
            val label = runCatching {
                XpVersionGate.readConstant(
                    labelFile,
                    XpVersionGate.LATEST_PATTERN,
                    "LATEST_RUNTIME_VERSION",
                )
            }.getOrNull()
            if (label != null && label != manager) {
                logger.warn(
                    "checkRuntimeVersion: ScopeScreens.LATEST_RUNTIME_VERSION=$label 与 " +
                        "RUNTIME_VERSION=$manager 不一致，界面会把最新补丁标成「旧，建议重新打包」"
                )
            }
        }
        logger.lifecycle("checkRuntimeVersion: 管理器与内核的 RUNTIME_VERSION 一致（$manager）")
    }
}

val xpRepoRoot: File = rootProject.projectDir.parentFile ?: rootProject.projectDir

val checkRuntimeVersionTask = tasks.register<CheckRuntimeVersionTask>("checkRuntimeVersion") {
    group = "xp"
    description = "校验 ApkPatcher.RUNTIME_VERSION 与内核 CoreEntry.RUNTIME_VERSION 一致"
    patcherFile.set(file("src/main/java/io/xpatch/patch/ApkPatcher.kt"))
    coreEntryFile.set(File(xpRepoRoot, "runtime/java/io/xpatch/core/CoreEntry.java"))
    latestLabelFile.set(file("src/main/java/io/xpatch/ui/screens/ScopeScreens.kt"))
}

val xpAssetsDir: File = file("src/main/assets/xp")
val xpDebugAssetsDir: File = file("src/debug/assets/xp")
val xpRuntimeSourceDir: File = File(xpRepoRoot, "runtime/java")
val xpFpaCoreDir: File = File(xpRepoRoot.parentFile ?: xpRepoRoot, "fpa-core")

val checkReleaseAssetsTask = tasks.register<CheckReleaseAssetsTask>("checkReleaseAssets") {
    group = "xp"
    description = "校验发布资产（hook_core*.so 导出符号、runtime.dex 类集合、来源产物）与当前源码一致"
    assetsDir.set(xpAssetsDir)
    runtimeSourceDir.set(xpRuntimeSourceDir)
    debugAssetsDir.set(xpDebugAssetsDir)
    fpaCoreBuildDir.set(File(xpFpaCoreDir, "build"))
    hookCoreSo.set(File(xpAssetsDir, "hook_core.so"))
    hookCoreX86So.set(File(xpAssetsDir, "hook_core_x86_64.so"))
    runtimeDex.set(File(xpAssetsDir, "runtime.dex"))
    stubDex.set(File(xpAssetsDir, "stub.dex"))
    stubSourceDir.set(File(rootProject.projectDir, "stub"))
    androidSdkDir.set(providers.provider { xpStubSdkDir })
    nmPath.set(providers.provider { XpNativeGate.resolveNm(xpStubSdkDir)?.absolutePath ?: "" })
}

tasks.matching { it.name == "check" || it.name == "assembleRelease" || it.name == "bundleRelease" }
    .configureEach {
        dependsOn(checkRuntimeVersionTask)
    }

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }
    .configureEach {
        dependsOn(checkReleaseAssetsTask)
    }

tasks.matching { it.name == "mergeReleaseAssets" }.configureEach {
    dependsOn(checkReleaseAssetsTask)
}
