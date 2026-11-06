# 内核源码

`java/` 是内核框架的主体源码（Java，javac + d8 构建）；`smali/` 保留两类无法以
public android.jar 编译的血统类，由 `build-java.ps1` 合并进同一 dex。

| 目录 | 职责 |
| --- | --- |
| `java/` | 主体：初始化编排、类加载器、签名绕过、模块接口、隐藏 API 绕过 |
| `java/stubs/` | 编译期 stub（sun.misc.Unsafe、sun.net.www.*、android.app.LoadedApk），不进 dex，运行时解析到 boot 真类 |
| `io/xpatch/core/` | 初始化编排、类加载器、签名绕过调度、模块接口 |
| `io/xpatch/core/module/` | 模块发现、加载器构建、wrapper 加载计划、API 级别探测 |
| `io/xpatch/core/entry/` | 与 native hook 引擎桥接的入口类 |
| `io/xpatch/hook/` | 环境信息、hook 桥、辅助工具 |
| `io/xpatch/core/reflect/` | 模块反射封装（XField/XClass/XMethod） |
| `io/xpatch/core/CoreEntry` | **引导桥**：由引导 dex 以字符串反射调用，类名固定不可改 |
| `smali/io/xpatch/bridge/` | XResourcesSuperClass / XTypedArraySuperClass / FUtils：依赖 android.jar 隐藏签名的血统类 |

## 构建

```powershell
./build-java.ps1   # 需要 APKTOOL_JAR / ANDROID_JAR / ANDROID_BUILD_TOOLS
```

产物：`../build/classes.dex`（与 `java/` + `smali/` 源码一一对应，84 个类）。

## 已针对 Android 17 调整的位置

| 文件 | 调整 |
| --- | --- |
| `io/xpatch/hook/XpHelper` | 原生库解包路径加入时间戳，避免多进程竞争 |
| `io/xpatch/core/XpInit` | 引导流程避开在 A17 无法初始化的原生库 |
| `io/xpatch/core/module/ModuleLoader` | 模块加载器改为 `PathClassLoader` + `InMemoryDexClassLoader` 三层结构；wrapper dex 构造时直接传入；显式设置原生库搜索路径 |
| `io/xpatch/core/XpInit` | 进程内注入 `setHiddenApiExemptions`（经 native 引擎直调 libart 实现） |

## 命名空间

内核统一使用 `io.xpatch.*`。引导桥 `io.xpatch.core.CoreEntry` 的全限定名被引导 dex
以字符串写死，改名需要同步修改引导 dex 与 APK 内资源布局。
