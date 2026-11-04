param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'

$forbidden = @(
    'fpa/core.dex',
    'fpa/config.json',
    'fpa.core.CoreEntry',
    'fpa.core.entry.NativeHookBridge',
    'fpa.hook.FPAHook',
    'fpa/pkg',
    'xpt/core.dex',
    'xpt/config.json',
    'fun/fpa/wrapper',
    'FPA',
    'sign/fpa'
)

$roots = @(
    'runtime/smali',
    'manager/stub',
    'manager/app/src/main/java',
    'manager/app/src/main/assets/xp'
)

$textExt = @('.smali', '.java', '.kt')
$compatNamingAllowlist = @(
    'manager/app/src/main/java/io/xpatch/data/SignatureStore.kt',
    'manager/app/src/main/java/io/xpatch/patch/ApkPatcher.kt',
    'manager/app/src/main/java/io/xpatch/ui/screens/SignaturesScreen.kt',
    'manager/app/src/main/assets/xp/sign/'
)
$failed = @()

foreach ($rel in $roots) {
    $dir = Join-Path $Root $rel
    if (-not (Test-Path $dir)) { continue }
    $files = Get-ChildItem -Recurse -File $dir
    foreach ($file in $files) {
        if ($file.FullName -like '*_legacy_*') { continue }
        $ext = $file.Extension.ToLowerInvariant()
        if ($textExt -notcontains $ext -and $file.DirectoryName -notlike '*assets*') { continue }
        $relPath = "$rel/$($file.FullName.Substring((Join-Path $Root $rel).Length + 1) -replace '\\', '/')"
        $tokens = if ($compatNamingAllowlist | Where-Object { $relPath.StartsWith($_) }) { $forbidden | Where-Object { $_ -ne 'FPA' } } else { $forbidden }
        $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
        $text = -join ($bytes | ForEach-Object { [char]$_ })
        foreach ($token in $tokens) {
            if ($text.Contains($token)) {
                $failed += "$($file.FullName) -> $token"
            }
        }
    }
}

if ($failed.Count -gt 0) {
    $failed | ForEach-Object { Write-Host "FAIL $_" }
    throw "namespace check failed: $($failed.Count)"
}

foreach ($rel in $roots) {
    $dir = Join-Path $Root $rel
    if (-not (Test-Path $dir)) { continue }
    $prefix = "$rel/"
    $badPaths = Get-ChildItem -Recurse -File $dir | Where-Object {
        $full = $prefix + ($_.FullName.Substring($dir.Length + 1) -replace '\\', '/')
        $full -match 'fpa' -and -not ($compatNamingAllowlist | Where-Object { $full.StartsWith($_) })
    }
    foreach ($p in $badPaths) {
        Write-Host "FAIL path $($p.FullName.Substring($dir.Length + 1))"
        $failed += $p.FullName
    }
}

if ($failed.Count -gt 0) {
    throw "namespace check failed: $($failed.Count)"
}

Write-Host "namespace check passed"
