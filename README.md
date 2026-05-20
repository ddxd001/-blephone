# BlePhone (Android Mock v1)

这是一个先基于 Mock 数据实现的安卓上位机原型，核心能力：

- 模拟蓝牙连接状态流转（未连接/扫描中/连接中/已连接）
- 16 点足压可视化（脚型 + 点位）
- 每个点显示力值（0~100）并按力度深浅变色
- 支持 5 种 Mock 场景（站立、行走、前掌重心、后跟重心、单点压测）

## 目录

- `app/src/main/java/com/blephone/MainActivity.kt`：当前首版全部逻辑
- `app/src/main/AndroidManifest.xml`：应用入口配置
- `scripts/mock_pressure_stream.py`：独立仿真数据流脚本（JSONL）
- `docs/DEV_ENV_SETUP.md`：开发环境搭建清单与一键安装说明

## 运行项目

1. 安装 Android Studio（建议 Hedgehog+）并准备 Android SDK 34。
2. 在项目根目录运行：
   - `chmod +x ./gradlew`
   - `./gradlew assembleDebug`
3. 安装 APK：
   - `./gradlew installDebug`

## 运行仿真脚本

- 输出 20 帧站立数据（10Hz）：
  - `python3 scripts/mock_pressure_stream.py --mode stand --hz 10 --frames 20`
- 输出行走循环数据：
  - `python3 scripts/mock_pressure_stream.py --mode walk --hz 10`
- 单点压测（第12点）：
  - `python3 scripts/mock_pressure_stream.py --mode single --single-id 12 --frames 30`

脚本每行输出一帧 JSON，字段包含 `ts/seq/deviceId/mode/points[16]`，可直接作为 App 数据源输入。

## 说明

当前仓库为最小可读的开发起步版本，便于快速验证交互与可视化规则。  
建议下一步拆分为 `ui/`, `data/`, `mock/`, `bluetooth/` 模块化结构，并接入真实蓝牙数据源。
