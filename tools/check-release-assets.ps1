[CmdletBinding()]
param(
    [string]$AssetsDir = '',
    [string]$SourceRoot = '',
    [string]$ManagerAssetsDir = '',
    [string]$DebugAssetsDir = '',
    [string]$NmPath = '',
    [string]$RepoRoot = '',
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ExpectedHookCoreExports = @(
    'JNI_OnLoad',
    'Java_io_xpatch_core_NativeCalls_enable_1io_1redirect',
    'Java_io_xpatch_core_NativeCalls_enable_1io_1redirect_1dobby',
    'Java_io_xpatch_core_NativeCalls_enable_1io_1redirect_1mix',
    'Java_io_xpatch_core_NativeCalls_enable_1io_1redirect_1plt',
    'Java_io_xpatch_core_entry_NativeHookBridge_deopt',
    'Java_io_xpatch_core_entry_NativeHookBridge_disableProfileSaver',
    'Java_io_xpatch_core_entry_NativeHookBridge_hookMethod',
    'Java_io_xpatch_core_entry_NativeHookBridge_nativeInvoke'
)

$RequiredRuntimeDescriptors = @(
    'Lio/xpatch/core/CoreEntry;',
    'Lio/xpatch/core/XpInit;',
    'Lio/xpatch/core/NativeLoader;',
    'Lio/xpatch/core/XpLayout;',
    'Lio/xpatch/core/signbypass/LevelPltSignBypass;',
    'Lio/xpatch/core/signbypass/PMSignBypass;',
    'Lio/xpatch/core/signbypass/SignatureFacade;',
    'Lio/xpatch/core/signbypass/FileLayerHooks;',
    'Lio/xpatch/core/module/ModuleLoader;',
    'Lio/xpatch/core/module/LocalStoreModuleProvider;'
)

$RequiredStubDescriptors = @(
    'Lio/xpatch/stub/AppEntry;',
    'Lio/xpatch/stub/XpProvider;'
)

function Resolve-DefaultPaths {
    if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
        $RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    }
    $RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
    $script:xpatchRoot = Join-Path $RepoRoot 'XPatch'
    if ([string]::IsNullOrWhiteSpace($AssetsDir)) {
        $script:AssetsDir = Join-Path $script:xpatchRoot 'manager\app\src\main\assets\xp'
    }
    if ([string]::IsNullOrWhiteSpace($DebugAssetsDir)) {
        $script:DebugAssetsDir = Join-Path $script:xpatchRoot 'manager\app\src\debug\assets\xp'
    }
    if ([string]::IsNullOrWhiteSpace($SourceRoot)) {
        $script:SourceRoot = Join-Path $script:xpatchRoot 'runtime\java'
    }
    if ([string]::IsNullOrWhiteSpace($ManagerAssetsDir)) {
        $script:ManagerAssetsDir = Join-Path $script:xpatchRoot 'manager\app\src\main\assets\xp'
    }
}

function Find-Nm {
    if (-not [string]::IsNullOrWhiteSpace($NmPath)) {
        if (Test-Path -LiteralPath $NmPath) { return (Resolve-Path -LiteralPath $NmPath).Path }
        throw "指定的 llvm-nm 不存在：$NmPath"
    }
    $sdkCandidates = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, $env:ANDROID_SDK) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    foreach ($sdk in $sdkCandidates) {
        $ndkRoot = Join-Path $sdk 'ndk'
        if (-not (Test-Path -LiteralPath $ndkRoot)) { continue }
        $hits = Get-ChildItem -LiteralPath $ndkRoot -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object {
                $candidate = Join-Path $_.FullName 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-nm.exe'
                if (Test-Path -LiteralPath $candidate) { $candidate }
            }
        foreach ($hit in $hits) { return (Resolve-Path -LiteralPath $hit).Path }
    }
    $names = if ($IsWindows -or $env:OS -eq 'Windows_NT') { @('llvm-nm.exe') } else { @('llvm-nm') }
    foreach ($name in $names) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
    }
    return $null
}

