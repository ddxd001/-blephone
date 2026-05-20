# BlePhone 开发环境搭建（macOS）

本文档总结了本项目从零开始开发所需的工具、环境变量和推荐版本，适用于 Apple Silicon（M 系列）macOS。

## 1. 必备工具清单

### 基础工具

- `Homebrew`：包管理器，用于安装 JDK 和 Android 工具。
- `Git`：代码拉取与版本管理。
- `zsh`：默认 Shell（用于加载环境变量）。

### Android 开发工具

- `JDK 17`：Gradle 与 Android 构建所需 Java 版本。
- `Android SDK Command-line Tools`：`sdkmanager`、`avdmanager`。
- `Android SDK Platform Tools`：`adb` 等调试工具。
- `Android SDK Build Tools 34.0.0`：APK 构建工具链。
- `Android Platform 34`：`platforms;android-34`。
- `Android Emulator`：本地模拟器运行环境。
- `Android System Image`：`system-images;android-34;google_apis;arm64-v8a`。

### 可选工具

- `Android Studio`（建议 Hedgehog 及以上）：用于图形化管理 SDK、模拟器和调试。
- `Python 3`：运行仓库中的压测/仿真脚本（`scripts/mock_pressure_stream.py`）。

## 2. 环境变量要求

将以下配置写入 `~/.zshrc`：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"
```

完成后执行：

```bash
source ~/.zshrc
```

## 3. SDK 组件要求（项目最低集合）

使用 `sdkmanager` 确保安装以下组件：

- `platform-tools`
- `emulator`
- `platforms;android-34`
- `build-tools;34.0.0`
- `system-images;android-34;google_apis;arm64-v8a`

并建议先接受 license：

```bash
yes | sdkmanager --licenses
```

## 4. 一键安装方式（推荐）

项目根目录已提供脚本：

```bash
./install_android_oneclick.sh
```

脚本会自动完成以下动作：

1. 检查并安装 `Homebrew`。
2. 安装 `openjdk@17` 与 Android 命令行工具。
3. 写入 `~/.zshrc` 环境变量。
4. 准备 `cmdline-tools/latest`。
5. 安装 SDK 34 相关组件并接受 licenses。
6. 创建并启动默认模拟器 `blephone_api34`。
7. 若当前目录包含 `./gradlew`，自动尝试 `installDebug` 并启动 App。

## 5. 验证步骤

在项目根目录执行：

```bash
chmod +x ./gradlew
./gradlew assembleDebug
./gradlew installDebug
```

确认设备已连接：

```bash
adb devices
```

手动拉起应用（如需）：

```bash
adb shell am start -n com.blephone/.MainActivity
```

## 6. 常见问题

- `sdkmanager: command not found`  
  通常是 `PATH` 未生效，重新执行 `source ~/.zshrc`，并确认 `cmdline-tools/latest/bin` 存在。

- `No Java runtime present` 或 JDK 版本不匹配  
  确认 `JAVA_HOME` 指向 JDK 17，并执行 `java -version` 检查。

- 模拟器无法启动或卡顿  
  优先使用 `arm64-v8a` 系统镜像；关闭其他占资源应用后重试。

## 7. 版本基线（建议）

- macOS：13+（Apple Silicon 优先）
- JDK：17
- Android API：34
- Build Tools：34.0.0
- Gradle Wrapper：以仓库内 `gradlew` 为准
