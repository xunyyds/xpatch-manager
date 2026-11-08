# Android 17 兼容性说明

Android 17（API 37）在 ART、类加载器、隐藏 API 三块都收紧了限制。
XPatch 在对接过程中逐项定位并处理了下列问题，本文记录现象、根因与最终方案，
方便后续维护与移植到新的 ART 版本。

---

## 1. ART 符号裁剪导致 hook 引擎初始化失败

**现象**

```
UnsatisfiedLinkError: JNI_ERR returned from JNI_OnLoad in ".../lsplant.so"
```

**根因**

Android 17 的 `libart` 删除了 hook 引擎在初始化阶段需要解析的若干内部符号
（入口点切换、静态 trampoline 修复、解释器入口判定等），
初始化流程判定失败后返回 `JNI_ERR`。

**方案**

在兼容层上做最小补丁：让类链接器初始化函数直接返回成功，跳过这组已不存在的符号。
补丁只有 8 个字节，文件大小、导出符号、JNI 协议与内存占用完全不变。

**能力影响**

Java 方法 hook 与 Native 方法 hook 均实测正常 —— 二者通过 ArtMethod 入口替换完成，
不依赖被跳过的那组 ART 内部 hook。相关符号在任何框架上都不复存在，属于平台级降级。

---

## 2. 多进程并发解包同一文件导致崩溃

**现象**

冷启动瞬间随机崩溃，表现二选一：

- `SIGILL / ILL_ILLOPC`，崩溃点是某个静态构造函数的第一条合法指令；
- `SIGSEGV / SEGV_MAPERR`，地址落在 `.text` 之后、`.plt` 起始处。

**根因**

内核把原生库解包到应用缓存目录的固定路径（`cache/android_id/<hash>`），
然后 `System.load`。而目标应用冷启动会同时拉起主进程与多个子进程，
每个进程都执行一次初始化，也就都对这个**固定路径**做截断+写入。
先完成 mmap 的进程会遇到别人写到一半的文件：

- 读到全 0 页 → 执行 `0x00000000`（`udf #0`）→ SIGILL；
- 文件被截短，映射同样变短 → 访问末尾段（`.plt`）→ SEGV_MAPERR。

**方案**

解包落点改为唯一文件名（追加单调时间戳），进程之间不再共享 inode；
加载完成后照旧删除。修复后主进程与子进程并发启动稳定。

---

## 3. `BaseDexClassLoader` 的内存 dex 构造函数被移除

**现象**

```
NoSuchMethodError: No method <init>([Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V
  in Ldalvik/system/BaseDexClassLoader;
```

**根因**

模块加载器原本继承 `BaseDexClassLoader` 并调用其接收 `ByteBuffer[]` 的隐藏构造函数。
Android 17 上该签名不存在（三参版本仍在，但两参版本已删），类校验直接失败。

> 注意：`InMemoryDexClassLoader` 在 Android 17 上被声明为 `final`，无法通过继承获得同等能力。

**方案**

不再继承框架隐藏类，改为组合系统公开 API（见下一条）。

---

## 4. 模块 ClassLoader 必须同时满足四类依赖

模块要真正跑起来，加载器要同时解决：

| 依赖 | 来源 |
| --- | --- |
| 模块自身的类 | 模块 APK 的 `classes*.dex` |
| Xposed API 垫片（`de.robv.android.xposed.*`） | wrapper dex |
| 模块 APK 的资源（`META-INF/services` 等） | 模块 APK |
| 模块自带的原生库（如 DexKit 的 `.so`） | 模块 APK 的 `lib/<abi>/` |

缺任何一条的表现分别是：类找不到、`NoClassDefFoundError`、
Kotlin 协程抛 `Main dispatcher is missing`、`UnsatisfiedLinkError: couldn't find libXXX.so`。

**方案**

按依赖方向串联三层加载器：

```
PathClassLoader(模块APK, 模块APK!/lib/arm64-v8a/)      ← 模块类 + APK 资源 + 原生库
   └─ InMemoryDexClassLoader(wrapper dex 列表)        ← Xposed API 垫片
        └─ 框架 ClassLoader                           ← 内核自身
```

要点：

- 模块类必须由 `PathClassLoader(APK)` 定义，这样 `getResources` 与 `System.loadLibrary`
  才会走 APK（否则协程与原生库都会失败）；
- wrapper dex 只放垫片类，避免抢先把模块类定义在没有资源的加载器上；
- 原生库搜索路径显式传 `模块APK!/lib/<abi>/`。

---

## 5. 隐藏 API 豁免失效

**现象**

```
NoSuchMethodException: dalvik.system.VMRuntime.setHiddenApiExemptions
```

原方案依赖隐藏 API 豁免后反射 `DexFile(ByteBuffer[], ClassLoader, Element[])`
并改写 `pathList.dexElements` 来追加 wrapper dex。Android 17 上该豁免入口已不可用，
整条注入路径被封。

**方案**

