<#
.SYNOPSIS
    Captures the auth cover page (signed out) across all four palettes and
    drives the real no-network failure path, for the derived-surface PO
    review gate (AC4) and the AC2 failure-drive evidence.

.DESCRIPTION
    For each palette: seeds it via the debug ThemePrefReceiver, cold-starts
    the signed-out app so the session gate lands on the editorial cover, and
    screencaps the idle cover. Then, on the last palette, disables the
    network (svc wifi/data off), taps the sign-in affordance so the real
    GetCredentialException failure path renders the editorial error notice,
    screencaps it, and restores the network.

    The app must cold-start SIGNED OUT: run against a device whose Firebase
    session is cleared (or the debug emulator gate enabled without a prior
    fixture sign-in). This script does NOT broadcast DebugAuthReceiver —
    the fixture sign-in / Home landing is the Maestro flow's job
    (editorial-auth-signin.yaml).

    Prerequisites: a booted emulator/device (API 29+, Play services) with the
    debug APK installed (./gradlew :app:installDebug).

.EXAMPLE
    powershell android/scripts/capture-auth-palettes.ps1 -OutDir ../.ai/workflows/editorial-reader-redesign/verify-evidence/auth-screen
#>
param(
    [string]$OutDir = '.',
    [int]$SettleSeconds = 2
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
    Write-Host "== $palette : seeding palette + cold-starting the signed-out cover"
    Invoke-Adb @('shell', 'am', 'broadcast',
        '-n', "$AppId/.debug.ThemePrefReceiver",
        '-a', "$AppId.debug.SET_PALETTE",
        '--es', 'palette', $palette)
    Invoke-Adb @('shell', 'am', 'force-stop', $AppId)
    Start-Sleep -Seconds 1
    Invoke-Adb @('shell', 'am', 'start', '-W', '-n', $Activity)
    Start-Sleep -Seconds $SettleSeconds
    Save-Screencap "auth-cover-idle-$palette"
}

Write-Host '== Failure path: airplane-mode sign-in attempt (real GetCredentialException)'
try {
    Invoke-Adb @('shell', 'svc', 'wifi', 'disable')
    Invoke-Adb @('shell', 'svc', 'data', 'disable')
    Start-Sleep -Seconds 2
    # Cold-start the signed-out cover (Cream), then tap the sign-in affordance
    # (resource-id auth-action, via testTagsAsResourceId) so the credential
    # request fails with no network and the editorial error notice renders.
    # The tap is driven by the operator or a Maestro `tapOn: { id: auth-action }`
    # step immediately after this point — screencap once the notice is up.
    Invoke-Adb @('shell', 'am', 'force-stop', $AppId)
    Invoke-Adb @('shell', 'am', 'start', '-W', '-n', $Activity)
    Start-Sleep -Seconds $SettleSeconds
    Read-Host 'Tap "Continue with Google" now; press Enter once the error notice shows'
    Save-Screencap 'auth-cover-failed-network'
}
finally {
    Write-Host '== Restoring network'
    Invoke-Adb @('shell', 'svc', 'wifi', 'enable')
    Invoke-Adb @('shell', 'svc', 'data', 'enable')
}

Write-Host ''
Write-Host "Done. Review the 4 idle covers + the failed-state screencap in $OutDir"
Write-Host 'PO gate (AC4): each cover must read as the editorial cover page in its palette,'
Write-Host '  composed only from existing primitives; the failed state is the editorial error'
Write-Host '  notice (accent top rule + italic message + Try again) — never a Material snackbar.'
