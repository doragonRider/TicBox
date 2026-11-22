# Windows PowerShell 5.1+ / PowerShell 7. Uses the existing adb and Android Perfetto.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$PhoneSerial,
    [Parameter(Mandatory = $true)][string]$TargetSerial,
    [string]$Adb = 'adb.exe',
    [ValidateRange(10, 60)][int]$DurationSeconds = 30,
    [string]$OutputDirectory = (Join-Path (Get-Location) ('screen-system-trace-' + (Get-Date -Format 'yyyyMMdd-HHmmss')))
)
$ErrorActionPreference = 'Stop'
if ($PhoneSerial -eq $TargetSerial) { throw 'PhoneSerial and TargetSerial must identify different devices.' }
$adbCommand = Get-Command $Adb -CommandType Application -ErrorAction Stop
$adbExecutable = $adbCommand.Source
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $outputRoot) { throw 'Choose a new output directory; existing recordings are not overwritten.' }
$utf8 = New-Object Text.UTF8Encoding($false)
$devices = @(
    [pscustomobject]@{ Role = 'phone'; Serial = $PhoneSerial },
    [pscustomobject]@{ Role = 'target'; Serial = $TargetSerial }
)
function Invoke-TraceAdb {
    param([string]$DeviceSerial, [string[]]$AdbArguments, [int[]]$AcceptedExitCodes = @(0))
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = @(& $adbExecutable -s $DeviceSerial @AdbArguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    $text = ($lines | ForEach-Object { $_.ToString() }) -join "`n"
    if ($exitCode -notin $AcceptedExitCodes) { throw "adb exit $exitCode : $text" }
    return $text.Trim()
}
# Check both devices before starting either capture. No app installation, root or radio changes.
foreach ($device in $devices) {
    if ((Invoke-TraceAdb $device.Serial @('get-state')) -ne 'device') { throw "$($device.Role) is not ready." }
    # Some Android builds return 1 after successfully printing help. This exception is
    # limited to this probe, and must include actual Perfetto usage/options. Recording,
    # adb connection checks, pull and hash verification still require exit code zero.
    $help = Invoke-TraceAdb $device.Serial @('shell', 'perfetto', '--help') -AcceptedExitCodes @(0, 1)
    if ($help -notmatch '(?im)^\s*Usage:\s*perfetto\b' -or
        $help -notmatch '--config\b' -or $help -notmatch '--out\b' -or $help -notmatch '--txt\b') {
        throw "$($device.Role): Perfetto help was not recognized: $help"
    }
}
$null = New-Item -ItemType Directory -Path $outputRoot
$runId = [Guid]::NewGuid().ToString('N')
# Android 12+ provides this directory with the SELinux label Perfetto can read.
$remoteConfig = "/data/misc/perfetto-configs/ticbox-screen-$runId.pbtxt"
# Android's shell tracing output directory. These are owned, unique capture files.
$remoteTrace = "/data/misc/perfetto-traces/ticbox-screen-$runId.perfetto-trace"
$config = @"
buffers { size_kb: 131072 fill_policy: RING_BUFFER }
buffers { size_kb: 4096 fill_policy: RING_BUFFER }
duration_ms: $($DurationSeconds * 1000)
data_sources {
  config {
    name: "linux.ftrace"
    target_buffer: 0
    ftrace_config {
      buffer_size_kb: 1024
      drain_period_ms: 250
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_waking"
      ftrace_events: "power/cpu_frequency"
      ftrace_events: "power/cpu_idle"
      ftrace_events: "sched/sched_process_exit"
      atrace_categories: "gfx"
      atrace_categories: "view"
      atrace_categories: "wm"
      atrace_categories: "am"
      atrace_categories: "video"
      atrace_categories: "hal"
      atrace_categories: "dalvik"
      atrace_categories: "binder_driver"
      atrace_apps: "dev.ticbox.companion"
    }
  }
}
data_sources {
  config {
    name: "linux.process_stats"
    target_buffer: 1
    process_stats_config { scan_all_processes_on_start: true }
  }
}
data_sources {
  config {
    name: "android.surfaceflinger.frametimeline"
    target_buffer: 0
  }
}
data_sources {
  config {
    name: "android.log"
    target_buffer: 1
    android_log_config { filter_tags: "TicBoxProtocol" }
  }
}
"@
$configPath = Join-Path $outputRoot 'trace-config.pbtxt'
[IO.File]::WriteAllText($configPath, $config, $utf8)
$manifest = [ordered]@{
    runId = $runId; durationSeconds = $DurationSeconds; requestedUtc = [DateTime]::UtcNow.ToString('o')
    captures = @(); errors = @()
    limitations = @(
        'Each device records for its own requested duration; the two starts are not atomic.'
        'Keep the same screen settings and frame statistics enabled. Reproduce while both jobs run.'
        'System scheduling is observed; vendor codec internals and Wi-Fi retransmissions may be unavailable.'
        'A missing track does not prove absence. Inspect Perfetto data loss and unsupported-source diagnostics.'
        'This is a profiling run with some overhead; traces contain process/package names and scheduling metadata.'
    )
}
function Save-TraceManifest {
    [IO.File]::WriteAllText((Join-Path $outputRoot 'manifest.json'), ($manifest | ConvertTo-Json -Depth 8), $utf8)
}
$recordings = @()
try {
    foreach ($device in $devices) {
        $null = Invoke-TraceAdb $device.Serial @('push', $configPath, $remoteConfig)
        $info = Invoke-TraceAdb $device.Serial @('shell', 'getprop', 'ro.build.fingerprint')
        [IO.File]::WriteAllText((Join-Path $outputRoot ($device.Role + '-build.txt')), $info, $utf8)
        # Foreground Perfetto exits only when its recording has finished. Its own duration bounds
        # device work even if this host/job is interrupted. Binary traces are never piped as text.
        $traceJob = Start-Job -ArgumentList $adbExecutable, $device.Serial, $remoteConfig, $remoteTrace -ScriptBlock {
            param($Executable, $DeviceSerial, $ConfigPath, $TracePath)
            $ErrorActionPreference = 'Continue'
            $started = [DateTime]::UtcNow.ToString('o')
            $lines = @(& $Executable -s $DeviceSerial shell perfetto --txt -c $ConfigPath -o $TracePath 2>&1)
            $exitCode = $LASTEXITCODE
            [pscustomobject]@{
                ExitCode = $exitCode; StartedUtc = $started; FinishedUtc = [DateTime]::UtcNow.ToString('o')
                Text = (($lines | ForEach-Object { $_.ToString() }) -join "`n")
            }
        }
        $recordings += [pscustomobject]@{ Device = $device; Job = $traceJob }
    }
    Write-Host "Both capture jobs launched. Reproduce the stutter now, with the same settings. Each records $DurationSeconds seconds."
    Write-Host 'Include continuous swipes, release/inertial scrolling, then a short still interval. Wait until this script finishes.'
    $deadline = [DateTime]::UtcNow.AddSeconds($DurationSeconds + 45)
    while (@($recordings | Where-Object { $_.Job.State -in @('NotStarted', 'Running') }).Count -gt 0) {
        if ([DateTime]::UtcNow -ge $deadline) { throw 'Host capture deadline exceeded. Android captures have their own duration limit.' }
        Start-Sleep -Seconds 1
    }
    foreach ($recording in $recordings) {
        $device = $recording.Device
        try {
            $result = Receive-Job -Job $recording.Job -ErrorAction Stop
            if ($null -eq $result) { throw 'No Perfetto command result.' }
            [IO.File]::WriteAllText((Join-Path $outputRoot ($device.Role + '-perfetto.txt')), $result.Text, $utf8)
            if ($result.ExitCode -ne 0) {
                # Preserve the complete output in the file and show a bounded diagnostic here.
                $detail = ([string]$result.Text).Trim()
                if ($detail.Length -eq 0) { $detail = '(Perfetto returned no diagnostic text.)' }
                if ($detail.Length -gt 4096) { $detail = $detail.Substring(0, 4096) + "`n[See the saved file for the remaining output.]" }
                throw "Perfetto exit $($result.ExitCode). See $($device.Role)-perfetto.txt.`n$detail"
            }
            $fileName = $device.Role + '.perfetto-trace'
            $destination = Join-Path $outputRoot $fileName
            $null = Invoke-TraceAdb $device.Serial @('pull', $remoteTrace, $destination)
            if ((Get-Item -LiteralPath $destination).Length -eq 0) { throw 'Empty trace.' }
            $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
            $remoteHash = Invoke-TraceAdb $device.Serial @('shell', 'sha256sum', $remoteTrace)
            if (-not $remoteHash.StartsWith($hash + ' ')) { throw 'Trace transfer digest mismatch.' }
            $manifest.captures += [ordered]@{
                role = $device.Role; file = $fileName; sha256 = $hash
                bytes = (Get-Item -LiteralPath $destination).Length
                commandStartedUtc = $result.StartedUtc; commandFinishedUtc = $result.FinishedUtc
            }
            # Only our exact two paths, only after a finished recording and verified local copy.
            $null = Invoke-TraceAdb $device.Serial @('shell', 'rm', '-f', $remoteConfig, $remoteTrace)
            Write-Host "$($device.Role): saved $fileName"
        } catch { $manifest.errors += "$($device.Role): $($_.Exception.Message)" }
    }
    Save-TraceManifest
    if ($manifest.errors.Count -gt 0) { throw ($manifest.errors -join "`n") }
    Write-Host "Saved both traces and manifest to $outputRoot"
} catch {
    $manifest.errors += $_.Exception.Message
    Save-TraceManifest
    Write-Warning "Partial results are in $outputRoot. Owned Android captures stop at their configured duration; no system-wide tracing reset is issued."
    throw
} finally {
    foreach ($recording in $recordings) {
        if ($recording.Job.State -in @('NotStarted', 'Running')) { Stop-Job -Job $recording.Job }
        Remove-Job -Job $recording.Job
    }
}
