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

运行时把原生库解包到应用缓存目录的固定路径（`cache/android_id/<hash>`），
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
        └─ 框架 ClassLoader                           ← 运行时自身
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

## 经验

- 判断"指令是否合法"不能只看崩溃点：`SIGILL` + `ESR=0` 往往意味着**执行到的内存与文件不一致**，
  应优先怀疑并发写文件，而不是 CPU 特性；
- 崩溃地址正好落在某个 section 边界外几个字节（例如 `.text` 末尾 +4）时，
  优先检查该段的映射是否被截短；
- 修改 dex 时，ART 的校验约束比想象的严格：`method_ids` 必须有序、`class_data` 必须连续、
  方法代码不能"删除"只能替换；改动前务必用离线探针先验证，再谈安装。
