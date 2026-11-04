param(
    [string]$Assets = (Join-Path (Split-Path -Parent $PSScriptRoot) 'manager/app/src/main/assets/xp')
)

$ErrorActionPreference = 'Stop'

function Get-Text([string]$Path) {
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    return -join ($bytes | ForEach-Object { [char]$_ })
}

$so = Join-Path $Assets 'hook_core.so'
$hookDex = Join-Path $Assets 'hook_core.dex'
$runtime = Join-Path $Assets 'runtime.dex'
$stub = Join-Path $Assets 'stub.dex'

foreach ($f in @($so, $hookDex, $runtime, $stub)) {
    if (-not (Test-Path $f)) { throw "missing asset: $f" }
}

$soText = Get-Text $so
if ($soText -notmatch 'Java_io_xpatch_core_entry_NativeHookBridge_hookMethod') {
    throw 'hook_core.so does not export Java_io_xpatch_core_entry_NativeHookBridge_hookMethod'
}
if ($soText -match 'Java_fpa_core_entry') {
    throw 'hook_core.so still exports Java_fpa_core_entry_*'
}

$hookText = Get-Text $hookDex
if ($hookText -notmatch 'Lio/xpatch/core/entry/NativeHookBridge;') {
    throw 'hook_core.dex does not reference io/xpatch/core/entry/NativeHookBridge'
}
if ($hookText -match 'Lfpa/core/entry/NativeHookBridge;') {
    throw 'hook_core.dex still references fpa/core/entry/NativeHookBridge'
}

$runtimeText = Get-Text $runtime
if ($runtimeText -notmatch 'Lio/xpatch/core/CoreEntry;') {
    throw 'runtime.dex does not define io/xpatch/core/CoreEntry'
}
if ($runtimeText -match 'Lfpa/core/CoreEntry;') {
    throw 'runtime.dex still defines fpa/core/CoreEntry'
}
if ($runtimeText -notmatch 'loadAppInPack') {
    throw 'runtime.dex does not expose loadAppInPack'
}

$stubText = Get-Text $stub
if ($stubText -notmatch 'xp/core.dex') { throw 'stub.dex does not read xp/core.dex' }
if ($stubText -notmatch 'io.xpatch.core.CoreEntry') { throw 'stub.dex does not call io.xpatch.core.CoreEntry' }
if ($stubText -match 'fpa/core.dex') { throw 'stub.dex still reads fpa/core.dex' }
if ($stubText -match 'fpa.core.CoreEntry') { throw 'stub.dex still calls fpa.core.CoreEntry' }

Write-Host 'asset namespace check passed'
