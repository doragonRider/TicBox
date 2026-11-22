[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$PhoneSerial,
    [string]$WatchSerial = "",
    [string]$Adb = "adb.exe",
    [string]$Apk = (Join-Path $PSScriptRoot "ticbox-alert-probe.apk"),
    [ValidateSet("SILENT", "SOUND_ONLY", "VIBRATE_ONLY", "BOTH")]
    [string[]]$Cases = @("SILENT", "SOUND_ONLY", "VIBRATE_ONLY", "BOTH"),
    [ValidateRange(3, 60)][int]$WaitSeconds = 8,
    [string]$ReportPath = "",
    [switch]$CleanupOnly
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$probePackage = "dev.ticbox.alertprobe"
$probeComponent = "$probePackage/.ProbeReceiver"
$companionService = "dev.ticbox.companion/.platform.service.BackendSessionService"

function Invoke-ProbeAdb {
    param([string[]]$AdbArguments, [string]$Device = $PhoneSerial)
    $lines = @(& $Adb -s $Device @AdbArguments 2>&1)
    $code = $LASTEXITCODE
    $text = ($lines | ForEach-Object { "$_" }) -join "`n"
    if ($code -ne 0) { throw "adb failed ($code): $text" }
    return $text
}

function Invoke-ProbeCase {
    param([string]$Name, [string]$Run = "")
    $arguments = @("shell", "am", "broadcast", "--user", "0", "--include-stopped-packages",
        "--receiver-foreground", "-n", $probeComponent, "-a", "dev.ticbox.alertprobe.RUN", "--es", "case", $Name)
    if ($Run) { $arguments += @("--es", "run", $Run) }
    $text = Invoke-ProbeAdb -AdbArguments $arguments
    $match = [regex]::Match($text, 'ALERT_PROBE_OK [^"\r\n]+')
    if (-not $match.Success) { throw "Probe did not confirm the operation: $text" }
    return $match.Value
}

function Get-CompanionState {
    $text = Invoke-ProbeAdb -AdbArguments @("shell", "dumpsys", "activity", "service", $companionService)
    if ($text -notmatch 'linkPhase=LINK_READY' -or $text -notmatch 'notificationPhase=ACTIVE' -or
            $text -notmatch 'dataPhase=ACTIVE' -or $text -notmatch 'matchingNotificationSession=true' -or
            $text -notmatch 'enabled=true notificationAccessGranted=true listenerConnected=true') {
        throw "TicBox notification sync is not ready. Open TicBox and wait for the connection; this state cannot validate watch alert behavior."
    }
    return @(($text -split '\r?\n') | Where-Object {
        $_ -match '^\s*(linkPhase=|notificationPhase=|enabled=|filterMode=|activeNotificationCount=|dataPhase=|sentDataItems=|notificationIssue=)'
    } | ForEach-Object { $_.Trim() })
}

function Get-WatchCounts {
    if (-not $WatchSerial) { return $null }
    $text = Invoke-ProbeAdb -Device $WatchSerial -AdbArguments @("shell", "dumpsys", "activity", "provider",
        "com.google.wear.services/com.google.wear.services.infra.dump.DumpableContentProvider", "Bridger")
    $bridged = [regex]::Match($text, '(\d+) items in bridged items map')
    $active = [regex]::Match($text, '(\d+) active stream items:')
    return [ordered]@{
        bridged = $(if ($bridged.Success) { [int]$bridged.Groups[1].Value } else { $null })
        active = $(if ($active.Success) { [int]$active.Groups[1].Value } else { $null })
    }
}

function Read-WatchObservation {
    while ($true) {
        $answer = (Read-Host "Watch only: visible,vibrated,sounded [y/n/u each, e.g. y,y,n; q stops]").Trim().ToLowerInvariant()
        if ($answer -eq "q") { return $null }
        if ($answer -match '^\s*([ynu])\s*,\s*([ynu])\s*,\s*([ynu])\s*$') {
            $values = @{ y = $true; n = $false; u = $null }
            return [ordered]@{ visible = $values[$Matches[1]]; vibrated = $values[$Matches[2]]; sounded = $values[$Matches[3]] }
        }
        Write-Host "Use y=yes, n=no, u=unknown. Example: y,n,n means visible, no vibration, no sound."
    }
}

$null = Invoke-ProbeAdb -AdbArguments @("get-state")
if ($CleanupOnly) {
    $present = Invoke-ProbeAdb -AdbArguments @("shell", "pm", "path", $probePackage)
    if ($present -match 'package:') { Write-Host (Invoke-ProbeCase -Name "CLEANUP") }
    else { Write-Host "Probe is not installed; nothing to clean." }
    return
}

$sdk = (Invoke-ProbeAdb -AdbArguments @("shell", "getprop", "ro.build.version.sdk")).Trim()
$characteristics = Invoke-ProbeAdb -AdbArguments @("shell", "getprop", "ro.build.characteristics")
if ($sdk -notmatch '^\d+$' -or [int]$sdk -lt 34 -or $characteristics -match '\bwatch\b') {
    throw "PhoneSerial must select the Android 14+ phone, not the watch."
}
if ($WatchSerial) { $null = Invoke-ProbeAdb -Device $WatchSerial -AdbArguments @("get-state") }
$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot "probe-manifest.json") -Raw | ConvertFrom-Json
$apkHash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($apkHash -cne $manifest.apkSha256) { throw "Probe APK does not match probe-manifest.json." }
Write-Host (Invoke-ProbeAdb -AdbArguments @("install", "-r", (Resolve-Path -LiteralPath $Apk).Path))
$null = Invoke-ProbeAdb -AdbArguments @("shell", "pm", "grant", $probePackage, "android.permission.POST_NOTIFICATIONS")
Write-Host (Invoke-ProbeCase -Name "CHECK")
$null = Invoke-ProbeCase -Name "CLEANUP"
Write-Host "In TicBox notification settings, enable 'TicBox Alert Probe'."
Write-Host "If hidden, include apps without Internet permission in the app list."
Write-Host "Unplug the watch USB cable and remove it from its charger; keep its Bluetooth link to the phone."
Write-Host "Keep the watch worn and check its sound/vibration settings. Turn phone DND off."
Write-Host "The phone can also alert: move it away so you only judge the watch."
$null = Read-Host "After enabling the probe and restoring the connection, press Enter"

