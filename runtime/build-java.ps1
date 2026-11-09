param(
    [string]$SmaliRoot = (Join-Path $PSScriptRoot 'smali')
)
$ErrorActionPreference = 'Stop'

foreach ($name in 'APKTOOL_JAR', 'ANDROID_JAR', 'ANDROID_BUILD_TOOLS') {
    if (-not (Get-Item "env:$name" -ErrorAction SilentlyContinue).Value) {
        throw "请先设置环境变量 $name"
    }
}

$repo = Split-Path -Parent $PSScriptRoot
$javaSrc = Join-Path $PSScriptRoot 'java'
$stubSrc = Join-Path $javaSrc 'stubs'
$outDir = Join-Path $repo 'build'
$work = Join-Path $outDir 'work-java'
$aapt2 = Join-Path $env:ANDROID_BUILD_TOOLS 'aapt2.exe'
$d8 = Join-Path $env:ANDROID_BUILD_TOOLS 'd8.bat'

foreach ($p in @($env:APKTOOL_JAR, $env:ANDROID_JAR, $aapt2, $d8)) {
    if (-not (Test-Path $p)) { throw "找不到依赖: $p" }
}

New-Item -ItemType Directory -Force $outDir | Out-Null
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Force $work | Out-Null
New-Item -ItemType Directory -Force "$work\stubcls", "$work\maincls", "$work\main-dex", "$work\merged" | Out-Null

$stubSrcs = Get-ChildItem $stubSrc -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
javac -encoding UTF-8 -nowarn -source 8 -target 8 -bootclasspath $env:ANDROID_JAR -d "$work\stubcls" $stubSrcs
if ($LASTEXITCODE -ne 0) { throw "stub 编译失败" }

$mainSrcs = Get-ChildItem $javaSrc -Recurse -Filter '*.java' |
    Where-Object { $_.FullName -notlike "$stubSrc*" } |
    Select-Object -ExpandProperty FullName
javac -encoding UTF-8 -nowarn -source 8 -target 8 -bootclasspath $env:ANDROID_JAR -classpath "$work\stubcls" -d "$work\maincls" $mainSrcs
if ($LASTEXITCODE -ne 0) { throw "运行时编译失败" }

$classes = Get-ChildItem "$work\maincls" -Recurse -Filter '*.class' | Select-Object -ExpandProperty FullName
# Windows 命令行长度上限约 32K；类文件变多后必须走 @argfile，否则 d8 报 "The command line is too long."
$classList = Join-Path $work 'main-classes.txt'
$classes | Set-Content -Path $classList -Encoding UTF8
& $d8 --release --min-api 29 --output "$work\main-dex" "@$classList"
if ($LASTEXITCODE -ne 0) { throw "d8 主 dex 失败" }

$manifestXml = Join-Path $work 'AndroidManifest.xml'
@'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="io.xpatch.runtime">
    <application android:hasCode="true" />
</manifest>
'@ | Set-Content -Path $manifestXml -Encoding UTF8
$template = Join-Path $work 'template.apk'
& $aapt2 link --manifest $manifestXml -o $template -I $env:ANDROID_JAR
if ($LASTEXITCODE -ne 0) { throw "aapt2 失败 (exit $LASTEXITCODE)" }

$project = Join-Path $work 'bridge-project'
& java -jar $env:APKTOOL_JAR d --no-res -f -o $project $template | Out-Null
if ($LASTEXITCODE -ne 0) { throw "apktool 解码失败" }

if (-not (Test-Path $SmaliRoot)) { throw "桥接 smali 缺失: $SmaliRoot" }
$projectSmali = Join-Path $project 'smali'
if (Test-Path $projectSmali) { Remove-Item $projectSmali -Recurse -Force }
Copy-Item $SmaliRoot $projectSmali -Recurse -Force

$bridgeApk = Join-Path $work 'bridge.apk'
& java -jar $env:APKTOOL_JAR b -o $bridgeApk $project | Out-Null
if ($LASTEXITCODE -ne 0) { throw "apktool 桥接构建失败" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($bridgeApk)
try {
    $entry = $zip.Entries | Where-Object { $_.FullName -eq 'classes.dex' }
    if (-not $entry) { throw '桥接 apk 中没有 classes.dex' }
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, "$work\bridge.dex", $true)
}
finally {
    $zip.Dispose()
}

& $d8 --release --min-api 29 --output "$work\merged" "$work\main-dex\classes.dex" "$work\bridge.dex"
if ($LASTEXITCODE -ne 0) { throw "d8 合并失败" }

$dex = Join-Path $outDir 'classes.dex'
if (Test-Path $dex) { Remove-Item $dex -Force }
Copy-Item "$work\merged\classes.dex" $dex -Force

Write-Host "构建完成: $dex ($((Get-Item $dex).Length) 字节)"
