$ErrorActionPreference = "Stop"

$ProjectDir = $PSScriptRoot
$SdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$EmulatorBin = Join-Path $SdkRoot "emulator\emulator.exe"
$AdbBin = Join-Path $SdkRoot "platform-tools\adb.exe"
$AvdName = if ($args.Count -gt 0) { $args[0] } else { "Pixel_6" }

# Use Android Studio's bundled JDK 17 if JAVA_HOME not already set
if (-not $env:JAVA_HOME) {
    $StudioJbr = if (Test-Path "D:\Program Files\Android\Android Studio\jbr") { "D:\Program Files\Android\Android Studio\jbr" } else { "C:\Program Files\Android\Android Studio\jbr" }
    if (Test-Path $StudioJbr) {
        $env:JAVA_HOME = $StudioJbr
        $env:Path = "$StudioJbr\bin;$env:Path"
        Write-Host "==> 使用 Android Studio 自带 JDK: $StudioJbr"
    }
}

if (-not (Test-Path $EmulatorBin)) {
    Write-Host "未找到 emulator: $EmulatorBin"
    Write-Host "请确认 Android SDK 已安装，或设置 ANDROID_HOME 环境变量"
    exit 1
}
if (-not (Test-Path $AdbBin)) {
    Write-Host "未找到 adb: $AdbBin"
    exit 1
}

Write-Host "==> 检查 AVD: $AvdName"
$avdList = & $EmulatorBin -list-avds
if (-not ($avdList -contains $AvdName)) {
    Write-Host "AVD 不存在: $AvdName"
    Write-Host "可用 AVD:"
    $avdList | ForEach-Object { Write-Host "  $_" }
    Write-Host "请在 Android Studio 的 Device Manager 中创建一个 AVD"
    exit 1
}

Write-Host "==> 检查已连接设备"
$deviceLines = & $AdbBin devices | Select-Object -Skip 1 | Where-Object { $_ -match "device$" }
if ($deviceLines.Count -eq 0) {
    Write-Host "==> 未检测到设备，启动模拟器: $AvdName"
    Start-Process -FilePath $EmulatorBin -ArgumentList "-avd",$AvdName -WindowStyle Hidden
    Write-Host "==> 等待模拟器启动..."
    & $AdbBin wait-for-device
    while ((& $AdbBin shell getprop sys.boot_completed 2>$null).Trim() -ne "1") {
        Start-Sleep -Seconds 2
    }
}

Write-Host "==> 编译 + 安装应用"
Set-Location $ProjectDir
& .\gradlew.bat installDebug
if ($LASTEXITCODE -ne 0) {
    Write-Host "==> Gradle 构建失败"
    exit $LASTEXITCODE
}

Write-Host "==> 启动应用"
& $AdbBin shell am force-stop com.blephone | Out-Null
& $AdbBin shell am start -n com.blephone/.MainActivity

Write-Host "完成：应用已安装并启动。"