彻底不再使用隐藏 API：wrapper dex 在**构造加载器时**直接作为参数传入（见上一条），
模块 dex 由公开的 `PathClassLoader` 承担。改造后与新平台的隐藏 API 策略完全解耦。

---

## 6. 原生重定向层在 Android 17 上无法初始化

**现象**

加载原生重定向库（负责把对补丁包的读取重定向到原始 APK）时，
进程在 `dlopen` 阶段挂死或崩溃，表现为启动超时被杀或非法指令。

**根因**

该库基于 PLT/GOT 重定向实现，其 ELF 解析与重定位假设在 Android 17 的链接器行为下不再成立。

**当前策略**

引导流程跳过该库的加载，框架以 hook 引擎完成 Java/Native hook，功能主体可用；
io 重定向与原生校验绕过的替代实现列入路线图（见 `docs/architecture.md`）。

---

## 7. 引导桩配置键不匹配 → 闪退且无日志

**现象**

打完包的应用点开就闪退，管理器日志页一片空白，`Android/data/<包名>/.logs/` 下连
`_framework` 目录都没有生成。`adb logcat` 里能看到类似
`ClassNotFoundException: <应用自己的 Application 类>`，随后进程被杀。

**根因**

注入后的清单把 `android:appComponentFactory` 指向引导桩，原厂工厂的类名写在 APK 的
`assets/xp/config.json` 里。两者的关系是：

| 位置 | 内容 |
| --- | --- |
| `AndroidManifest.xml` → `<application android:appComponentFactory>` | `io.xpatch.stub.AppEntry`（固定值，由打包器改写） |
| `assets/xp/config.json` → `app_component_name` | 原厂 `AppComponentFactory` 的类名（可能为空） |

引导桩启动后先读 `config.json` 拿到原厂工厂，再把自己的
`instantiateApplication / instantiateActivity / ...` 全部转交给它。
历史版本的打包器写的是 `app_component_name`，而更早的引导桩只认 `factory`：
键名对不上 → 读不到原厂工厂 → 走到 `super.instantiateApplication`。
系统默认实现要求 Application 类就在传入的 ClassLoader 里，Tencent 系应用
（壳类是 `QFixApplicationImplProxy` 这类）不满足这个前提，于是在 `makeApplication`
阶段抛 `ClassNotFoundException`。此时内核 ClassLoader 还没建立，框架一条日志都写不出来——
这就是「闪退且无日志」。

**方案**

引导桩按顺序尝试 `app_component_name`、`factory` 两个键，任意一个命中即可，旧产物仍然可用；
配置缺失或指回自身（`io.xpatch.stub.AppEntry`）时**不委派**，避免无限递归，
也不落到 `super` 那条必崩路径。

**用 `bootstrap.log` 定位**

引导阶段的崩溃由引导桩自己落盘，不依赖内核任何类：

```
/storage/emulated/0/Android/data/<包名>/.logs/_framework/bootstrap.log   # 与内核日志同目录
/data/data/<包名>/xpatch-logs/bootstrap.log                              # 外部存储不可写时
```

```powershell
adb shell cat /sdcard/Android/data/<包名>/.logs/_framework/bootstrap.log
```

文件里的 `Stub -> bootstrap failed at <阶段>` 直接指向分支：

| 阶段 | 含义 / 处理 |
| --- | --- |
| `runtime dex missing` 或 `inject runtime into <包名>` | `assets/xp/core.dex` 没注进去或读不出来，与配置键无关，回去查打包产物 |
| `super.instantiateApplication(<类名>)` | 原厂工厂没读到 → 核对 `config.json` 的键名与值 |
| `delegate.instantiateApplication(<类名>)` | 原厂工厂拿到了，但它自己抛异常（原厂类被裁剪 / 混淆） |

排查顺序：

1. 先看 `bootstrap.log` 在不在。在 → 按上表定位，文件里同时记了包名、进程名、
   `sourceDir`、Android 版本、设备型号与完整堆栈；
2. 不在 → 说明崩得比引导桩更早（Zygote fork 或 ClassLoader 建立之前），
   改看 `adb logcat | Select-String XPatch`；
3. 核对配置：`unzip -p app.apk assets/xp/config.json`，`app_component_name` 应等于
   原包清单里的 `android:appComponentFactory`；原包没有该属性时这个键应当为空，
   而不是指向 `io.xpatch.stub.AppEntry`。

---

## 经验

- 判断"指令是否合法"不能只看崩溃点：`SIGILL` + `ESR=0` 往往意味着**执行到的内存与文件不一致**，
  应优先怀疑并发写文件，而不是 CPU 特性；
- 崩溃地址正好落在某个 section 边界外几个字节（例如 `.text` 末尾 +4）时，
  优先检查该段的映射是否被截短；
- 修改 dex 时，ART 的校验约束比想象的严格：`method_ids` 必须有序、`class_data` 必须连续、
  方法代码不能"删除"只能替换；改动前务必用离线探针先验证，再谈安装。
