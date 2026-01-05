# VIVO-Watch-AutoGameMode (GameWatch)
## 赛博乞讨
![PayPal](https://pic.smallway.top/blog/202279-pay.png)

**打破设备界限，让手机与电脑无缝联动，打造沉浸式游戏伴侣。**



VIVO-Watch-AutoGameMode 是一个开源的跨设备游戏状态同步工具。它能够在 PC 端自动检测游戏运行状态，并实时同步到 Android 手机（及连接的手表），自动触发“电竞模式”界面，展示游戏名称与游玩时长。

## ✨ 核心功能

- **🤖 智能识别**：PC 端自动扫描运行中的进程，根据配置识别游戏（支持自定义）。
- **⚡ 毫秒级同步**：利用 UDP 局域网广播与 mDNS 服务发现技术，实现低延迟状态同步。
- **📱 沉浸展示**：
  - 游戏开始时，手机自动亮屏并全屏展示当前游戏信息。
  - 实时显示游玩时长，让你对游戏时间心中有数。
  - **横屏适配**：专为桌面支架设计，横屏模式下提供更佳的视觉体验。
- **📜 历史记录**：自动记录每一次游戏会话的时长，生成游玩报告（开发中）。
- **🚀 极简轻量**：
  - PC 客户端采用延迟加载技术，内存占用极低。


## 🛠️ 项目结构

- `pc_client/`: **PC 客户端** (Python) - 负责进程监控与信号发送。
- `app/`: **Android 客户端** (Kotlin/Jetpack Compose) - 负责接收信号与界面展示。
- `pc_client_go/`: **PC 客户端 (Go 版)** - 实验性的高性能版本。**（暂未开源）**

## 🚀 快速开始

### 1. PC 端 (Windows)

**前置要求**: 安装 [Python 3.8+](https://www.python.org/downloads/)

1. **克隆项目**:
   ```bash
   git clone https://github.com/smallway233/VIVO-Watch-AutoGameMode.git
   cd GameWatch
   ```

2. **安装依赖**:
   ```bash
   pip install -r pc_client/requirements.txt
   ```

3. **配置游戏**:
   在根目录下找到或创建 `games.json`，添加你想要监控的游戏：
   ```json
   {
       "EldenRing.exe": "Elden Ring",
       "League of Legends.exe": "英雄联盟",
       "Valorant.exe": "无畏契约"
   }
   ```

4. **运行客户端**:
   ```bash
   python pc_client/game_sync_client.py
   ```
   *程序启动后将最小化至系统托盘，右键托盘图标可退出。*

### 2. Android 端

1. 将项目导入 **Android Studio**。
2. 编译并安装 APK 到你的 Android 手机。
3. 确保手机与电脑连接在**同一 Wi-Fi** 下。
4. 打开 App，点击“启动服务”。
   *首次运行需授予通知权限与后台相关权限以确保服务稳定运行。*

## 🤝 贡献与反馈

欢迎提交 Issue 反馈 Bug 或建议新功能！如果你对代码有改进意见，请随时提交 Pull Request。


## 📄 许可证

本项目开源，具体的开源协议请查看仓库中的 LICENSE 文件（如有）。

---
*Created by [Smallway](https://github.com/smallway233)*
