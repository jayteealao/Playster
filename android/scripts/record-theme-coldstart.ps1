<#
.SYNOPSIS
    Records a cold start so the first rendered frames can be reviewed
    frame-by-frame for palette correctness (no wrong-theme flash) —
    including the API 31+ system splash, which follows the saved palette
    once the app has synced its splash theme.

.DESCRIPTION
    Seeds the saved palette via the debug ThemePrefReceiver broadcast,
    launches once so the app persists the palette's splash theme (the OS
    applies a splash-theme override from the NEXT cold start), force-stops,
    starts a screen recording, cold-starts the activity, then pulls the mp4.

    Defaults to the real MainActivity (the editorial chrome). Pass
    -ActivityName '.debug.TokenSampleSheetActivity' to record the
    design-tokens sample sheet instead.

    Prerequisites: a booted emulator/device (API 29+) with the debug APK
    installed (./gradlew :app:installDebug).

.EXAMPLE
    powershell android/scripts/record-theme-coldstart.ps1 -Palette night
    powershell android/scripts/record-theme-coldstart.ps1 -Palette cream -OutDir out
    powershell android/scripts/record-theme-coldstart.ps1 -Palette night -ActivityName '.debug.TokenSampleSheetActivity'
#>
param(
    [ValidateSet('cream', 'vellum', 'newsprint', 'night')]
    [string]$Palette = 'night',
    [string]$OutDir = '.',
    [int]$RecordSeconds = 8,
    [string]$ActivityName = '.MainActivity'
)

$ErrorActionPreference = 'Stop'
$AppId = 'com.github.jayteealao.playster'
$Activity = "$AppId/$ActivityName"
$DeviceFile = "/data/local/tmp/theme-coldstart-$Palette.mp4"
$LocalFile = Join-Path $OutDir "theme-coldstart-$Palette.mp4"

function Invoke-Adb {
    param([string[]]$AdbArgs)
    & adb @AdbArgs
    if ($LASTEXITCODE -ne 0) { throw "adb $($AdbArgs -join ' ') failed ($LASTEXITCODE)" }
}

Write-Host "== Seeding palette '$Palette' via debug broadcast"
Invoke-Adb @('shell', 'am', 'broadcast',
    '-n', "$AppId/.debug.ThemePrefReceiver",
    '-a', "$AppId.debug.SET_PALETTE",
    '--es', 'palette', $Palette)

Write-Host '== Force-stopping the app (cold start required)'
Invoke-Adb @('shell', 'am', 'force-stop', $AppId)
Start-Sleep -Seconds 1

Write-Host "== Recording $RecordSeconds s of screen"
$recorder = Start-Process -FilePath 'adb' -ArgumentList @(
    'shell', 'screenrecord', '--time-limit', "$RecordSeconds", $DeviceFile
) -NoNewWindow -PassThru
Start-Sleep -Seconds 1

Write-Host "== Cold-starting $Activity"
Invoke-Adb @('shell', 'am', 'start', '-W', '-n', $Activity)

Write-Host '== Waiting for the recording to finish'
$recorder.WaitForExit()
Start-Sleep -Seconds 1

Write-Host "== Pulling recording to $LocalFile"
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }
Invoke-Adb @('pull', $DeviceFile, $LocalFile)
Invoke-Adb @('shell', 'rm', $DeviceFile)

Write-Host ''
Write-Host "Done: $LocalFile"
Write-Host 'Review frame-by-frame (e.g. ffmpeg -i <file> -vsync 0 frames/f%04d.png):'
Write-Host '  every app-drawn frame from window creation onward must already be'
Write-Host "  the '$Palette' paper tone - no wrong-palette flash frame."
