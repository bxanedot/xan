$ErrorActionPreference = 'Stop'
$adbCandidates = @(
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
    (Join-Path $PSScriptRoot 'platform-tools\adb.exe')
)
$adb = $adbCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $adb) { throw 'Android platform-tools were not found. Android Studio SDK Manager can install them.' }

function Invoke-Adb([string[]] $AdbArgs) {
    # Windows PowerShell wraps native stderr as ErrorRecords. ADB writes normal
    # daemon startup messages there, so judge success by its exit code instead.
    # These preferences are local to this function, preserving script failures.
    $ErrorActionPreference = 'Continue'
    $PSNativeCommandUseErrorActionPreference = $false
    $output = @(& $adb @AdbArgs 2>&1 | ForEach-Object { "$_" })
    [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $output }
}

$deviceResult = Invoke-Adb -AdbArgs @('devices')
$devices = $deviceResult.Output
if ($deviceResult.ExitCode -ne 0) { throw ($devices -join "`n") }
$connected = @($devices | Where-Object { "$_" -match '^\S+\s+device$' })
if ($connected.Count -ne 1) {
    Write-Host ($devices -join "`n")
    throw 'Connect one phone with USB debugging enabled and approve the USB debugging prompt on the phone.'
}
$serial = ("$($connected[0])" -split '\s+')[0]
function Read-Phone([string[]] $AdbArgs) {
    $result = Invoke-Adb -AdbArgs (@('-s', $serial) + $AdbArgs)
    if ($result.ExitCode -ne 0) { return @("Command unavailable (exit $($result.ExitCode)): $($AdbArgs -join ' ')") + $result.Output }
    return $result.Output
}

Write-Host 'Start a song in Xan and leave it playing.'
Read-Host 'Press Enter while the notification is missing' | Out-Null

$package = 'app.xan.music'
$report = [System.Collections.Generic.List[string]]::new()
function Add-Section([string] $Name, [string[]] $Lines) {
    $report.Add("`n=== $Name ===")
    foreach ($line in $Lines) { $report.Add($line) }
}
$report.Add("XAN media diagnostics - $(Get-Date -Format o)")
Add-Section 'Phone' (Read-Phone -AdbArgs @('shell', 'getprop', 'ro.product.manufacturer'))
Add-Section 'Model' (Read-Phone -AdbArgs @('shell', 'getprop', 'ro.product.model'))
Add-Section 'Android SDK' (Read-Phone -AdbArgs @('shell', 'getprop', 'ro.build.version.sdk'))
$packageInfo = Read-Phone -AdbArgs @('shell', 'dumpsys', 'package', $package)
Add-Section 'Installed Xan and notification permission' @($packageInfo | Where-Object {
    $_ -match 'versionCode=|versionName=|lastUpdateTime=|POST_NOTIFICATIONS|FOREGROUND_SERVICE|stopped=' 
})
Add-Section 'Xan playback service' (Read-Phone -AdbArgs @('shell', 'dumpsys', 'activity', 'services', "$package/.playback.MusicService"))
Add-Section 'Xan notification records and channels' (Read-Phone -AdbArgs @('shell', 'dumpsys', 'notification', '--package', $package))
Add-Section 'Xan notification app-op' (Read-Phone -AdbArgs @('shell', 'cmd', 'appops', 'get', $package, 'POST_NOTIFICATION'))

# Android does not offer a package filter for media_session. Keep only Xan's
# session blocks and the media-button routing summary in the saved report.
$sessions = Read-Phone -AdbArgs @('shell', 'dumpsys', 'media_session')
$selected = [System.Collections.Generic.List[string]]::new()
for ($i = 0; $i -lt $sessions.Count; $i++) {
    $line = $sessions[$i]
    if ($line -match 'Media button session|Last MediaButtonReceiver') { $selected.Add($line) }
    if ($line -match '^\s*package=app\.xan\.music\b') {
        $indent = $line.Length - $line.TrimStart().Length
        $selected.Add($line)
        for ($j = $i + 1; $j -lt $sessions.Count; $j++) {
            $next = $sessions[$j]
            $nextIndent = $next.Length - $next.TrimStart().Length
            if ($next.Trim().Length -eq 0) { break }
            if ($nextIndent -lt $indent -or $next -match '^\s*package=') { break }
            if ($next -match '^\s*(ownerPid|ownerUid|userId|active|flags|rating type|controllers|state|audioAttrs|volumeType|controlType|maxVolume|currentVolume|volumeProvider|launchIntent|mediaButtonReceiver|queueTitle|queue size)[=:]') {
                $selected.Add($next)
            }
        }
    }
}
if ($selected.Count -eq 0) { $selected.Add('No Xan platform media session found.') }
Add-Section 'Platform media session and headset routing' $selected.ToArray()
$notificationLogs = Read-Phone -AdbArgs @('logcat', '-d', '-t', '1500', '-s', 'NotificationService:V', 'ActivityManager:W', 'MSessionService:V')
Add-Section 'Android messages mentioning Xan' @($notificationLogs | Where-Object { $_ -match 'app\.xan\.music|MusicService' })

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$outputDir = Join-Path $repoRoot 'dist'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$outputFile = Join-Path $outputDir ("XAN-media-diagnostics-$(Get-Date -Format yyyyMMdd-HHmmss).txt")
$report | Set-Content -LiteralPath $outputFile -Encoding UTF8
Write-Host "Saved: $outputFile"
Write-Host 'Attach this text file in Codex. This did not change or reinstall Xan.'
