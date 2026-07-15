<#
.SYNOPSIS
    Cold-starts the seeded Home screen and screencaps it at the 412x892
    reference viewport, for the first full-screen pixel gate (AC3) against the
    Claude_Browser-rendered mock Home.

.DESCRIPTION
    Assumes the fixture-auth harness is live and the editorial corpus is seeded
    (see android/maestro/editorial-home-shelf.yaml prerequisites): the Firebase
    Auth+Firestore emulators running, seed-auth-emulator.sh + seed-editorial-
    corpus.sh applied, the debug APK installed, the emulator gate enabled, and
    a fixture sign-in broadcast so the session gate lands on Home.

    For each palette it seeds the theme via the debug ThemePrefReceiver, cold-
    starts the app (landing signed-in on Home), lets the seeded shelf settle,
    and screencaps. The screencaps feed the in-session zai ui_diff_check against
    the mock Home render at verify; the enumerated real-data deviations
    (issue no., derived hours/tag/volume/dek, unread aggregate) go on the C1 list.

    Prerequisites: a booted emulator/device (API 29+) with the debug APK
    installed and the fixture session established.

.EXAMPLE
    powershell android/scripts/capture-home-pixelgate.ps1 -OutDir ../.ai/workflows/editorial-reader-redesign/verify-evidence/home-screen
#>
param(
    [string]$OutDir = '.',
    [int]$SettleSeconds = 3
)

$ErrorActionPreference = 'Stop'
$AppId = 'com.github.jayteealao.playster'
$Activity = "$AppId/.MainActivity"
$Palettes = @('cream', 'vellum', 'newsprint', 'night')

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

if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }

foreach ($palette in $Palettes) {
    Write-Host "== $palette : seeding palette + cold-starting seeded Home"
    Invoke-Adb @('shell', 'am', 'broadcast',
        '-n', "$AppId/.debug.ThemePrefReceiver",
        '-a', "$AppId.debug.SET_PALETTE",
        '--es', 'palette', $palette)
    Invoke-Adb @('shell', 'am', 'force-stop', $AppId)
    Start-Sleep -Seconds 1
    Invoke-Adb @('shell', 'am', 'start', '-W', '-n', $Activity)
    Start-Sleep -Seconds $SettleSeconds
    Save-Screencap "home-seeded-$palette"
}

Write-Host ''
Write-Host "Done. Diff home-seeded-cream.png against the Claude_Browser mock Home render"
Write-Host '  (editorial/screens-1.jsx HomeScreen at 412x892) via zai ui_diff_check.'
Write-Host 'AC3 pass: layout / type ramp / spacing / rules match, only enumerated real-data'
Write-Host '  deviations (issue no., derived hours/tag/volume/dek, unread aggregate).'
