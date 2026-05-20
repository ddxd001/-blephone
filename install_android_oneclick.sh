#!/usr/bin/env bash
set -euo pipefail

echo "==> [1/9] 检查 Homebrew"
if ! command -v brew >/dev/null 2>&1; then
  echo "未检测到 Homebrew，开始安装..."
  /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
fi

echo "==> [2/9] 安装 JDK17 与 Android 命令行工具"
brew install openjdk@17
if ! brew install --cask android-commandlinetools; then
  echo "Homebrew cask 安装失败，改用 Google 官方包兜底安装"
fi

echo "==> [3/9] 配置环境变量到 ~/.zshrc"
if ! grep -q 'ANDROID_SDK_ROOT' "$HOME/.zshrc" 2>/dev/null; then
  cat >> "$HOME/.zshrc" <<'EOF'

# Android SDK (auto-added)
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"
EOF
fi

export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"

echo "==> [4/9] 准备 cmdline-tools 目录"
mkdir -p "$ANDROID_HOME/cmdline-tools"
if [ -d "$(brew --prefix)/share/android-commandlinetools" ]; then
  ln -sfn "$(brew --prefix)/share/android-commandlinetools" "$ANDROID_HOME/cmdline-tools/latest"
else
  TMP_DIR="$(mktemp -d)"
  TOOL_ZIP="$TMP_DIR/cmdline-tools.zip"
  TOOL_URL="https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip"
  curl -L --retry 3 --connect-timeout 20 -o "$TOOL_ZIP" "$TOOL_URL"
  unzip -q -o "$TOOL_ZIP" -d "$TMP_DIR"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mkdir -p "$ANDROID_HOME/cmdline-tools/latest"
  cp -R "$TMP_DIR/cmdline-tools/"* "$ANDROID_HOME/cmdline-tools/latest/"
fi

echo "==> [5/9] 接受 Android licenses"
yes | sdkmanager --licenses >/dev/null || true

echo "==> [6/9] 安装 Android SDK 组件"
sdkmanager \
  "platform-tools" \
  "emulator" \
  "platforms;android-34" \
  "build-tools;34.0.0" \
  "system-images;android-34;google_apis;arm64-v8a"

echo "==> [7/9] 创建 AVD（如已存在则跳过）"
AVD_NAME="blephone_api34"
if ! avdmanager list avd | grep -q "Name: ${AVD_NAME}"; then
  echo "no" | avdmanager create avd -n "$AVD_NAME" -k "system-images;android-34;google_apis;arm64-v8a"
fi

echo "==> [8/9] 启动模拟器"
nohup emulator -avd "$AVD_NAME" >/tmp/"$AVD_NAME".log 2>&1 &

echo "==> [9/9] 等待设备启动并安装项目（如果当前目录有 blephone）"
adb wait-for-device || true
sleep 8

if [ -f "./gradlew" ]; then
  chmod +x ./gradlew
  ./gradlew installDebug || true
  adb shell am start -n com.blephone/.MainActivity || true
  echo "已尝试安装并启动 com.blephone"
else
  echo "当前目录没有发现 ./gradlew，已完成 Android 环境安装。"
  echo "请 cd 到项目目录后执行："
  echo "  ./gradlew installDebug"
  echo "  adb shell am start -n com.blephone/.MainActivity"
fi

echo "完成。新终端可执行：source ~/.zshrc"
