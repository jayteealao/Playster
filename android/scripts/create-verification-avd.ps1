<#
.SYNOPSIS
    Provisions the pinned Android Virtual Device used for reproducible app verification.

.DESCRIPTION
    Creates the canonical verification AVD for this project:
      name:     playster-verify-pixel6-api34
      profile:  pixel_6           (412dp-wide device class)
      image:    system-images;android-34;google_apis;x86_64
                (Google APIs image: Play services available for real-account
                 sign-in, while the image stays adb-writable — unlike the
                 Play Store images.)

    Every emulator-backed check (Maestro flows, screenshot comparisons) should run
    on this profile so results are comparable across machines and over time.

    Idempotent: re-running skips work that is already done.

.NOTES
    Requires the Android SDK command-line tools (sdkmanager/avdmanager) and takes
    ANDROID_HOME / ANDROID_SDK_ROOT or the default Windows SDK path.
#>

$ErrorActionPreference = "Stop"

$AvdName = "playster-verify-pixel6-api34"
$SystemImage = "system-images;android-34;google_apis;x86_64"
$DeviceProfile = "pixel_6"

# --- Locate the SDK -----------------------------------------------------------
$SdkRoot = $env:ANDROID_HOME
if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_SDK_ROOT }
if (-not $SdkRoot) { $SdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
if (-not (Test-Path $SdkRoot)) {
    throw "Android SDK not found. Set ANDROID_HOME or install the SDK at $SdkRoot."
}

$SdkManager = Join-Path $SdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
$AvdManager = Join-Path $SdkRoot "cmdline-tools\latest\bin\avdmanager.bat"
$Emulator = Join-Path $SdkRoot "emulator\emulator.exe"

foreach ($tool in @($SdkManager, $AvdManager)) {
    if (-not (Test-Path $tool)) {
        throw "Missing $tool - install the Android SDK command-line tools (latest)."
    }
}

# --- Install the pinned system image (skips if present) ------------------------
$imageDir = Join-Path $SdkRoot ($SystemImage -replace ";", "\")
if (Test-Path $imageDir) {
    Write-Host "System image already installed: $SystemImage"
} else {
    Write-Host "Installing system image: $SystemImage (this downloads ~1.5 GB)..."
    & $SdkManager --install $SystemImage
    if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed installing $SystemImage" }
}

# --- Create the AVD (skips if present) -----------------------------------------
$existing = & $Emulator -list-avds 2>$null
if ($existing -contains $AvdName) {
    Write-Host "AVD already exists: $AvdName"
} else {
    Write-Host "Creating AVD '$AvdName' on device profile '$DeviceProfile'..."
    # echo "no" declines the custom hardware profile prompt.
    "no" | & $AvdManager create avd --name $AvdName --package $SystemImage --device $DeviceProfile
    if ($LASTEXITCODE -ne 0) { throw "avdmanager failed creating $AvdName" }
}

Write-Host ""
Write-Host "Done. Launch with:"
Write-Host "  `"$Emulator`" -avd $AvdName"
Write-Host "Headless (CI/agent) variant:"
Write-Host "  `"$Emulator`" -avd $AvdName -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect"