function Get-DexDefinedDescriptors([string]$Path) {
    $data = [System.IO.File]::ReadAllBytes($Path)
    if ($data.Length -lt 0x70) { throw "dex 头长度不足：$Path" }
    if ($data[0] -ne 0x64 -or $data[1] -ne 0x65 -or $data[2] -ne 0x78 -or $data[3] -ne 0x0A) {
        throw "不是合法的 dex（magic 不是 dex\n）：$Path"
    }
    $stringIdsSize = [BitConverter]::ToUInt32($data, 0x38)
    $stringIdsOff = [BitConverter]::ToUInt32($data, 0x3C)
    $typeIdsSize = [BitConverter]::ToUInt32($data, 0x40)
    $typeIdsOff = [BitConverter]::ToUInt32($data, 0x44)
    $classDefsSize = [BitConverter]::ToUInt32($data, 0x60)
    $classDefsOff = [BitConverter]::ToUInt32($data, 0x64)
    if ($classDefsOff + ($classDefsSize * 32) -gt $data.Length) { throw "class_defs 越界：$Path" }
    $result = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $classDefsSize; $i++) {
        $defOff = $classDefsOff + ($i * 32)
        $classIdx = [BitConverter]::ToUInt32($data, $defOff)
        if ($classIdx -ge $typeIdsSize) { continue }
        $typeOff = $typeIdsOff + ($classIdx * 4)
        $descriptorIdx = [BitConverter]::ToUInt32($data, $typeOff)
        if ($descriptorIdx -ge $stringIdsSize) { continue }
        $strOff = [BitConverter]::ToUInt32($data, $stringIdsOff + ($descriptorIdx * 4))
        if ($strOff -ge $data.Length) { continue }
        $uleb = [uint64]0
        $shift = 0
        $pos = $strOff
        while ($pos -lt $data.Length) {
            $byte = $data[$pos]
            $pos++
            $uleb = $uleb -bor ([uint64]($byte -band 0x7F) -shl $shift)
            if (($byte -band 0x80) -eq 0) { break }
            $shift += 7
            if ($shift -gt 28) { break }
        }
        if ($uleb -eq 0 -or $uleb -gt 65535) { continue }
        $chars = New-Object System.Collections.Generic.List[char]
        $guard = 0
        while ($pos -lt $data.Length -and $guard -lt 4096) {
            $b = $data[$pos]
            $pos++
            if ($b -eq 0) { break }
            if ($b -lt 0x80) {
                $chars.Add([char]$b)
            } elseif (($b -band 0xE0) -eq 0xC0) {
                if ($pos -ge $data.Length) { break }
                $b2 = $data[$pos]
                $pos++
                $chars.Add([char]((($b -band 0x1F) -shl 6) -bor ($b2 -band 0x3F)))
            } elseif (($b -band 0xF0) -eq 0xE0) {
                if ($pos + 1 -ge $data.Length) { break }
                $b2 = $data[$pos]
                $b3 = $data[$pos + 1]
                $pos += 2
                $chars.Add([char]((($b -band 0x0F) -shl 12) -bor (($b2 -band 0x3F) -shl 6) -bor ($b3 -band 0x3F)))
            } else {
                $pos += 3
                $chars.Add([char]0xFFFD)
            }
            $guard++
        }
        $result.Add((-join $chars))
    }
    return $result
}

