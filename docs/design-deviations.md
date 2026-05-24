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

## 全局约定 — 导航分工（navController vs 回调）

v4 doc 统一把 `NavController` 传进每个屏幕、由屏幕自己 `navigate(...)`。本仓库采用**混用约定**：

- **路由器屏幕持 navController**：`SplashScreen` 需按 `deviceStatus` 在 Init/Login/Main/停留 间做多向决策，决策逻辑属于它本身，故直接注入 `navController`（同 v4）。
- **叶子屏幕用事件回调**：`InitScreen`(`onInitComplete`)、`LoginScreen`(`onLoginSuccess`/`onForgotPassword`)、`MainScreen`(`onFolderClick` 等) 只暴露"我完成了 X"事件、**不持有 navController**，由 `NavGraph` 集中决定去向。
- **原因** 回调上抛是 Google 官方导航指南 / Now in Android 推荐：屏幕与导航库解耦、可 `@Preview`、可单测、可复用，且全流程集中在 `NavGraph` 一处便于审计。能力上两者等价（回调闭包里就握着 navController），故非能力差异而是组织差异。Splash 是唯一例外——其多向路由决策塞进多个回调反而更乱。
- **影响范围** M3.2(Splash) / M3.3(Init) / M3.4 + M3.5(Login) / M4(Main)。

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
- **更新（M3.5, `1278c60`）** wipeAll 进一步升级为"整卡擦除"，连带 `clear()` 文件与聊天，详见 M3.5 条目。

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
- **本仓库实现** 保留 M0 时期 2.5s fade+scale 入场动画（品牌曝光）；动画结束后用 `LaunchedEffect(animationDone, deviceStatus)` 按 v4 §3.1 状态分支路由。DISCONNECTED 时**不画任何额外 UI**（停留在动画完成态的 Splash 上）。两个 DEV 入口的最终去向见 M3.3（结论：未进覆盖层，改为独立常驻的 `DevControlPanel`）。
- **原因** M0 入场动画是有意打磨过的品牌曝光，丢掉是 UX 倒退；DISCONNECTED 状态下 M2 决策保留的 `UsbDisconnectedOverlay`（全屏覆盖层）会盖在 SplashScreen 之上，再在 Splash 里画警告 UI 用户看不见；DEV 按钮挪到覆盖层里更合理（统一了拔卡场景的所有调试入口）。
- **Commit** `c94679c`

### M3.3 InitScreen — 保留 M0 五步向导，仅把"初始化"那步接通真实 initDevice

- **v4 §3.3** InitScreen 是**单屏表单**：密码框 + 确认框 + 绑定开关 + "完成初始化"按钮；点按钮即调 `authViewModel.initDevice(...)`，`InitState.Success` 后跳 Login。
- **本仓库实现** 保留 M0 时期的 **5 步向导**（0 检测设备 → 1 设置密码 → 2 绑定设备 → 3 初始化中 → 4 完成，带步骤指示器、设备信息卡、ECDH 文案）。接线方式：
  - `InitScreen` 仍用 `onInitComplete` 回调（导航权威留在 NavGraph），仅新增 `authViewModel: AuthViewModel = hiltViewModel()` 默认参数做业务。
  - step 3「正在初始化」由写死的 `delay(2000)` 改为 `authViewModel.initDevice(password, confirmPassword, bindDevice)`；顶层 `LaunchedEffect(initState)` 按 `Success`→step 4 / `Error`→回 step 1 推进。
  - step 4「前往登录」触发 `onInitComplete()`，NavGraph 跳 Login。
- **原因** 与 M3.2 同一立场——M0 向导是打磨过的 UX，砍成单屏是倒退；v4 单屏只是接线骨架，没有理由因接线而丢交互。接线选回调+默认注入是为了 NavGraph 零改动、影响面最小。
- **遗留** initState 的 `Error` 分支在 mock 下不可达（`MockUsbManager.initDevice` 恒成功，且 UI 已门控密码长度/一致），仅作防御。`initDevice` 后状态即 `AUTHENTICATED`，仍按 v4 流程跳 Login 让用户用刚设密码登一次（靠 M1.2 `storedPassword` 契约跑通）。
- **Commit** `dfb8436`

### M3.3 测试设施 — DevControlPanel 三按钮，经 Splash 重走真实路由进 Init/Login

