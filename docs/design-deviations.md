# MiDun 实现偏离记录（vs v4 设计文档）

本文档跟踪本仓库实现与 `MiDun_开发里程碑文档_v4.md`（下称 "v4 doc"）的所有有意偏离。**v4 doc 仍是产品意图的权威参考**；本文件记录"在何处、为何"偏离。

每条按以下结构：
- **v4 §X.Y** — 偏离对应 v4 文档的位置
- **v4 设计** — 文档原方案
- **本仓库实现** — 实际写进代码的方案
- **原因** — 决策依据
- **Commit** — 落地的 commit

新决策做完一次偏离就追加一节。下次审计或对外汇报时以本文为准。

---

## M1 — Mock 数据层

### M1.2 MockUsbManager — 增加密码持久化

- **v4 §1.2** `authenticate(password)` 硬编码 `password == "123456"`；`initDevice(password, bindDevice)` 接收密码参数但不存。
- **本仓库实现** 新增 `private var storedPassword: String?`；`initDevice` 写入，`authenticate` 比对。`simulateInsert` 种入 `"123456"`（v4 验收里的出厂卡默认密码），`simulateRemove`/`simulateFirstInsert` 清空。
- **原因** v4 设计让 Init→Login 流程自相矛盾：用户在 InitScreen 设了自定义密码，但 LoginScreen 只能用 `"123456"` 登。"init 时设的密码 = login 时验证的密码" 才是产品本意，mock 层应当忠实建模。
- **Commit** `e834807`

### M1.2 MockUsbManager — wipeAll 真正重置状态

- **v4 §1.2** `wipeAll()` 只 `delay(2000)` 后 `Result.success(Unit)`，不动 `_deviceStatus`。
- **本仓库实现** 同时 `isInitialized=false`、`boundPhoneId=null`、`status=CONNECTED`，保证擦卡后 SplashScreen 能按 §3.1 路由回 InitScreen。
- **原因** v4 文档 bug：擦卡后 status 仍是 AUTHENTICATED 会让 SplashScreen 错误地跳 Home。
- **Commit** `e834807`

### M1.4 MockChatRepository — clearContact 拆分为 clearMessages + deleteContact

- **v4 §1.4** 单一方法 `clearContact(contactId)`，**同时**删除联系人和其消息。
- **本仓库实现** 拆为两个：
  - `clearMessages(contactId)` — 保留联系人，清空消息列表，重置 `lastMessage`/`lastMessageTime`
  - `deleteContact(contactId)` — 联系人 + 消息一起删
- **原因** 标准聊天 App UX（微信、Telegram）对"清空聊天记录"和"删除联系人"是两个独立动作。单一方法在 UI 层会很别扭。
- **Commit** `095d7e7`

---

## M2 — USB 状态感知

### M2 视觉 — 保留 M0 UsbDisconnectedOverlay，跳过 v4 UsbStatusBar

- **v4 §2.3** 规定顶部细窄状态条 `UsbStatusBar`，按状态变色（红/橙/绿）显示连接信息。
- **本仓库实现** 保留 M0 时期的全屏 `UsbDisconnectedOverlay`（拔卡 → 全屏黑底警告 + 5s 倒计时 → `finishAndRemoveTask()`）。v4 §2.3 的 `UsbStatusBar` 组件及其配色 token（`AccentRed/Amber/Green`）**未创建**。
- **原因** M0 戏剧化覆盖层提供强视觉安全信号 + 5s 重插宽限 + 进程死亡级别的内存擦除保证。v4 细条设计偏含蓄；在 M10 真实 SDK 落地、`clearSensitiveMemory()` 真正生效之前，v4 方案无法提供真实的内存擦除保证。用户选择保留 M0 UX。
- **Commit** `aea10a7`

### M2.2 MainActivity — 启动兜底分支

- **v4 §2.1** 仅在 `usbManager.deviceList.isNotEmpty()` 时调 `deviceViewModel.onUsbAttached(...)`。
- **本仓库实现** `else` 分支也调 `deviceViewModel.onUsbAttached(null)`（开发期 emulator 没有真实 USB 设备）。
- **原因** Emulator 没有真实 USB，按 v4 设计每次启动都会立刻显示拔卡覆盖层 + 5s 倒计时；这条兜底让 mock 阶段开发体验顺畅。**M10 接真实 SDK 时这行必须删掉**——届时手机没插卡启动时，App 本就应该停在拔卡态。
- **Commit** `aea10a7`

---

## M3 — 认证流程

### M3.2 SplashScreen — 保留 2.5s 入场动画 + DISCONNECTED 分支不画 UI

- **v4 §3.1** 即时路由，无入场动画；DISCONNECTED 时停留在 Splash 并显示红色"操作异常：请插入安全卡"提示 + 两个 DEV 按钮（"模拟插卡（已初始化）"、"模拟首次插卡（未初始化）"）。
- **本仓库实现** 保留 M0 时期 2.5s fade+scale 入场动画（品牌曝光）；动画结束后用 `LaunchedEffect(animationDone, deviceStatus)` 按 v4 §3.1 状态分支路由。DISCONNECTED 时**不画任何额外 UI**（停留在动画完成态的 Splash 上）。两个 DEV 按钮挪到 `UsbDisconnectedOverlay` 里（见 M3.3）。
- **原因** M0 入场动画是有意打磨过的品牌曝光，丢掉是 UX 倒退；DISCONNECTED 状态下 M2 决策保留的 `UsbDisconnectedOverlay`（全屏覆盖层）会盖在 SplashScreen 之上，再在 Splash 里画警告 UI 用户看不见；DEV 按钮挪到覆盖层里更合理（统一了拔卡场景的所有调试入口）。
- **Commit** `c94679c`

<!-- 后续里程碑的偏离继续在下面追加 -->
