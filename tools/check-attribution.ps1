param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [ValidateSet('advisory', 'strict')][string]$Mode = 'advisory'
)

$ErrorActionPreference = 'Stop'
$warn = @()

foreach ($p in 'LICENSE', 'ACKNOWLEDGEMENTS.md') {
    if (-not (Test-Path (Join-Path $Root $p))) { $warn += "缺失: $p" }
}

$ack = Join-Path $Root 'ACKNOWLEDGEMENTS.md'
if (Test-Path $ack) {
    $text = Get-Content $ack -Raw
    $required = 'FPA', 'LSPatch', 'NPatch', 'LSPosed', 'Dobby', 'LSPlant',
                'XposedBridge', 'libxposed', 'AndroidLiquidGlass', 'Miuix', 'Shizuku'
    foreach ($r in $required) {
        if ($text -notmatch [regex]::Escape($r)) { $warn += "致谢清单缺少: $r" }
    }
}

$scrubbers = Get-ChildItem (Join-Path $Root 'tools') -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match 'check-namespace|verify-assets' }
foreach ($s in $scrubbers) { $warn += "存在清痕脚本（应删除）: tools/$($s.Name)" }

$src = Get-ChildItem $Root -Recurse -File -Include *.kt, *.java, *.ps1 |
    Where-Object { $_.FullName -notmatch '\\build\\|\\.git\\' }
foreach ($f in $src) {
    if ((Get-Content $f.FullName -Raw) -match '"xp"\s*\+\s*"t/"') {
        $warn += "发现规避式拼接: $($f.FullName.Substring($Root.Length + 1))"
    }
}

$sign = Join-Path $Root 'manager/app/src/main/assets/xp/sign'
if (Test-Path $sign) {
    $ackText = if (Test-Path $ack) { Get-Content $ack -Raw } else { '' }
    Get-ChildItem $sign -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match 'fpa|npatch|lspatch|^np$' } |
        ForEach-Object {
            if ($ackText -match [regex]::Escape($_.Name)) {
                Write-Host "INFO 已声明的兼容签名: assets/xp/sign/$($_.Name)"
            } else {
                $warn += "未声明出处的签名材料: assets/xp/sign/$($_.Name)"
            }
        }
}

if ($warn.Count -eq 0) {
    Write-Host 'attribution check passed'
    exit 0
}

$warn | ForEach-Object { Write-Host "WARN $_" }
if ($Mode -eq 'strict') { throw "attribution check failed: $($warn.Count)" }
Write-Host "attribution check: $($warn.Count) 项待处理（advisory 模式）"
