# AutoPhigros 🎵

[![Version](https://img.shields.io/badge/version-0.2.9--beta-blue)](https://github.com/bzmxs/AutoPhigros)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

> **一个 Android 上的 Phigros / Phira 自动打歌工具（测试版）**
> 通过实时录屏识别音符，配合无障碍服务模拟点击，让你解放双手。

---

## ✨ 最新更新 (v0.2.9-beta)

- **🧹 伪音符治理**：过滤宽度 ≤8px 的描边/背景亮线，同一横向位置 110ms 内只排一次点击。日志里 `排队=` 从 834 降到 200 附近（谱面真实音符是 181）。
- **⏱ 时序微调**：悬浮窗新增 ⏱ 按钮，可在 `-30ms ~ +30ms` 之间调整补偿并自动记住，用结算里的 Early / Late 数量对着调。
- **🎯 判定线与速度**：判定线逐帧跟踪（不再是固定 0.766），下落速度取 5 帧中位数。
- **🖐 Hold 按住不放**：按“条长 ÷ 下落速度”算一次按住时长，不再点一下就松。
- **🧩 多指合并**：同一时刻的多个音符合成一个手势（最多 10 个触点），减少手势排队造成的延迟。
- **🛡️ 崩溃可追溯**：崩溃自动写入文件，下次打开 App 会弹窗显示上次的堆栈。

---

## ✨ 功能特性

- ✅ **实时录屏识别** – MediaProjection 半分辨率（960×540）采集，零位图分配、纯字节扫描
- ✅ **无障碍点击** – 无需 Root，`dispatchGesture` 模拟多指同时按下
- ✅ **四类音符** – Tap（蓝）/ Flick（红）/ Hold（浅蓝竖条）/ Drag（横向长条）
- ✅ **悬浮窗控制** – 可拖动，不影响游戏操作；▶ 开始 / ⏹ 停止 / ✖ 关闭面板 / ⏱ 时序补偿
- ✅ **动态分辨率** – 进游戏切横屏时自动重建虚拟屏，坐标按真实分辨率换算
- ✅ **一键启动游戏** – 主界面可一键打开 Phigros / Phira

---

## ⚙️ 工作原理

```
MediaProjection 录屏 (960×540)
        │
        ├─ 逐帧扫描判定线附近 ±46px 的带状区域
        │    · 只读 ImageReader 的 ByteBuffer，按 rowStride/pixelStride 算偏移，不创建 Bitmap
        │    · 颜色分类：蓝=Tap、红=Flick、浅蓝(高 G/B)=Hold、米黄=Drag、白边/高光并入条体
        │
        ├─ 每个音符「跨过触发线（判定线上方 45px）」时登记一次
        │    · 用相邻帧的位移估算下落速度 → 反推提前量 lead
        │    · Hold 用竖直长度 ÷ 速度 估算该按多久（上限 3s）
        │    · 同一音符去重：同位置 110ms 内不再重复登记
        │
        └─ 每 8ms 检查一次待办队列，到点的音符合并成一个手势下发
             · 一个 GestureDescription 最多 10 个触点
             · 所有触点 startTime 必须相同（否则会触发系统 InputDispatcher 断言崩溃）
```

时序补偿（⏱ 按钮）最终作用在登记时刻上：

```java
long arrival = now + lead + timingBias;   // timingBias 来自 ⏱ 按钮，-30 ~ +30ms
```

---

## 📱 系统要求

- Android 7.0 (API 24) 及以上（Android 14 已适配 MediaProjection 回调与前台服务类型）
- 已开启 **无障碍服务**（设置 → 无障碍 → AutoClickService）
- 已开启 **悬浮窗权限**（设置 → 应用 → 特殊权限 → 显示在其他应用上层）
- 已授权 **录屏权限**（App 内会弹出申请）
- Android 13+ 还需要 **通知权限**（否则前台服务通知不显示）

---

## 🚀 快速开始

1. 从 [Releases](https://github.com/bzmxs/AutoPhigros/releases) 下载最新的 APK，或直接用仓库里的 `apk/AutoPhigros-0.2.9-beta-debug.apk`。
2. 进入手机 **设置 → 无障碍**，找到 **AutoClickService**，打开开关。
3. 打开 App，点击 **“启动悬浮窗”**，按提示授权悬浮窗和录屏权限。
4. 点击主界面的 **“打开 Phira”**（或“打开 Phigros”），进入游戏。
5. 点击悬浮窗的 **▶** 开始自动打歌；**⏹** 停止识别（录屏保持开着）；**✖** 只关闭悬浮窗（回 App 再点“启动悬浮窗”可重新打开）。

### 悬浮窗状态栏说明

```
1920x1080  64fps  c:0/6  队:0  点:3/s  丢:0/s  总:125  条:2  宽:96  长:70  v:520
```

| 字段 | 含义 |
|---|---|
| `1920x1080` | 屏幕真实分辨率 |
| `64fps` | 识别循环帧率 |
| `c:0/6` | 每帧识别耗时 均值/峰值（ms），持续偏高会自动隔帧 |
| `队:` | 当前待下发队列长度 |
| `点:N/s` | 最近 500ms 的点击速率 |
| `丢:0/s` | 被丢弃的音符数（通道被占或太晚） |
| `总:` | 本次累计点击次数 |
| `条/宽/长` | 最近一帧识别到的音符段数 / 最宽段 / Hold 竖直长度 |
| `v:` | 估算的下落速度（像素/秒） |

### 时序校准（重要）

打一局后看结算页的 **Early / Late**：

- **Late 偏多** → 点 ⏱ 往负方向调（一般 `-20` ~ `-30`）
- **Early 偏多** → 点 ⏱ 往正方向调
- 每点一次循环 `-30 → -20 → -10 → 0 → +10 → +20 → +30`，会自动保存

---

## 🛠️ 构建与开发

用 Android Studio 打开项目，等待 Gradle 同步完成，连手机点 Run 即可。

命令行构建（等价于 Android Studio 的 Build APK）：

```bash
./gradlew assembleDebug --console=plain
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

> **关于 JDK**：`gradle/gradle-daemon-jvm.properties` 里声明了 JDK 25 toolchain。如果你的机器只有 JDK 17/21/22，删掉这个文件后用 `-Dorg.gradle.java.home=<你的 JDK 路径>` 构建即可：
>
> ```bash
> ./gradlew assembleDebug -Dorg.gradle.java.home="C:\Program Files\Java\jdk-22"
> ```

---

## 📜 版本历史

| 版本 | 主要改动 |
|---|---|
| v0.1.0-beta | 悬浮窗、录屏、无障碍点击、像素识别雏形 |
| v0.2.0-beta | 包名改为 `com.bzmxs.autophigros`；半分辨率采集；零位图扫描；判定线自动校准；Hold/Flick 支持；一键启动游戏 |
| v0.2.1-beta | 修复 Hold 长度被白边截断；速度取中位数；长按期间预排点击 |
| v0.2.2-beta | 背景宽段过滤；触发线 70→28px；时序补偿 +20ms |
| v0.2.3-beta | 触发线下调至 45px；下发间隔 16ms；悬浮窗 ⏱ 时序补偿按钮 |
| v0.2.4-beta | 批量排程（提前 70ms 收集音符） |
| v0.2.6-beta | 修复多指 startTime 不一致导致 system_server 断言崩溃；撤回预排；TAP 20ms |
| v0.2.7-beta | 丢弃原因拆分统计（丢hold / 丢晚） |
| v0.2.8-beta | 累计入队计数 `排队=`，用于和结算音符数对账 |
| v0.2.9-beta | 过滤 ≤8px 伪音符；同位置 110ms 去重（排队 834 → ~200） |

---

## 🚧 当前版本限制

> ⚠️ 本项目目前为 **概念验证（PoC）** 阶段，请合理使用，避免被封号风险。

- 同一时刻只能下发一个手势，**长按（Hold）期间无法同时点击其他音符**
- 对大块亮色背景的谱面仍可能有误判（正在逐步收紧颜色与形状门槛）
- 判定线若被大面积白色特效遮挡，偶尔会短暂锁错位置
- **需要手动开启无障碍服务**，否则无法点击

---

## 🐞 出问题了怎么办

1. App 崩溃后重新打开，会弹窗显示上次的崩溃堆栈；完整日志在：
   `Android/data/com.bzmxs.autophigros/files/crash/crash_yyyyMMdd_HHmmss.txt`
2. 抓识别日志：
   ```bash
   adb logcat -s AutoPhigros:* > log.txt
   ```
3. 关键日志行：`capture ready ...`、`judge line found: y=... ratio=...`、`queue ...`、`fire N note(s) ...`、`hold len=... -> ...ms`、`autoplay stopped, total clicks=...`

---

## 📄 许可证

本项目基于 **MIT License** 开源。

---

## ⚠️ 免责声明

> 本工具仅供学习交流使用，请勿用于商业用途或破坏游戏公平性。
> 使用本工具可能导致游戏账号被限制，请自行承担风险。
