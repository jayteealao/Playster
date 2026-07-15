<#
.SYNOPSIS
    Cold-starts the seeded app, opens a playlist volume, and screencaps its three
    tabs (Episodes / Summary / Notes) at the 412x892 reference viewport for the
    Playlist pixel gate (AC4) against the Claude_Browser-rendered mock Playlist.

.DESCRIPTION
    Assumes the fixture-auth harness is live and the editorial corpus is seeded
    (see android/maestro/cached-summary-navigation.yaml prerequisites): the
    Firebase Auth+Firestore emulators running, seed-auth-emulator.sh +
    seed-editorial-corpus.sh applied (ed-p1 + ed-p2 disjoint episodes), the debug
    APK installed, the emulator gate enabled, and a fixture sign-in broadcast so
    the session gate lands on Home.

    It cold-starts Home, taps the ed-p1 shelf row (bounds resolved from a
    uiautomator dump so no coordinate is hard-coded), lets the volume settle, and
    screencaps the Episodes tab; then taps the Summary and Notes tab labels and
    screencaps each. The screencaps feed the in-session zai ui_diff_check against
    the mock Playlist render at verify; the enumerated real-data deviations
    (derived dek, hours/tag/volume, per-episode durations, note timestamps) go on
    the C1 list.

    Density/scale/tolerance were calibrated at home-screen; this reuses them and
    adds only the per-tab captures.

    Prerequisites: a booted emulator/device (API 29+) with the debug APK
    installed and the fixture session established, per the flow prerequisites.

.EXAMPLE
    powershell android/scripts/capture-playlist-pixelgate.ps1 -OutDir ./playlist-screencaps
#>
param(
    [string]$OutDir = '.',
    [string]$PlaylistRowId = 'home-shelf-row-ed-p1',
    [int]$SettleSeconds = 3
)

$ErrorActionPreference = 'Stop'
$AppId = 'com.github.jayteealao.playster'
$Activity = "$AppId/.MainActivity"

function Invoke-Adb {
    param([string[]]$AdbArgs)
    & adb @AdbArgs
    if ($LASTEXITCODE -ne 0) { throw "adb $($AdbArgs -join ' ') failed ($LASTEXITCODE)" }
}

function Save-Screencap {
    param([string]$Name)
    $device = "/data/local/tmp/$Name.png"
    $local = Join-Path $OutDir "$Name.png"
    Invoke-Adb @('shell', 'screencap', '-p', $device)
    Invoke-Adb @('pull', $device, $local)
    Invoke-Adb @('shell', 'rm', $device)
    Write-Host "  saved $local"
}

# Tap the centre of the first UI node whose resource-id (testTag) or text matches.
# Bounds come from a uiautomator dump so nothing is hard-coded to a resolution.
function Tap-Node {
    param([string]$ById, [string]$ByText)
    Invoke-Adb @('shell', 'uiautomator', 'dump', '/data/local/tmp/ui.xml') | Out-Null
    $xml = & adb shell cat /data/local/tmp/ui.xml
    $pattern = if ($ById) { "resource-id=`"$ById`"" } else { "text=`"$ByText`"" }
    $node = ($xml -split '<node') | Where-Object { $_ -match [regex]::Escape($pattern) } | Select-Object -First 1
    if (-not $node -or $node -notmatch 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
        throw "node not found: $pattern"
    }
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    Invoke-Adb @('shell', 'input', 'tap', "$x", "$y")
    Start-Sleep -Milliseconds 600
}

if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }

Write-Host "== cold-starting Home, then opening the volume ($PlaylistRowId)"
Invoke-Adb @('shell', 'am', 'force-stop', $AppId)
Start-Sleep -Seconds 1
Invoke-Adb @('shell', 'am', 'start', '-W', '-n', $Activity)
Start-Sleep -Seconds $SettleSeconds

Tap-Node -ById $PlaylistRowId
Start-Sleep -Seconds $SettleSeconds
Save-Screencap 'playlist-episodes'

Tap-Node -ByText 'Summary'
Save-Screencap 'playlist-summary'

Tap-Node -ByText 'Notes'
Save-Screencap 'playlist-notes'

Write-Host ''
Write-Host 'Done. Diff playlist-episodes.png against the Claude_Browser mock Playlist render'
Write-Host '  (editorial/screens-1.jsx PlaylistScreen at 412x892) via zai ui_diff_check.'
Write-Host 'AC4 pass: cover / tabs / episode rows / drop-cap Summary / Folio match, only'
Write-Host '  enumerated real-data deviations (derived dek, hours/tag/volume, durations, notes).'
