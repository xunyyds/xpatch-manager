package io.xpatch.ui.screens

import io.xpatch.data.UpdateChecker

internal data class AcknowledgementEntry(
    val name: String,
    val meta: String,
    val url: String = "",
)

internal object AcknowledgementsData {

    const val TITLE = "特别鸣谢"

    const val INTRO =
        "XPatch 建立在许多优秀项目之上，感谢以下项目及其作者的付出。" +
            "若您是相关权利人并对署名有异议，请通过关于页的反馈邮箱告知，我们会尽快核实处理。"

    const val CONTRIBUTORS_NOTE = "特别感谢所有提供建议的小伙伴"

    const val FULL_LIST_URL = UpdateChecker.REPO_URL + "/blob/main/ACKNOWLEDGEMENTS.md"

    val entries: List<AcknowledgementEntry> = listOf(
        AcknowledgementEntry(
            "FPA（fun.fpa）",
            "闭源，版权归其作者 · 优秀的内核架构",
        ),
        AcknowledgementEntry(
            "LSPatch",
            "GPL-3.0 · 免 Root 补丁方案设计",
            "https://github.com/LSPosed/LSPatch",
        ),
        AcknowledgementEntry(
            "NPatch",
            "GPL-3.0 · 补丁加载器设计",
            "https://github.com/7723mod/NPatch",
        ),
        AcknowledgementEntry(
            "AndroidHiddenApiBypass（LSPosed）",
            "Apache-2.0 · 隐藏 API 绕过（org.lsposed.hidden.*）",
            "https://github.com/LSPosed/AndroidHiddenApiBypass",
        ),
        AcknowledgementEntry(
            "XposedBridge（rovo89）",
            "Apache-2.0 · Legacy Xposed API（xp89 封装）",
            "https://github.com/rovo89/XposedBridge",
        ),
        AcknowledgementEntry(
            "libxposed / api",
            "Apache-2.0 · Modern API（xp100~xp102 封装）",
            "https://github.com/LibXposed/api",
        ),
        AcknowledgementEntry(
            "Dobby（jmpews）",
            "Apache-2.0 · 内联 Hook 后端（静态链接进 hook_core.so）",
            "https://github.com/jmpews/Dobby",
        ),
        AcknowledgementEntry(
            "LSPlant（LSPosed）",
            "Apache-2.0 · ART Hook 后端（静态链接进 hook_core.so）",
            "https://github.com/LSPosed/LSPlant",
        ),
        AcknowledgementEntry(
            "Android Open Source Project",
            "Apache-2.0 · 平台类与工具代码片段",
            "https://source.android.com/",
        ),
        AcknowledgementEntry(
            "MTDataFilesProvider（L-JINBIN）",
            "仓库未声明许可证 · MT 文件提供器（bin.mt.file.content.*，已去注释并适配 XPatch 注入）",
            "https://github.com/L-JINBIN/MTDataFilesProvider",
        ),
        AcknowledgementEntry(
            "AndroidLiquidGlass（Kyant0）",
            "Apache-2.0 · 液体玻璃组件（com.kyant.backdrop.catalog.*，部分文件有修改）",
            "https://github.com/Kyant0/AndroidLiquidGlass",
        ),
        AcknowledgementEntry(
            "Miuix（YuKongA）",
            "Apache-2.0 · Compose Multiplatform UI 组件库",
            "https://github.com/YuKongA/miuix",
        ),
        AcknowledgementEntry(
            "Shizuku（RikkaApps）",
            "Apache-2.0 · 免 Root 安装通道",
            "https://github.com/RikkaApps/Shizuku",
        ),
        AcknowledgementEntry(
            "apksig",
            "Apache-2.0 · APK 签名",
            "https://developer.android.com/studio/command-line/apksigner",
        ),
        AcknowledgementEntry(
            "Jetpack Compose（AndroidX）",
            "Apache-2.0 · 基础库与 UI 框架",
            "https://developer.android.com/jetpack",
        ),
    )
}