$run = [Guid]::NewGuid().ToString("N")
if (-not $ReportPath) { $ReportPath = Join-Path $PSScriptRoot ("alert-probe-results-" + $run + ".json") }
if (Test-Path -LiteralPath $ReportPath) { throw "Report already exists: $ReportPath" }
$report = [ordered]@{
    schema = "ticbox-notification-alert-probe/v1"
    run = $run
    startedUtc = [DateTime]::UtcNow.ToString("o")
    apkSha256 = $apkHash
    phoneSdk = [int]$sdk
    watchAdbUsed = [bool]$WatchSerial
    authority = "phone channel configuration plus user-observed watch behavior"
    cases = @()
    cleanup = "pending"
    status = "running"
}
try {
    foreach ($requestedCase in $Cases) {
        $case = $requestedCase.ToUpperInvariant()
        $before = Get-CompanionState
        $environment = Invoke-ProbeCase -Name "CHECK"
        if ($environment -notmatch '\bdnd=1\b') { throw "Phone DND is active or unknown; turn it off and rerun this test." }
        $null = Invoke-ProbeCase -Name "CLEANUP"
        Write-Host ""
        $null = Read-Host "Press Enter to send $case"
        $sentAt = [DateTime]::UtcNow.ToString("o")
        $metadata = Invoke-ProbeCase -Name $case -Run $run
        Write-Host $metadata
        Write-Host "Waiting $WaitSeconds seconds for the Bluetooth notification and icon..."
        Start-Sleep -Seconds $WaitSeconds
        $after = Get-CompanionState
        $watchCounts = Get-WatchCounts
        $observation = Read-WatchObservation
        if ($null -eq $observation) { $report.status = "stopped_by_user"; break }
        $report.cases += [ordered]@{
            case = $case
            sentUtc = $sentAt
            sourceChannel = $metadata
            companionBefore = $before
            companionAfter = $after
            watchCounts = $watchCounts
            observed = $observation
        }
        $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
    }
    if ($report.status -eq "running") { $report.status = "observations_recorded" }
} catch {
    $report.status = "interrupted"
    throw
} finally {
    try { $null = Invoke-ProbeCase -Name "CLEANUP"; $report.cleanup = "test_notifications_and_channels_removed" }
    catch { $report.cleanup = "incomplete"; Write-Warning "Run this script with -CleanupOnly to remove test notifications." }
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
    Write-Host "Report: $ReportPath"
}
$report.cases | ForEach-Object {
    [pscustomobject]@{ Case = $_.case; Visible = $_.observed.visible; Vibrated = $_.observed.vibrated; Sounded = $_.observed.sounded }
} | Format-Table -AutoSize
Write-Host "Results are observations, not an automatic PASS. If a card was not visible, its alert behavior is unverified."
Write-Host "The small probe app remains installed. You can uncheck it in TicBox and uninstall dev.ticbox.alertprobe afterwards."
