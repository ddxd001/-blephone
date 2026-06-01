$ErrorActionPreference = "Stop"

$ProjectDir = $PSScriptRoot
$RepoDir = Split-Path -Parent $ProjectDir
$LocalSdk = Join-Path $RepoDir "tools\android-sdk"
$LocalJdk = Join-Path $RepoDir "tools\jdk17"
$LocalAvd = Join-Path $RepoDir "tools\avd"
$SdkRoot = if ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) { $env:ANDROID_HOME } elseif (Test-Path $LocalSdk) { $LocalSdk } else { "$env:LOCALAPPDATA\Android\Sdk" }
$EmulatorBin = Join-Path $SdkRoot "emulator\emulator.exe"
$AdbBin = Join-Path $SdkRoot "platform-tools\adb.exe"
$AvdName = if ($args.Count -gt 0) { $args[0] } else { "blephone_api34" }

$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
if (Test-Path $LocalAvd) {
    $env:ANDROID_AVD_HOME = $LocalAvd
}

# Use the repo-local JDK first, then Android Studio's bundled JDK 17.
if (Test-Path (Join-Path $LocalJdk "bin\java.exe")) {
    $env:JAVA_HOME = $LocalJdk
    $env:Path = "$(Join-Path $LocalJdk "bin");$env:Path"
    Write-Host "==> Using repo-local JDK: $LocalJdk"
} elseif (-not $env:JAVA_HOME) {
    $StudioJbr = if (Test-Path "D:\Program Files\Android\Android Studio\jbr") { "D:\Program Files\Android\Android Studio\jbr" } else { "C:\Program Files\Android\Android Studio\jbr" }
    if (Test-Path $StudioJbr) {
        $env:JAVA_HOME = $StudioJbr
        $env:Path = "$StudioJbr\bin;$env:Path"
        Write-Host "==> Using Android Studio JDK: $StudioJbr"
    }
}

if (-not (Test-Path $EmulatorBin)) {
    Write-Host "emulator not found: $EmulatorBin"
    Write-Host "Run setup_windows_local.ps1 first, or set ANDROID_HOME."
    exit 1
}
if (-not (Test-Path $AdbBin)) {
    Write-Host "adb not found: $AdbBin"
    exit 1
}

Write-Host "==> Check AVD: $AvdName"
$avdList = & $EmulatorBin -list-avds
if (-not ($avdList -contains $AvdName)) {
    Write-Host "AVD does not exist: $AvdName"
    Write-Host "Available AVDs:"
    $avdList | ForEach-Object { Write-Host "  $_" }
    Write-Host "Run setup_windows_local.ps1 to create blephone_api34."
    exit 1
}

Write-Host "==> Check connected devices"
$deviceLines = & $AdbBin devices | Select-Object -Skip 1 | Where-Object { $_ -match "device$" }
if ($deviceLines.Count -eq 0) {
    Write-Host "==> No device detected, starting emulator: $AvdName"
    Start-Process -FilePath $EmulatorBin -ArgumentList "-avd",$AvdName,"-no-snapshot-load"
    Write-Host "==> Waiting for emulator boot..."
    & $AdbBin wait-for-device
    $bootDeadline = (Get-Date).AddMinutes(3)
    while ((& $AdbBin shell getprop sys.boot_completed 2>$null).Trim() -ne "1") {
        if ((Get-Date) -gt $bootDeadline) {
            Write-Host "==> Emulator boot timed out. The emulator window may still be starting."
            exit 1
        }
        Start-Sleep -Seconds 2
    }
}

Write-Host "==> Build and install app"
Set-Location $ProjectDir
& .\gradlew.bat installDebug
if ($LASTEXITCODE -ne 0) {
    Write-Host "==> Gradle build failed"
    exit $LASTEXITCODE
}

Write-Host "==> Launch app"
& $AdbBin shell am force-stop com.blephone | Out-Null
& $AdbBin shell am start -n com.blephone/.MainActivity

Write-Host "Done: app installed and launched."
