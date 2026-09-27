param(
    [string]$HostSerial = '003203627002399',
    [string]$GuestSerial = 'R52YA0E5KEZ',
    [string]$Mode = 'LAN',
    [int]$Runs = 1,
    [string]$Label = 'baseline',
    [string]$Only = '',
    [switch]$StaleHint,
    [switch]$WarmBackup,
    [int]$HoldBackupMs = 0,
    [switch]$DeadCandidate,
    [switch]$ShortCode
)
$ErrorActionPreference = 'Stop'
$devices = @(& adb devices | ForEach-Object { if ($_ -match '^(\S+)\s+device$') { $Matches[1] } })
if ($devices.Count -ne 2 -or $HostSerial -eq $GuestSerial -or $HostSerial -notin $devices -or $GuestSerial -notin $devices) {
    throw 'Exactly two usable devices matching HostSerial and GuestSerial are required.'
}
$root = Split-Path -Parent $PSScriptRoot
$output = Join-Path $root "build/transport-results/$Label"
New-Item -ItemType Directory -Force $output | Out-Null
$runner = 'app.morphe.jam.companion.test/app.morphe.jam.companion.DeviceScenario'
$options = @()
if ($WarmBackup) { $options += @('-e', 'warmBackup', 'true') }
if ($HoldBackupMs) { $options += @('-e', 'holdBackupMs', "$HoldBackupMs") }
$sources = @{ InviteHints = 'INVITE_HINT'; Ipv4Broadcast = 'IPV4_BROADCAST'; Ipv6Multicast = 'IPV6_MULTICAST'; ActiveProbe = 'ACTIVE_PROBE' }
if ($Only) {
    foreach ($provider in @('InviteHints','Nsd','GatewayProbe','Ipv4Broadcast','Ipv6Multicast','ActiveProbe','Aware','Ble')) {
        if ($provider -ne $Only) { $options += @('-e', "disable$provider", 'true') }
    }
    if ($sources.ContainsKey($Only)) { $options += @('-e', 'expectedSource', $sources[$Only]) }
}
for ($i = 1; $i -le $Runs; $i++) {
    $prefix = Join-Path $output "$Mode-$HostSerial-$i"
    foreach ($serial in @($HostSerial, $GuestSerial)) {
        & adb -s $serial shell am force-stop app.morphe.jam.companion
        & adb -s $serial logcat -c
    }
    $hostProcess = Start-Process adb -ArgumentList (@('-s', $HostSerial, 'shell', 'am', 'instrument', '-w', '-e', 'role', 'layerHost', '-e', 'transport', $Mode) + $options + @($runner)) -WindowStyle Hidden -PassThru -RedirectStandardOutput "$prefix-host.txt" -RedirectStandardError "$prefix-host-error.txt"
    try {
        $ready = $false
        for ($wait = 0; $wait -lt 100; $wait++) {
            if ((Get-Content "$prefix-host.txt" -Raw) -match 'LAYER_HOST_READY') { $ready = $true; break }
            if ($hostProcess.HasExited) { break }
            Start-Sleep -Milliseconds 100
        }
        if (!$ready) { throw "Host failed: $(Get-Content "$prefix-host.txt" -Raw)" }
        # Invitation stays in memory and is never printed or written to test logs.
        $invite = (& adb -s $HostSerial shell run-as app.morphe.jam.companion cat files/layer-invite.txt).Trim()
        if ($ShortCode) { $invite = (& adb -s $HostSerial shell run-as app.morphe.jam.companion cat files/layer-code.txt).Trim() }
        if ($StaleHint) { $invite = $invite -replace '&ep=[^&]*', '&ep=CgAA_g.9' }
        if ($DeadCandidate) { $invite = $invite -replace '&ep=', '&ep=CgAAAQ.9,' }
        $extra = $options -join ' '
        $guestOutput = & adb -s $GuestSerial shell "am instrument -w -e role layerGuest -e transport $Mode -e invite '$invite' $extra $runner"
        $guestOutput | Set-Content "$prefix-guest.txt"
        $guestOutput | Where-Object { $_ -match 'PASS|FAIL|LAYER_OK' } | Write-Output
        if (($guestOutput -join "`n") -notmatch 'LAYER_OK') { throw 'Guest scenario failed' }
    } finally {
        & adb -s $HostSerial shell run-as app.morphe.jam.companion touch files/layer-stop
        $hostProcess.WaitForExit(5000) | Out-Null
        foreach ($serial in @($HostSerial, $GuestSerial)) {
            & adb -s $serial logcat -d -s MorpheJam | Set-Content "$prefix-$serial-log.txt"
            & adb -s $serial logcat -d -b crash | Set-Content "$prefix-$serial-crash.txt"
            & adb -s $serial shell am force-stop app.morphe.jam.companion
        }
        if ((Get-Content "$prefix-host.txt" -Raw) -notmatch 'LAYER_OK') { throw 'Host cleanup did not pass; inspect saved logs.' }
    }
}
