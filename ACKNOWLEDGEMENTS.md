# 致谢与第三方组件

XPatch 建立在许多优秀项目之上。本文件用于说明来源、许可以及本项目的修改，
也是处理署名与授权问题的第一入口。

> 若您是下列任一项目的权利人，认为本项目存在未获授权或署名不当之处，
> 请邮件联系 **3069670151@qq.com**，我们会尽快核实并处理。

## 一、内核

| 项目 | 许可 | 用途 | 本项目修改 |
|---|---|---|---|
| **FPA**（`fun.fpa`） | 闭源，版权归其作者 | 内核架构与实现来源 | 命名空间、配置层、多进程初始化适配 |
| [LSPatch](https://github.com/LSPosed/LSPatch) | GPL-3.0 | 免 Root 补丁方案设计 | 签名兼容与加载流程 |
| [NPatch](https://github.com/7723mod/NPatch) | GPL-3.0 | 补丁加载器设计 | 加载计划与 API 分级 |

## 二、运行时组件

| 项目 | 许可 | 用途 |
|---|---|---|
| [LSPosed / AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) | Apache-2.0 | 隐藏 API 绕过（`org.lsposed.hidden.*`） |
| [rovo89 / XposedBridge](https://github.com/rovo89/XposedBridge) | Apache-2.0 | Legacy Xposed API（`xp89` 封装）<br/>*其内部含 Apache Commons Lang，同为 Apache-2.0* |
| [libxposed / api](https://github.com/LibXposed/api) | Apache-2.0 | Modern API（`xp100`~`xp102` 封装） |
| [jmpews / Dobby](https://github.com/jmpews/Dobby) | Apache-2.0 | 内联 Hook 后端（静态链接进 `hook_core.so`） |
| [LSPosed / LSPlant](https://github.com/LSPosed/LSPlant) | Apache-2.0 | ART Hook 后端（静态链接进 `hook_core.so`） |
| [Android Open Source Project](https://source.android.com/) | Apache-2.0 | 平台类与工具代码片段 |
| [L-JINBIN / MTDataFilesProvider](https://github.com/L-JINBIN/MTDataFilesProvider) | 仓库未声明许可证 | MT 文件提供器（`bin.mt.file.content.*`，已去注释并适配 XPatch 注入） |

## 三、管理器与 UI

| 项目 | 许可 | 用途 |
|---|---|---|
| [Kyant0 / AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) | Apache-2.0 | 液体玻璃组件（`com.kyant.backdrop.catalog.*`，部分文件有修改） |
| [YuKongA / Miuix](https://github.com/YuKongA/miuix) | Apache-2.0 | Compose Multiplatform UI 组件库 |
| [RikkaApps / Shizuku](https://github.com/RikkaApps/Shizuku) | Apache-2.0 | 免 Root 安装通道 |
| [apksig](https://developer.android.com/studio/command-line/apksigner) | Apache-2.0 | APK 签名 |
| [AndroidX / Jetpack Compose](https://developer.android.com/jetpack) | Apache-2.0 | 基础库与 UI 框架 |

## 四、兼容签名材料

`manager/app/src/main/assets/xp/sign/` 下的两组签名材料，用于对**已被其它方案补丁过**的应用做覆盖安装
（系统只接受同一签名的升级包）。

| 目录 | 证书主体 | 出处 | 说明 |
|---|---|---|---|
| `sign/fpa` | `C=GL, O=FunProject, CN=FPA` | 取自 FPA 公开发行包的 `assets/fpa_app.jks` | 上游本就将该 keystore 随 APK 分发 |
| `sign/np` | `CN=xiawanli, …` | 取自同一发行包内的 `assets/lspatch_app.jks` | 同上，为 NPatch/LSPatch 系兼容身份 |

这两份材料在各自上游的发行包中即为公开内容，本项目仅沿用其签名身份以保持覆盖安装能力，
不包含任何未公开信息。若权利人希望停止此用法，请来信告知，我们会改为由用户自行导入密钥。

## 五、说明

1. 上表按「主要用途」归类，未逐一列出传递性依赖；完整依赖树见 `gradle dependencies`。
2. 标为「有修改」的组件，其修改内容随源码一并提供。
3. 本项目自身新增代码以 MIT 发布；第三方组件的版权与许可条款优先于本项目声明。
4. 若某一项标注有误或遗漏，请通过上方邮箱告知，我们会及时更正。
