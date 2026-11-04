# 对齐并签名 APK
param(
    [Parameter(Mandatory = $true)][string]$In,
    [Parameter(Mandatory = $true)][string]$Out,
    [Parameter(Mandatory = $true)][string]$Keystore,
    [string]$Alias = 'release',
    [string]$StorePass = '12345678',
    [string]$KeyPass = '',
    [int]$MinSdk = 28
)

$ErrorActionPreference = 'Stop'

if (-not $env:ANDROID_BUILD_TOOLS) {
    throw '请先设置环境变量 ANDROID_BUILD_TOOLS（build-tools 目录）'
}

$zipalign = Join-Path $env:ANDROID_BUILD_TOOLS 'zipalign.exe'
$apksigner = Join-Path $env:ANDROID_BUILD_TOOLS 'apksigner.bat'
foreach ($tool in @($zipalign, $apksigner)) {
    if (-not (Test-Path $tool)) { throw "找不到工具: $tool" }
}
if (-not $KeyPass) { $KeyPass = $StorePass }

$aligned = [System.IO.Path]::ChangeExtension($Out, '.aligned.apk')
if (Test-Path $aligned) { Remove-Item $aligned -Force }
if (Test-Path $Out) { Remove-Item $Out -Force }

& $zipalign -p -f 4 $In $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败 (exit $LASTEXITCODE)" }

& $apksigner sign `
    --ks $Keystore `
    --ks-pass "pass:$StorePass" `
    --key-pass "pass:$KeyPass" `
    --ks-key-alias $Alias `
    --min-sdk-version $MinSdk `
    --v1-signing-enabled false `
    --v2-signing-enabled true `
    --v3-signing-enabled true `
    --out $Out $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner 签名失败 (exit $LASTEXITCODE)" }

& $apksigner verify --min-sdk-version $MinSdk --print-certs $Out | Select-Object -First 3

Remove-Item $aligned -Force -ErrorAction SilentlyContinue
Write-Host "完成: $Out"
