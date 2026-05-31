$ErrorActionPreference = "Stop"

$ProjectDir = $PSScriptRoot
$RepoDir = Split-Path -Parent $ProjectDir
$ToolsDir = Join-Path $RepoDir "tools"
$DownloadsDir = Join-Path $ToolsDir "downloads"
$JdkDir = Join-Path $ToolsDir "jdk17"
$SdkRoot = Join-Path $ToolsDir "android-sdk"
$AvdHome = Join-Path $ToolsDir "avd"
$CmdlineLatest = Join-Path $SdkRoot "cmdline-tools\latest"

New-Item -ItemType Directory -Force -Path $ToolsDir, $DownloadsDir, $SdkRoot, $AvdHome | Out-Null

function Add-Path-For-Process($path) {
    if ($env:Path -notlike "*$path*") {
        $env:Path = "$path;$env:Path"
    }
}

function Download-File($url, $output) {
    if (Test-Path $output) {
        Write-Host "==> Exists: $output"
        return
    }
    Write-Host "==> Download: $url"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $lastError = $null
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        try {
            if (Test-Path $output) {
                Remove-Item -Force $output
            }
            Write-Host "==> Download attempt $attempt/5"
            Invoke-WebRequest -Uri $url -OutFile $output -UseBasicParsing
            return
        } catch {
            $lastError = $_
            Write-Host "==> Download failed: $($_.Exception.Message)"
            Start-Sleep -Seconds (5 * $attempt)
        }
    }
    throw $lastError
}

function Expand-SingleRootZip($zipPath, $targetDir) {
    $tmp = Join-Path $DownloadsDir ([IO.Path]::GetFileNameWithoutExtension($zipPath))
    if (Test-Path $tmp) {
        Remove-Item -Recurse -Force $tmp
    }
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    Expand-Archive -Path $zipPath -DestinationPath $tmp -Force

    $root = Get-ChildItem -Path $tmp -Directory | Select-Object -First 1
    if (-not $root) {
        throw "Zip has no usable root directory: $zipPath"
    }

    if (Test-Path $targetDir) {
        Remove-Item -Recurse -Force $targetDir
    }
    New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
    Copy-Item -Path (Join-Path $root.FullName "*") -Destination $targetDir -Recurse -Force
}

Write-Host "==> [1/7] Prepare JDK 17"
$JdkZip = Join-Path $DownloadsDir "temurin-jdk17.zip"
$JdkUrl = "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk"
if (-not (Test-Path (Join-Path $JdkDir "bin\java.exe"))) {
    if (Test-Path $JdkZip) {
        Remove-Item -Force $JdkZip
    }
    Download-File $JdkUrl $JdkZip
    Expand-SingleRootZip $JdkZip $JdkDir
}
$env:JAVA_HOME = $JdkDir
Add-Path-For-Process (Join-Path $JdkDir "bin")
& (Join-Path $JdkDir "bin\java.exe") -version

Write-Host "==> [2/7] Prepare Android command-line tools"
$CliZip = Join-Path $DownloadsDir "commandlinetools-win.zip"
$CliUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
$SdkManager = Join-Path $CmdlineLatest "bin\sdkmanager.bat"
if (-not (Test-Path $SdkManager)) {
    if (Test-Path $CliZip) {
        Remove-Item -Force $CliZip
    }
    Download-File $CliUrl $CliZip
    $tmpCli = Join-Path $DownloadsDir "android-cli"
    if (Test-Path $tmpCli) {
        Remove-Item -Recurse -Force $tmpCli
    }
    New-Item -ItemType Directory -Force -Path $tmpCli | Out-Null
    Expand-Archive -Path $CliZip -DestinationPath $tmpCli -Force

    if (Test-Path $CmdlineLatest) {
        Remove-Item -Recurse -Force $CmdlineLatest
    }
    New-Item -ItemType Directory -Force -Path $CmdlineLatest | Out-Null
    Copy-Item -Path (Join-Path $tmpCli "cmdline-tools\*") -Destination $CmdlineLatest -Recurse -Force
}

$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:ANDROID_AVD_HOME = $AvdHome
Add-Path-For-Process (Join-Path $SdkRoot "platform-tools")
Add-Path-For-Process (Join-Path $SdkRoot "emulator")
Add-Path-For-Process (Join-Path $CmdlineLatest "bin")

Write-Host "==> [3/7] Accept Android SDK licenses"
$yes = "y`n" * 100
$yes | & $SdkManager --sdk_root=$SdkRoot --licenses | Out-Host

Write-Host "==> [4/7] Install Android SDK 34 base components"
& $SdkManager --sdk_root=$SdkRoot `
    "platform-tools" `
    "emulator" `
    "platforms;android-34" `
    "build-tools;34.0.0" `
    "system-images;android-34;google_apis;x86_64"

Write-Host "==> [4b/7] Create local AVD if missing"
$AvdManager = Join-Path $CmdlineLatest "bin\avdmanager.bat"
$AvdName = "blephone_api34"
$avdList = & $AvdManager list avd
if (-not ($avdList -match "Name:\s+$AvdName")) {
    "no" | & $AvdManager create avd `
        --name $AvdName `
        --package "system-images;android-34;google_apis;x86_64" `
        --device "pixel_6"
}

Write-Host "==> [5/7] Write current user environment variables"
[Environment]::SetEnvironmentVariable("JAVA_HOME", $JdkDir, "User")
[Environment]::SetEnvironmentVariable("ANDROID_HOME", $SdkRoot, "User")
[Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", $SdkRoot, "User")
[Environment]::SetEnvironmentVariable("ANDROID_AVD_HOME", $AvdHome, "User")

$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
$pathsToAdd = @(
    (Join-Path $JdkDir "bin"),
    (Join-Path $SdkRoot "platform-tools"),
    (Join-Path $SdkRoot "emulator"),
    (Join-Path $CmdlineLatest "bin")
)
foreach ($p in $pathsToAdd) {
    if ($userPath -notlike "*$p*") {
        $userPath = if ([string]::IsNullOrWhiteSpace($userPath)) { $p } else { "$userPath;$p" }
    }
}
[Environment]::SetEnvironmentVariable("Path", $userPath, "User")

Write-Host "==> [6/7] Build Debug APK"
Set-Location $ProjectDir
& .\gradlew.bat assembleDebug

Write-Host "==> [7/7] Environment ready"
Write-Host "JAVA_HOME=$JdkDir"
Write-Host "ANDROID_HOME=$SdkRoot"
Write-Host "ANDROID_AVD_HOME=$AvdHome"
Write-Host "AVD: blephone_api34"
Write-Host "APK: $(Join-Path $ProjectDir 'app\build\outputs\apk\debug\app-debug.apk')"
Write-Host "New PowerShell windows will inherit these user environment variables."
