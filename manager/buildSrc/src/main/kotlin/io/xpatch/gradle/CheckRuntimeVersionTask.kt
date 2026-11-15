package io.xpatch.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

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
