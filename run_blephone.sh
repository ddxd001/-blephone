#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_ROOT="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
EMULATOR_BIN="$SDK_ROOT/emulator/emulator"
ADB_BIN="$SDK_ROOT/platform-tools/adb"
AVD_NAME="${1:-blephone_api34}"

if [ ! -x "$EMULATOR_BIN" ]; then
  echo "未找到 emulator: $EMULATOR_BIN"
  exit 1
fi

if [ ! -x "$ADB_BIN" ]; then
  echo "未找到 adb: $ADB_BIN"
  exit 1
fi

echo "==> 检查 AVD: $AVD_NAME"
if ! "$EMULATOR_BIN" -list-avds | grep -q "^${AVD_NAME}$"; then
  echo "AVD 不存在: $AVD_NAME"
  echo "可用 AVD:"
  "$EMULATOR_BIN" -list-avds || true
  exit 1
fi

echo "==> 检查已连接设备"
DEVICE_COUNT=$("$ADB_BIN" devices | awk 'NR>1 && $2=="device" {count++} END {print count+0}')
if [ "$DEVICE_COUNT" -eq 0 ]; then
  echo "==> 未检测到设备，启动模拟器: $AVD_NAME"
  nohup "$EMULATOR_BIN" -avd "$AVD_NAME" >/tmp/"$AVD_NAME".log 2>&1 &
  echo "==> 等待模拟器启动"
  "$ADB_BIN" wait-for-device
  until [ "$("$ADB_BIN" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 2
  done
fi

echo "==> 安装应用"
cd "$PROJECT_DIR"
./gradlew installDebug

echo "==> 启动应用"
"$ADB_BIN" shell am start -n com.blephone/.MainActivity

echo "完成：应用已安装并启动。"