- **v4** SDK 缺位期没有规定调试入口；v4 §3.1 仅在 Splash 的 DISCONNECTED 态放了两个 DEV 按钮。
- **本仓库实现** 把 M0 单个 `UsbToggleButton` 升级为常驻顶部的 `DevControlPanel`（一列三按钮）：拔插开关 / 模拟未初始化插入→初始化 / 模拟已初始化插入→登录。后两个按钮**先改 mock 卡状态**（`debugSimulateFirstInsert()` / 新增 `debugSimulateInitializedInsert()`），**再 `navigate(Splash){ popUpTo(0) }` 重走真实路由**，从而由 SplashScreen 这一唯一路由权威决定落到 Init 还是 Login。
- **原因** ① M2.2 兜底分支让启动恒为"已初始化已连接"，Init 流程原本**不可达**、无法验证，必须补 DEV 入口；② 选"改状态 + 重走 Splash"而非直接 `navigate(Init/Login)`，是为了验证真实路由路径（用户明确选此方案），而非绕过它；③ M3.2 曾设想把 DEV 按钮放进 `UsbDisconnectedOverlay`，但该覆盖层 5s 倒计时即 `finishAndRemoveTask()` 自毁，托管需要 CONNECTED 态的入口不现实，故改为独立常驻面板。
- **遗留** 整组 `DevControlPanel` 及 `DeviceViewModel` 的 `debug*` 方法是 mock 期脚手架，**M10 接真实 FSShell SDK 时连同 MainActivity 的启动兜底 `else` 分支一起删除**。
- **Commit** `dfb8436`

### M3.4 LoginScreen — 保留 M0 视觉，接通 login，删除不可达的 usbConnected 逻辑

- **v4 §3.4** 单屏：密码框 + 错误文案"${message}（剩余${attemptsLeft}次）" + 验证按钮；按钮/onDone 调 `authViewModel.login(password)`，`LoginState.Success` 跳 Home。
- **本仓库实现** 保留 M0 视觉（渐变头 + 登录卡片 + 设备 ID/SN 信息行），仅换里子：
  - 仍用 `onLoginSuccess` 回调（导航留 NavGraph）+ 注入 `authViewModel = hiltViewModel()`，与 M3.3 同构。
  - 删掉 M0 桩里写死的 `isLoading`/`showError`/`delay(1500)` 假登录；改由 `loginState` 驱动：`Loading`→转圈，`Error`→密码框红框 + supportingText 显示 `"${message}（剩余${attemptsLeft}次）"`（锁定态 attemptsLeft=0 时只显示 message），`Success`→`onLoginSuccess()`。
  - 补 doc 的键盘 `ImeAction.Done` → `login`；锁定（`attemptsLeft == 0`）时禁用登录按钮（doc 未明确，本仓库加强）。
- **删除（判断点 A）** M0 桩里 `val usbConnected = true` 及其 `if (!usbConnected){警告卡}` + 按钮门控是**不可达死代码**——按 M2 决策，DISCONNECTED 由全局 `UsbDisconnectedOverlay` 全屏兜底，人能停在 Login 时 USB 必连。故删除该分支，表头"已连接"指示保留为**静态恒真**（不引入 DeviceViewModel，保持本屏聚焦认证）。
- **遗留** `loginAttempts` 5 次锁定计数器存于 `AuthViewModel`（沿用 doc §3.2），而 Login 的 AuthViewModel 按 NavBackStackEntry 作用域——**离开再回 Login 或进程重建即清零、锁定失效**。真实安全卡须把失败次数记在硬件，**M10 收口**。另：`initDevice`(M3.3)→`authenticate`(本阶段) 接通后，"设密码→用该密码登录成功"端到端链路至此首次闭环。
- **Commit** `e21906c`

### M3.4 调整 — 锁定文案改 "身份认证失败，请联系技术人员" 且提前到次数耗尽即显示

- **v4 §3.2** 锁定守卫文案为 "尝试次数过多，请通过串口重置"，且仅在锁定后**再次**点击登录时由守卫分支抛出；第 5 次失败本身显示的是 `authenticate` 的 "密码错误（剩余0次）"。
- **本仓库实现** 抽出常量 `LOCKOUT_MESSAGE = "身份认证失败，请联系技术人员"`；`onFailure` 里当 `attemptsLeft <= 0` 即用该文案（守卫分支同用），使**第 5 次失败当场**就显示锁定文案，而非泛泛的"密码错误"。LoginScreen 显示逻辑不变（attemptsLeft==0 直接显示 message）。
- **原因** ① 用户要求改文案；② 原 v4 时机下"尝试次数过多…"那句要等第 6 次点击才出现，而此时按钮已因 attemptsLeft==0 禁用，用户几乎看不到——把锁定文案提前到次数耗尽当次更符合预期。
- **Commit** `e65cada`

### M3.5 LoginScreen 忘记密码入口（patch §M3）— 适配回调风格 + 稳健擦卡

- **patch §M3** 在验证按钮下加"忘记密码？"→ 红色危险确认框 → `deviceViewModel.wipeAndReset()` 后**立即** `navController.navigate(Init){popUpTo(0)}`；`wipeAndReset` 仅 `launch{ wipeAll() }`（fire-and-forget）。patch 代码用 v4 深色 token 与 navController 下传。
- **本仓库实现**
  - 导航走新增 `onForgotPassword` 回调（NavGraph 跳 Init），延续叶子屏幕回调约定（见"全局约定"），不把 navController 塞进 LoginScreen。
  - 主题 token 深色→浅色映射：`AccentRed→Danger`、`AccentCyan→Accent`、`DarkSurface`→去掉用默认浅色弹框底。
  - **稳健擦卡**：`wipeAndReset(onComplete)` 改为 **wipeAll 完成后**才回调导航；确认框在擦卡 ~2s 期间显示"正在清除数据" loading 并锁定（隐藏按钮、禁外部点关）。
