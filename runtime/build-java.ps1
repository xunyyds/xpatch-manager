param(
    [string]$SmaliRoot = (Join-Path $PSScriptRoot 'smali')
)
$ErrorActionPreference = 'Stop'

# 本文件必须以 UTF-8「带 BOM」保存，改动时不要去掉 BOM：
# powershell.exe 5.1 在文件无 BOM 时按系统 ANSI(GBK) 解码，中文注释的多字节序列会吞掉行尾换行，
# 让脚本在解析阶段就报 "Unexpected token"（实测：去掉 BOM 后 5.1 在本文件上 2 处语法错误）。
# 与它相对的是下面为 d8 生成的 @argfile：那份必须「无 BOM」，d8 不接受 BOM。

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

$classes = @(Get-ChildItem "$work\maincls" -Recurse -Filter '*.class' |
    Sort-Object FullName |
    Select-Object -ExpandProperty FullName)
if ($classes.Count -eq 0) { throw "javac 没有产出任何 .class（$work\maincls 为空）" }
# cmd.exe 的单条命令行上限是 8,191 字符（不是 32K）：99 个类、argfile 9,342 B 触发的
# "The command line is too long." 就是这条限制，所以类列表必须走 @argfile。
# argfile 语法是「一行一个参数」，不支持引号（路径里有空格也不用加，加了反而报 Invalid char）。
$classList = Join-Path $work 'main-classes.txt'
# d8 不接受 UTF-8 BOM（实测 InvalidPathException: Illegal char <:> at index 2，exit=1），
# 而 Windows PowerShell 5.1 的 `Set-Content -Encoding UTF8` 会写 BOM，所以这里显式写无 BOM 的 UTF-8。
[System.IO.File]::WriteAllLines($classList, [string[]]$classes, (New-Object System.Text.UTF8Encoding($false)))
$classListBytes = [System.IO.File]::ReadAllBytes($classList)
$classListHasBom = ($classListBytes.Length -ge 3) -and
    ($classListBytes[0] -eq 0xEF) -and
    ($classListBytes[1] -eq 0xBB) -and
    ($classListBytes[2] -eq 0xBF)
if ($classListHasBom) { throw "@argfile 带了 UTF-8 BOM，d8 会解析失败：$classList" }
& $d8 --release --min-api 29 --output "$work\main-dex" "@$classList"
if ($LASTEXITCODE -ne 0) { throw "d8 主 dex 失败" }

$manifestXml = Join-Path $work 'AndroidManifest.xml'
$manifestText = @'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="io.xpatch.runtime">
    <application android:hasCode="true" />
</manifest>
'@
# 也写无 BOM 的 UTF-8：aapt2 对 BOM 宽容（实测 exit=0），但脚本不该依赖解释器的默认编码，
# powershell.exe(5.1) 与 pwsh 必须产出同样的模板清单。
[System.IO.File]::WriteAllText($manifestXml, $manifestText, (New-Object System.Text.UTF8Encoding($false)))
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
