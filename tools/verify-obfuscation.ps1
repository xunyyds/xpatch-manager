[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Apk,
    [Parameter(Position = 1)][string]$Source = '',
    [switch]$WithZipfp,
    [switch]$AuditLegacy
)

$ErrorActionPreference = 'Stop'
try { Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop } catch { }
try { Add-Type -AssemblyName System.IO.Compression -ErrorAction Stop } catch { }

$script:Latin1 = $null
function Get-Latin1 {
    if ($null -eq $script:Latin1) {
        try { $script:Latin1 = [System.Text.Encoding]::Latin1 } catch { $script:Latin1 = $null }
        if ($null -eq $script:Latin1) {
            $script:Latin1 = [System.Text.Encoding]::GetEncoding(28591)
        }
    }
    return $script:Latin1
}

$script:Encodings = @(
    [pscustomobject]@{ Name = 'UTF-8'; Enc = [System.Text.Encoding]::UTF8 },
    [pscustomobject]@{ Name = 'UTF-16LE'; Enc = [System.Text.Encoding]::Unicode }
)

function Format-Size([long]$Bytes) {
    if ($Bytes -ge 1MB) { return ('{0:N2} MB' -f ($Bytes / 1MB)) }
    if ($Bytes -ge 1KB) { return ('{0:N1} KB' -f ($Bytes / 1KB)) }
    return "$Bytes B"
}

function Get-DosTimeText([int]$DosDate, [int]$DosTime) {
    if ($DosDate -le 0) { return '(无)' }
    $year = 1980 + (($DosDate -shr 9) -band 0x7F)
    $month = ($DosDate -shr 5) -band 0x0F
    $day = $DosDate -band 0x1F
    $hour = ($DosTime -shr 11) -band 0x1F
    $minute = ($DosTime -shr 5) -band 0x3F
    $second = ($DosTime -band 0x1F) * 2
    return ('{0:D4}-{1:D2}-{2:D2} {3:D2}:{4:D2}:{5:D2}' -f $year, $month, $day, $hour, $minute, $second)
}

function Get-MethodName([int]$Method) {
    switch ($Method) {
        0 { return 'stored' }
        8 { return 'deflate' }
        12 { return 'bzip2' }
        14 { return 'lzma' }
        93 { return 'zstd' }
        default { return "method=$Method" }
    }
}

function Test-DosEpoch([int]$DosDate, [int]$DosTime) {
    return (($DosDate -eq 0x0021) -and ($DosTime -eq 0)) -or ($DosDate -eq 0)
}

