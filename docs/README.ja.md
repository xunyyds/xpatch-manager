# XPatch

<h3 align="center">Root 不要の Android モジュールローディングフレームワーク</h3>

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

XPatch は、**Root 化・ファームウェア書き換え・Magisk / LSPosed のインストールなしで**
Xposed 形式のモジュールをアプリに読み込ませるフレームワークです。
モジュールのエントリを対象 APK に注入し、カーネル起動の最も早い段階で
クラスローディングとメソッド呼び出しを引き受けます。

**Android 10 – 17** に対応。

## 特徴

| | |
|---|---|
| **Root 不要** | 注入は再パッケージ後のアプリ内で完結。インストール経路として Shizuku を任意で利用可能 |
| **インプレース注入** | 影響する zip エントリのみを書き換え、APK 全体を再構築しない |
| **上書きインストール** | 旧フレームワークの注入層を検出・除去し、再署名して上書きインストール |
| **完全なモジュール機能** | Java / Native メソッドフック、deopt、モジュール同梱のネイティブライブラリとリソース |
| **マルチプロセス安全** | メインプロセスと子プロセスの同時初期化に対応 |
| **検証可能** | ART オフライン検証プローブを内蔵し、インストール前に成果物を確認できる |

## アーキテクチャ

```
対象アプリ → ブートストラップスタブ → カーネル ─┬→ フックエンジン
                                                └→ モジュールローダー → モジュール APK
```

カーネルはメモリ上で読み込まれ（ディスクに書き出さない）、その後フックエンジンを
マウントしてモジュールのクラスローダーを再構築します。

## 互換性

| 項目 | 範囲 |
|---|---|
| Android | 10 – 17 |
| ABI | arm64-v8a / armeabi-v7a / x86_64 / x86 |
| モジュール API | Legacy Xposed API · Modern API (libxposed) |
| インストール経路 | 通常インストール · Shizuku |

## クイックスタート

1. マネージャー APK をインストール；
2. 対象アプリを選択し、必要に応じてモジュールスコープを設定；
3. 注入してインストール —— 他のフレームワークで注入済みのアプリは旧層を除去して再署名されます。

## ビルド

```powershell
./runtime/build-java.ps1

python ./tools/repack.py app-base.apk build/classes.dex
./tools/sign.ps1 -In build/app-repacked.apk -Out build/app-signed.apk `
    -Keystore ./release.p12 -Alias release
```

Android SDK、JDK 17+、Gradle が必要です。詳細は [`build.md`](build.md) を参照。

## 謝辞

本プロジェクトのカーネルは **FPA**（`fun.fpa`）を基盤としています。また LSPatch、
NPatch、LSPosed、Dobby、LSPlant、XposedBridge、libxposed、AndroidLiquidGlass、
Miuix、Shizuku などのオープンソースプロジェクトに支えられています。

完全なリスト・ライセンス・改変内容は [`ACKNOWLEDGEMENTS.md`](../ACKNOWLEDGEMENTS.md) を参照。

## ライセンス

- 本プロジェクト固有のコードは **MIT** で公開されています（[`LICENSE`](../LICENSE)）。
- サードパーティ製コンポーネントの著作権・ライセンスは各作者に帰属し、**本プロジェクトの MIT 許諾には含まれません**。
- 本プロジェクトは個人の技術研究であり、Google / LSPosed / LSPatch / NPatch / FPA とは無関係です。
- 権利者の方で、未許諾のコンテンツが含まれているとお考えの場合は
  **3069670151@qq.com** までご連絡ください。確認のうえ速やかに対応します
  （公開停止 / クレジット追加 / 実装の差し替え）。
