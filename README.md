# AutoPhigros 🎵

[![Version](https://img.shields.io/badge/version-0.1.0--beta-blue)](https://github.com/BZMXS/AutoPhigros)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

> **一个 Android 上的 Phigros / Phira 自动打歌工具（测试版）(无法正常使用！！！有时间再修)**  
> 通过实时录屏识别音符，配合无障碍服务模拟点击，让你解放双手。

---

## ✨ 功能特性

- ✅ **实时录屏识别** – 获取屏幕画面，检测音符位置
- ✅ **无障碍点击** – 无需 Root，通过 Android 无障碍服务模拟手指点击
- ✅ **悬浮窗控制** – 随时开始/停止，不影响游戏操作
- ✅ **轻量级颜色识别** – 快速检测蓝色（Tap）和红色（Flick）音符
- ✅ **适配动态分辨率** – 自动适配不同手机屏幕

---

## 🚧 当前版本限制（测试版 v0.1.0）

> ⚠️ 本项目目前为 **概念验证（PoC）** 阶段，请合理使用，避免被封号风险。

- 仅识别 **屏幕中央水平线** 附近的音符（判定线固定位置）
- 只支持 **蓝色（Tap）** 和 **红色（Flick）** 音符的颜色检测，**暂不支持黄色长按（Hold）**
- 对背景复杂的谱面可能误判
- **需要手动开启无障碍服务**，否则无法点击

---

## 📱 系统要求

- Android 7.0 (API 24) 及以上
- 已开启 **无障碍服务**（设置 → 无障碍 → AutoClickService）
- 已开启 **悬浮窗权限**（设置 → 应用 → 特殊权限 → 显示在其他应用上层）
- 已授权 **录屏权限**（App 内会弹出申请）

---

## 🚀 快速开始

### 1. 下载安装
- 从 [Releases](https://github.com/BZMXS/AutoPhigros/releases) 下载最新的 `app-debug.apk`
- 或自行克隆源码，用 Android Studio 编译安装

### 2. 开启无障碍服务
进入手机 **设置 → 无障碍**，找到 **AutoClickService**，打开开关。

### 3. 打开 App 并授权
- 点击 **“启动悬浮窗”**
- 允许 **悬浮窗权限**
- 允许 **录屏权限**（点击“立即开始”）

### 4. 进入游戏
- 打开 Phigros 或 Phira，进入任意歌曲
- 点击悬浮窗的 ▶ 按钮开始自动识别
- 点击 ⏹ 按钮停止

---

## 📦 依赖

| 依赖 | 用途 |
|------|------|
| AndroidX AppCompat | 基础 UI 支持 |
| AndroidX Core | 前台服务通知支持 |

> 本项目未使用第三方图像识别库，仅采用原生像素扫描，因此体积小巧。

---

## 🛠️ 构建与开发

如果你想自己修改代码，请使用 Android Studio 打开项目。

```bash
git clone https://github.com/BZMXS/AutoPhigros.git
cd AutoPhigros
# 用 Android Studio 打开，等待 Gradle 同步完成
# 连接手机，点击 Run 即可
