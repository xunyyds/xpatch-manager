# XPatch

<h3 align="center">免 Root 的 Android 模組載入框架</h3>

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

XPatch 讓應用在**不 Root、不刷機、不安裝 Magisk / LSPosed** 的前提下載入
Xposed 風格的模組：將模組入口注入目標應用，並在內核啟動的最早時機接管
類別載入與方法呼叫。

支援 **Android 10 – 17**。

## 特性

| | |
|---|---|
| **免 Root** | 注入發生於重打包後的應用內，不依賴任何提權元件；可選用 Shizuku 作為安裝通道 |
| **原位注入** | 只改寫受影響的 zip 條目，不重建整個 APK，產物大小與原包接近 |
| **覆蓋安裝** | 自動辨識並剝離舊方案的注入層，重新簽名後直接覆蓋安裝 |
| **完整模組能力** | Java / Native 方法 Hook、deopt、模組自帶原生庫與資源 |
| **多程序安全** | 主程序與子程序並行初始化互不干擾 |
| **可驗證** | 內建 ART 離線校驗探針，安裝前即可確認產物可被系統接受 |

## 架構

```
目標應用 → 引導樁 → 內核 ─┬→ Hook 引擎
                            └→ 模組載入器 → 模組 APK
```

內核以記憶體載入方式啟動（不落盤），隨後掛載 Hook 引擎並重建模組類別載入器。

## 相容性

| 項目 | 範圍 |
|---|---|
| Android | 10 – 17 |
| ABI | arm64-v8a / armeabi-v7a / x86_64 / x86 |
| 模組 API | Legacy Xposed API · Modern API (libxposed) |
| 安裝通道 | 一般安裝 · Shizuku |

## 快速開始

1. 安裝管理器（Manager）APK；
2. 選擇目標應用，視需要設定模組作用域（Scope）；
3. 注入並安裝 —— 曾被其它方案注入過的應用會自動剝離舊層後重新簽名覆蓋。

## 建置

```powershell
./runtime/build-java.ps1

python ./tools/repack.py app-base.apk build/classes.dex
./tools/sign.ps1 -In build/app-repacked.apk -Out build/app-signed.apk `
    -Keystore ./release.p12 -Alias release
```

需要 Android SDK、JDK 17+ 與 Gradle，詳見 [`build.md`](build.md)。

## 致謝

本專案內核基於 **FPA**（`fun.fpa`）；同時受益於 LSPatch、NPatch、LSPosed、Dobby、
LSPlant、XposedBridge、libxposed、AndroidLiquidGlass、Miuix、Shizuku 等開源專案。

完整清單、授權與修改說明見 [`ACKNOWLEDGEMENTS.md`](../ACKNOWLEDGEMENTS.md)。

## 授權

- 本專案自身程式碼以 **MIT** 釋出，全文見 [`LICENSE`](../LICENSE)；
- 第三方元件之版權與授權歸其作者，**不在本專案 MIT 授權範圍內**；
- 本專案為個人技術研究，與 Google、LSPosed、LSPatch、NPatch、FPA 官方均無隸屬關係；
- 若您是權利人，認為本專案包含未經授權的內容，請來信 **3069670151@qq.com**，
  我們將於核實後盡快處理（下架 / 補充署名 / 替換實作）。
