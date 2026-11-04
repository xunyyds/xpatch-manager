param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [long]$CleanBytes = 0,
    [switch]$Embedded
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $Apk)) { throw "missing apk: $Apk" }
$size = (Get-Item $Apk).Length
Write-Host "apk=$Apk size=$size"

if ($CleanBytes -gt 0) {
    if ($Embedded) {
        $limit = [long]($CleanBytes * 1.15)
    } else {
        $limit = $CleanBytes + 1048576
    }
    Write-Host "budget=$limit"
    if ($size -gt $limit) {
        throw "apk size $size exceeds budget $limit"
    }
    Write-Host "size budget passed"
}

$managerLimit = 9437184
if ($Apk -match 'manager') {
    if ($size -gt $managerLimit) {
        Write-Host "WARN manager apk exceeds 9 MiB target ($size)"
    } else {
        Write-Host "manager size within target"
    }
}