function Read-ZipCentralDirectory {
    param([string]$Path)

    $list = New-Object System.Collections.Generic.List[object]
    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    try {
        $length = $stream.Length
        if ($length -lt 22) { throw '文件太小，不是 zip' }

        $tailLen = [int][Math]::Min([long]66000, $length)
        $tail = New-Object byte[] $tailLen
        $stream.Position = $length - $tailLen
        $got = 0
        while ($got -lt $tailLen) {
            $n = $stream.Read($tail, $got, $tailLen - $got)
            if ($n -le 0) { break }
            $got += $n
        }
        $eocd = -1
        for ($i = $tailLen - 22; $i -ge 0; $i--) {
            if ($tail[$i] -eq 0x50 -and $tail[$i + 1] -eq 0x4B -and $tail[$i + 2] -eq 0x05 -and $tail[$i + 3] -eq 0x06) {
                $eocd = $i
                break
            }
        }
        if ($eocd -lt 0) { throw '找不到 EOCD（不是 zip 或被截断）' }

        $count = [BitConverter]::ToUInt16($tail, $eocd + 10)
        $cdSize = [BitConverter]::ToUInt32($tail, $eocd + 12)
        $cdOffset = [BitConverter]::ToUInt32($tail, $eocd + 16)
        if ($cdOffset -eq [uint32]::MaxValue -or $count -eq 65535) {
            throw 'ZIP64 中央目录：本脚本不支持，请用 unzip -l 人工核对'
        }

        $cd = New-Object byte[] $cdSize
        $stream.Position = $cdOffset
        $got = 0
        while ($got -lt $cdSize) {
            $n = $stream.Read($cd, $got, $cdSize - $got)
            if ($n -le 0) { break }
            $got += $n
        }

        $pos = 0
        $index = 0
        while ($pos + 46 -le $cd.Length) {
            $sig = [BitConverter]::ToUInt32($cd, $pos)
            if ($sig -ne 0x02014B50) { break }
            $flags = [BitConverter]::ToUInt16($cd, $pos + 8)
            $method = [BitConverter]::ToUInt16($cd, $pos + 10)
            $dosTime = [BitConverter]::ToUInt16($cd, $pos + 12)
            $dosDate = [BitConverter]::ToUInt16($cd, $pos + 14)
            $crc = [BitConverter]::ToUInt32($cd, $pos + 16)
            $compSize = [BitConverter]::ToUInt32($cd, $pos + 20)
            $uncompSize = [BitConverter]::ToUInt32($cd, $pos + 24)
            $nameLen = [BitConverter]::ToUInt16($cd, $pos + 28)
            $extraLen = [BitConverter]::ToUInt16($cd, $pos + 30)
            $commentLen = [BitConverter]::ToUInt16($cd, $pos + 32)
            $name = [System.Text.Encoding]::UTF8.GetString($cd, $pos + 46, $nameLen)
            $list.Add([pscustomobject]@{
                    Index    = $index
                    Name     = $name
                    DosDate  = [int]$dosDate
                    DosTime  = [int]$dosTime
                    Method   = [int]$method
                    Flags    = [int]$flags
                    Crc      = $crc
                    Size     = [long]$uncompSize
                    Packed   = [long]$compSize
                })
            $index++
            $pos += 46 + $nameLen + $extraLen + $commentLen
        }
    } finally {
        $stream.Dispose()
    }
    return $list
}

$script:Zip = $null
function Open-ProductZip([string]$Path) {
    $script:Zip = [System.IO.Compression.ZipFile]::OpenRead($Path)
}

function Close-ProductZip {
    if ($null -ne $script:Zip) {
        $script:Zip.Dispose()
        $script:Zip = $null
    }
}

function Get-EntryBytes([string]$Name) {
    if ($null -eq $script:Zip) { return $null }
    $entry = $script:Zip.GetEntry($Name)
    if ($null -eq $entry) { return $null }
    $ds = $entry.Open()
    try {
        $ms = New-Object System.IO.MemoryStream
        $ds.CopyTo($ms)
        return , $ms.ToArray()
    } finally {
        $ds.Dispose()
    }
}

function Find-TextIn {
    param(
        [string]$Haystack,
        [System.Text.Encoding]$Enc,
        [string]$Text,
        [int]$Max = 4
    )
    $result = New-Object System.Collections.Generic.List[int]
    if ([string]::IsNullOrEmpty($Haystack)) { return $result }
    $needle = (Get-Latin1).GetString($Enc.GetBytes($Text))
    if ($needle.Length -eq 0) { return $result }
    $idx = $Haystack.IndexOf($needle, [System.StringComparison]::Ordinal)
    while ($idx -ge 0 -and $result.Count -lt $Max) {
        $result.Add($idx)
        $next = $idx + 1
        if ($next -ge $Haystack.Length) { break }
        $idx = $Haystack.IndexOf($needle, $next, [System.StringComparison]::Ordinal)
    }
    return $result
}

$script:Failures = New-Object System.Collections.Generic.List[string]
$script:Legacy = $false
$script:NotApplicable = New-Object System.Collections.Generic.List[string]

function Write-Section([string]$Title) {
    Write-Host ''
    Write-Host ('[' + $Title + ']') -ForegroundColor Cyan
}

function Write-Note([string]$Text) {
    Write-Host ('        ' + $Text) -ForegroundColor DarkGray
}

