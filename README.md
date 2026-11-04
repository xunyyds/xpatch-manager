# XPatch

**免 Root 的 LSP 框架。**

XPatch 让应用在不 Root、不刷机、不装 Magisk / LSPosed 的前提下加载 Xposed 风格模块：把模块的 hook 入口注入目标应用，在应用启动的最早时机接管类加载与方法调用，让模块像在 LSPosed 里一样工作。

适配 **Android 10 到 Android 17**。

## 为什么选 XPatch

- **直接覆盖安装**：从其它框架迁移过来，无需卸载重装、无需清空数据。管理器识别目标应用的当前签名并内置对应兼容密钥，打包后用同一签名重签，安装即覆盖，聊天记录、登录状态、本地数据全部保留。
- **打包极快**：注入采用原位替换，只改写目标 APK 中受影响条目的压缩数据，不重建整个 zip。打包 QQ 9.3.80（约 390 MB）仅需 **5 秒**，产物体积与干净原包基本一致，不会翻倍。
- **免 Root**：注入发生在重打包后的应用内，不依赖任何提权模块；可选接入 Shizuku 作为安装通道。
- **完整模块能力**：Java 方法 hook、Native 方法 hook、deopt、模块自带的原生库（如 DexKit）与资源均可正常工作。
- **多进程安全**：主进程与各子进程并发初始化互不破坏。
- **可验证**：提供 ART 离线校验探针，安装前即可确认产物能被系统接受。

## 工作原理

```
┌──────────────────────────────────────────────┐
│ 目标应用进程                                    │
│                                                │
│  AppComponentFactory ──> 引导入口                │
│        │                                         │
│        ├─ 加载运行时（内存加载，不落盘）          │
│        ├─ 加载 hook 引擎（LSPlant 兼容层）        │
│        └─ 模块加载器                              │
│              ├─ wrapper dex（Xposed API）         │
│              └─ 模块 APK（类 + 资源 + 原生库）    │
└──────────────────────────────────────────────┘
```

## 仓库结构

```
XPatch/
├── runtime/
│   ├── java/             # 运行时框架源码（Java）
│   ├── smali/            # 依赖隐藏签名的桥接类
│   └── build-java.ps1    # 构建 classes.dex
├── manager/
│   ├── stub/             # 引导桩源码（io.xpatch.stub.AppEntry）
│   └── app/              # 管理器 App（Compose）
├── tools/
│   ├── repack.py         # 原位写回目标 APK
│   ├── sign.ps1          # 对齐 + 签名
│   ├── check-namespace.ps1 / verify-assets.ps1 / size-budget.ps1  # 交付门禁
│   └── dex-verify/       # ART 离线校验探针
├── docs/
│   ├── architecture.md   # 架构与启动链路
│   ├── compat-android17.md
│   ├── build.md          # 构建流程
│   ├── module-api.md     # 模块 API 级别说明
│   └── roadmap.md        # 路线图
└── build/                # 产物（不入库）
```

## 快速开始

### 使用管理器

1. 安装管理器 APK；
2. 选择目标应用，按需开启模块作用域；
3. 打包并安装——若目标已被其它框架打过，管理器会剥离旧注入层、用兼容签名重签，直接覆盖安装。

### 从源码构建

```powershell
# 构建运行时
./runtime/build-java.ps1

# 真机校验产物（无需安装，见 tools/dex-verify/README.md）

# 写回目标 APK 并签名
python ./tools/repack.py app-base.apk build/classes.dex
./tools/sign.ps1 -In build/app-repacked.apk -Out build/app-signed.apk `
    -Keystore ./release.p12 -Alias release
```

## 状态

| 模块 | 状态 |
| --- | --- |
| 运行时框架 | 稳定，已在 Android 17 真机验证 |
| hook 引擎 | 稳定 |
| 模块加载器（类 / 资源 / 原生库） | 稳定 |
| 管理器 App（打包 / 作用域 / 签名 / 日志） | 稳定 |

## 贡献

见 `CONTRIBUTING.md`。提交信息使用约定式提交（`feat:` / `fix:` / `docs:` …）。

## 许可

MIT，见 `LICENSE`。
