<#
.SYNOPSIS
    Cold-starts the seeded app, opens an episode's Transcript, and screencaps the
    article + Masthead-strip mini-embed at the 412x892 reference viewport for the
    Transcript pixel gate (AC7) against the Claude_Browser-rendered mock Transcript.

.DESCRIPTION
    Assumes the fixture-auth harness is live and the editorial corpus is seeded
    (see android/maestro/editorial-transcript-sync.yaml prerequisites): the
    Firebase Auth+Firestore emulators running, seed-auth-emulator.sh +
    seed-editorial-corpus.sh applied (with a seeded transcript carrying a
    highlight + a note so the marginalia renders), the debug APK installed, the
    emulator gate enabled, and a fixture sign-in broadcast so the session gate
    lands on Home.

    It cold-starts Home, opens the Player, switches to the Transcript (opening the
    standalone route with the shared embed), lets the Masthead-strip band settle,
    and screencaps the article. The screencap feeds the in-session zai
    ui_diff_check against the mock Transcript render at verify.

    Enumerated deviations against the mock (pre-agreed, C1): the mini-player pill's
    single sanctioned drop shadow; the PO-picked Masthead-strip mini-embed band
    (the mock drew no video here — the derived Option-1 surface, Probe B); the note
    affordance in the AppBar right slot (the mock drew a text-size icon; type sizing
    lives in Settings); plus real-data differences (real title/channel, real
    timestamps). Density/scale/tolerance were calibrated at home-screen; this reuses
    them.

    Prerequisites: a booted emulator/device (API 29+) with the debug APK installed
    and the fixture session established, per the flow prerequisites.

.EXAMPLE
    powershell android/scripts/capture-transcript-pixelgate.ps1 -OutDir ./transcript-screencaps
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

Write-Host "== cold-starting Home, then opening the Transcript ($OpenNodeId)"
Invoke-Adb @('shell', 'am', 'force-stop', $AppId)
Start-Sleep -Seconds 1
Invoke-Adb @('shell', 'am', 'start', '-W', '-n', $Activity)
Start-Sleep -Seconds $SettleSeconds

Tap-Node -ById $OpenNodeId
Start-Sleep -Seconds $SettleSeconds

# Open the standalone Transcript route from the Player's Transcript tab.
Tap-Node -ByText 'Transcript'
Start-Sleep -Seconds $SettleSeconds
Save-Screencap 'transcript-article'

Write-Host ''
Write-Host 'Done. Diff transcript-article.png against the Claude_Browser mock Transcript render'
Write-Host '  (editorial article at 412x892) via zai ui_diff_check.'
Write-Host 'AC7 pass: dateline / timestamped serif paragraphs / highlighter span / accent-ruled'
Write-Host '  marginalia / mini-player pill match, with the Masthead-strip mini-embed band + the'
Write-Host '  note affordance + the pill shadow as the enumerated deviations.'