function Write-Check {
    param([string]$Name, [bool]$Ok)
    if ($script:Legacy) {
        Write-Host ('  N/A   ' + $Name + '（旧命名布局，本项不适用）') -ForegroundColor DarkYellow
        $script:NotApplicable.Add($Name)
        return
    }
    if ($Ok) {
        Write-Host ('  PASS  ' + $Name) -ForegroundColor Green
    } else {
        Write-Host ('  FAIL  ' + $Name) -ForegroundColor Red
        $script:Failures.Add($Name)
    }
}

$apkPath = ''
try {
    $apkPath = (Resolve-Path -LiteralPath $Apk -ErrorAction Stop).Path
} catch {
    Write-Host ('找不到 APK：' + $Apk) -ForegroundColor Red
    exit 2
}
if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) {
    Write-Host ('不是文件：' + $apkPath) -ForegroundColor Red
    exit 2
}

$srcPath = ''
if ($Source) {
    try {
        $srcPath = (Resolve-Path -LiteralPath $Source -ErrorAction Stop).Path
    } catch {
        Write-Host ('找不到源包：' + $Source) -ForegroundColor Red
        exit 2
    }
}

Write-Host ''
Write-Host 'XPatch 产物反痕迹体检（离线 / 只读）' -ForegroundColor Cyan
Write-Host ('  APK    : ' + $apkPath + '  (' + (Format-Size (Get-Item -LiteralPath $apkPath).Length) + ')')
if ($srcPath) { Write-Host ('  源包   : ' + $srcPath) }
Write-Host ('  时间   : ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))

$entries = Read-ZipCentralDirectory -Path $apkPath
if ($null -eq $entries -or $entries.Count -eq 0) {
    Write-Host 'APK 里一个条目都没有，无法体检。' -ForegroundColor Red
    exit 2
}
Write-Host ('  条目数 : ' + $entries.Count)

$entryNames = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($e in $entries) { [void]$entryNames.Add($e.Name) }

$isInjected = New-Object bool[] $entries.Count
foreach ($e in $entries) { $isInjected[$e.Index] = $false }

$hasLayoutEntry = $false
$legacyCount = 0
foreach ($e in $entries) {
    if ($e.Name -match '^assets/[0-9a-f]{8}/[0-9a-z]{10,14}\.(dat|bin|json|so)$') { $hasLayoutEntry = $true }
    if ($e.Name -match '^(xp/|assets/xposed_init)') { $legacyCount++ }
}
$script:Legacy = (-not $hasLayoutEntry) -and ($legacyCount -gt 0) -and (-not $AuditLegacy)
if ($script:Legacy) {
    Write-Host ''
    Write-Host ('  布局   : 旧命名（xp/* 共 ' + $legacyCount + ' 项）—— 注入路径混淆开关处于关闭状态') -ForegroundColor Yellow
    Write-Host '           反痕迹判据不适用，逐项标 N/A；本脚本仅作回归对照用。' -ForegroundColor Yellow
}

try {
    Open-ProductZip $apkPath

    Write-Section ('1. 条目表（zip 顺序，共 ' + $entries.Count + ' 项）')
    Write-Host ('  {0,4}  {1,-64}  {2,-19}  {3,-8}  {4,12}' -f '#', '名称', 'DOS 时间', '压缩', '原始大小') -ForegroundColor DarkGray
    foreach ($e in $entries) {
        Write-Host ('  {0,4}  {1,-64}  {2,-19}  {3,-8}  {4,12}' -f `
                $e.Index, $e.Name, (Get-DosTimeText $e.DosDate $e.DosTime), (Get-MethodName $e.Method), $e.Size)
    }

    Write-Section '2. 条目名反痕迹（xp/ xpatch npatch lspatch fpa/ assets/xposed_init）'
    $badPatterns = @('xp/', 'xpatch', 'npatch', 'lspatch', 'fpa/', 'assets/xposed_init')
    $badHits = New-Object System.Collections.Generic.List[object]
    foreach ($e in $entries) {
        foreach ($p in $badPatterns) {
            if ($e.Name.IndexOf($p, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
                $badHits.Add([pscustomobject]@{ Name = $e.Name; Pattern = $p })
                break
            }
        }
    }
    if ($badHits.Count -eq 0) {
        Write-Note ('扫描 ' + $entries.Count + ' 个条目名，无命中')
    } else {
        $shown = 0
        foreach ($h in $badHits) {
            if ($shown -ge 25) { break }
            Write-Note ('命中「' + $h.Pattern + '」：' + $h.Name)
            $shown++
        }
        if ($badHits.Count -gt $shown) { Write-Note ('... 另有 ' + ($badHits.Count - $shown) + ' 个条目命中，略') }
    }
    Write-Check '条目名不含已知框架前缀' ($badHits.Count -eq 0)

    Write-Section '3. 清单（AndroidManifest.xml）原始字节'
    $manifestBytes = Get-EntryBytes 'AndroidManifest.xml'
    if ($null -eq $manifestBytes) {
        Write-Note '条目表里没有 AndroidManifest.xml'
        Write-Check '清单里不含 io.xpatch / xpatch / io/xpatch' $false
    } else {
        Write-Note ('AndroidManifest.xml ' + (Format-Size $manifestBytes.Length))
        $sourceTokens = New-Object System.Collections.Generic.List[string]
        if ($Source -and (Test-Path -LiteralPath $Source)) {
            try {
                $srcZip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Source).Path)
                try {
                    $srcEntry = $srcZip.GetEntry('AndroidManifest.xml')
                    if ($null -ne $srcEntry) {
                        $srcStream = $srcEntry.Open()
                        try {
                            $srcMs = New-Object System.IO.MemoryStream
                            $srcStream.CopyTo($srcMs)
                            $srcBytes = $srcMs.ToArray()
                        } finally { $srcStream.Dispose() }
                        foreach ($enc in $script:Encodings) {
                            $decoded = $enc.Enc.GetString($srcBytes)
                            foreach ($m in [regex]::Matches($decoded, '[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+')) {
                                if (-not $sourceTokens.Contains($m.Value)) { $sourceTokens.Add($m.Value) }
                            }
                        }
                    }
                } finally { $srcZip.Dispose() }
            } catch { }
        }
        $sourceTokens = @($sourceTokens | Where-Object { $_.Length -ge 8 -and ($_.Split('.').Count -ge 3) })
        if ($sourceTokens.Count -gt 0) {
            Write-Note ('已从源包清单掩掉 ' + $sourceTokens.Count + ' 个自身标识：' + ($sourceTokens -join ', '))
        }
        $manifestHits = New-Object System.Collections.Generic.List[object]
        foreach ($enc in $script:Encodings) {
            $masked = $enc.Enc.GetString($manifestBytes)
            foreach ($tok in $sourceTokens) { $masked = $masked.Replace($tok, ('#' * $tok.Length)) }
            foreach ($p in @('io.xpatch', 'xpatch', 'io/xpatch')) {
                $hitCount = ([regex]::Matches($masked, [regex]::Escape($p))).Count
                if ($hitCount -gt 0) {
                    $manifestHits.Add([pscustomobject]@{ Text = $p; Enc = $enc.Name; Count = $hitCount })
                }
            }
        }
        foreach ($h in $manifestHits) {
            Write-Note ('命中「' + $h.Text + '」 编码=' + $h.Enc + ' 次数=' + $h.Count)
        }
        if ($manifestHits.Count -eq 0) { Write-Note '3 个关键词 × 2 种编码均未命中（已排除源包自身标识）' }
        Write-Check '清单里不含 io.xpatch / xpatch / io/xpatch' ($manifestHits.Count -eq 0)
    }

    Write-Section '4. 内容反痕迹（*.dex / *.so / assets/**）'
    $contentNeedles = @('io.xpatch.stub.AppEntry', 'io/xpatch/stub/AppEntry')
    $contentHits = New-Object System.Collections.Generic.List[object]
    $scannedCount = 0
    $scannedBytes = [long]0
    foreach ($e in $entries) {
        $isAsset = $e.Name.StartsWith('assets/', [System.StringComparison]::OrdinalIgnoreCase)
        $isDex = $e.Name -match '(?i)\.dex$'
        $isSo = $e.Name -match '(?i)\.so$'
        if (-not ($isAsset -or $isDex -or $isSo)) { continue }
        $bytes = $null
        try {
            $bytes = Get-EntryBytes $e.Name
        } catch {
            Write-Note ('跳过（解压失败）：' + $e.Name)
            continue
        }
        if ($null -eq $bytes) { continue }
        $scannedCount++
        $scannedBytes += $bytes.Length
        $hay = (Get-Latin1).GetString($bytes)
        foreach ($n in $contentNeedles) {
            foreach ($enc in $script:Encodings) {
                foreach ($off in @(Find-TextIn -Haystack $hay -Enc $enc.Enc -Text $n -Max 2)) {
                    $contentHits.Add([pscustomobject]@{ Entry = $e.Name; Text = $n; Enc = $enc.Name; Offset = $off })
                }
            }
        }
    }
    Write-Note ('扫描 ' + $scannedCount + ' 个条目 / ' + (Format-Size $scannedBytes))
    foreach ($h in $contentHits) {
        Write-Note ('命中「' + $h.Text + '」 条目=' + $h.Entry + ' 编码=' + $h.Enc + ' 偏移=0x' + ('{0:X}' -f $h.Offset))
    }
    if ($contentHits.Count -eq 0) { Write-Note '2 个关键词 × 2 种编码均未命中' }
    Write-Check '内容里不含 io.xpatch.stub.AppEntry' ($contentHits.Count -eq 0)

    Write-Section '5. 注入条目元数据（DOS 时间戳 / 在 zip 里的位置）'
    $injectPattern = '^assets/[0-9a-f]{8}/[0-9a-z]{10,14}\.(dat|bin|json|so)$'
    $injected = New-Object System.Collections.Generic.List[object]
    $injectSource = '候选正则'
    foreach ($e in $entries) {
        if ($e.Name -match $injectPattern) {
            $injected.Add($e)
            $isInjected[$e.Index] = $true
        }
    }
    if ($injected.Count -eq 0) {
        foreach ($e in $entries) {
            if ($e.Name -match '^(xp/|assets/xposed_init)') {
                $injected.Add($e)
                $isInjected[$e.Index] = $true
            }
        }
        if ($injected.Count -gt 0) { $injectSource = '旧前缀 xp/（说明仍是未混淆产物）' }
    }
    $addedNames = New-Object System.Collections.Generic.List[string]
    if ($srcPath) {
        $srcEntries = Read-ZipCentralDirectory -Path $srcPath
        $srcNames = New-Object 'System.Collections.Generic.HashSet[string]'
        foreach ($se in $srcEntries) { [void]$srcNames.Add($se.Name) }
        foreach ($e in $entries) {
            if ($srcNames.Contains($e.Name)) { continue }
            $addedNames.Add($e.Name)
            if ($e.Name -match '^classes[0-9]*\.dex$') { continue }
            if (-not $isInjected[$e.Index]) {
                $injected.Add($e)
                $isInjected[$e.Index] = $true
            }
        }
        if ($addedNames.Count -gt 0 -and $injectSource -eq '候选正则' -and $injected.Count -gt 0) {
            $injectSource = '候选正则 + 与源包的差集'
        }
    }

    if ($injected.Count -eq 0) {
        Write-Note '既没有 assets/<hex8>/... 形状的条目，也没有 xp/ 旧前缀条目'
        Write-Check '注入条目 DOS 时间戳等于真实条目众数' $false
        Write-Check '注入条目未形成结束于末尾的连续区间' $false
    } else {
        Write-Note ('注入条目 ' + $injected.Count + ' 项（来源：' + $injectSource + '）')
        $shown = 0
        foreach ($e in $injected) {
            if ($shown -ge 20) { break }
            Write-Note ('  #' + $e.Index + '  ' + $e.Name + '  ' + (Get-DosTimeText $e.DosDate $e.DosTime))
            $shown++
        }
        if ($injected.Count -gt $shown) { Write-Note ('  ... 另有 ' + ($injected.Count - $shown) + ' 项，略') }

        $stampSet = New-Object 'System.Collections.Generic.HashSet[string]'
        $epochCount = 0
        foreach ($e in $injected) {
            if (Test-DosEpoch $e.DosDate $e.DosTime) { $epochCount++ }
            [void]$stampSet.Add((Get-DosTimeText $e.DosDate $e.DosTime))
        }

        $modeMap = @{}
        foreach ($e in $entries) {
            if ($isInjected[$e.Index]) { continue }
            $key = ('{0}/{1}' -f $e.DosDate, $e.DosTime)
            if ($modeMap.ContainsKey($key)) { $modeMap[$key] = $modeMap[$key] + 1 } else { $modeMap[$key] = 1 }
        }
        $modeKey = $null
        $modeHits = 0
        foreach ($k in $modeMap.Keys) {
            if ($modeMap[$k] -gt $modeHits) { $modeHits = $modeMap[$k]; $modeKey = $k }
        }

        $stampOk = $false
        if ($null -eq $modeKey) {
            Write-Note '没有任何非注入条目可作参照，时间戳判据跳过'
            $stampOk = $true
        } else {
            $stampOf = @{}
            foreach ($e in $injected) {
                $ze = $script:Zip.GetEntry($e.Name)
                if ($null -ne $ze) { $stampOf[$e.Name] = $ze.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss') }
            }
            $refStamps = @{}
            foreach ($e in $entries) {
                if ($isInjected[$e.Index]) { continue }
                $ze = $script:Zip.GetEntry($e.Name)
                if ($null -eq $ze) { continue }
                $k = $ze.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')
                if ($refStamps.ContainsKey($k)) { $refStamps[$k] = $refStamps[$k] + 1 } else { $refStamps[$k] = 1 }
            }
            $modeText = $null
            $modeHits = 0
            foreach ($k in $refStamps.Keys) { if ($refStamps[$k] -gt $modeHits) { $modeHits = $refStamps[$k]; $modeText = $k } }
            $mismatch = 0
            foreach ($name in $stampOf.Keys) { if ($stampOf[$name] -ne $modeText) { $mismatch++ } }
            Write-Note ('真实条目众数时间戳 = ' + $modeText + '（' + $modeHits + ' / ' + $refStamps.Count + ' 种取值里最多）')
            Write-Note ('注入条目时间戳 ' + $stampOf.Count + ' 项，与众数不同的 ' + $mismatch + ' 项（各注入条目取值见上表）')
            $stampOk = ($mismatch -eq 0)
        }
        Write-Check '注入条目时间戳等于真实条目众数（.NET LastWriteTime）' $stampOk

        $maxInjectedIndex = -1
        foreach ($e in $injected) { if ($e.Index -gt $maxInjectedIndex) { $maxInjectedIndex = $e.Index } }
        $afterCount = 0
        foreach ($e in $entries) {
            if ($e.Index -gt $maxInjectedIndex -and -not $isInjected[$e.Index]) { $afterCount++ }
        }
        $sortedIdx = @($injected | ForEach-Object { $_.Index } | Sort-Object)
        $contiguous = $true
        for ($i = 1; $i -lt $sortedIdx.Count; $i++) {
            if ($sortedIdx[$i] -ne $sortedIdx[$i - 1] + 1) { $contiguous = $false; break }
        }
        $endsAtLast = ($sortedIdx.Count -gt 0) -and ($sortedIdx[$sortedIdx.Count - 1] -eq ($entries.Count - 1))
        $tailAppend = $contiguous -and $endsAtLast
        Write-Note ('注入条目序号 ' + ($sortedIdx -join ',') + '；连续=' + $contiguous + '，结束于末尾=' + $endsAtLast)
        Write-Note ('最后一个注入条目是 #' + $maxInjectedIndex + ' / 共 ' + $entries.Count + ' 项；其后还有 ' + $afterCount + ' 个非注入条目')
        Write-Check '注入条目未形成结束于末尾的连续区间' (-not $tailAppend)
    }

    Write-Section '6. 布局自洽（config 的 res_* 键与条目表）'
    $cfgPattern = '^assets/[0-9a-f]{8}/[0-9a-z]{10,14}\.(dat|bin|json)$'
    $configEntry = $null
    $configText = $null
    $configObj = $null
    foreach ($e in $entries) {
        if ($e.Name -notmatch $cfgPattern) { continue }
        $bytes = $null
        try { $bytes = Get-EntryBytes $e.Name } catch { continue }
        if ($null -eq $bytes) { continue }
        if ($bytes.Length -gt 1MB) { continue }
        $text = (Get-Latin1).GetString($bytes)
        if (-not $text.TrimStart().StartsWith('{')) { continue }
        if ($text.IndexOf('res_cfg', [System.StringComparison]::Ordinal) -lt 0) { continue }
        $configEntry = $e
        $configText = $text
        break
    }

    if ($null -eq $configEntry) {
        if ($entryNames.Contains('xp/config.json')) {
            Write-Note '没有 assets/<hex8>/... 形状的 config；但存在 xp/config.json（= 未混淆的老产物）'
        } else {
            Write-Note '候选正则扫遍条目表，没有找到以 { 开头且含 res_cfg 的 config'
        }
        Write-Check 'config 能解析出 res_cfg / res_core / res_boot_dex / res_factory' $false
        Write-Check 'res_cfg 匹配布局发现正则' $false
        Write-Check 'res_* 指向的条目都在条目表里' $false
        Write-Check 'res_boot_dex 是 classes*.dex' $false
    } else {
        Write-Note ('config 候选：' + $configEntry.Name + '  (' + (Format-Size $configEntry.Size) + ')')
        try {
            $configObj = $configText | ConvertFrom-Json
        } catch {
            Write-Note ('JSON 解析失败：' + $_.Exception.Message)
            $configObj = $null
        }

        $requiredKeys = @('res_cfg', 'res_core', 'res_boot_dex', 'res_factory')
        $missing = New-Object System.Collections.Generic.List[string]
        if ($null -eq $configObj) {
            foreach ($k in $requiredKeys) { $missing.Add($k) }
            Write-Note 'config 不是合法 JSON'
        } else {
            foreach ($k in $requiredKeys) {
                $prop = $configObj.PSObject.Properties[$k]
                if ($null -eq $prop -or [string]::IsNullOrWhiteSpace([string]$prop.Value)) {
                    $missing.Add($k)
                } else {
                    Write-Note ($k.PadRight(14) + '= ' + [string]$prop.Value)
                }
            }
        }
        if ($missing.Count -gt 0) { Write-Note ('缺少键：' + ($missing -join ', ')) }
        Write-Check 'config 能解析出 res_cfg / res_core / res_boot_dex / res_factory' ($missing.Count -eq 0)

        $cfgSelf = ''
        if ($null -ne $configObj) {
            $cfgProp = $configObj.PSObject.Properties['res_cfg']
            if ($null -ne $cfgProp) { $cfgSelf = [string]$cfgProp.Value }
        }
        $cfgSelfOk = ($cfgSelf -match '^assets/[0-9a-f]{8}/[0-9a-z]{10,14}\.(dat|bin|json)$')
        if (-not $cfgSelfOk) { Write-Note ('res_cfg = "' + $cfgSelf + '"（要求匹配 ^assets/[0-9a-f]{8}/[0-9a-z]{10,14}\.(dat|bin|json)$）') }
        Write-Check 'res_cfg 匹配布局发现正则' $cfgSelfOk

        $pathMissing = New-Object System.Collections.Generic.List[string]
        $pathChecked = 0
        if ($null -ne $configObj) {
            foreach ($prop in $configObj.PSObject.Properties) {
                if (-not $prop.Name.StartsWith('res_', [System.StringComparison]::Ordinal)) { continue }
                if ($prop.Value -isnot [string]) { continue }
                $value = [string]$prop.Value
                if ($value.IndexOf('/') -lt 0) { continue }
                $pathChecked++
                if ($prop.Name -eq 'res_dir') {
                    $prefix = $value.TrimEnd('/') + '/'
                    $prefixFound = $false
                    foreach ($n in $entryNames) {
                        if ($n.StartsWith($prefix, [System.StringComparison]::Ordinal)) { $prefixFound = $true; break }
                    }
                    if (-not $prefixFound) {
                        $pathMissing.Add($prop.Name + '=' + $value + '（目录下没有任何条目）')
                        Write-Note ('目录下没有任何条目：' + $prop.Name + '=' + $value)
                    }
                    continue
                }
                if (-not $entryNames.Contains($value)) {
                    $pathMissing.Add($prop.Name + '=' + $value)
                    Write-Note ('条目表里没有：' + $prop.Name + '=' + $value)
                }
            }
        }
        if ($pathChecked -eq 0) { Write-Note 'config 里没有路径形状的 res_* 值' }
        Write-Check 'res_* 指向的条目都在条目表里' ($pathChecked -gt 0 -and $pathMissing.Count -eq 0)

        $boot = ''
        if ($null -ne $configObj) {
            $bootProp = $configObj.PSObject.Properties['res_boot_dex']
            if ($null -ne $bootProp) { $boot = [string]$bootProp.Value }
        }
        $bootOk = ($boot -match '^classes[0-9]*\.dex$') -and $entryNames.Contains($boot)
        if (-not $bootOk) { Write-Note ('res_boot_dex = "' + $boot + '"（要求 classes*.dex 且存在于条目表）') }
        Write-Check 'res_boot_dex 是 classes*.dex' $bootOk
    }

    if ($srcPath) {
        Write-Section '7. 与源包对比（新增条目 = 注入集合）'
        Write-Note ('源包条目数 ' + $srcEntries.Count + '，产物条目数 ' + $entries.Count + '，新增 ' + $addedNames.Count + ' 项')
        if ($addedNames.Count -eq 0) {
            Write-Note '产物与源包的条目名完全相同（可能根本没注入）'
            Write-Check '产物相对源包有新增条目' $false
        } else {
            foreach ($n in $addedNames) {
                $mark = ''
                if ($n -match '^classes[0-9]*\.dex$') { $mark = '   （引导桩，必须留在根目录）' }
                Write-Note ('+ ' + $n + $mark)
            }
            Write-Check '产物相对源包有新增条目' $true
        }
    } else {
        Write-Section '7. 与源包对比（跳过：未给 -Source）'
        Write-Note '给了 -Source 时会打印条目名差集，即注入集合'
    }

    if ($WithZipfp) {
        $zipfp = 'D:\ovo\wrok\fpa\litmus\tools\zipfp.py'
        if (Test-Path -LiteralPath $zipfp) {
            try {
                $fp = & python $zipfp $apkPath 2>&1
                Write-Host ''
                Write-Host ('  参考 zipfp : ' + ($fp -join ' ')) -ForegroundColor DarkGray
            } catch {
                Write-Host '  参考 zipfp : python 调用失败（跳过）' -ForegroundColor DarkGray
            }
        } else {
            Write-Host ('  参考 zipfp : 找不到 ' + $zipfp) -ForegroundColor DarkGray
        }
    }
} finally {
    Close-ProductZip
}

Write-Host ''
if ($script:Legacy) {
    Write-Host ('RESULT: N/A (legacy layout)') -ForegroundColor Yellow
    Write-Host ('        旧命名条目 ' + $legacyCount + ' 项；反痕迹判据 ' + $script:NotApplicable.Count + ' 项不适用') -ForegroundColor DarkGray
    exit 0
}
if ($script:Failures.Count -eq 0) {
    Write-Host 'RESULT: PASS' -ForegroundColor Green
    exit 0
}
Write-Host ('失败项（' + $script:Failures.Count + '）：') -ForegroundColor Red
foreach ($f in $script:Failures) { Write-Host ('  - ' + $f) -ForegroundColor Red }
Write-Host ('RESULT: FAIL (' + $script:Failures.Count + ' 项)') -ForegroundColor Red
exit 1