- **原因（稳健擦卡）** patch 的 fire-and-forget + 立即 `popUpTo(0)` 会销毁 Login 的 NavBackStackEntry → 其 `DeviceViewModel.viewModelScope` 取消 → `wipeAll` 卡在 `delay(2000)` 被取消、擦除不完整。改为"擦完再导航"规避。mock 下虽因直接跳 Init + initDevice 覆盖而暂不出错，但 M10 真擦卡时是 bug。
- **Commit** `1278c60`

### M3.5 MockUsbManager.wipeAll — 升级为整卡擦除（连带清文件/聊天）

- **承接上文 M1.2 "wipeAll 真正重置状态"** 此前 wipeAll 只重置认证/绑定状态，**不动** `MockFileSystem`/`MockChatRepository`，与"删除所有文件、聊天记录"文案不符。
- **本仓库实现** 给 `MockFileSystem`/`MockChatRepository` 各加 `clear()`；`MockUsbManager` 注入两者，`wipeAll()` 内一并 `clear()`。所有 wipeAll 调用方（忘记密码、后续 M7 恢复出厂、HomeScreen 一键清理）自动获得真实清数据。
- **原因** 让"整卡擦除"名副其实，并把清除逻辑收口在 wipeAll 一处，避免各调用方各自拼。
- **Commit** `1278c60`

---

## M4 — 主页与底部导航

### M4 架构 — `Screen.Main` + `selectedTab` 取代 v4 `Screen.Home` + 嵌套 NavHost

- **v4 §0.6 / §0.7 / §4** 顶层路由定义 `Screen.Home`（route `"home"`）；`HomeScreen` 是登录后的**纯壳子**，内部再开一个嵌套 `NavHost`（`innerNavController`），底部 **3 个 Tab**（`Files` / `ChatList` / `Settings`）作为嵌套图的子页，靠 `navigate + saveState/restoreState` 切换。
- **本仓库实现**
  - 顶层路由叫 `Screen.Main`（route `"main"`），**没有** `Screen.Home`。
  - 主壳子叫 `MainScreen`，底部 **4 个 Tab**：**首页（dashboard）** / 文件夹 / 通信 / 设置。Tab 切换用 `var selectedTab by rememberSaveable` + `when(selectedTab)`，**不使用嵌套 NavHost**。
  - 名称撞车提醒：本仓库的 `HomeScreen` ≠ v4 的 `HomeScreen`。本仓库 `HomeScreen` 只是"首页"那一个 Tab 的内容（设备状态卡 + 快捷功能 + 最近操作日志）；v4 的 `HomeScreen` 是整个主框架。v4 设计里**根本没有这个首页 dashboard**，属本仓库增量。
  - `FileDetail` / `ChatDetail` / `QrCode` 与 v4 一致，仍注册在**顶层** NavGraph 上，由各 Tab 持有的顶层 `navController` 跳转。
- **原因** 这几个 Tab 都是**叶子页**——所有详情页（FileDetail/ChatDetail/QrCode）都走顶层导航，Tab 内部没有多级返回栈需求。嵌套 NavHost 的核心优势（每 Tab 独立 back stack）在此**零收益**，徒增内外两个 navController 的复杂度；且嵌套 NavHost 会把每个 Tab 的 `hiltViewModel()` 切到不同 NavBackStackEntry 作用域，容易让 `DeviceViewModel` 分裂成多实例、USB 状态对不上（v4 §0.7/§4 自己也反复提醒这个坑）。`selectedTab + when` 天然共享 Activity 级作用域，更简单也更稳。
- **Commit** `f663cf8`（M0 脚手架即采用此结构）

### M4 补救 — `SaveableStateProvider` 保留切走 Tab 的状态

- **背景** `selectedTab + when` 相比嵌套 NavHost 缺一个能力：切走的 Tab 会整个退出 composition，其瞬态 UI 状态（如列表滚动位置）默认丢失；嵌套 NavHost 靠 `saveState/restoreState` 自动保留。
- **本仓库实现** `MainScreen` 用 `rememberSaveableStateHolder()` + `SaveableStateProvider(selectedTab) { when(...) }` 按 Tab 分桶保存。`rememberSaveable` 支撑的状态（含 `rememberScrollState` 的滚动位置）切走再切回会恢复。
- **边界** 仅保留 `rememberSaveable` 状态；纯 `remember`（如 HomeScreen 的 `showCleanDialog`）不保留，符合预期。今后需跨切换保留的瞬态应改用 `rememberSaveable` 或提到 ViewModel。
- **Commit** `fe95c47`

<!-- 后续里程碑的偏离继续在下面追加 -->
