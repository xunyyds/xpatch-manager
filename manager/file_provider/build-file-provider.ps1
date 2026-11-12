param(
    [string]$Output = (Join-Path $PSScriptRoot '..\app\src\main\assets\xp\file_provider.dex')
)

$ErrorActionPreference = 'Stop'

foreach ($name in 'ANDROID_JAR', 'ANDROID_BUILD_TOOLS') {
    if (-not (Get-Item "env:$name" -ErrorAction SilentlyContinue).Value) {
        throw "请先设置环境变量 $name"
    }
}

$java = Get-Command javac -ErrorAction SilentlyContinue
if (-not $java) {
    throw '找不到 javac，请把 JDK 的 bin 加进 PATH'
}

$outDir = Join-Path $PSScriptRoot 'out'
$classes = Join-Path $outDir 'classes'
$dexDir = Join-Path $outDir 'dex'
if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
New-Item -ItemType Directory -Force $classes, $dexDir | Out-Null

$srcs = Get-ChildItem (Join-Path $PSScriptRoot 'bin') -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName
& javac -encoding UTF-8 -nowarn -source 8 -target 8 -bootclasspath $env:ANDROID_JAR -d $classes $srcs
if ($LASTEXITCODE -ne 0) { throw '文件提供器编译失败' }

$classFiles = Get-ChildItem $classes -Recurse -Filter '*.class' | Select-Object -ExpandProperty FullName
& (Join-Path $env:ANDROID_BUILD_TOOLS 'd8.bat') --release --min-api 29 `
    --lib $env:ANDROID_JAR --output $dexDir $classFiles
if ($LASTEXITCODE -ne 0) { throw '文件提供器 d8 失败' }

$dex = Join-Path $dexDir 'classes.dex'
Copy-Item $dex $Output -Force
Write-Host "构建完成: $Output ($((Get-Item $Output).Length) 字节)"
