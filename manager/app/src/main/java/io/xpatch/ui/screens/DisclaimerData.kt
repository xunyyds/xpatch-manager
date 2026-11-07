package io.xpatch.ui.screens

import io.xpatch.BuildConfig

internal data class DisclaimerSection(
    val title: String,
    val paragraphs: List<String>,
    val highlight: Boolean = false,
)

internal object DisclaimerData {

    const val INTRO =
        "在使用 XPatch（以下简称「本软件」）前，请仔细阅读并充分理解本声明的全部内容。" +
            "使用本软件即表示你已阅读、理解并接受以下全部条款；如不同意，请点击退出并停止使用本软件。"

    const val CONTACT = "3069670151@qq.com"

    const val EFFECTIVE = "2026-11-07"

    val sections: List<DisclaimerSection> = listOf(
        DisclaimerSection(
            "软件性质",
            listOf(
                "本软件是面向 Android 平台的免 Root 模块加载框架管理器，提供模块管理、作用域配置、" +
                    "签名管理、安装包重打包、打包历史与日志排查等本地工具功能。",
                "本软件为个人技术研究项目，与 Google、LSPosed、LSPatch、NPatch、FPA 官方均无隶属或授权关系；" +
                    "本软件不提供、不分发任何模块，也不对任何目标应用的内容承担责任。",
            ),
        ),
        DisclaimerSection(
            "使用限制",
            listOf(
                "本软件仅供学习交流、技术研究等非商业用途。严禁将本软件用于任何商业目的，" +
                    "包括但不限于付费分发、捆绑销售、作为商业服务的组成部分或以本软件名义开展经营活动。",
                "严禁将本软件用于违反你所在国家或地区法律法规、目标应用服务条款的用途，" +
                    "包括但不限于破解付费功能、绕过授权校验、传播盗版内容、实施网络攻击或侵犯他人合法权益。",
            ),
        ),
        DisclaimerSection(
            "用户责任",
            listOf(
                "你保证对使用本软件处理的目标应用拥有合法使用权。对目标应用进行重打包、注入或覆盖安装前，" +
                    "请自行评估并遵守其用户协议与授权条款。",
                "重打包会更换安装包签名，可能导致目标应用登录态失效、云同步中断、支付与推送异常等后果。" +
                    "操作前请自行备份重要数据；由此产生的任何数据丢失均由你自行承担。",
            ),
        ),
        DisclaimerSection(
            "数据与隐私",
            listOf(
                "本软件的全部处理均在本地设备完成，不收集、不上传你的任何个人数据。" +
                    "网络访问仅用于模块仓库检索、模块下载与版本更新检查。",
                "运行日志仅保存在本地与目标应用目录中，仅在你主动导出时离开设备。" +
                    "导出的日志包内含环境信息与崩溃记录，分享前请自行确认内容。",
            ),
        ),
        DisclaimerSection(
            "固有技术风险",
            listOf(
                "模块注入依赖对目标应用的离线重打包，可能被其完整性校验、加固方案或风控策略识别，" +
                    "导致应用无法启动或功能受限。",
                "覆盖安装可能因签名不一致、ROM 校验或系统策略而失败；个别情况下目标应用可能无法启动。" +
                    "建议先在可丢弃的环境验证后再正式使用。",
            ),
        ),
        DisclaimerSection(
            "第三方组件与许可",
            listOf(
                "本软件自身代码以 MIT 许可发布；所依赖的内核与组件（FPA、LSPatch、NPatch、LSPlant、Dobby、" +
                    "XposedBridge、libxposed、Shizuku、Miuix 等）版权归各自作者所有，不在 MIT 授权范围内。",
                "兼容签名材料仅用于对已被其他方案处理过的应用做覆盖安装，均取自上游公开发行包。" +
                    "若你是相关权利人并有异议，请通过文末联系方式告知，我们会及时处理。完整清单见仓库 ACKNOWLEDGEMENTS.md。",
            ),
        ),
        DisclaimerSection(
            "免责与责任限制",
            listOf(
                "本软件按「现状」提供，不附带任何明示或默示的担保，" +
                    "包括但不限于适销性、特定用途适用性与不侵权的保证。",
                "在任何情况下，开发者均不对你因使用或无法使用本软件而产生的任何直接、间接、附带、" +
                    "特殊或后果性损失承担责任。你使用本软件的一切风险由你自行承担。",
            ),
            highlight = true,
        ),
        DisclaimerSection(
            "声明变更",
            listOf(
                "本声明可能随版本更新而修订，修订后的内容将在本页面展示。" +
                    "你在版本更新后继续使用本软件，即视为接受修订后的声明。",
            ),
        ),
        DisclaimerSection(
            "法律适用与争议解决",
            listOf(
                "本声明的适用法律为中华人民共和国法律。因本声明或本软件产生的争议，双方应先行友好协商；" +
                    "协商不成的，任何一方均可向开发者所在地有管辖权的人民法院提起诉讼。",
            ),
        ),
        DisclaimerSection(
            "联系方式",
            listOf(
                "若你是权利人，认为本软件包含未获授权的内容，或对声明内容有任何疑问，" +
                    "请发送邮件至 " + CONTACT + "，我们会在核实后尽快处理（下架、补充署名或替换实现）。",
            ),
        ),
    )

    fun versionLine(): String = "v" + BuildConfig.VERSION_NAME + " · " + EFFECTIVE + " 生效"
}