function Get-ExpectedDescriptors([string]$Root) {
    if (-not (Test-Path -LiteralPath $Root)) { return @() }
    $pattern = [regex]'^\s*package\s+([\w.]+)\s*;'
    $stubRoot = Join-Path $Root 'stubs'
    $list = New-Object System.Collections.Generic.List[string]
    foreach ($file in (Get-ChildItem -LiteralPath $Root -Recurse -Filter '*.java' -File)) {
        if ($stubRoot.Length -gt 0 -and $file.FullName.StartsWith($stubRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            continue
        }
        $text = [System.IO.File]::ReadAllText($file.FullName)
        $m = $pattern.Match($text)
        $pkg = if ($m.Success) { $m.Groups[1].Value } else { '' }
        $simple = [System.IO.Path]::GetFileNameWithoutExtension($file.Name)
        $desc = if ($pkg.Length -eq 0) { "L$simple;" } else { 'L' + $pkg.Replace('.', '/') + "/$simple;" }
        $list.Add($desc)
    }
    return ($list | Sort-Object -Unique)
}

function Get-DexSummary([string]$Path) {
    $data = [System.IO.File]::ReadAllBytes($Path)
    return [pscustomobject]@{
        Path = $Path
        Size = $data.Length
        Sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
        ClassDefs = [BitConverter]::ToUInt32($data, 0x60)
        Descriptors = Get-DexDefinedDescriptors $Path
    }
}

$problems = New-Object System.Collections.Generic.List[string]
$notes = New-Object System.Collections.Generic.List[string]
$report = [ordered]@{}

Resolve-DefaultPaths

if (-not (Test-Path -LiteralPath $script:AssetsDir)) {
    throw "资产目录不存在：$($script:AssetsDir)"
}

$nm = Find-Nm
if ($null -eq $nm) {
    $problems.Add('找不到 llvm-nm（设置 ANDROID_SDK_ROOT 或用 -NmPath 指定），无法校验 .so 导出')
} else {
    $notes.Add("llvm-nm: $nm")
}

$soExpect = [ordered]@{
    'hook_core.so' = 'fpa-core\build\libhook_core.so'
    'hook_core_x86_64.so' = 'fpa-core\build-x86\libhook_core.so'
}

foreach ($name in $soExpect.Keys) {
    $assetPath = Join-Path $script:AssetsDir $name
    if (-not (Test-Path -LiteralPath $assetPath)) {
        $problems.Add("缺少资产 $name")
        continue
    }
    $currentHash = (Get-FileHash -LiteralPath $assetPath -Algorithm SHA256).Hash
    $report[$name] = [ordered]@{
        Path = $assetPath
        Size = (Get-Item -LiteralPath $assetPath).Length
        Sha256 = $currentHash
    }
    if ($null -ne $nm) {
        $lines = & $nm '-D' '--defined-only' $assetPath
        if ($LASTEXITCODE -ne 0) {
            $problems.Add("llvm-nm 读取 $name 失败（exit=$LASTEXITCODE）")
        } else {
            $exports = @()
            foreach ($line in $lines) {
                if ([string]::IsNullOrWhiteSpace($line)) { continue }
                $parts = ($line.Trim() -split '\s+')
                if ($parts.Count -ge 2) { $exports += $parts[$parts.Count - 1] }
            }
            $exports = @($exports | Sort-Object -Unique)
            $report[$name].Exports = $exports
            $missing = @($ExpectedHookCoreExports | Where-Object { $exports -notcontains $_ })
            $extra = @($exports | Where-Object { $ExpectedHookCoreExports -notcontains $_ })
            if ($missing.Count -gt 0) {
                $problems.Add("$name 缺少导出：" + ($missing -join ', '))
            }
            if ($extra.Count -gt 0) {
                $problems.Add("$name 存在非白名单导出：" + ($extra -join ', '))
            }
            if ($exports.Count -ne $ExpectedHookCoreExports.Count) {
                $problems.Add("$name 导出数量 $($exports.Count) ≠ 期望 $($ExpectedHookCoreExports.Count)")
            }
            if ($exports -notcontains 'Java_io_xpatch_core_NativeCalls_enable_1io_1redirect_1plt') {
                $problems.Add("$name 缺少 enable_io_redirect_plt（PLT 档在该产物上不可用）")
            }
        }
    }
    $sourceName = $soExpect[$name]
    $sourcePath = Join-Path (Join-Path $script:xpatchRoot '..') $sourceName
    if (Test-Path -LiteralPath $sourcePath) {
        $sourceHash = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash
        $report[$name].Source = $sourcePath
        $report[$name].SourceSha256 = $sourceHash
        if ($sourceHash -ne $currentHash) {
            $problems.Add("$name 与 $sourceName 不一致（$currentHash ≠ $sourceHash），资产不是最近一次原生构建的产物")
        }
    } else {
        $problems.Add("找不到原生构建产物 $sourceName，无法证明 $name 来源")
    }
    if (Test-Path -LiteralPath $script:DebugAssetsDir) {
        $debugSame = Join-Path $script:DebugAssetsDir $name
        if (Test-Path -LiteralPath $debugSame) {
            $debugHash = (Get-FileHash -LiteralPath $debugSame -Algorithm SHA256).Hash
            if ($debugHash -ne $currentHash) {
                $notes.Add("debug 变体存在不同的 $name（$debugHash），release 使用 main 资产，未受影响")
            }
        }
    }
}

$runtimeDex = Join-Path $script:AssetsDir 'runtime.dex'
if (-not (Test-Path -LiteralPath $runtimeDex)) {
    $problems.Add('缺少资产 runtime.dex')
} else {
    $runtimeSummary = Get-DexSummary $runtimeDex
    $expected = Get-ExpectedDescriptors $script:SourceRoot
    $have = @($runtimeSummary.Descriptors)
    $missingClasses = @($expected | Where-Object { $have -notcontains $_ })
    $report['runtime.dex'] = [ordered]@{
        Path = $runtimeDex
        Size = $runtimeSummary.Size
        Sha256 = $runtimeSummary.Sha256
        ClassDefs = $runtimeSummary.ClassDefs
        SourceClasses = $expected.Count
    }
    if ($missingClasses.Count -gt 0) {
        $problems.Add('runtime.dex 缺少当前源码类：' + ($missingClasses -join ', '))
    }
    foreach ($desc in $RequiredRuntimeDescriptors) {
        if ($have -notcontains $desc) {
            $problems.Add("runtime.dex 缺少必需类 $desc")
        }
    }
}

$stubDex = Join-Path $script:AssetsDir 'stub.dex'
if (-not (Test-Path -LiteralPath $stubDex)) {
    $problems.Add('缺少资产 stub.dex')
} else {
    $stubSummary = Get-DexSummary $stubDex
    $report['stub.dex'] = [ordered]@{
        Path = $stubDex
        Size = $stubSummary.Size
        Sha256 = $stubSummary.Sha256
        ClassDefs = $stubSummary.ClassDefs
    }
    foreach ($desc in $RequiredStubDescriptors) {
        if (@($stubSummary.Descriptors) -notcontains $desc) {
            $problems.Add("stub.dex 缺少引导桩 $desc")
        }
    }
}

$hookCoreDex = Join-Path $script:AssetsDir 'hook_core.dex'
if (-not (Test-Path -LiteralPath $hookCoreDex)) {
    $problems.Add('缺少资产 hook_core.dex')
} else {
    $report['hook_core.dex'] = [ordered]@{
        Path = $hookCoreDex
        Size = (Get-Item -LiteralPath $hookCoreDex).Length
        Sha256 = (Get-FileHash -LiteralPath $hookCoreDex -Algorithm SHA256).Hash
    }
}

$result = [ordered]@{
    Ok = ($problems.Count -eq 0)
    AssetsDir = $script:AssetsDir
    SourceRoot = $script:SourceRoot
    Details = $report
    Notes = $notes
    Problems = $problems
}

if (-not $Quiet) {
    Write-Host "资产目录：$($script:AssetsDir)"
    foreach ($key in $report.Keys) {
        $item = $report[$key]
        if ($item.Contains('Exports')) {
            Write-Host ("  {0,-22} {1,9:N0} B  {2}  导出={3}" -f $key, $item.Size, $item.Sha256.Substring(0, 12), $item.Exports.Count)
        } else {
            Write-Host ("  {0,-22} {1,9:N0} B  {2}" -f $key, $item.Size, $item.Sha256.Substring(0, 12))
        }
    }
    foreach ($note in $notes) { Write-Host "  备注：$note" }
}

if ($problems.Count -gt 0) {
    foreach ($p in $problems) { Write-Host "  失败：$p" -ForegroundColor Red }
    Write-Host "check-release-assets: FAILED（$($problems.Count) 项）" -ForegroundColor Red
    exit 1
}

Write-Host 'check-release-assets: OK' -ForegroundColor Green
exit 0
