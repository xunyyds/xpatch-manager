# 构建

## 依赖

- JDK 17 或更高（构建脚本需要 `java`）
- [apktool](https://apktool.org) 3.x
- Android SDK：`build-tools`（提供 `aapt2`、`zipalign`、`apksigner`）与 `platforms` 里的 `android.jar`
- Python 3.10+（重打包脚本）

```powershell
$env:APKTOOL_JAR         = 'C:\tools\apktool_3.0.3.jar'
$env:ANDROID_JAR         = 'C:\android-sdk\platforms\android-37.0\android.jar'
$env:ANDROID_BUILD_TOOLS = 'C:\android-sdk\build-tools\37.0.0'
```

## 1. 构建内核 dex

```powershell
./runtime/build-java.ps1
# 产物：build/classes.dex
```

脚本流程：`javac` 编译 `runtime/java/`（`runtime/java/stubs/` 只作编译期符号，不进 dex）→ `d8` 生成主 dex → `apktool` 组装 `runtime/smali/` 里的桥接类 → `d8` 合并为单一 `classes.dex`。桥接类是依赖 android.jar 隐藏签名、无法用 public SDK 编译的少数血统类。

## 2. 校验 dex

安装前强烈建议先跑一次 ART 校验探针（`tools/dex-verify/`），
它会在真机上用系统 ART 真实加载并校验所有关键类：

```powershell
adb push build/classes.dex /data/local/tmp/xpatch.dex
adb shell "CLASSPATH=/data/local/tmp/verify.dex app_process /data/local/tmp DexVerify /data/local/tmp/xpatch.dex"
# 期望输出：ALL OK
```

## 3. 写回目标 APK

`repack.py` 采用**原位替换**：只替换目标 APK 中目标条目的压缩数据与头部 CRC/长度，
不重建整个 zip。这样能保住原包的存储布局与体积，避免重打包导致包体翻倍。

```powershell
python ./tools/repack.py app-base.apk build/classes.dex --entry classes.dex
# 产物：build/app-base-repacked.apk
```

> 说明：部分应用的 APK 内部存在共享数据区（多个条目指向同一段数据），
> 用通用 zip 库重建会把这段数据复制多份，导致包体膨胀。原位替换可以避免该问题。

## 4. 对齐与签名

```powershell
./tools/sign.ps1 -In build/app-base-repacked.apk -Out build/app-signed.apk `
    -Keystore ./release.p12 -Alias release
```

覆盖安装要求签名与目标应用当前签名一致；首次使用请先在设备上确认签名指纹：

```powershell
adb shell dumpsys package <package> | Select-String signatures
```

## 5. 安装

```powershell
adb install -r build/app-signed.apk
```

部分厂商 ROM 会拦截 `adb install` 的同版本覆盖，此时把 APK 推到 `/sdcard/Download/`
用安装器界面更新即可。
