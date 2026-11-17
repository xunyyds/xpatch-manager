package io.xpatch.gradle

import java.io.File
import org.gradle.api.Project

fun Project.registerXpTasks(compileSdk: Int, buildToolsVersion: String) {
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

    val xpStubAndroidJar: File? = XpStubDex.resolveAndroidJar(xpStubSdkDir, compileSdk)
    val xpStubD8: File? = XpStubDex.resolveD8(xpStubSdkDir, buildToolsVersion)
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

    val buildStubDexTask = tasks.register("buildStubDex", BuildStubDexTask::class.java) { task ->
        task.group = "xp"
        task.description = "编译 manager/stub 的引导桩源码并用 d8 生成 assets/xp/stub.dex"
        if (xpStubSourceDir.isDirectory) task.sourceDir.fileValue(xpStubSourceDir)
        task.dexFile.set(xpStubDexFile)
        task.checksumFile.set(xpStubChecksumFile)
        task.stampFile.set(layout.buildDirectory.file("stub-dex.ok"))
        task.workDir.set(layout.buildDirectory.dir("stub-dex"))
        task.autoBuild.set(xpStubAutoBuild)
        task.javaVersion.set(xpStubJavaVersion)
        task.minApi.set(xpStubMinApi)
        task.javaHome.set(System.getProperty("java.home") ?: "")
        xpStubJavac?.let { task.javacPath.set(it.absolutePath) }
        xpStubD8?.let { task.d8Path.set(it.absolutePath) }
        xpStubAndroidJar?.let { task.androidJar.set(it) }
    }

    val verifyStubDexTask = tasks.register("verifyStubDex", VerifyStubDexTask::class.java) { task ->
        task.group = "xp"
        task.description = "校验 assets/xp/stub.dex 确实由 manager/stub 当前源码生成（默认严格，挂 check 与 assembleRelease）"
        if (xpStubSourceDir.isDirectory) task.sourceDir.fileValue(xpStubSourceDir)
        task.dexFile.set(xpStubDexFile)
        task.checksumFile.set(xpStubChecksumFile)
        task.strict.set(xpStubVerifyStrict)
        task.dependsOn(buildStubDexTask)
    }

    tasks.matching { it.name == "preBuild" }.configureEach { task ->
        task.dependsOn(buildStubDexTask)
    }
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { task ->
        task.dependsOn(buildStubDexTask)
    }
    tasks.matching { it.name == "check" || it.name == "assembleRelease" || it.name == "bundleRelease" }
        .configureEach { task ->
            task.dependsOn(verifyStubDexTask)
        }

    val xpRepoRoot: File = rootProject.projectDir.parentFile ?: rootProject.projectDir

    val checkRuntimeVersionTask = tasks.register("checkRuntimeVersion", CheckRuntimeVersionTask::class.java) { task ->
        task.group = "xp"
        task.description = "校验 ApkPatcher.RUNTIME_VERSION 与内核 CoreEntry.RUNTIME_VERSION 一致"
        task.patcherFile.set(file("src/main/java/io/xpatch/patch/ApkPatcher.kt"))
        task.coreEntryFile.set(File(xpRepoRoot, "runtime/java/io/xpatch/core/CoreEntry.java"))
        task.latestLabelFile.set(file("src/main/java/io/xpatch/ui/screens/ScopeScreens.kt"))
    }

    val xpAssetsDir: File = file("src/main/assets/xp")
    val xpDebugAssetsDir: File = file("src/debug/assets/xp")
    val xpRuntimeSourceDir: File = File(xpRepoRoot, "runtime/java")
    val xpNativeCoreDir: File = File(xpRepoRoot.parentFile ?: xpRepoRoot, "native-core")

    val checkReleaseAssetsTask = tasks.register("checkReleaseAssets", CheckReleaseAssetsTask::class.java) { task ->
        task.group = "xp"
        task.description = "校验发布资产（hook_core*.so 导出符号、runtime.dex 类集合、来源产物）与当前源码一致"
        task.assetsDir.set(xpAssetsDir)
        task.runtimeSourceDir.set(xpRuntimeSourceDir)
        task.debugAssetsDir.set(xpDebugAssetsDir)
        task.nativeCoreBuildDir.set(File(xpNativeCoreDir, "build"))
        task.hookCoreSo.set(File(xpAssetsDir, "hook_core.so"))
        task.hookCoreX86So.set(File(xpAssetsDir, "hook_core_x86_64.so"))
        task.runtimeDex.set(File(xpAssetsDir, "runtime.dex"))
        task.stubDex.set(File(xpAssetsDir, "stub.dex"))
        task.stubSourceDir.set(File(rootProject.projectDir, "stub"))
        task.androidSdkDir.set(providers.provider { xpStubSdkDir })
        task.nmPath.set(providers.provider { XpNativeGate.resolveNm(xpStubSdkDir)?.absolutePath ?: "" })
    }

    tasks.matching { it.name == "check" || it.name == "assembleRelease" || it.name == "bundleRelease" }
        .configureEach { task ->
            task.dependsOn(checkRuntimeVersionTask)
        }

    tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }
        .configureEach { task ->
            task.dependsOn(checkReleaseAssetsTask)
        }

    tasks.matching { it.name == "mergeReleaseAssets" }.configureEach { task ->
        task.dependsOn(checkReleaseAssetsTask)
    }
}
