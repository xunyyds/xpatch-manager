# XPatch

<h3 align="center">Rootless module loading framework for Android</h3>

<p align="center">
  <img alt="Platform" src="https://img.shields.io/badge/Android-10%20~%2017-3DDC84?logo=android&logoColor=white">
  <img alt="Kernel" src="https://img.shields.io/badge/Kernel-FPA-6C5CE7">
  <img alt="Language" src="https://img.shields.io/badge/Kotlin%20%7C%20Java-11-7F52FF?logo=kotlin&logoColor=white">
  <img alt="License" src="https://img.shields.io/badge/License-MIT-2F81F7">
</p>

<p align="center">
  <a href="../../README.md">简体中文</a> ·
  <a href="README.en.md">English</a> ·
  <a href="README.zh-TW.md">繁體中文</a> ·
  <a href="README.ja.md">日本語</a>
</p>

---

XPatch loads Xposed-style modules into Android apps **without root, without flashing,
without Magisk or LSPosed**. The module entry point is injected into the target APK and
takes over class loading and method dispatch at the earliest stage of kernel startup.

Supports **Android 10 – 17**.

## Features

| | |
|---|---|
| **No root** | Injection happens inside the repackaged app; Shizuku is optional for installation |
| **In-place patching** | Only the affected zip entries are rewritten — output size stays close to the original |
| **Overwrite install** | Detects and strips legacy injection layers, re-signs, and installs over the existing app |
| **Full module support** | Java / native method hooks, deopt, module-bundled native libraries and resources |
| **Multi-process safe** | Concurrent initialisation across the main and child processes |
| **Verifiable** | Built-in offline ART verification probe to validate the output before installing |

## Architecture

```
Target App → Bootstrap Stub → Kernel ─┬→ Hook Engine
                                      └→ Module Loader → Module APK
```

The kernel is loaded in memory (never written to disk), then mounts the hook engine
and rebuilds the module class loader.

## Compatibility

| Item | Range |
|---|---|
| Android | 10 – 17 |
| ABI | arm64-v8a / armeabi-v7a / x86_64 / x86 |
| Module API | Legacy Xposed API · Modern API (libxposed) |
| Install channel | Standard installer · Shizuku |

## Quick start

1. Install the Manager APK.
2. Pick a target app and configure the module scope.
3. Patch and install — apps previously patched by other frameworks are stripped and re-signed automatically.

## Build

```powershell
./runtime/build-java.ps1

python ./tools/repack.py app-base.apk build/classes.dex
./tools/sign.ps1 -In build/app-repacked.apk -Out build/app-signed.apk `
    -Keystore ./release.p12 -Alias release
```

Requires Android SDK, JDK 17+ and Gradle. See [`build.md`](build.md) for details.

## Acknowledgements

The kernel is based on **FPA** (`fun.fpa`). XPatch also benefits from LSPatch, NPatch,
LSPosed, Dobby, LSPlant, XposedBridge, libxposed, AndroidLiquidGlass, Miuix and Shizuku.

The full list, licenses and modification notes are in
[`ACKNOWLEDGEMENTS.md`](../ACKNOWLEDGEMENTS.md).

## License

- Project-specific code is released under **MIT** — see [`LICENSE`](../LICENSE).
- Third-party components remain under their own licenses and are **not** covered by the MIT grant.
- This is a personal research project; it is not affiliated with Google, LSPosed, LSPatch, NPatch or FPA.
- If you are a rights holder and believe this project contains unauthorised material,
  please contact **3069670151@qq.com** and we will take action promptly
  (takedown / attribution / reimplementation).
