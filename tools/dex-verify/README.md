# AR T 离线校验探针

在把 `classes.dex` 装到设备之前，先在真机上用系统 ART 真实加载一次，
把所有关键类强制加载并初始化。这样能在几十秒内发现 dex 结构/校验问题，
避免"装完才炸、还得反复重装 400MB 应用"。

## 原理

用 `app_process` 起一个普通 ART 进程，通过反射调用
`dalvik.system.InMemoryDexClassLoader` 加载待测 dex，
再对运行时框架的关键类依次执行 `Class.forName(name, true, loader)`：

- 类校验（verifier）会真实执行，dex 结构错误、寄存器/参数数量不一致都会在这里暴露；
- 不会触碰目标应用，也不会改动设备。

## 用法

```powershell
# 1) 编译探针（只需一次）
javac -d out DexVerify.java
d8 --min-api 30 --output out out/DexVerify.class      # 产物 out/classes.dex 即探针

# 2) 推到设备
adb push out/classes.dex /data/local/tmp/verify.dex
adb push build/classes.dex /data/local/tmp/xpatch.dex

# 3) 执行
adb shell "CLASSPATH=/data/local/tmp/verify.dex app_process /data/local/tmp DexVerify /data/local/tmp/xpatch.dex"
```

期望输出：

```
verified+init: io.xpatch.core.CoreEntry
verified+init: io.xpatch.hook.XpHelper
...
ALL OK
```

出现 `VerifyError` / `Failure to verify dex file` 时，日志里会给出精确原因，
例如 `Out-of-order method_ids`、`bad field index`、`invalid argument count`。

> 提示：修改 dex 时最常见的三个雷区是
> ① `method_ids` 必须按 (class, name, proto) 有序；② `class_data` 条目必须连续且不能缩短；
> ③ 方法代码只能替换不能删除，且 `outs_size` 必须不小于方法内的最大调用参数数。
