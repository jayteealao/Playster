<#
.SYNOPSIS
    Cold-starts the seeded app, opens an episode's Player, and screencaps the
    article page + each tab at the 412x892 reference viewport for the Player
    pixel gate (AC10) against the Claude_Browser-rendered mock Player.

.DESCRIPTION
    Assumes the fixture-auth harness is live and the editorial corpus is seeded
    (see android/maestro/editorial-player-playback.yaml prerequisites): the
    Firebase Auth+Firestore emulators running, seed-auth-emulator.sh +
    seed-editorial-corpus.sh applied, the debug APK installed, the emulator gate
    enabled, and a fixture sign-in broadcast so the session gate lands on Home.

    It cold-starts Home, taps the continue headliner to open the Player, lets the
    Masthead-band panel settle, and screencaps the Summary view; then taps the
    Chapters and Notes tab labels and screencaps each. The screencaps feed the
    in-session zai ui_diff_check against the mock Player render at verify.

    Enumerated deviations against the mock (pre-agreed, C1): the collapsible
    16:9 video panel (the mock never drew a video surface — the PO-pinned
    Masthead-band derived surface) and any dropped rows, plus real-data
    differences (episode kicker, real title/channel/views, real chapter/note
    timestamps). Density/scale/tolerance were calibrated at home-screen; this
    reuses them and adds only the per-tab captures.

    Prerequisites: a booted emulator/device (API 29+) with the debug APK
    installed and the fixture session established, per the flow prerequisites.

.EXAMPLE
    powershell android/scripts/capture-player-pixelgate.ps1 -OutDir ./player-screencaps
#>
param(
    [string]$OutDir = '.',
    [string]$OpenNodeId = 'home-headliner',
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

Write-Host "== cold-starting Home, then opening the Player ($OpenNodeId)"
Invoke-Adb @('shell', 'am', 'force-stop', $AppId)
Start-Sleep -Seconds 1
Invoke-Adb @('shell', 'am', 'start', '-W', '-n', $Activity)
Start-Sleep -Seconds $SettleSeconds

Tap-Node -ById $OpenNodeId
Start-Sleep -Seconds $SettleSeconds
Save-Screencap 'player-summary'

Tap-Node -ByText 'Chapters'
Save-Screencap 'player-chapters'

Tap-Node -ByText 'Notes'
Save-Screencap 'player-notes'

# Collapsed-panel capture — proves the panel retracts while the embed stays visible.
Tap-Node -ById 'player-panel-collapse'
Start-Sleep -Milliseconds 800
Save-Screencap 'player-panel-collapsed'

Write-Host ''
Write-Host 'Done. Diff player-summary.png against the Claude_Browser mock Player render'
Write-Host '  (editorial article-page Player at 412x892) via zai ui_diff_check.'
Write-Host 'AC10 pass: header / seek bar / speed / tabs / drop-cap Summary / Folio match,'
Write-Host '  with the video panel + any dropped rows as the enumerated deviations.'
