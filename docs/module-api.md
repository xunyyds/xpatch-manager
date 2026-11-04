# 模块开发

XPatch 加载的模块与 Xposed / LSPosed 模块写法一致，支持 Legacy（`IXposedHookLoadPackage`）
与现代 API（`io.github.libxposed.api`）两种入口。

## 最小模块

`AndroidManifest.xml`：

```xml
<application android:label="MyModule">
    <meta-data android:name="xposedmodule" android:value="true" />
    <meta-data android:name="xposeddescription" android:value="示例模块" />
    <meta-data android:name="xposedminversion" android:value="93" />
</application>
```

入口类：

```java
package com.example.module;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Entry implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.example.target".equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log("module loaded in " + lpparam.processName);

        XposedHelpers.findAndHookMethod(
                "com.example.target.MainActivity",
                lpparam.classLoader,
                "onCreate",
                android.os.Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        XposedBridge.log("onCreate hooked");
                    }
                });
    }
}
```

`assets/xposed_init` 中写入口类全名（每行一个）。

## 模块自带原生库

模块 APK 中的 `lib/<abi>/*.so` 可以直接用 `System.loadLibrary("xxx")` 加载，
运行时会把 `模块APK!/lib/<abi>/` 加入模块加载器的原生库搜索路径，无需自行解包。

## 依赖说明

- 模块中可自由使用 Kotlin、协程、DexKit 等依赖；
- 依赖的 `META-INF/services` 资源会从模块 APK 中正常解析；
- 模块内不要打包自己的 Xposed API 实现（`de.robv.android.xposed.*`），这些类由运行时提供。

## 调试建议

```text
1. 先确认模块被加载：日志中应出现 "load module success … apiLevel=…"
2. 再确认入口被调用：模块内自行 XposedBridge.log 打点
3. 若出现 NoClassDefFoundError，检查是否把 Xposed API 打进了模块 APK
4. 若出现 UnsatisfiedLinkError，检查 .so 是否放在 lib/<对应 ABI>/
```
