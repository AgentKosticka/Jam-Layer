param([string[]]$Labels = @('final-auto','final-lan','final-aware','final-broadcast','final-active','final-stale','final-backup','final-code','hints','ipv6','racing','keepalive'))
$ErrorActionPreference = 'Stop'
$root = Join-Path (Split-Path -Parent $PSScriptRoot) 'build/transport-results'
function Median($values) {
    $sorted = @($values | Sort-Object)
    if (!$sorted.Count) { return '-' }
    $middle = [int][Math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2) { return $sorted[$middle] }
    return ($sorted[$middle - 1] + $sorted[$middle]) / 2
}
'| Scenario | Passed / attempted | Join median / worst (ms) | Recovery median / worst (ms) |'
'| --- | --- | --- | --- |'
foreach ($label in $Labels) {
    $files = @(Get-ChildItem (Join-Path $root $label) -Filter '*guest.txt' -ErrorAction SilentlyContinue)
    $joins = @(); $recoveries = @()
    foreach ($file in $files) {
        $body = Get-Content $file.FullName -Raw
        if ($body -match 'PASS \w+ join=(\d+)ms reconnect=(\d+)ms' -and $body.Contains('LAYER_OK')) {
            $joins += [int]$Matches[1]; $recoveries += [int]$Matches[2]
        }
    }
    if ($files.Count) {
        "| $label | $($joins.Count) / $($files.Count) | $(Median $joins) / $(($joins | Measure-Object -Maximum).Maximum) | $(Median $recoveries) / $(($recoveries | Measure-Object -Maximum).Maximum) |"
    }
}
