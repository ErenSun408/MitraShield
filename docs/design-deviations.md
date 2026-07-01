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

## 里程碑重编号（HomeScreen 正式纳入）

HomeScreen 接通由"v4 外增量"正式定为 **M9**，原后续里程碑各后推一位：

- **M9** = HomeScreen 接通（接已有 Mock 单例，UI 收尾；原编号为 H1/M7.6 提案）
- **M10** = 即时通信网络层（IPv6 P2P，真 Socket + ECDH，对应 v4 doc **§9**）
- **M11** = 真 SDK 收口（FSShell 接入、authenticate 校验 boundPhoneId、自动锁定持久化、真实文件选取器/导出、签名上架等）

⚠️ **编号错位提醒**：自 M9 起，本项目里程碑编号比 v4 doc 章节号 **+1**（M10 ↔ v4 §9）。v4 doc 章节号不动（它是不可改的 spec）。本文档内早先各节里"留待 M10/M11"等前瞻引用已按此规则同步更新（真 SDK 统一指 M11，网络层指 M10）。

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
- **原因** M0 戏剧化覆盖层提供强视觉安全信号 + 5s 重插宽限 + 进程死亡级别的内存擦除保证。v4 细条设计偏含蓄；在 M11 真实 SDK 落地、`clearSensitiveMemory()` 真正生效之前，v4 方案无法提供真实的内存擦除保证。用户选择保留 M0 UX。
- **Commit** `aea10a7`

### M2.2 MainActivity — 启动兜底分支

- **v4 §2.1** 仅在 `usbManager.deviceList.isNotEmpty()` 时调 `deviceViewModel.onUsbAttached(...)`。
- **本仓库实现** `else` 分支也调 `deviceViewModel.onUsbAttached(null)`（开发期 emulator 没有真实 USB 设备）。
- **原因** Emulator 没有真实 USB，按 v4 设计每次启动都会立刻显示拔卡覆盖层 + 5s 倒计时；这条兜底让 mock 阶段开发体验顺畅。**M11 接真实 SDK 时这行必须删掉**——届时手机没插卡启动时，App 本就应该停在拔卡态。
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
- **遗留** 整组 `DevControlPanel` 及 `DeviceViewModel` 的 `debug*` 方法是 mock 期脚手架，**M11 接真实 FSShell SDK 时连同 MainActivity 的启动兜底 `else` 分支一起删除**。
- **Commit** `dfb8436`

### M3.4 LoginScreen — 保留 M0 视觉，接通 login，删除不可达的 usbConnected 逻辑

- **v4 §3.4** 单屏：密码框 + 错误文案"${message}（剩余${attemptsLeft}次）" + 验证按钮；按钮/onDone 调 `authViewModel.login(password)`，`LoginState.Success` 跳 Home。
- **本仓库实现** 保留 M0 视觉（渐变头 + 登录卡片 + 设备 ID/SN 信息行），仅换里子：
  - 仍用 `onLoginSuccess` 回调（导航留 NavGraph）+ 注入 `authViewModel = hiltViewModel()`，与 M3.3 同构。
  - 删掉 M0 桩里写死的 `isLoading`/`showError`/`delay(1500)` 假登录；改由 `loginState` 驱动：`Loading`→转圈，`Error`→密码框红框 + supportingText 显示 `"${message}（剩余${attemptsLeft}次）"`（锁定态 attemptsLeft=0 时只显示 message），`Success`→`onLoginSuccess()`。
  - 补 doc 的键盘 `ImeAction.Done` → `login`；锁定（`attemptsLeft == 0`）时禁用登录按钮（doc 未明确，本仓库加强）。
- **删除（判断点 A）** M0 桩里 `val usbConnected = true` 及其 `if (!usbConnected){警告卡}` + 按钮门控是**不可达死代码**——按 M2 决策，DISCONNECTED 由全局 `UsbDisconnectedOverlay` 全屏兜底，人能停在 Login 时 USB 必连。故删除该分支，表头"已连接"指示保留为**静态恒真**（不引入 DeviceViewModel，保持本屏聚焦认证）。
- **遗留** `loginAttempts` 5 次锁定计数器存于 `AuthViewModel`（沿用 doc §3.2），而 Login 的 AuthViewModel 按 NavBackStackEntry 作用域——**离开再回 Login 或进程重建即清零、锁定失效**。真实安全卡须把失败次数记在硬件，**M11 收口**。另：`initDevice`(M3.3)→`authenticate`(本阶段) 接通后，"设密码→用该密码登录成功"端到端链路至此首次闭环。
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
- **原因（稳健擦卡）** patch 的 fire-and-forget + 立即 `popUpTo(0)` 会销毁 Login 的 NavBackStackEntry → 其 `DeviceViewModel.viewModelScope` 取消 → `wipeAll` 卡在 `delay(2000)` 被取消、擦除不完整。改为"擦完再导航"规避。mock 下虽因直接跳 Init + initDevice 覆盖而暂不出错，但 M11 真擦卡时是 bug。
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

---

## M5 — 隐私文件夹数据流

### M5.1 FileViewModel — 用单一 `FileUiState` 取代 v4 的多条 StateFlow

- **v4 §5.1** `FileViewModel` 分别暴露 `folders`、`currentFiles`、`isLoading` 三条 `StateFlow`，再用 `operationResult` 发送一次性操作结果。
- **本仓库实现** 保留 `operationResult`，但把持久 UI 状态合并为一个 `FileUiState`：`folders` / `currentFolderId` / `currentFiles` / `isLoading`。`FileViewModel` 仍通过 Hilt 注入 `MockFileSystem`，并提供 `loadFolders()`、`loadFiles(folderId)`、`createFolder(...)`、`importFile(...)`、`deleteFile(...)`、`deleteFolder(...)`。
- **原因** 文件模块后续会从 `MockFileSystem` 替换到真实 FSShell SDK。统一 UI state 可以把“当前文件夹 + 当前文件列表 + loading”作为一个页面快照交给 UI，避免多个 Flow 在组合时出现短暂不同步，也更接近后续 SDK/Repository 层会返回的聚合状态。一次性提示仍用 `SharedFlow`，避免把 toast/snackbar/dialog 事件塞进持久状态。
- **Commit** `b1d121f`

### M5.2 FilesScreen — 文件夹列表改接 `FileViewModel + MockFileSystem`，继续保留回调导航

- **v4 §5.2** `FilesScreen(navController, fileViewModel = hiltViewModel())` 直接持有 `NavController`，文件夹点击时在屏幕内部 `navController.navigate(Screen.FileDetail.createRoute(folder.id))`；新建文件夹通过 `FilesScreen` 内部弹窗完成。
- **本仓库实现** `FilesScreen` 继续遵守全局导航约定：只接收 `onFolderClick(folderId)` / `onCreateFolder()` 回调，不持有 `NavController`。数据源从旧 `MockData.folders` 切换为 `fileViewModel.uiState.folders`，`FolderCard` 的模型从旧 `SecureFolder` 改为 `FileItem`，并按 `CopyPolicy` 显示拷贝策略；空列表显示 `EmptyFoldersState`。新建动作仍走现有顶层 `CreateFolder` 路由，未改成 v4 的内联弹窗。
- **原因** 导航职责已经在全局约定中收口到 `NavGraph`，文件夹列表只是叶子 Tab 内容，不应重新把 `NavController` 下传。保留独立 `CreateFolderScreen` 是为了延续当前路由结构和已有 UI，避免把 M5.2 的读数据迁移与创建流程改造混在一个提交里。
- **Commit** `c41fe81`

### M5.3 FileDetailScreen — 详情页改接同一套文件数据源，修正旧 ID 不兼容问题

- **v4 §5.3** `FileDetailScreen(navController, folderId, fileViewModel = hiltViewModel())` 在详情页内持有 `NavController`，用 `FileViewModel.currentFiles` 显示文件列表。
- **本仓库实现** `FileDetailScreen(folderId, onBack, fileViewModel = hiltViewModel())` 继续使用回调返回，不持有 `NavController`；进入页面时调用 `loadFolders()` + `loadFiles(folderId)`，从 `FileUiState` 中查找当前文件夹和文件。`FileItemCard` 从旧 `SecureFile` 改为 `FileItem`，并补充 `formatFileSize()` 对 `Long` 文件大小做展示格式化。
- **原因** M5.2 后文件夹 ID 已变为 `MockFileSystem` 的 `folder_1` / `folder_2` / `folder_3`，而旧 `MockData.files` 使用 `"1"` / `"2"` / `"3"`，如果详情页不迁移会导致点击文件夹后找不到文件。详情页是顶层路由，和 Tab 内 `FilesScreen` 可能不是同一个 `FileViewModel` 实例；但 `MockFileSystem` 是 Hilt 单例，所以重新加载仍能拿到同一份数据，且更符合后续真实 SDK 重新查询目录的行为。
- **Commit** `cf85ea5`

### M5.4 创建/导入接线 — 创建走独立屏、导入保留来源选择弹窗

- **v4 §5.2 / §5.3** 新建文件夹用 `FilesScreen` 内联 `CreateFolderDialog`；导入文件用 `FileDetailScreen` 顶栏单个 `Add` 按钮**直接** `importFile(folderId, "文件_…pdf", 1024000L)`，无来源选择、文件名写死。
- **本仓库实现**
  - 创建延续 M5.2 决策走独立 `CreateFolderScreen` 路由（非内联弹窗），此阶段接通 `fileViewModel.createFolder`：3 档单选 `copyMode` 经新增 `Int.toCopyPolicy()` 映射到 `CopyPolicy`；按钮按 `uiState.isLoading` 显示转圈并禁用；`collect operationResult`，`Success`→成功态、`Error`→红字提示。
  - 导入保留 M0 的**来源选择弹窗**（"手机导入" / "U盘导入" 两个 `OutlinedButton`），各自用不同占位文件名+大小调 `importFile`，而非 v4 的单一直接导入。
- **原因** 创建走独立屏延续 M5.2，避免把流程改造混进本提交；导入留来源选择更贴近产品形态（发送/导入来源分 隐私区 / U盘 / 手机，见 patch "待实现说明 第3条"），mock 期先用占位文件名区分来源，待 M11 SDK + 系统文件选取器落地后替换为真实选取。
- **Commit** `9e76749`

### M5.5 删除确认 — 抽出共享 `DeleteConfirmDialog`，并给文件夹删除补确认

- **v4 §5.2 / §5.3** `FolderCard` 的删除菜单项**直接** `onDelete()`，**无确认弹窗**；文件删除则有一个内联 `AlertDialog` 确认。
- **本仓库实现** 抽出一个共享的 `DeleteConfirmDialog`（标题/文案参数化），文件夹与文件删除**共用**。文件夹删除**新增确认弹窗**（v4 缺），文案明确提示"文件夹内所有文件也会被删除，且无法恢复"。
- **原因** 文件夹删除是破坏性操作且在 `MockFileSystem.deleteFolder` 里级联删除内部文件，v4 无确认是疏漏；统一弹窗去掉文件/文件夹两处重复的对话框代码。
- **Commit** `3d8f584`

### M5.6 重命名（patch §M5 改动1–4）— 抽共享弹窗 + 浅色 token + 空名守卫

- **patch §M5** `MockFileSystem`/`FileViewModel` 加 `renameFolder`/`renameFile`；`FolderCard` 与 `FileItemCard` **各内联一个**重命名 `AlertDialog`（深色 token、`DriveFileRenameOutline` 图标）；`FileViewModel` 仅在失败时 `emit(Error)`。
- **本仓库实现**
  - 后端 `MockFileSystem.renameFolder/renameFile`（`indexOfFirst` + `copy(name=…)`，找不到返回 `failure`）照 patch。
  - UI 抽出**共享 `RenameDialog`** 替代两处内联弹窗；主题 token 深色→浅色映射（`AccentCyan→Primary`），图标改 `Icons.Default.Edit`；确认按钮 `enabled = value.isNotBlank()`。
  - `FileViewModel.renameFolder/renameFile` 增加**空名守卫**（`trim()` 后 blank 即 `emit(Error)` 并 `return`），且**成功也 `emit(Success)` 文案**（"文件夹已重命名"/"文件已重命名"），patch 两者皆无。
- **原因** 共享弹窗去重、浅色映射延续全局主题；空名守卫 + 成功提示是健壮性/反馈增强——patch 只处理失败分支，用户改空名或改成功都无反馈。
- **Commit** `a07e2a9`

### M5.7 导出策略（patch §M5 改动5）— 以文件夹策略为权威，并补文件夹/批量导出

- **patch §M5 改动5** 仅给 `FileItemCard` 的导出菜单项按**单个文件的** `file.copyPolicy` 置灰/可点：`NO_COPY` → "不可导出" 且 `enabled=false`。
- **本仓库实现**
  - **导出策略以所在文件夹为准，而非单文件**：`FileDetailScreen` 取 `effectiveCopyPolicy = folder.copyPolicy`，下传给该文件夹内**所有** `FileItemCard`，而非 patch 的 `file.copyPolicy`。**原因**：本仓库数据模型里 `copyPolicy` 实际是**文件夹级**属性——`MockFileSystem` 种子中只有文件夹带策略（folder_1=COPY_PLAIN / folder_2=NO_COPY / folder_3=COPY_ENCRYPTED），而 `file_1…file_5` 全部落在 `FileItem` 默认 `NO_COPY` 上。若照 patch 用 `file.copyPolicy`，所有种子文件都会"不可导出"，与顶栏展示的文件夹策略自相矛盾。产品语义是"文件夹的拷贝策略决定其内文件能否导出"，故以文件夹策略为权威。
  - **增量 1** `FolderCard` 菜单新增"导出文件夹"项（`NO_COPY` 置灰），patch 无此项。
  - **增量 2** 接通 `FileDetailScreen` 顶栏溢出菜单的"导出全部文件"（M0 占位项），按 `effectiveCopyPolicy` 置灰、`files` 为空时禁用。
  - **导出仍是 mock 占位**：触发后只弹"导出已触发"/"文件夹导出已触发"提示框（含按策略给出的"明文/密文导出"文案），**不做真实文件操作**。真实导出到 U盘/手机需 FSShell SDK + 系统文件 API，见 patch "待实现说明 第3、9条"，**M11 收口**。
- **Commit** `74280c2`

### M5.8 删除全部文件 — v4/patch 未规定，完成 M0 占位项并与删文件夹区分

- **v4 / patch** 均**未规定**此功能。M0 脚手架的 `FileDetailScreen` 溢出菜单里有"全部删除"占位项，但无任何后端逻辑。
- **本仓库实现** `MockFileSystem` 新增 `deleteAllFilesInFolder(folderId)`（按 `parentId` 删文件、**保留文件夹本身**）；`FileViewModel` 加同名方法；`FileDetailScreen` 溢出项改"删除全部文件"接确认弹窗（复用 M5.5 的 `DeleteConfirmDialog`，文案强调"文件夹会保留，但文件无法恢复"），`files` 为空时禁用。另给空文件列表态补"导入第一个文件"入口。
- **原因** 落实 M0 占位项；与 `deleteFolder`（删文件夹**连同**文件）刻意区分——"删除全部文件"只清空内容、保留文件夹，是常见的两个独立动作。
- **Commit** `c9f410a`

---

## M6 — 即时通信

### M6.1 ChatViewModel — `contacts` 改私有 + `loadContacts()` 重读单例

- **v4 §6.1** `val contacts = MutableStateFlow(chatRepo.getContacts())` —— 公开可变、init 时一次性快照，之后仅 `sendMessage`/`clearContact` 内手动 `contacts.value = …` 刷新。
- **本仓库实现** 改为私有 `_contacts` + 只读 `contacts`，新增 `fun loadContacts()` 重读 `MockChatRepository`；`init`、`sendMessage`/`clearAllMessages`/`deleteContact`/`addContact` 后均刷新。
- **原因** M4 用 `selectedTab + when`（见 M4 偏离），故 `ChatListScreen`（Main 作用域）、`ChatDetailScreen`、`QrCodeScreen`（各自顶层路由）拿到的是**三个不同的 `ChatViewModel` 实例**（与 M5.3 同构，靠 `MockChatRepository` 单例兜底数据）。v4 的一次性快照会导致：QrCode 实例 `addContact`、或会话清空后，**列表实例的 `contacts` 不刷新** → 新建联系人/清空结果在列表里看不到。`loadContacts()` 让各屏进入时（`LaunchedEffect`）重读单例，对齐 M5 `FileViewModel.loadFolders()` 的"操作后重读"模式。
- **连带约定（影响 M6.2+）** `ChatListScreen` 的 onClick **不照抄** v4 §6.2 的 `chatViewModel.loadMessages(...)`——那是跨实例调用、对 `ChatDetail` 实例无效（`ChatDetailScreen` 自身 `LaunchedEffect(contactId)` 会 load）。列表点击只上抛 `contactId`。
- **Commit** `7f7c302`

### M6.1 ChatViewModel.sendMessage — 修复 v4 失效的"乐观更新"竞态

- **v4 §6.1** `sendMessage` 在 `viewModelScope.launch{ chatRepo.sendMessage(...).onSuccess{ 仅刷 contacts } }` **之外**同步执行 `_messages.value = chatRepo.getMessages(contactId)`，注释称"乐观更新"。
- **本仓库实现** 删掉 launch 外的同步刷新；在 `onSuccess` 内**同时**重读 `_messages` 与 `_contacts`。
- **原因** `chatRepo.sendMessage` 是 suspend 且内部 `delay(200)` 后才落库；launch 外那行同步 `getMessages` 此刻读到的是**旧列表**（新消息尚未入库），而 `onSuccess` 又只刷 `contacts` 不刷 `messages` → 发送后气泡要等下次进会话才出现。改为成功后以 repo 为准一并重读，行为正确（mock delay 内气泡延迟出现，可接受；如需即时反馈再做真乐观插入）。
- **Commit** `7f7c302`

### M6.1 ChatViewModel — `clearAllMessages` 接 `clearMessages`，丢弃 v4 的 `clearContact`

- **v4 §6.1 / patch §M6 改动1** v4 有 `fun clearContact(id)` 调 `chatRepo.clearContact`；patch 的 `clearAllMessages(id)` 同样调 `chatRepo.clearContact`。两者都依赖一个**本仓库已不存在**的方法。
- **本仓库实现** 只保留 `clearAllMessages(contactId)`，接 M1.4 拆分后的 `chatRepo.clearMessages`（保留联系人、清空消息），并 `_messages = emptyList()` + 重读 `_contacts`；**不**保留 v4 的 `clearContact`。
- **原因** M1.4 偏离已把单一 `clearContact` 拆成 `clearMessages` + `deleteContact`（见 M1.4 条）。patch UI"清空聊天记录"语义=保留联系人，对应 `clearMessages`；两个清空方法（v4 `clearContact` 与 patch `clearAllMessages`）功能重叠，去其一避免双份死代码。
- **Commit** `7f7c302`

### M6.1 ChatViewModel — 预留 `deleteContact`、不实现 `simulateReceiveMessage`

- **v4 §6.1** 含 `simulateReceiveMessage(content)`（模拟收消息的调试桩，仅往 `_messages` 内存追加、不落 repo）；无独立"删除联系人"方法（删除联系人能力在被拆分的 `clearContact` 里）。
- **本仓库实现**
  - 新增 `fun deleteContact(contactId)`，接 M1.4 的 `chatRepo.deleteContact`（连人带消息删）后重读 `_contacts`。**M6 patch UI 暂无入口**，先预留。
  - **不实现** `simulateReceiveMessage`。
- **原因** M6 各屏（patch 改动2–4）均无触发 `simulateReceiveMessage` 的 UI，且 v4 原版不落 repo（离开会话即丢）、行为不一致 → YAGNI，待真要演示"收消息"时再加并落库。`deleteContact` 则因 mock 层能力已具备、未来"删除联系人"动作大概率会用，成本极低，先暴露占位。
- **遗留** `connectionState`（v4 §6.1）保留为 mock 期占位，M6 UI 未消费，待真实 SDK 接入后驱动连接指示。`addContact` 的二维码解析沿用 patch 的 `substringAfter("sn=").substringBefore(",")`，对非法二维码不防御、对同一 deviceId 不去重——留待 M6.7 真实扫码阶段稳健化。
- **Commit** `7f7c302`

### M6.2 + M6.3 ChatListScreen — 迁移到 ChatViewModel，保留 M0 头部，绿点替验证徽标，搜索分三态

- **v4 §6.2 / patch §M6 改动2** v4 用 `TopAppBar`（标题 + QrCode action）、`navController` 下传、`ContactItem` 取 `contact.remark`/`isOnline` 绿点；列表来自 init 快照的 `contacts`。patch 改动2 把 `topBar` 改 Column 加搜索框、数据源换 `filteredContacts`。
- **本仓库实现**（M6.2 接线 `099e9d8` + M6.3 搜索 `834ba11`，同一文件 `ChatScreen.kt`）
  - 数据源 `MockData.contacts`（旧模型）→ `chatViewModel.contacts`（新 `data/model/Contact`）。字段映射：`name`→`remark`、`lastTime: String`→`formatTime(lastMessageTime: Long)`。
  - **保留 M0 自定义头部**（"即时通信" + "扫码建链" `FilledTonalButton` + "端到端加密…"副标题），未改用 v4 的 `TopAppBar`；导航维持 M0 回调（`onContactClick`/`onQrCodeClick`，经 MainScreen→NavGraph），未回退到 v4 的 `navController` 下传。
  - **绿色在线点替换验证徽标**：M0 脚手架在名字旁画 `VerifiedUser`（已验证身份）青色图标；改为 v4 §6.2 的 `isOnline` 绿点（`Success` token）。**原因** 用户 2026-05-24 选 v4 原方案；新模型已带 `isOnline` 字段。
  - **进屏刷新** 加 `LaunchedEffect(Unit){ loadContacts() }`（M6.1 约定，解决跨 VM 实例列表陈旧）。
  - **新增 `formatTime(Long)`**（仓库原有 `formatFileSize`/`formatFolderDate` 是 FilesScreen 私有，无时间格式化）：今日→HH:mm、昨日→昨天、本周→周X、跨周→MM-dd、跨年→yyyy-MM-dd；`<=0L`→空串（清空会话后 `lastMessageTime` 被置 0，避免显示 1970）。
  - **头像首字母** `remark.firstOrNull()` 兜底（脚手架 `name.first()` 对空串会崩）。
  - **搜索分三态**（patch 只是把数据源换 `filteredContacts`、沿用同一空态）：真无联系人→`EmptyContactsState`（"暂无联系人"+发起连接）；搜索无匹配→新增 `NoSearchResultState`（"未找到匹配「query」…"）；否则列表。搜索框仅在有联系人时显示。
  - 搜索基础设施（`searchQuery`/`filteredContacts`/`updateSearchQuery`）在 M6.1 已建（见上）；本阶段仅接 UI。
- **已知缺口** 进入会话清除 `unreadCount` 未在本阶段做——按用户 2026-05-24 要求放到 M6.4（"进入会话"即 ChatDetail 的职责）。
- **Commit** `099e9d8`（接线）、`834ba11`（搜索）

### M6.4 ChatDetailScreen — 接线 + 进会话清未读 + 保留 M0 装饰

- **v4 §6.3** `ChatDetailScreen(navController, contactId, …)` 持 navController；标题用裸 `contactId`；极简 `MessageBubble`（无头像、无气泡时间、无加密横幅）；底栏发文字 + AttachFile 发 mock FILE 消息；长按 → 删除（仅本端）/撤回（双向）。无"进会话清未读"。
- **本仓库实现**
  - **接线**：数据源 `MockData.chatMessages`（旧模型）→ `chatViewModel.messages`；`LaunchedEffect(contactId)` 调 `loadMessages`；回调返回 `onBack`（不持 navController，延续全局约定）。标题用 `contacts.find{ it.id==contactId }?.remark`（v4 是裸 `contactId`，不可读）。
  - **进会话清未读（用户 2026-05-24 要求，v4/patch 均无）**：新增 `MockChatRepository.markContactRead(contactId)`（`copy(unreadCount=0)`）+ `ChatViewModel.markRead`（清后重读 `_contacts`），在 `LaunchedEffect(contactId)` 随 `loadMessages` 一起调。
  - **底部导航真实未读角标**：M0 在 `MainScreen` 通信 Tab 写死 `Badge{Text("3")}`（注释标注"待 M6 替换"）。改为 `MainScreen` 注入同实例 `ChatViewModel`（与内部 `ChatListScreen` 同属 Main NavBackStackEntry），`totalUnread = contacts.sumOf{ it.unreadCount }`，`>0` 才显示且为真实数；`LaunchedEffect(Unit){ loadContacts() }` 使进/返本屏重读单例 → ChatDetail 清未读后角标同步递减/消失。
  - **保留 M0 装饰**（v4 极简版没有）：ECDH 副标题、"已建立端到端加密连接"横幅、双侧 Person 头像、气泡下方 Lock+时间。
  - **发送/发文件**：文字发送非空可点 + trim；AttachFile 接 mock `sendMessage("[文件] 示例文件.pdf", FILE)`（真实文件选取器按 patch 待实现第3条留 M11）。
  - **长按删除/撤回**：`combinedClickable` 长按 → `messageToDelete` 弹框，删除/撤回（仅 isMine）mock 下都调 `deleteMessage`。
  - **自动滚底**：`rememberLazyListState` + `LaunchedEffect(messages.size){ animateScrollToItem(messages.size) }`；因列表 index 0 是加密横幅，末条索引为 `messages.size`（非 `size-1`）。
  - **模型适配**：`msg.time:String`→本地 `formatMessageTime(timestamp:Long)`(HH:mm)；FILE 气泡优先 `fileName`+`fileSize`（新增本地 `formatFileSize`，因 FilesScreen 的同名函数是 file-private 不可跨文件复用），缺省回退 `content`；补 `AUDIO` 分支（v4 有，mock 下不可达，为完整保留）。
  - **键盘遮挡修复**：`MainActivity` 启用 `enableEdgeToEdge()`（`decorFitsSystemWindows=false`），manifest 虽为 `adjustResize` 但系统不再自动顶起布局；给底栏 Row 补 `.navigationBarsPadding().imePadding()`（对齐 v4 §6.3，脚手架曾丢失），使输入栏随键盘上升、Scaffold 相应缩短消息区。
  - 输入框沿用本地 `inputText`（不走 ViewModel StateFlow 往返，无中文/IME 输入问题，见 M6.3 修复条）。
- **遗留** 阅后即焚（火焰图标 + 时长弹框）保留为 M0 占位、仍不生效（模型有 `burnAfterRead` 字段未接逻辑）；`MoreVert` 暂空，M6.5 接搜索/清空菜单。撤回与删除在 mock 下行为相同（都仅删本端），真实双向撤回待 SDK。
- **Commit** `410cf42`

### M6.5 ChatDetailScreen 会话内搜索 + 清空会话（patch §M6 改动3）— 搜索模式 + 清完才返回

- **patch §M6 改动3** topBar actions 切 `showSearchBar`：true 时在 actions 内放一个 200dp 内联搜索框；false 时 `MoreVert` 菜单（搜索消息 / 清空聊天记录）。清空确认后 `clearAllMessages(contactId)` 然后**立即** `navController.popBackStack()`。`clearAllMessages` 调 `chatRepo.clearContact`。
- **本仓库实现**
  - **搜索模式（标题变输入框）**：选"搜索消息"后，顶栏**标题区**整体变白色 `OutlinedTextField`（适配 Primary 色顶栏），actions 变关闭按钮，火焰图标隐藏；关闭即清空退出。非 patch 的"actions 内嵌 200dp 框"——避免与标题+火焰图标在彩色顶栏上拥挤。
  - 搜索框用**本地 `remember` 状态**（patch 本就如此），非 ViewModel StateFlow，故无中文/IME 输入问题（见 M6.3 修复）。
  - `displayMessages = remember(messages, searchQuery){ 空→messages / 非空→searchMessages }`；无匹配显示"未找到匹配「…」的消息"（patch 未处理空结果）。
  - **清空接 `clearMessages`**（非 patch 的 `clearContact`，M1.4 已拆分，保留联系人）。
  - **清完才返回（修作用域取消 bug）**：`clearAllMessages` 加 `onComplete` 回调，确认后 `clearAllMessages(contactId, onComplete = onBack)`——**清除完成后才** `onBack()`。patch 的 fire-and-forget + 立即 popBackStack 会销毁本 ChatDetail VM 的 `viewModelScope` → 打断 `clearMessages` 的 `delay(300)` → 清除半途中断（与 M3.5 忘记密码同款坑）。
- **Commit** `02a36de`

### M6.6 联系人列表滑动操作（置顶 + 删除）— v4/patch 之外的增量

- **v4 / patch** 均**无**联系人列表的滑动操作；contact 也无置顶概念。
- **本仓库实现**（用户 2026-05-24 要求，`77e34e9`，`ChatScreen.kt` + 模型/repo/VM）
  - **左滑显示右侧操作块**：`SwipeableContactItem` 用 `Animatable` 偏移前景卡片 + `detectHorizontalDragGestures` 拖动，松手按半程阈值吸附开/合（Compose 无现成"滑出常驻按钮"组件，`SwipeToDismissBox` 是滑动消除，故自实现）。最外层 `clip(RoundedCornerShape(12.dp))`，否则卡片圆角缺口处会露出背景方形操作块的方角。
  - **置顶**：`Contact` 新增 `isPinned: Boolean = false`；`MockChatRepository.getContacts()` 排序由"按 lastMessageTime 倒序"改为 `compareByDescending{isPinned}.thenByDescending{lastMessageTime}`（置顶优先）；新增 `MockChatRepository.togglePin` + `ChatViewModel.togglePin`（切后重读 `_contacts` 重排）。列表项置顶态：浅灰底（`Surface`）+ 名字旁图钉图标；滑块文案按状态"置顶/取消置顶"。
  - **删除**：滑块"删除"→ 屏幕级二次确认框（与 M6.5"清空聊天记录"同款文案）→ `clearAllMessages(contactId)`。**注意**：按用户指定，此"删除"实际走的是**清空聊天记录**逻辑（保留联系人、清空消息），而非 `deleteContact`（删除联系人本身，VM 已预留但此处未用）。
- **遗留** `togglePin` 是单纯切换、无"置顶上限/排序时间戳"；"删除"语义上仅清消息不删联系人（如日后要真删联系人，VM 的 `deleteContact` 已就绪可换接）。
- **Commit** `77e34e9`

### M6.4 跟进 — 长按消息菜单：全屏弹框 → 微信式深色横排气泡（锚定气泡下方）

- **承接上文 M6.4** 原实现把长按消息的"删除/撤回"放在一个**全屏居中** `AlertDialog`（`messageToDelete` 状态驱动）。用户 2026-05-24 要求改成微信式、显示在消息下方的上下文菜单。
- **本仓库实现**（`ChatScreen` 无关，仅 `ChatDetailScreen.kt`）
  - 删除 `messageToDelete` 全屏弹框；改为 `ChatBubble` 内本地 `showMenu`，长按气泡 `combinedClickable(onLongClick)` 触发。
  - 自定义 `MessageActionMenu`：**深灰圆角 `Popup`**（`#4C4C4C`）+ 横排 `MessageActionItem`（图标在上、文字在下，删除/撤回），`PopupProperties(focusable=true)` 点外部/返回关闭。
  - **定位**：先用 `Popup(alignment = BottomStart)` 会把弹窗压在气泡之上（覆盖消息）；改用自定义 `PopupPositionProvider`，弹窗左上角 = 气泡左下角 + 6dp 间隙，强制显示在消息**下方**；x 方向 clamp 不超出屏幕。
  - 菜单标签精简为"删除/撤回"（原全屏版为"删除（仅本端）/撤回（双向）"），贴合微信小菜单；mock 下两者仍都调 `deleteMessage`。
  - **遗留** `Popup` 不像 `DropdownMenu` 自动按空间上/下翻转——靠屏幕最底部的消息其菜单仍固定显示在下方，必要时后续再加按位置翻转的逻辑。
- **Commit** `eebba8b`

### M3.3 跟进 — DevControlPanel 三个常驻按钮 → 左上角三点菜单

- **承接上文 M3.3 测试设施** 原实现把"模拟拔出/插入 USB"、"模拟未初始化插入 → 初始化"、"模拟已初始化插入 → 登录"三项 DEV 操作以三个常驻胶囊按钮显示在顶部居中。
- **本仓库实现** 保留三项 DEV 能力与调用链不变，仅把 UI 收束为左上角一个圆形 `MoreVert` 悬浮按钮；点击后通过 `DropdownMenu` 展开三项操作。按钮使用 `statusBarsPadding()` 避开状态栏，仍覆盖在 `NavGraph` / `UsbDisconnectedOverlay` 之上，便于调试拔卡场景。
- **原因** 三个常驻按钮遮挡真实页面，影响验收与截图观察；收束为单入口后默认只占 44dp 左上角区域，DEV 操作仍随时可达。
- **遗留** 该入口仍是 mock 期脚手架；M11 接真实 FSShell SDK 时与 `MainActivity` 启动兜底分支、`DeviceViewModel.debug*` 方法一起删除。
- **Commit** `08c223a`

### M6.4 跟进 — 删除/撤回消息后重算联系人列表摘要

- **问题** 联系人列表显示 `Contact.lastMessage` / `lastMessageTime`，但原 `MockChatRepository.deleteMessage()` 只删除会话消息，不回写联系人摘要；`ChatViewModel.deleteMessage()` 也只刷新当前会话消息。因此删除或撤回最后一条消息后，列表仍显示旧摘要，只有清空会话会变空。
- **本仓库实现** `MockChatRepository` 新增 `updateContactPreview(contactId)`，按该会话剩余消息中 `timestamp` 最新的一条回写 `lastMessage` / `lastMessageTime`；若会话已空则写空串与 `0L`。`sendMessage`、`deleteMessage`、`clearMessages` 统一调用该函数。`ChatViewModel.deleteMessage()` 删除后同步重读 `_contacts`。
- **原因** mock 期也应维护与真实数据源一致的会话摘要契约：联系人列表永远展示对应会话的最后一条消息，不区分己方/对方。
- **Commit** `1550632`

### M6.7 QrCodeScreen 生成模式 — 保留 M0 Tab 壳 + 120s 倒计时，仅替换生成区为真实渲染

- **v4 §6.4** 单屏（仅生成）布局：`QrCodeScreen(navController, chatViewModel)` 内 `generateQrContent()` → `QRCode.ofSquares().withSize(10).build(content).render()` → `BitmapFactory.decodeByteArray` → `Image`；失败兜底文案；屏幕持 `navController` 自行返回。
- **本仓库实现**（`QrCodeScreen.kt`）
  - **保留 M0 Tab 壳**（生成/识别双 Tab + `TopAppBar("扫码建链")`），仅替换"生成" Tab 内容；"识别" Tab 维持 M0 占位不动，留给 M6.8 接 CameraX。**原因** 用户 2026-05-24 决策：M6.8 仍要双 Tab 形态，来回拆装更乱。
  - **保留 M0 120s 倒计时**：现屏 `qrGenerated` 触发倒计时；过期 → `qrGenerated=false` 回 pre-gen，且**同时清 `qrBitmap`/`qrContent`**（v4 未规定过期行为）。**原因** 用户 2026-05-24 决策保留；与 v4 patch 后续扫码侧的 TTL 概念一致。
  - **真实渲染**：注入 `ChatViewModel = hiltViewModel()`；点"生成"按钮 → `chatViewModel.generateQrContent()` → 在 `Dispatchers.Default` 内 `QRCode.ofSquares().withSize(10).build(content).render().getBytes()` → `BitmapFactory.decodeByteArray` → `qrBitmap`；成功置 `qrGenerated=true`，失败置 `qrError="二维码生成失败，请重试"` 显示在按钮下方。**原因** v4 范例直接在 LaunchedEffect 内同步渲染；qrcode-kotlin 渲染是 CPU 工作，提到 `withContext(Dispatchers.Default)` 避免阻塞主线程。按钮 `enabled=!isGenerating` 防双击。
  - **InfoRow 字段对齐 mock schema**：M0 写死 `SC-2026051300001/ECDH secp256r1/33字节(压缩格式)` 与 `MockChatRepository.generateQrContent()` 实际输出（`ver=1,sn=MOCK_SN_001,ipv6=fe80::1,sid=…,tpk=MOCK_PUBLIC_KEY_BASE64`）不符。改为 `设备SN=MOCK_SN_001 / IPv6=fe80::1 / 会话ID=随机生成 / 临时公钥=MOCK_PUBLIC_KEY_BASE64 / 有效期=120秒`，pre-gen 卡片所列字段即 QR 实际内容。
  - **生成后内容预览**：M0 在二维码下方写死 JSON 风格字串；改为显示 `qrContent`（实际 `generateQrContent()` 返回的 CSV 风格 `key=value,…`）。
  - **导航**：维持 M0 `onBack` 回调（不持 navController），延续全局约定（见顶部"全局约定"节）。
  - **"重新生成" / "分享"**：重新生成按钮额外清 `qrBitmap`/`qrContent`；"分享"按钮维持 M0 no-op 占位，未在 v4 §6.4 中规定，留待后续。
- **遗留**
  - "识别" Tab 整体仍是 M0 假相机占位 → M6.8 接 CameraX + MLKit。
  - `generateQrContent()` 输出为 `key=value,…` CSV 而非 v4 §6.4 范例提到的 JSON——属 M1.4 既有现状（mock 期），未改动。
  - `qrcode-kotlin` 解码出的 PNG 直接 `BitmapFactory` 解码后未做尺寸裁剪/缓存，重复生成会重新分配 Bitmap；mock 期可接受。
- **Commit** `99ef281`

### M6.8 QrCodeScreen 扫码模式 — 取消按钮 + 主动相机释放 + 独立 onScanConnected 回调

- **v4 §6.4 / patch §M6 改动4** v4 扫描分支用 `mode=="scan"` 启 CameraX + MLKit `BarcodeScanning`（FORMAT_QR_CODE）；扫到 `rawValue` 设 `scanned=true` 后直接 `popBackStack()`。patch 把成功回调改为弹"备注对话框"，`onDismissRequest = { /* 不允许点外部关闭 */ }`，只暴露"确认建链"按钮（`remark.isNotBlank()` enabled）→ `chatViewModel.addContact` + `popBackStack()`。**无取消按钮、无返回键逃生口、无运行时权限处理、无相机生命周期收尾**。
- **本仓库实现**（`QrCodeScreen.kt` + `NavGraph.kt`）
  - **保留 M0 Tab 壳**（生成/识别双 Tab），仅替换"识别" Tab 内容；与 M6.7 决策一致。CameraX 提取到私有 `ScanTab` / `CameraPreview` Composable，便于权限态切换与 dispose。
  - **运行时 CAMERA 权限**：用 `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`（非 accompanist——已废弃且会增加依赖）。首次进识别 Tab 自动 `launch`；被拒后改显"需要相机权限以扫描二维码"+「授权相机」按钮，重新点击重试。v4/patch 均未处理权限。
  - **取消按钮（**偏离 patch**）**：备注 `AlertDialog` 加 `dismissButton = "取消"`，点击 → 关弹框 + `scanned=false` + `scannedContent=""`，回到扫码态可重扫。`onDismissRequest = {}` 仍然 no-op，故**点外部 / 返回键不关闭**，保持 patch 的"强制选择"半意图；只是给用户**显式逃生口**。**原因** 用户 2026-05-28 决策：误扫无任何取消方式 UX 太硬，且后续无法验证 QR 真伪时也需能退出。
  - **CameraX 主动释放**（v4 无）：私有 `CameraPreview` Composable 内 `DisposableEffect(Unit){ onDispose{ ProcessCameraProvider.getInstance(...).unbindAll(); barcodeScanner.close() } }`。`bindToLifecycle` 虽绑 Activity 生命周期、能跟随屏幕销毁释放，但**Tab 切换（识别 → 生成）只是 AndroidView 离场、Activity 仍 RESUMED**，不主动 unbind 则相机传感器、analyzer 与 MLKit scanner 全部继续工作。
  - **scanned 闭包穿透**：`ImageAnalysis.setAnalyzer` 的 lambda 只在 `factory` 内注册一次；用 `rememberUpdatedState(scanned)` / `rememberUpdatedState(onQrDetected)` 让 analyzer 每帧读 MutableState 最新值，避免捕获初始 `false`/初始回调；扫到后再设 `scanned=true` 才能真正阻止后续帧重复触发。
  - **独立 `onScanConnected` 回调**：新增 `onScanConnected: () -> Unit = onBack`，与顶栏 `onBack` 解耦（即便今天两者都 `popBackStack`，扫码成功 vs 用户主动返回是不同语义；未来如改去新建联系人的 Chat 详情，只改 NavGraph 一处）。沿用全局回调约定，QrCodeScreen 不持 `navController`。
  - **`scannedContent` 显示截断**：dialog 内展示 `scannedContent.take(40) + "..."`（patch 是 `take(20) + "..."`），但 mock `generateQrContent()` 返回 `ver=1,sn=MOCK_SN_001,...` 约 70+ 字符，patch 截 20 字看不到 sn → 调到 40 字够暴露 deviceId 又不撑爆 dialog。截断仅在长度真正超过 40 才追加省略号。
  - **`addContact` 入参 `remark.trim()`**：patch 原文 `chatViewModel.addContact(scannedContent, remark)` 直接传，含首尾空格。trim 防"   "之类瞎填能 `enabled` 但实际为空名。仍保留 `enabled = remark.isNotBlank()` 防止全空白。
  - **导航不调 `loadContacts()`**：扫码成功 → `popBackStack` 回到 ChatList，依赖 M6.1 偏离 `ChatListScreen` 的 `LaunchedEffect(Unit){ loadContacts() }` 重读单例 → 新联系人自然出现，无需在 QR 屏额外触发。
- **遗留**
  - "从手机相册选择二维码图片"按钮维持 M0 no-op 占位（patch 未规定）；如后续要做需引入 `ActivityResultContracts.PickVisualMedia` + `BarcodeScanner.process(InputImage.fromBitmap(...))`。
  - `addContact` 解析 `substringAfter("sn=").substringBefore(",")` 对非法 QR 不防御（M6.1 已记），M6.8 维持。若扫到非密盾 QR，会用整串后段当 deviceId 创建联系人，靠"取消"按钮逃生。
  - 被永久拒绝相机权限（"不再询问"）的兜底（跳系统设置）未做；当前表现为"授权相机"按钮无效，可后续加 `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` intent。
  - 阅后即焚、连接验证（v4 patch 卡片提到的"验证来源、IPv6 地址及签名"）仍是 mock 期文案，未实接 SDK，留待 M11+。
- **Commit** `2244876`

### M6.7 跟进 — "重新生成" 改为原地刷新（不退回 pre-gen 卡片）

- **承接上文 M6.7** 原实现的"重新生成"按钮把 `qrGenerated=false / qrBitmap=null / qrContent=null / countdown=120` 全部清空，UI 退回 pre-gen 的连接信息卡片，用户需再点一次"生成临时ECDH密钥对并创建二维码"才能拿到新二维码——多一次点击。
- **本仓库实现**（用户 2026-05-28 要求，`QrCodeScreen.kt`）
  - 把渲染 `LaunchedEffect(isGenerating)` 从 pre-gen 分支提到屏幕作用域顶层，让 pre-gen 与 post-gen 共享同一渲染路径。
  - "重新生成"按钮只 `isGenerating=true`（不再清空状态），顶层 effect 渲染成功后**原地替换** `qrBitmap`/`qrContent`、`countdown=120`，UI 始终停在 post-gen 视图。
  - 刷新期间按钮 `enabled=!isGenerating`、显示 `CircularProgressIndicator(16dp)+"刷新中…"`；用户不会看到 QR 闪空，旧码维持显示直到新码就绪。
  - 渲染失败时旧码维持不变，按钮下方追加 `qrError` 文案，可直接再点重试。
- **原因** v4 §6.4 是 generate-only 单屏、无 TTL 概念，自然没有"刷新"语义；我们保留 120s 倒计时后，过期 + 用户主动刷新都应保持在"已生成"视觉上，避免来回切换 pre/post 两套布局；切换会丢失上下文，且 pre-gen 卡片对已生成过一次的用户已无信息价值。
- **Commit** `2b6debf`

---

## M7 — 设置页重写

### M7.1 SettingsScreen 骨架 — 回调式导航 + 删 M0 防录屏 Switch + 退出登录走 Splash

- **v4 §7.1 / patch §M7** v4 `SettingsScreen(navController, deviceViewModel)` 持 navController 自行 `popBackStack/navigate`；分三区（设备信息 / 安全操作 / 账户），无"危险操作"分区（一键清理在"安全操作"里），无关于；helper 命名 `SettingsSectionHeader / SettingsInfoItem / SettingsActionItem`。M0 仓库的 `SettingsScreen()` 无参、无 ViewModel、hardcoded 假数据，含**防录屏 Switch（只读，无 FLAG_SECURE 后端）**、自动锁定 5min（只读）、关于密盾、一键清理 + 恢复出厂双按钮（弹框 confirm 不动作）。
- **本仓库实现**（`SettingsScreen.kt` + `MainScreen.kt` + `NavGraph.kt`）
  - **签名走回调**：`SettingsScreen(onLogoutComplete, onFactoryResetComplete, deviceViewModel)`，**不持 navController**，延续全局约定。MainScreen 新增同名 2 个回调，NavGraph 在 `Screen.Main` 内 wire 到 `popUpTo(0) + navigate(Splash/Init)`。理由同既往叶子屏。
  - **退出登录走 Splash 而非直跳 Login**（用户 2026-05-28 决策）：`onLogoutComplete = { navigate(Splash) { popUpTo(0){inclusive=true} } }`。Splash 看到 `status≠AUTHENTICATED && isInitialized=true` 会自动路由到 Login，并附带 2.5s 启动动画作为视觉反馈。直跳 Login 更快但缺反馈。v4 §7.1 仅写 `deviceViewModel.logout()`、不导航，等同方案 A——屏幕不动，用户困惑；本仓库改方案 B。
  - **恢复出厂跳 Init（不经 Splash）**：`onFactoryResetComplete = { navigate(Init) { popUpTo(0){inclusive=true} } }`，照 patch §M7 改动1 字面（避免再加 2.5s 动画延迟，且 wipeAll 后 isInitialized=false → Splash 也会路由到 Init，直跳等价但更快）。
  - **保留"一键清理 + 恢复出厂"双按钮（用户 2026-05-28 决策）**：patch §M7 改动1 把"一键清理"合并掉换成"恢复出厂"，用户否决——两条独立通路：一键清理保持登录态仅清数据，恢复出厂走 wipeAll+跳 Init。M7.1 仅留两按钮 UI 与 M0 弹框壳（确认即关、不动作），真接线推到 M7.2。
  - **删 M0 防录屏 Switch**（用户 2026-05-28 决策）：M0 的 `antiScreenshot: Boolean` 只是本地 state，不真正调 `window.setFlags(FLAG_SECURE)`（M2/M8 才该做），保留误导用户。M7.1 范围内整块删除；M8 落 FLAG_SECURE 时再决定是否暴露开关。
  - **保留"关于密盾"对话框**（M0 装饰，v4/patch 无）：仅展示文案，无误导；与其他保留 M0 装饰（M2 `UsbDisconnectedOverlay`、M6.2 自定义头部）同一类决策。
  - **保留"自动锁定 5 分钟"行**（用户决策"实现"，M7.5 落地）：M7.1 范围内保留为占位（onClick 空），M7.5 把 `DeviceViewModel.INACTIVITY_TIMEOUT_MS` 改成可调 state 并接弹框选项。
  - **保留"密钥更新 / 设备绑定管理"行**（占位）：onClick 空，接线由 M7.4/M7.3 完成。
  - **设备信息字段对齐**：M0 写死 `SC-2026051300001 / MI-X8F2K9A3 / 已绑定 / v1.0.3 / 16GB(明文)+32GB(加密) / T620`，与 `MockUsbManager.simulateInsert` 实际产出（`deviceId=MOCK_DEVICE_001` / boundPhoneId 视 initDevice 时的 bindDevice）不符。改为读 `deviceStatus.deviceId.ifEmpty{未知}` + 真实绑定状态 + 容量保持 "-- / 32 GB" 占位（M11 SDK 接入后读卡）；删除固件版本/芯片型号/明文区容量等 mock 无对应字段的行。
  - **Helper 改 v4 命名**：M0 的 `DeviceInfoRow / SettingItem` 改名 `SettingsInfoItem / SettingsActionItem`，新增 `SettingsSectionHeader`（带 `color` 参数，危险分区用 Danger、设备信息分区用 Primary、其余用 TextSecondary）。
  - **TopBar 不加**：MainScreen 已含 BottomBar，无 TopBar；Settings Tab 沿用 M0 内联 `Text("设置")` 标题（v4 §7.1 是全屏独立路由，故有 TopAppBar；本仓库 Settings 是 Tab，加 TopAppBar 与其他 Tab 不一致且占空间）。
- **遗留**
  - 一键清理 + 恢复出厂 在 M7.1 阶段是 M0 弹框壳（无密码、确认即关），真实接线在 M7.2。期间用户点确认无任何后端效果；为窗口期可接受。
  - 自动锁定行（M7.5）/ 设备绑定管理行（M7.3）/ 密钥更新行（M7.4）onClick 空，点了无反馈。
- **Commit** `d124f13`

### M7.1 跟进 — 退出登录改为底部红色按钮，关于密盾降为页脚链接

- **承接上文 M7.1** 原实现把"退出登录"放在"账户"分区卡片里作为一个普通 `SettingsActionItem`（图标 + 标题/副标题 + ChevronRight），把"关于密盾"放在最下方独立卡片里同样是 `SettingsActionItem`。用户 2026-05-28 反馈：希望退出登录视觉上更突出（底部红色按钮）、关于降为最底部小字链接。
- **本仓库实现**（`SettingsScreen.kt`）
  - 删除"账户"分区（含 `SettingsSectionHeader("账户")` 与其 Card）；改在"危险操作"分区下方 24dp 间距处放 `Button(containerColor=Danger, fillMaxWidth, height=48dp, shape=RoundedCornerShape(12dp))`，内含 `Icons.Default.Logout` + "退出登录" 文字。onClick 行为不变（`deviceViewModel.logout() + onLogoutComplete()`）。
  - 删除"关于密盾" Card；改在退出按钮下方 24dp 处放 `Box(fillMaxWidth, contentAlignment=Center)` 包 `TextButton`，文字 `"关于密盾 v1.0.0"`（12sp / `TextSecondary`）。点击仍弹原 About 对话框（M0 保留装饰）。
- **原因** 用户视觉优先级取舍：退出登录是高频且需要明确可见性的操作，做成红色 filled 按钮符合常见 App 末位"退出/注销"惯例（微信、支付宝、Telegram 等）；关于则是低频信息查阅，footer 小字链接足够。
- **影响** 退出按钮使用 `Danger` 色与"一键清理 / 恢复出厂"危险操作同色，视觉上略有"危险化"暗示（用户接受）；如需弱化可改 OutlinedButton + `Danger` 边框，本次按 patch 用户原意"红色按钮"取 filled 风格。
- **Commit** `67dbf38`

### M7.2 一键清理 + 恢复出厂 — 双危险通路、密码确认、抗作用域取消

- **v4 §7.1 / patch §M7 改动1** v4 §7.1 一键清理仅"确认即关、不动作"的占位弹框；patch §M7 改动1 把"一键清理"**直接换成**"恢复出厂设置"（密码确认 + `factoryReset` + 跳 Init），不保留独立一键清理通路。
- **本仓库实现**（用户 2026-05-28 决策保留两条独立通路，`MockUsbManager.kt` + `DeviceViewModel.kt` + `SettingsScreen.kt`）
  - **`MockUsbManager.wipeUserData()`**（新增）：`delay(1500)` + `fileSystem.clear() + chatRepository.clear()`，**不动** `storedPassword`/`isInitialized`/`boundPhoneId`/`status`。与 `wipeAll()` 严格区分——后者多 4 项写回（清密码、清绑定、退初始化、退到 CONNECTED），是"恢复出厂"语义。
  - **`DeviceViewModel.wipeUserData(password, onSuccess, onError)`**（新增）：`authenticate(password)` 通过 → `wipeUserData()` → `onSuccess()`；失败 → `onError("密码错误")`。
  - **`DeviceViewModel.factoryReset(password, onSuccess, onError)`**（新增）：`authenticate(password)` 通过 → `wipeAll()` → `onSuccess()`；失败同上。与已有的 `wipeAndReset(onComplete)` 行为相似但多一道密码校验——前者来自 LoginScreen 忘记密码（无密码）、本方法来自 Settings 危险操作（必须二次校验，与 patch 一致）。两者并存、未合并，避免改动 LoginScreen。
  - **抗作用域取消**：`factoryReset` 的 onSuccess 在弹框内触发 `dismiss() + onFactoryResetComplete()`，后者执行 `navigate(Init){popUpTo(0)}` 会销毁 Main NavBackStackEntry → DeviceViewModel.viewModelScope 取消。关键：`wipeAll()` 已在 onSuccess 触发前完成（`viewModelScope.launch{ authenticate.onSuccess{ wipeAll(); onSuccess() } }`，wipeAll 是 suspend、完成才推进），故取消时 onSuccess lambda 已经在执行同步导航；与 M3.5 忘记密码同款模式。
  - **密码弹框**：`AlertDialog` 含 `OutlinedTextField(PasswordVisualTransformation + KeyboardType.Password)`，错误时 `isError=true + supportingText=红色"密码错误"`；处理中 confirm 按钮变 `CircularProgressIndicator + "清理中…/重置中…"`、`enabled=false`，期间 `onDismissRequest`/取消按钮均禁用（防中途关弹框、防 wipe 被异常打断）。
  - **`onDismissRequest`**：与 M6.8 备注弹框一致——非处理中允许点外关闭并清状态（密码/错误/loading）；处理中 no-op。
  - **一键清理副本上下游影响**：清完不导航、用户留在 Settings；返回 Files / Chat Tab 时各自 `LaunchedEffect{loadFolders()/loadContacts()}` 重读单例（M5.3 / M6.1 偏离）→ 列表自然变空。Settings 本屏不显示文件/聊天列表，故无需额外刷新。
- **遗留**
  - 没有"取消时也清状态"以外的回滚——一旦点确认、密码正确，清理无法撤销（与产品本意一致）。
  - 处理失败（authenticate 网络异常等非"密码错误"场景）当前统一报"密码错误"。mock 期 `authenticate` 只可能返 `password mismatch`，所以语义无误；M11 真 SDK 接入时应按真实异常类型分支显示。
  - 一键清理无 Snackbar/Toast 成功反馈，仅靠弹框关闭。Settings 内层无 SnackbarHost（Tab 内容、无 Scaffold），加全局 Snackbar 需上提到 MainScreen 改架构，**本里程碑不做**。
- **Commit** `4796306`

### M7.3 绑定管理 — 单行动态化、复用 SettingsActionItem，未实接绑定校验

- **patch §M7 改动2** 单独建一个 `SettingsSectionHeader("手机绑定管理")` 区块：里面是一行 `SettingsInfoItem("绑定状态", ...)` + 一行 `SettingsActionItem`（按 isBound 切"绑定本机/解绑本机"），下方一个密码确认 `AlertDialog`。
- **本仓库实现**（`MockUsbManager.kt` + `DeviceViewModel.kt` + `SettingsScreen.kt`）
  - **`MockUsbManager.updateBinding(bind)`**（新增）：非 suspend，按 patch 取 `Settings.Secure.ANDROID_ID` 写 `boundPhoneId`；bind=false 清空。仅改状态、不延迟。
  - **`DeviceViewModel.updateBinding(password, bind, onSuccess, onError)`**（新增）：与 `wipeUserData / factoryReset` 同结构——`authenticate` 通过 → `updateBinding` → onSuccess；失败 → "密码错误"。
  - **不另建"手机绑定管理"分区**：M7.1 的设备信息卡顶部已显示"绑定状态"（真 boundPhoneId 驱动），独立分区会与之重复。沿用 M7.1 的"安全操作"卡片，把其中的"设备绑定管理"占位行替换为 **按 isBound 动态渲染** 的 `SettingsActionItem`：
    - 已绑定：`title="解绑本机"`, `subtitle="解绑后安全卡可在其他手机使用"`, `icon=PhonelinkErase`, `iconTint=Warning`
    - 未绑定：`title="绑定本机"`, `subtitle="绑定后安全卡只能在此手机使用"`, `icon=PhonelinkSetup`, `iconTint=Accent`
    点击即弹密码确认框；成功后 deviceStatus 经由 StateFlow 推到设备信息卡 + 同一行 ActionItem，整页同步翻转。
  - **`bindAction` 捕获时机**：弹框打开时按当前 `isBound` 捕获 `bindAction = !isBound`，避免成功后 deviceStatus 变化导致弹框文案/按钮颜色在 dismiss 动画期间抖动。
  - **弹框样式**：与 M7.2 一致的密码 OutlinedTextField + 内联错误 + loading spinner + 处理中禁用 dismiss。confirm 按钮颜色随 bindAction 切（绑定=Accent，解绑=Warning），与上方 ActionItem 图标颜色一致。
  - **认证未联动 boundPhoneId**：mock 期 `MockUsbManager.authenticate` 仍只校验 storedPassword，**不**校验 boundPhoneId——即用户在 A 手机绑定后插入 B 手机，仍能用同一密码登录。这是 M2/M3 的既有现状，M7.3 不扩张范围；真 SDK 接入时（M11）需在 authenticate 处补 `boundPhoneId == currentPhoneId || boundPhoneId == null` 校验。
- **遗留**
  - 解绑/绑定的安全卡 boundPhoneId 字段是 `Settings.Secure.ANDROID_ID`——Android 8.0+ 该值按 app 签名 + 用户隔离，重装会变；mock 期可接受，真产品需配合 SDK 出货时的唯一手机标识方案。
  - 无"绑定到不同设备时强制清空数据"流程，patch/v4 均未要求；如需可后续与 wipeUserData 串联。
- **Commit** `a0c4ad1`

### M7.4 密钥更新 — 两态弹框（输入 / 成功），mock 期纯流程

- **patch §M7 改动3** 单弹框含输入态（密码 + 三条要点说明 + "确认更新"）与成功态（CheckCircle 图标 + "密钥已更新" + 提示文案 + "完成"），靠 `keyUpdateSuccess: Boolean` 切换；onConfirm 在输入态调 `deviceViewModel.updateKey`，成功态 onConfirm 是关闭弹框；输入态保留 "取消" 按钮，成功态隐藏。
- **本仓库实现**（`MockUsbManager.kt` + `DeviceViewModel.kt` + `SettingsScreen.kt`）
  - **`MockUsbManager.updateKey()`**（新增 suspend）：仅 `delay(1000)` 占位。mock 期没有可轮换的实体密钥状态——文件加密 / 消息加密均未实接；此方法存在的意义是保持"Mock 提供 IO + ViewModel 编排"的分层，真 SDK 接入后只需在这里调安全卡密钥更新接口，VM 与 UI 不动。
  - **`DeviceViewModel.updateKey(password, onSuccess, onError)`**（新增）：与 M7.2/M7.3 同结构——`authenticate` 通过 → `updateKey()` → onSuccess；失败 → "密码错误"。
  - **统一弹框两态**：保留单个 `AlertDialog`，icon / title / text / confirm / dismiss 五处全按 `keySuccess` 分支：
    - 输入态：Key 图标 / Accent 色 / "密钥更新" 标题 / 三要点说明文 + `OutlinedTextField(Password)` / "确认更新" + "取消"；处理中 confirm 变 `CircularProgressIndicator + "更新中…"` 并禁用 dismiss。
    - 成功态：CheckCircle 图标 / Success 色 / "密钥已更新" 标题 / 纯文案（无输入框）/ "完成" (Success 色) / 无 dismiss 按钮（避免与"完成"语义重复）。
  - **状态切换**：`onSuccess` 内 `keyLoading=false + keyPassword="" + keySuccess=true`；不立刻 dismiss——用户需要主动点"完成"关闭，确认看到反馈。再开弹框前 `dismiss` lambda 把 `keySuccess` 也重置为 false，保证下次开框从输入态开始。
  - **`onDismissRequest`**：与其他危险/重要操作一致——处理中 no-op；非处理中 dismiss（含成功态点外）。成功态点外属"等同点完成"，可接受。
- **遗留**
  - mock 期无密钥实体，"更新"后无任何下游可观察的变化（既看不到旧密钥也看不到新密钥，文件解密链路不受影响）。真 SDK 接入后会有钥匙轮换的副作用（如挂载阶段重新解密 KEK），届时这里的成功态文案应补充实际效果说明。
  - 没有"撤销 / 恢复旧密钥"通道——产品本意密钥更新即不可逆；mock 期保持一致。
  - patch 文案"新密钥不暴露给上层、无法查看或复制"在成功态被压缩到一句话；如需展开三要点也可，目前简化以减少视觉重复（输入态已列）。
- **Commit** `a4b0b82`

### M7.5 自动锁定可调 — StateFlow 化、SettingsActionItem 加 trailing 槽、Compose 原生滚轮 picker

- **patch §M7 / v4** v4 把超时硬编码、UI 仅展示文字"5 分钟"；patch §M7 未涉及。属用户决策范围（2026-05-28）：要做成可调，UI 用滚轮 picker。
- **本仓库实现**（`DeviceViewModel.kt` + `SettingsScreen.kt`）
  - **`DeviceViewModel`**：删 `companion object INACTIVITY_TIMEOUT_MS = 5 * 60 * 1000L`，改为：
    - `private val _inactivityTimeoutMinutes = MutableStateFlow(DEFAULT_TIMEOUT_MIN=5)`
    - `val inactivityTimeoutMinutes: StateFlow<Int>` 对外只读
    - `fun setInactivityTimeoutMinutes(minutes: Int)` 写入
    - `resetInactivityTimer()` 改读 `_inactivityTimeoutMinutes.value * 60_000L`
  - **`SettingsActionItem` 增加 `trailing` 槽**：默认 `@Composable () -> Unit = { Icon(ChevronRight,...) }`，调用方可覆盖。自动锁定行用此槽显示当前值文字而非箭头：
    ```
    [⏲ 自动锁定]   [后台无操作 5 分钟后自动退出]   [5 分钟]
    ```
  - **`WheelTimePicker`（私有 Composable）**：Compose 原生滚轮，无新增依赖：
    - `LazyColumn + rememberLazyListState() + rememberSnapFlingBehavior()` → 滚停吸附
    - `contentPadding = vertical itemHeight (48dp)` → 中间一行为选中、上下各一行半透明背景留白
    - 中间高亮 `Box` 绘 `Primary.copy(0.08f)` + 8dp 圆角作为选中条
    - `centerIndex = derivedStateOf{ listState.firstVisibleItemIndex }`：snap 后该值即中心项
    - `LaunchedEffect(centerIndex){ onSelectionChanged(...) }` 实时回调上抛
    - `LaunchedEffect(Unit){ scrollToItem(indexOf(initialValue)) }` 初始定位
  - **可选项 `TIMEOUT_OPTIONS_MIN = [1, 3, 5, 10, 15, 30, 60]`**：覆盖常见 1 / 5 / 10 / 30 + 中间挡位，7 项让滚轮有可滚动手感（少于 5 项会显得呆板）。
  - **暂选/提交分离**：`pickedTimeout` 在 dialog 打开期间存活、随滚动更新；只有点"确定"才 `deviceViewModel.setInactivityTimeoutMinutes(pickedTimeout)`。取消/点外 → 丢弃，VM 不变。打开 dialog 时 `pickedTimeout = timeoutMin` 重置为当前 VM 值。
- **遗留**
  - **无持久化**：mock 期重启回 5 分钟。M11 接 SharedPreferences/DataStore：只需把 `MutableStateFlow(DEFAULT_TIMEOUT_MIN)` 换成从存储读初值 + `setInactivityTimeoutMinutes` 写存储，公开接口与 UI 不动。
  - **不在登录态外生效**：超时计时仅在 `isAuthenticated.value == true` 时由 `onAppBackground()` 触发；未登录态调超时无意义。
  - **滚轮 UX 边界**：极少项（≤3）时上下"空白行"会显得突兀；7 项是经验值。如未来扩展到含"永不"选项，需特殊处理（"永不"通常映射到 0 或 `Int.MAX_VALUE`，得在 `resetInactivityTimer` 加分支不启 job）。
  - **WheelTimePicker 形参 `options` IDE 警告"始终是 TIMEOUT_OPTIONS_MIN"**：保留形参便于后续在改密码超时/QR 有效期等场景复用；轻微 IDE noise 接受。
- **Commit** `ba5566f`

---

## M8 — 防录屏 & 安全加固

### M8.1 / M8.2 — 由 M2 + M7.5 预满足，M8 范围内仅追认

- **v4 §8.1** 要求 `MainActivity.onCreate` 调 `window.setFlags(FLAG_SECURE)`。
- **v4 §8.2** 要求 `DeviceViewModel.onAppBackground/Foreground` + `MainActivity` 用 `DefaultLifecycleObserver` 触发，超时 5 分钟。
- **本仓库实现** 两条均在 M2 commit `aea10a7` 落地（M2 文档原文 §2.1/§2.2 就把代码写在 M2 章节、注释里标 "见 M8"，v4 §M8 文本也声明 "已在 M2 实现，本里程碑仅验收"）。M7.5 commit `ba5566f` 进一步把 5 分钟硬编码升级为 1–60 分钟可调（详见 M7.5 条目）。M8 范围内**不动 code**，仅追认 + 写本条偏离。
- **Commit** `9bad006`（M8 范围内仅追认；FLAG_SECURE 实现见 `aea10a7`、超时可调实现见 `ba5566f`）

### M8.3 ProGuard / R8 — 最小规则集 + 不开 shrinkResources + lint detector 绕过

- **v4 §8.3** 给三条 proguard 规则（`-keep seczure.fsudisk.**` / `-keep com.example.midun.data.**` / `-assumenosideeffects android.util.Log.d|v|i`）+ 验收 "Release 构建开启代码混淆"，未明说要翻 `isMinifyEnabled`。
- **本仓库实现**
  - **`app/build.gradle.kts` release buildType**：`isMinifyEnabled = true`（v4 验收第 3 条隐含要求，doc 未明写），`isShrinkResources` 保持隐式 false（资源剥离误删动态加载资源风险 > 边际 APK-size 收益）。
  - **`app/proguard-rules.pro` 写最小集**：
    - 保留 `-keep class seczure.fsudisk.** { *; }` 占位规则（M11 接入 FSShell SDK 前包不存在，R8 静默忽略；保留作为前瞻护栏，少一件 M11 遗忘事）
    - 保留 `-assumenosideeffects ... Log.d|v|i`（仓库当前零 Log 调用，规则前瞻；**约定：未来生产诊断日志须用 `Log.w`/`Log.e` 或独立 logging facade，d/v/i 会被 R8 静默剥离**）
    - **舍弃 `-keep class com.example.midun.data.** { *; }`**：grep 全仓零反射 / 零序列化（无 Gson/Moshi/kotlinx.serialization/@Serializable/Class.forName），data 包整包 keep 等于禁用 R8 对业务核心的工作，无收益且会掩盖未来 Hilt 接线被混淆破坏的真实问题。M11 接 SDK 若引入 JNI 按全限定名查 data class，再针对性 keep 即可。
    - **未预先塞 Hilt / CameraX / MLKit / Compose / Navigation keep 规则**：这五个库通过 AAR 内置 consumer rules 向 R8 注入 keep，`assembleRelease` 已验证 R8 干净通过，无需手写。
  - **`android.lint { disable += "NullSafeMutableLiveData" }`**：AGP 8.7.3 + Kotlin 2.1.21 组合下，`NonNullableMutableLiveDataDetector` 在新版 Kotlin analysis API 下抛 `IncompatibleClassChangeError`，导致 `lintVitalAnalyzeRelease` 必崩。本仓库零 LiveData 使用（全 StateFlow），detector 是纯 false-positive，安全禁用。AGP 8.8+ 修复后可移除该 disable。
- **验收**
  - `./gradlew assembleRelease` 干净通过（R8 / lint / 打包全绿）
  - APK 体积：debug 42.9 MB → release minified 22.4 MB（缩减 ~48%）
- **遗留**
  - Release APK 当前未签名（`app-release-unsigned.apk`）。真签名走 M11 / 上架范围。
  - 未做真机装机验证（emulator/device 在 M10 网络层一并跑）。若 R8 在运行时仍有 missing-class 表现（特别是 MLKit 扫码场景），需要按 logcat 报错补 keep。
- **Commit** `9bad006`（M8 全部动作；docs 偏离记录由本条本身提交）

## M9 — HomeScreen 接通

### M9 整体 — 首页 dashboard 是 v4 外增量，全程无 v4 spec 可对照

- **v4 设计** v4 doc 的 `HomeScreen`（§4.1）是底部导航**壳**（嵌套 NavHost），本仓库 M4 已用 `MainScreen`（`selectedTab + when` 四 Tab）取代（见本文「M4」节）。**v4 从未设计「首页 dashboard」**——当前 `screen/HomeScreen.kt`（设备状态卡 + 快捷功能 + 最近操作）是 M0 脚手架阶段本仓库自加的"首页 Tab"内容，纯硬编码。
- **本仓库实现** M9 把这张 v4 外的首页接到真实 mock 数据。因无 v4 spec，所有字段来源/占位/语义由项目自定，逐子阶段记录于本节。里程碑编号见本文顶部「里程碑重编号」节（M9 = HomeScreen，2026-06-04 用户决策）。

### M9.1 — 设备状态卡 + 快捷功能副标题接线

- **现状** 设备卡 SN/状态/容量/已用/文件数全写死（`SC-2026051300001` / `已连接` / `16GB·8.2GB·61个`）；快捷功能副标题写死（`4个文件夹` / `2条新消息`）。
- **本仓库实现** `HomeScreen` 用 `hiltViewModel()` 注入 `DeviceViewModel`/`FileViewModel`/`ChatViewModel`（落在 Main 的 NavBackStackEntry scope，与各 Tab 同实例；底层三个 Mock 为 `@Singleton`，数据天然一致）。`LaunchedEffect(Unit)` 重读 `loadFolders()`/`loadContacts()`，使其它 Tab 增删即时反映到首页。
  - **SN** → `device.deviceId.ifEmpty{"未知"}`（对齐 M7 SettingsScreen 设备信息策略；mock 期 = `MOCK_DEVICE_001`）
  - **连接状态** → `device.status`（AUTHENTICATED/CONNECTED → 绿「已连接」，否则红「未连接」；首页只在登录后显示，拔卡有全局 overlay 兜底）
  - **文件数量** → 真实计数：新增 `MockFileSystem.getTotalFileCount()` + `FileUiState.totalFileCount`（`loadFolders` 时一并算）
  - **文件夹数副标题** → `fileState.folders.size`；**未读数副标题** → `contacts.sumOf{ it.unreadCount }`（与 MainScreen 底部角标同源），0 时显示「暂无新消息」
- **占位（无 mock 数据源）** 存储容量 `-- / 32 GB`、已用空间 `--`（DeviceInfo 无容量字段；对齐 M7 设备信息占位）。**M11 接 FSShell SDK 读真实卡容量**（加密安全卡容量是否可读取决于 FSShell 是否暴露该接口，M11 接入时确认）。
- **本阶段不动** 一键清理弹框（仍为 M0 假操作，留 M9.2 接 `wipeUserData`）；最近操作日志卡（留 M9.3）。
- **关键事实记录** 经搜 v4 doc + patch 全文，**「操作日志」在两文档中无任何功能或数据层设计**（命中均为「聊天记录」「会议记录.docx」「移除 Log 打印」等无关项）。最近操作卡的「操作日志」字样仅是 M0 自加 UI + 一键清理弹框的被清项文案。用户 2026-06-04 决策：M9.3 将其作为 **v4 外新功能真实实现**（内存版操作日志数据层，mock 期不持久化，M11 写安全卡 EMMC）。
- **Commit** `5533042`

### M9.2 — 一键清理接 `wipeUserData`（复用 M7.2 密码确认）

- **现状** HomeScreen 一键清理弹框是 M0 假操作：确认/取消都只 `showCleanDialog = false`，不清任何数据，无密码门槛。
- **本仓库实现** 套用 M7.2 SettingsScreen 同款危险操作弹框（`cleanPassword`/`cleanError`/`cleanLoading` 三态 + `dismiss` lambda 重置 + loading 期禁关）：密码输入 → `deviceViewModel.wipeUserData(password, onSuccess, onError)`（已存在，M7.2 落地）→ 校验通过清 `MockFileSystem` + `MockChatRepository`、保留登录态/绑定/密码。
  - **清完刷新首页统计**（v4/M7.2 均无此步）：`onSuccess` 里先 `fileViewModel.loadFolders()` + `chatViewModel.loadContacts()` 再 `dismiss()`，使设备卡文件数、文件夹/未读副标题即时归零。M7.2 在 Settings 不需要这步（Settings 不显示这些统计）。
  - **弹框文案改写**：原 M0 列「聊天记录/隐私文件/联系人/操作日志」四条 + 「不可恢复」，改为 M7.2 同款一句话 + 密码输入提示。删掉「操作日志」被清项文字——操作日志的真实清除归 M9.3（届时 `wipeUserData` 一并 clear 日志单例后再在文案体现）。
- **决策依据** 两个一键清理入口（首页 + 设置）走同一 `wipeUserData`，行为/密码门槛一致；首页入口因展示统计需额外刷新。
- **Commit** `8026955`

### M9.3 — 操作日志：v4 外新功能，真实实现（内存版）

- **v4 设计** **无**。经搜 v4 doc + patch 全文，「操作日志」无任何功能或数据层设计（见 M9.1 条「关键事实记录」）。HomeScreen「最近操作」卡原是 M0 写死的 4 条 `Triple`。
- **本仓库实现**（用户 2026-06-04 决策：作为 v4 外新功能真实实现）
  - **数据层**：`model/OperationLog.kt`（`OperationType` 枚举带 `label` + `OperationLog(type, description, timestamp)`）；`mock/MockOperationLog.kt`（`@Singleton`，`MutableStateFlow<List<OperationLog>>`，`record`/`clear`，最新在前、上限 50 条）。
  - **不持久化**：内存存储，进程重启即清空——既因 mock 期无卡存储，也贴合「不留痕」。M11 接真实 SDK 时写安全卡 EMMC（聊天记录同款）。
  - **记录点**（各 ViewModel/Manager 注入同一 `@Singleton`，在成功分支 `record`）：
    - 登录 → `AuthViewModel.login` onSuccess（注意：只此处记，**不**在 `MockUsbManager.authenticate` 记——后者被一键清理/恢复出厂/绑定/密钥等多处复用，非「登录」语义）
    - 文件导入/删除文件/清空文件夹/删除文件夹 → `FileViewModel` 各 onSuccess
    - 文件导出 → 导出逻辑仍在 `FilesScreen` 本地 state（M5 mock 占位，未下沉 VM），故新增 `FileViewModel.recordExport(desc)`，由 screen 三个导出点（文件夹导出/导出全部/单文件导出）调用
    - 扫码建联 → `ChatViewModel.addContact`
  - **不记录的操作**（用户 2026-06-04 决策）：**一键清理 / 恢复出厂不记自身**——这两个操作本身要 `clear()` 日志，记了会被同操作立刻清掉（record 在 clear 前）或留孤儿记录、下次清理又消失（record 在 clear 后），逻辑自相矛盾；且「不留痕」语境下清空动作本不该留痕。二者只在 `MockUsbManager.wipeUserData()`/`wipeAll()` 里 `operationLog.clear()`。**消息收发亦不记**（过于频繁，会淹没列表）。
  - **展示**：新增 `OperationLogViewModel` 转发 StateFlow，HomeScreen 注入后 `collectAsState` 实时渲染最近 `MAX_HOME_LOGS=5` 条（区别于 contacts/folders 的 reload 模式——日志是响应式流，无需手动 reload）；空态显示「暂无操作记录」；按 `OperationType` 映射图标，`timestamp` 格式化为 HH:mm。
  - **一键清理文案**：M9.2 曾删掉「操作日志」被清项，本阶段日志真实接入 `clear()` 后加回（首页弹框文案「…联系人与操作日志…」）。
- **遗留** 登录后首页仅 1 条「登录认证」（其余靠用户操作产生），不预置种子日志——保持「真实记录」语义，不造假数据。
- **Commit** `2fe4608`

## M10 — 即时通信网络层（IPv6 P2P）

> ⚠️ **编号错位**：M10 = v4 **§9**（自 M9=HomeScreen 起里程碑号比 v4 章节号 +1，见本文顶部「里程碑重编号」节）。
>
> **v4 §9 本身是「半 mock」**：传输层真实（`ServerSocket`/`Socket`/真 IPv6/JSON frame），但加密被推给 FSShell（=M11）——`sessionKey = ByteArray(32){it}` 写死、ECDH 标 `// TODO 调用FSShell`、`mockEncrypt = Base64`、`tempPublicKey = "MOCK..."` 全是假的。照搬 v4 则「加密通信」仍是明文 Base64。
>
> **整体决策（用户 2026-06-04）**：
> 1. **加密走 B 方案**——传输真实 + `java.security` 软件 ECDH(P-256) 协商 + AES-256-GCM 真加密，不依赖 FSShell。M10 端到端真加密（密钥来自软件而非安全卡硬件）；M11 再把软件 ECDH 换成 FSShell 硬件密钥接口。（v4 把 ECDH 留给 FSShell，B 方案是提前用标准库实现 → 偏离。）
> 2. **序列化用 `org.json`**（Android 内置）而非 v4 的 kotlinx.serialization——零依赖、不破坏 M8「零序列化让 R8 干净」、混淆更彻底（kotlinx 需 `-keep` 暴露协议结构类名）。

### M10.1 — `P2PSessionManager` 传输骨架（明文跑通，不接 UI）

- **v4 设计** §9.2 给出 `P2PSessionManager`（`@Singleton`，`generateConnectionInfo`/`startListening`/`connectTo`/`disconnect`/`performHandshake`/`getLocalIPv6Address`）、`ConnectionInfo` data class、§9.3 `MessageFrame` + `Json.encodeToString` 编码，类散在示例片段里。
- **本仓库实现** 新建 `network/P2PSessionManager.kt`，含 `P2PSessionManager` + `ConnectionInfo` + `MessageFrame` 三类。本阶段只建**传输骨架**，握手沿用 v4 mock 明文（`sessionKey = ByteArray(32){it}`、`tpk = MOCK_...` 占位），真 ECDH/AES-GCM 留 M10.2，与 ChatViewModel/QR 的接线留 M10.3。
  - **偏离1 — 序列化用 org.json**：`ConnectionInfo`/`MessageFrame` 各带 `toJson()`/`fromJson()`（`org.json.JSONObject`），替换 v4 的 `Json.encodeToString`（M10 决策2）。`MessageFrame` 字段对齐 v4（type/payload/timestamp，JSON key 用 `ts`）；`ConnectionInfo` JSON key 沿用 M6 二维码既有约定（`ver`/`sn`/`ipv6`/`sid`/`tpk` + 新增 `exp`）。
  - **偏离2 — 包归属**：统一收入 `com.example.midun.network`（v4 把类散在示例里无明确包）。
  - **`getLocalIPv6Address()` 真实化 + 两点加固**：在 v4 遍历网卡基础上①额外排除 link-local（`!addr.isLinkLocalAddress`）——`fe80::` 段跨设备不可路由，v4 只排回环会选到无用的 link-local 地址；②`stripZoneId()` 去掉 `%wlan0` 之类 zone id——scope id 只在本机有意义，跨设备连接需裸地址。
  - **`disconnect()` 补 `serverSocket.value = null`**：v4 关 socket 后未清 serverSocket 引用，重新监听会残留旧引用；本阶段一并置空（与 activeSession 对齐）。
  - **`MessageType` 复用**：v4 §9.3 `sendEncryptedMessage` 引用的 `MessageType` 直接用 M1 既有 `data/model/ChatMessage.kt` 的枚举，不重定义（M10.4 发消息时接入）。
- **本阶段不动** `ChatViewModel.generateQrContent`/`addContact`、`MockChatRepository.generateQrContent`（仍产 mock 二维码）——接线属 M10.3，M10.1 保持骨架隔离、独立可编译。
- **验证** `:app:compileDebugKotlin` BUILD SUCCESSFUL；真实连接行为需两台真机（M10.3+）。
- **Commit** `14ab583`

### M10.2 — 真实 ECDH(P-256) + AES-256-GCM + QR 绑定式握手（B 方案核心）

- **v4 设计** §9.2 `performHandshake` 用 `mockSessionKey = ByteArray(32){it}` 写死会话密钥，注 `// TODO 真实ECDH协商，调用FSShell密钥接口`；§9.3 `mockEncrypt = Base64`（明文 Base64 假加密）；`generateConnectionInfo.tempPublicKey = "MOCK_TEMP_PK_..."`。即 v4 的「加密通信」实为明文，加密整体留给 FSShell（=M11）。
- **本仓库实现**（用户 2026-06-04 决策：B 方案——传输真实 + 软件标准库真加密，不依赖 FSShell）
  - **新建 `network/P2PCrypto.kt`**（`object`，纯 `java.security`/`javax.crypto`，零 Android 依赖）：
    - `generateEcKeyPair()` EC secp256r1（NIST P-256）临时密钥对
    - `deriveSharedKey(private, peerPubBytes)` ECDH 协商 → **HKDF-SHA256**（RFC 5869，salt 全零、info=`MiDun-P2P-AES256`、单块输出）派生 32 字节 AES-256 密钥
    - `encrypt/decrypt` **AES-256-GCM**（12B 随机 IV ‖ 密文+128bit tag），替换 Base64 假加密
    - **拆为独立类的理由**：只收发 `ByteArray`（Base64 包装留给 SessionManager），从而脱离 `android.util.Base64`（JVM 单测里是抛异常的桩）→ 可在 JVM 直接单测。
  - **握手模型改为「QR 绑定式 ECDH」**（偏离 v4 把 tpk 与握手脱节的写法，使二维码 `tpk` 真正被消费、无死代码）：
    - A（出码/监听方）`generateConnectionInfo` 生成临时对，公钥真实 Base64 写入二维码 `tpk`，私钥暂存 `listenerKeyPair`；`startListening` 接入后用「暂存私钥 + 对端 socket 发来的公钥」派生。
    - B（扫码/连接方）`connectTo(ConnectionInfo)` 用二维码里 A 的 `tpk`，生成自己临时对、把公钥经 socket 发给 A，用「自己私钥 + A 的 tpk」派生。
    - 双方得同一 ECDH 共享密钥。**A 的公钥走二维码带外通道**，对 TCP 路径上替换 A 密钥的 MITM 免疫（**单向认证**：A→B 已认证；B→A 未认证，因 B 公钥仍走 socket）。
  - **`connectTo` 签名变更** `(ipv6: String)` → `(info: ConnectionInfo)`：握手需读 QR 里的 `tpk`，故传整个 ConnectionInfo。M10.1 时无 UI 调用方，改动零波及（M10.3 接线时按新签名）。
  - **新增消息加解密 API** `encryptMessage(session, plaintext): String` / `decryptMessage(session, payload): String`（AES-GCM + `android.util.Base64`），供 M10.4 收发用。
  - **`disconnect` 销毁会话密钥** `session.sessionKey.fill(0)` 抹内存 + 清 `listenerKeyPair`（对齐 v4 验收「断开后会话密钥清除」；v4 仅关 socket 未抹密钥）。socket/serverSocket 关闭包 `runCatching` 防抛。
  - **`tempPublicKey` 真实化** 从 `"MOCK_TEMP_PK_..."` 改为真实 P-256 临时公钥的 Base64。
- **自测** 新建 `src/test/.../P2PCryptoTest.kt`（纯 JVM，无 socket/Android）：ECDH 双方派生同一密钥、AES-GCM 往返、随机 IV 使同明文密文不同、tag 防篡改、错误密钥拒解，共 5 例。`:app:testDebugUnitTest --tests P2PCryptoTest` 全过。
- **遗留 / M11 收口**
  - **仅单向认证**：完整双向认证需两端密钥都走带外通道或硬件密钥证明 → M11 接 FSShell 安全卡硬件密钥时一并解决；M10 阶段软件 ECDH 已是端到端真加密，仅认证不对称。
  - **HKDF salt 取全零**：每会话临时密钥新生（`generateConnectionInfo` 每次新对）已保证会话密钥不复用，故未把 `sessionId` 编入 salt；如需更强会话绑定可后续把 sid 作 salt。
  - **`contactId = "unknown"`**：握手未协商联系人身份（M10.3 由扫码建联时绑定）。
- **Commit** `6dba79c`

### M10.3 — ChatViewModel/QrCodeScreen 接真实 P2P（连接生命周期 + 真实 connectionState）

> ⚠️ 真机验证项：本阶段本地仅编译 + 单测通过；二维码建链/连接成功/失败的真实行为需两台真机 + 移动数据公网 IPv6（M10.5 集中联调）。

- **v4 设计** §9 给出 manager API，但 UI 接线（ChatViewModel/QrCodeScreen）沿用 M6 mock：`generateQrContent()` 返回 `MockChatRepository` 的 CSV 假串；扫码 `addContact(qrContent, remark)` 直接 `substringAfter("sn=")` 加联系人、**不**真实连接；`connectionState` 为 ChatViewModel 内部占位枚举（DISCONNECTED/CONNECTING/CONNECTED），M6 UI 未消费。
- **本仓库实现**
  - **connectionState 上移到 P2PSessionManager（单例=唯一真相）**：枚举扩为 `DISCONNECTED/LISTENING/CONNECTING/CONNECTED/FAILED`，由 `startListening`/`connectTo`/`disconnect` 真实驱动；ChatViewModel 删掉自有枚举与 `_connectionState`，`connectionState` 直接转发 `p2pManager.connectionState`。理由：QR 屏（A）建链、聊天屏（B）用会话是不同 NavBackStackEntry → 不同 VM 实例，状态须放共享单例才跨屏一致。
  - **A（出码方）`prepareConnection()`**（替换 `generateQrContent`）：`disconnect()` 关旧 ServerSocket/会话 → `generateConnectionInfo()`（真实本机 IPv6 + 临时 ECDH 公钥）→ 后台 `viewModelScope.launch { startListening { } }` 监听对端连入 → 返回 `info.toJson()` 供渲染二维码。QrCodeScreen 生成区由 `generateQrContent()` 改调 `prepareConnection()`（已在 LaunchedEffect 协程内）。
  - **B（扫码方）`connectToContact(qr, remark, onConnected, onError)`**（替换 `addContact(qr, remark)` 的直接加联系人）：`ConnectionInfo.fromJson` 解析（失败 → onError「二维码格式无效」，**对非密盾 QR 防御**，修掉 M6.8 记的「整串当 deviceId」隐患）→ `connectTo(info)` 真实 TCP+ECDH → **成功才** `addContact` + 记 CONNECT 日志；失败 onError 反馈、不建联系人。`addContact` 改为私有、入参 `ConnectionInfo`，deviceId 取 `info.deviceSn`。
  - **QrCodeScreen 扫码弹框异步化**：新增 `connecting`/`connectError` 局部态——「确认建链」改为先 `connectToContact`（按钮转「连接中…」+ spinner、禁输入禁取消），成功才关弹框 + `onScanConnected()`，失败弹框内红字显错可改备注重试；「取消」调 `stopConnection()` 放弃尝试。
  - **A 出码区连接状态指示**：二维码下方按 `connectionState` 显示 LISTENING「等待对方扫码连接…」(spinner) / CONNECTED「✓ 已建立加密连接」(绿)。
  - **生命周期清理**：QrCodeScreen 顶层 `DisposableEffect` onDispose——未连上则 `stopConnection()` 释放 ServerSocket（已连上保留会话供 M10.4 聊天）；120s 倒计时到期同理（未连上才停监听）。解决 `startListening` 的 `accept()` 阻塞在 IO 线程、viewModelScope 取消无法打断 → 须显式关 socket 才能解阻塞。
  - **pre-gen InfoRow 去假值**：原写死 `MOCK_SN_001/fe80::1/MOCK_PUBLIC_KEY_BASE64` 改为字段说明（「本机安全卡」「生成时获取本机地址」「P-256 临时 ECDH 公钥」），实际值在生成时取真实。
  - **清理**：删 `MockChatRepository.generateQrContent()`（mock CSV，已无调用方）。
- **遗留 / 后续**
  - **A（监听方）侧不建联系人、connectionState 之外无身份**：握手未交换对端身份（`contactId="unknown"`，见 M10.2），故 A 接受连接后仅状态转 CONNECTED、未把 B 加入联系人。**M10.4 补握手后身份交换（互发 deviceSn/显示名）+ 双向建联系人**，使 A 也能发起会话。当前 M10.3 只满足 memory 明列的「扫码方成功才加联系人」。
  - **端口固定 8888、单会话**：P2PSessionManager 单 activeSession 模型；多并发会话/端口协商非 v4 验收范围，不做。
  - `onScanConnected` 仍 `popBackStack()` 回会话列表（NavGraph 未改）；M10.4 可考虑改为直接进新联系人会话。
- **验证** `:app:compileDebugKotlin` + `:app:testDebugUnitTest` BUILD SUCCESSFUL（含 M10.2 的 P2PCryptoTest）。真机联调留 M10.5。
- **Commit** `ecd2e43`
- **修订（真机验证发现，`4f8a728`）** `getLocalIPv6Address()` 原仅排回环 + link-local，**漏排站点本地 `fec0::/10`**（已废弃、不可路由）→ 真机/模拟器拿到 `fec0::` 地址写进二维码，对端连接报 `ENETUNREACH`。修为：排除 loopback/link-local/**site-local**/multicast/anyLocal，**优先全局单播 2000::/3**（`isGlobalUnicast`：首字节 `and 0xE0 == 0x20`），都没有才回退 `::1`（明确「无可用 IPv6」而非塞不可路由地址误导对端）。另：模拟器（MAC `52:54:00:..` QEMU）网络为 NAT、无真实可路由 IPv6，**P2P 必须两台真机**（v4 §9 明文警告），此修订不改变该结论。

### M10.4 — 加密消息收发走 socket + 身份交换 + 双向建联系人

> ⚠️ 真机验证项：本地仅编译 + 单测通过；互发加密消息需两台真机联调（M10.5 集中验收）。

- **v4 设计** §9.3 仅给 `sendEncryptedMessage`（`mockEncrypt = Base64` 假加密 + `Json.encodeToString` 发 frame）；**无接收循环、无身份交换、无联系人创建**（v4 把这些留白）。
- **本仓库实现**
  - **接收循环 + 消息落库归 `P2PSessionManager`（单例）**：新增常驻 `CoroutineScope(SupervisorJob+IO)`，握手成功后 `startReceiveLoop` 后台逐行 `reader.readLine()` → `MessageFrame.fromJson` → AES-GCM 解密 → 分发。**注入 `MockChatRepository`+`MockOperationLog`**（network→data 耦合，**偏离分层**）。理由：连接由 QR 屏 VM 建立但须跨屏存活独占 socket，只有单例能跑唯一接收循环（多 VM 实例各自读同一 socket 会乱）。M11 接 FSShell 时此持久化职责重构（FSShell 既管传输又管存储）。
  - **身份交换（补 M10.3 遗留的 A 侧建联系人）**：握手后双方各发一条**加密** `IDENTITY` 帧（type=`"IDENTITY"`，payload=本机 deviceSn）。`handleIncoming` 收到 IDENTITY → `bindContact(peerSn)`（按 deviceId 去重，没有才建）→ 绑定到 `session.contactId`。
    - **B 侧**：`connectTo(info, remark)` 握手成功即用「二维码 deviceSn + 用户备注」`bindContact` 并绑定会话（B 事先知对端身份）。
    - **A 侧**：监听方事先不知对端，收到 B 的 IDENTITY 才首次 `bindContact`（remark 用对端 deviceSn 占位，无用户备注通道）。
  - **`connectTo` 签名加 `remark`**：`(info, port)` → `(info, remark, port)`，联系人创建从 ChatViewModel 下沉到 manager（单一创建路径，消除「VM 建 + 身份帧建」竞态导致的重复联系人）。ChatViewModel 删去自有 `addContact`。
  - **deviceSn 每进程唯一**：`localDeviceSn = "DEV-<8hex>"`（构造时随机一次）替换 M10.2 写死的 `"DEVICE_SN_001"`——否则两机同 SN，身份去重/联系人区分失效。M11 取安全卡真实 SN。
  - **发送路由（ChatViewModel.sendMessage）**：有活动会话且 `session.contactId == 当前会话` → `p2pManager.sendText`（AES-GCM 加密 → frame → socket，manager 内 `chatRepo.sendMessage` 本地入库 isMine=true）；否则回退 `chatRepo.sendMessage`（离线本地）。
  - **响应式收消息**：manager 暴露 `incomingMessages: SharedFlow<String>`（contactId，`extraBufferCapacity=32` 防 emit 阻塞接收循环）；ChatViewModel `init` 里 collect → 重载 contacts，正看该会话则刷 messages + 清未读。`MockChatRepository.receiveMessage`（isMine=false/RECEIVED/未读+1/刷预览）+ `findContactByDevice`（去重）新增。
  - **断开检测**：接收循环 `readLine` 返回 null / 抛异常（对端关闭）→ `onPeerDisconnected`：若非主动 disconnect（状态仍 CONNECTED）则抹密钥 + 置 DISCONNECTED。完整断开 UI 反馈留 M10.5。
  - **一帧一行**：`MessageFrame.toJson()` 单行 JSON + `writer.println`；payload 为 Base64.NO_WRAP（无换行）故 `readLine` 按帧切分可靠。
- **未做 / 后续**
  - **文件传输仍是占位**：ChatDetailScreen 发文件按钮仍发 mock 字串 `[文件] 示例文件.pdf`（会作为 type=FILE 帧真实加密传输并在对端显示，但**无真实文件分块/落盘**）。真实文件收发（分块/进度/落隐私区）属更大工作，按 v4 §9 验收范围（仅文字）不展开，留后续。
  - **阅后即焚 / 撤回**：UI 占位未联网络语义（M6 既有现状）。
  - **A 侧联系人 remark = 对端 deviceSn**（无备注输入通道）；可后续加 A 侧备注编辑。
  - **单会话**：单 activeSession 模型，多并发会话不支持（v4 验收不要求）。
- **验证** `:app:compileDebugKotlin` + `:app:testDebugUnitTest` BUILD SUCCESSFUL。两机互发加密消息留 M10.5 真机联调。
- **Commit** `d346129`

### M10.5 — 撤回联网（共享消息 ID + RECALL 控制帧）

> 子阶段调换（用户 2026-06-06）：原 M10.5（网络提示+错误/断开反馈+验收）与 M10.6（撤回联网）对调。撤回是纯代码功能、本地可编译，先做；验收/反馈多为真机失败场景，挪到 M10.6 真机联调一起做。

- **v4 设计** 无。v4 §9 不涉及撤回；M6 的撤回是 `deleteMessage` 纯本地删除（每端各自生成 msg id，无共享引用，无法通知对端）。
- **本仓库实现**
  - **MessageFrame 加 `id` 字段**（消息稳定 ID）：发送方生成、**两端按同一 id 入库**，使撤回能引用对端的同一条消息。`MockChatRepository.sendMessage`/`receiveMessage` 加 `messageId: String? = null` 参（联网传稳定 id，本地/离线缺省自生成）。
  - **新增 `RECALL` 控制帧**：`P2PSessionManager.recallMessage(id)` 发 type=`RECALL`、payload=目标消息 id（同样 AES-GCM 加密）→ 对端 `handleIncoming` 解出目标 id → `chatRepo.deleteMessage` 删本地对应消息；发送方本地也删。
  - **「删除」vs「撤回」语义分离**：ChatDetailScreen 长按菜单——「删除」→ `deleteMessage`（仅删本机视图，不通知对端，所有消息可用）；「撤回」→ `recallMessage`（仅自己消息可用，联网删双方）。原本两者都调 `deleteMessage`。
  - **离线退化**：`recallMessage` 无活动会话时退化为本地删除（同删除）。
  - **`writeFrame` 加 id 参**；`generateMessageId()` = `msg_<ts>_<6hex>`（两端用发送方 id，撤回引用一致）。IDENTITY/RECALL 帧的 `id` 为一次性占位（接收方不据此入库）。
- **后续/补充**
  - 跨端 msg id 理论可在同毫秒撞车，已加 6hex 随机降概率；会话内引用足够。
- **验证** `:app:compileDebugKotlin` + `:app:testDebugUnitTest` BUILD SUCCESSFUL。撤回双删的真机效果留 M10.6 联调。
- **Commit** `951858b`

#### M10.5 补充 — 已撤回墓碑（`4db15cf`）
- **改为墓碑而非删除**（用户 2026-06-06 追加）：撤回不再整条删除，而是 `ChatMessage` 加 `recalled: Boolean`，`MockChatRepository.markRecalled` 标记 + **抹掉原文**（content=""、fileName/fileSize=null、type=TEXT）。三处撤回路径（发送方本地、接收方收 RECALL、离线退化）由 `deleteMessage` 改 `markRecalled`。
- **安全考量**：撤回须让内容从存储消失（不只 UI 隐藏）——密盾语境下原文不应残留内存；搜索（按 content）/预览自然不再命中。
- **渲染**：ChatDetailScreen 对 `msg.recalled` 渲染居中灰色 `RecalledTombstone` 替代气泡——撤回方「你撤回了一条消息」、对端「对方撤回了一条消息」（按 isMine）。墓碑无长按菜单（终态，不可再撤/删墓碑本身；WeChat 可删墓碑，暂从简）。
- **会话预览**：最后一条被撤回时列表预览显示「[消息已撤回]」（`updateContactPreview` 加分支），避免显示空或残留原文。
- **「删除」不变**：仍整条移除本机视图（不通知对端、无墓碑）。删除 vs 撤回语义保持分离。

### M10.6 — 网络提示 + 连接超时/断开反馈 +（验收待真机）

> 子阶段调换后 M10.6 = 原 M10.5 内容（网络提示+错误/断开反馈+验收）。本次完成**本地可写部分**；**v4 四条验收**（两机建链/互发加密消息/断开清密钥/不复用旧会话）须两台真机联调，留待拿到设备时执行。

- **v4 设计** §9.1 仅文字要求「App 内提示用户『通信时建议使用移动数据以确保连接稳定』」；§9 连接/超时/断开的 UI 反馈无具体设计。
- **本仓库实现（本地部分）**
  - **TCP 连接超时**：`connectTo` 由 `Socket(ip, port)`（无超时，不可达时卡到系统级 ~分钟）改为 `Socket().connect(InetSocketAddress, CONNECT_TIMEOUT_MS=10s)`，不可达/对方未监听时 10s 内快速失败。
  - **连接失败提示中文化**：ChatViewModel 新增 `friendlyConnectError`——`SocketTimeoutException` → 「连接超时…建议改用移动数据」；其余（ENETUNREACH/无路由/拒绝）→ 「无法连接：网络不可达或对方未在等待…双方均在移动数据(公网IPv6)」。替换 M10.3 直接抛 `it.message`。
  - **网络提示卡（v4 §9.1）**：QrCodeScreen TabRow 下方常驻黄色提示卡「建议双方使用移动数据(4G/5G)：WiFi 下家用路由器常拦截入站连接，易失败」，生成/识别两 Tab 都可见。
  - **会话内实时连接横幅**：ChatDetailScreen 顶部加密横幅由写死「已建立端到端加密连接」改为**随真实连接态变化**——`connectedHere = connectionState==CONNECTED && activeContactId==本会话` 时绿锁「已建立端到端加密连接」，否则灰开锁「未连接 · 消息仅存本地，未实时送达」。ChatViewModel 新增 `activeContactId: StateFlow<String?>`（map 自 `p2pManager.activeSession`）。对端断开时 `onPeerDisconnected` 置 DISCONNECTED → 横幅实时变灰（断开反馈）。
    - 副作用：mock 种子联系人（张三/李四/王五，无真实会话）横幅显示「未连接·离线」——属诚实反映（它们本就无对端），非 bug。
- **未做（留真机联调 = M10 收尾验收）**
  - **v4 四条验收**：两机建链 / 互发加密消息 / 断开清密钥 / 不复用旧会话——全程真机 + 移动数据公网 IPv6。
  - 断开后的「重连」入口、消息发送失败的逐条重发——超出 v4 §9 范围，未做。
- **验证** `:app:compileDebugKotlin` + `:app:testDebugUnitTest` BUILD SUCCESSFUL。
- **Commit** `4e74393`

#### M10.6 补充 — 未送达状态诚实化（`9ff0a58`）
- **背景**：纯 P2P 无服务器、无离线队列/重连补发——未连接时发的消息只存本机、不会在对方上线后送达（架构本质，见 M10 计划「不做后台保活」）。原实现离线消息默认 `status=SENT`，气泡看起来"已发"，误导。
- **改动**（用户 2026-06-06 要求诚实化）
  - `MockChatRepository.sendMessage` 加 `status: MessageStatus = SENT` 参。
  - **离线发送**（ChatViewModel 无会话分支）→ 存 `status=FAILED`；**联网发送 socket 写失败**（P2PSessionManager.sendText catch）→ 也存 `status=FAILED`（原来直接 return failure、根本不入库，气泡不显，用户以为没发）。联网成功才 SENT。
  - **气泡渲染**：ChatDetailScreen 自己发的且 `status==FAILED` → 时间行前加红色 `ErrorOutline` + 「未送达」。
  - **横幅文案**：「未连接 · 消息仅存本地，未实时送达」→ 「未连接 · 消息无法送达（需双方同时在线）」，杜绝"稍后会发"的误解。
- **未做**：发送失败的「重发」按钮、本地待发队列（outbox）、store-and-forward 中转——真异步离线消息需中转服务器，破坏无服务器安全模型，须甲方决策，不在此阶段做。

#### M10.6 补充 — 真机排障：网络诊断层 + 局域网 IPv4 回退 + 跨网络边界定论（2026-06-09）

- **背景（真机实测结论）**：两台真机在 **4G 流量与 WiFi 下均连不上**，前端只显示一句「网络不可达」、看不到细节。诊断后定论：
  - **不是代码 bug**：两机都拿到真实公网 IPv6（中国移动 `2409:817c::`），但互连立刻 `ENETUNREACH`（`from /:: port 0`，内核无路由）——**国内运营商不在 subscriber 间路由公网 IPv6**（入站防火墙 + 不转发终端互访）。
  - **蜂窝 IPv4 更糟**：运营商 CGNAT，手机只有大内网 `10.x`/`100.64.x.x`，无全局地址，无法被对端寻址。手机也拿不到公网 IPv4。
  - **定论**：纯 P2P 无服务器只在「双方同一局域网」成立；跨网络两端都在 NAT(IPv4 CGNAT)/防火墙(IPv6 入站)后，物理上无可达地址 = NAT 穿透问题，与 v4/v6 无关。要跨网络只有 STUN 打洞（CGNAT/对称 NAT 国内成功率低）或 TURN 中转（需公网服务器，可保持端到端加密）——**两者都需引入服务器、破坏 v4 无服务器安全模型，属架构级，须甲方决策，归 M11 之后**。
- **本仓库实现（排障增强，偏离 v4 纯 IPv6 设计）**
  - **偏离1 — 网络诊断层**：`P2PSessionManager` 新增 `networkDiagnostics(): NetworkDiagnostics`（选中出站地址 + 路径类型 `kind` + 全部接口地址清单）。QrCodeScreen 生成二维码后显示诊断卡（绿=有可用地址/红=`::1` 无可用地址 + 全部地址）；`ChatViewModel.friendlyConnectError` 改为**附带真实细节**——底层异常类名+message、目标地址、本机地址及路径类型（区分 `ENETUNREACH` 无路由 vs 超时 vs 拒绝）。替换原先把所有非超时错误压成一句「网络不可达」的写法（用户实测「看不到细节」即此）。
  - **偏离2 — 局域网 IPv4 回退（地址选择优先级变更）**：`getLocalIPv6Address` → `getLocalReachableAddress`，优先级改为 **① WiFi/有线私网 IPv4（`wlan*`/`eth*` 接口上的 192.168/10/172.16）→ ② 公网全局 IPv6(2000::/3) → ③ `::1`**。同 WiFi 两机走局域网 IPv4 直连恒可达，**绕开运营商 IPv6 那堵墙**，用于验证握手/ECDH/AES/收发整条链路。按**接口名**区分 WiFi 私网 IPv4 与蜂窝 CGNAT IPv4（蜂窝 `10.x` 对端不可达，只认 WiFi/有线接口）。`ConnectionInfo.ipv6` 字段现在可能装 IPv4 字面量（字段名沿用 `ipv6`，`InetSocketAddress` 对 v4/v6 通用，connect 路径无需改）。
  - **偏离3 — 删除「建议移动数据」黄色提示卡**：原 M10.6 加在 QrCodeScreen 顶部的黄卡（v4 §9.1 文字要求）移除——实测移动数据下反而连不上（运营商挡），该提示与实情相反，故删。
- **真机验收结果（用户 2026-06-09）**：**同 WiFi 局域网 IPv4 下完整跑通**——扫码秒连成功，证明握手/加密/收发代码全对。**跨蜂窝/公网仍连不上属运营商网络限制，非代码问题**，需中转服务器（甲方决策）。v4 四条验收（建链/互发加密/断开清密钥/不复用旧会话）在同 WiFi 范围内可执行。
- **验证** `:app:assembleDebug` BUILD SUCCESSFUL；同 WiFi 真机扫码建链成功。

## M10.7 — 联系人资料页：编辑备注 + 删除联系人（v4 外增量）

> **完全是 v4 外增量**：v4 doc + patch 无「联系人资料/详情页」设计，也无改备注/删联系人入口。这两项在 M6/M10 多处被记为推迟项（「当前任何联系人都不能改备注」「deleteContact 后端已建但 UI 无入口」「A 侧 remark=对端 deviceSn，无备注输入通道」）。本节把它们补齐。M11 仍 = 真 SDK，未被占用。

- **触发入口**：ChatDetailScreen 顶栏联系人名（原为纯展示 `Text`）改为 `clickable` → 新增 `onOpenProfile` 回调进资料页。
- **新增屏幕** `screen/ContactProfileScreen.kt`：头像（备注首字符）+ 备注 + 设备 ID + 在线态；信息卡内「备注名称」整行可点 → 编辑弹框（OutlinedTextField，非空才可保存）；底部 Danger 按钮「删除联系人」→ 二次确认弹框。叶子屏幕走回调（`onBack`/`onContactDeleted`），不持 navController（全局约定）。
- **新增路由** `Screen.ContactProfile`（`contact_profile/{contactId}`）；NavGraph 中 `onContactDeleted` 用 `popBackStack(Screen.Main.route, inclusive=false)` 越过已失效的会话页回列表。
- **后端**
  - `MockChatRepository.updateRemark(contactId, remark)`（同步 copy 改 remark）+ `ChatViewModel.updateRemark`（写后重读 `_contacts`，使会话/列表标题刷新）。
  - `ChatViewModel.deleteContact` 加 `onComplete` 回调：删除落定后才导航，避免资料页提前 popBackStack 销毁本 VM scope 打断 `repo.deleteContact` 的 `delay(500)`（同 M3.5 / clearAllMessages 坑）。`deleteContact` 后端自 M6.4 就在，本节才接 UI。
- **未做 / 后续**：A 侧扫码建联仍把 remark 设为对端 deviceSn（建联时无备注输入），现在可进资料页补改；联系人头像仍是首字符占位，无真实头像。
- **验证** `:app:assembleDebug` BUILD SUCCESSFUL（仅 AutoMirrored 图标弃用告警，与全项目既有写法一致）。
- **Commit** `d974a29`

## M10.8 — 阅后即焚（联网双端：模式制 + 读触发焚毁）

> **实质扩展，非 v4 既有语义**：v4 doc + patch 只把「阅后即焚」当作 M0 起就存在的**单条消息标记**——`ChatMessage.burnAfterRead` 字段从一开始就在、火焰图标 + 时长弹框是 M0 占位 UI，但**从未接任何焚毁逻辑**（见本文 M6 节「遗留 阅后即焚仍不生效」「UI 占位未联网络语义」三处）。v4 §9 网络层也不要求焚毁。本里程碑把它实现为一套**完整的双端联网焚毁机制**，语义远超 v4 的「单条标记」，故整节记为偏离。
>
> **设计定案由用户拍板**（2026-06-09）：①焚毁是**会话级模式开关**，不是单条勾选；②开/关模式 = 双端各插一条**居中系统行**（复用撤回墓碑的居中渲染，非顶栏 banner）；③时长用**选择器**（5 / 30 / 60 / 300 秒）；④接收方收到焚毁消息**先遮罩「🔥 点击查看」、点按才揭示**→启动倒计时→归零时**两端**变焚毁墓碑「🔥 阅后即焚消息已焚毁」；⑤仅文字消息焚。

**架构关键**
- **`burned` / `recalled` 是消息原地标记**（变残骸但保 id 与时间位置，撤回墓碑承重「双删引用」故不能搬走）；只有**开/关模式那两行**是新增 `MessageType.SYSTEM`（独立插入的事件行）。渲染统一到 `SystemLine`，但底层模型一个是标记、一个是独立消息。
- **焚毁状态全在 `P2PSessionManager` 单例**：`burnMode`（BurnMode(enabled, ttlSeconds)）与 `burnTimers`（messageId → 焚毁截止 epoch ms）都挂单例 scope——倒计时**必须活过会话页导航**（读过即注定焚，离开会话也照焚）。
- **协议帧**（org.json，`optBoolean/optInt` 解析 → 向后兼容旧端）：`BURN_MODE`（开关广播，仿 IDENTITY）/ `TEXT` 帧带可选 `burn`+`ttl` / `BURN`（读方到点令双端焚，仿 RECALL，payload=目标 messageId）。
- **离线退化**（同撤回）：无活动会话时本端照常焚毁、对端收不到 BURN 帧。

**改动（按子阶段）**
- **B.1 数据 + 协议地基**（`6647d9a`）：`ChatMessage` 加 `MessageType.SYSTEM` + `burnTtl` + `burned`；`MessageFrame` 加 `burn`+`ttl`（`optBoolean/optInt` 兼容）；`MockChatRepository` 加 `addSystemMessage` / `markBurned` + 预览串带 🔥 标。
- **B.2 开关 + 双端系统行**（`77b02bc`）：`P2PSessionManager.setBurnMode` + `BurnMode` 状态 + `BURN_MODE` 帧收发（开/关广播 → 双端插系统行）；`ChatViewModel.burnMode` 透传 + 火苗图标随真实模式高亮；ChatDetailScreen 火苗真开关 + 时长选择器 + 未连接 gate（仅本会话已连接可开）。
- **B.3 揭示 + 倒计时 + 双端焚毁**（`cb80c9d`）：`P2PSessionManager` 加 `burnTimers` 状态 + `revealBurnMessage`（登记倒计时，重复点开忽略）+ `burnMessage`（发 BURN 帧 + 本端 `markBurned`）+ `BURN` 帧接收焚毁；`MockChatRepository.receiveMessage` 透传 `burnAfterRead/burnTtl`；`ChatViewModel` 暴露 `burnTimers` + 转发 `revealBurnMessage`；ChatDetailScreen 接收方遮罩「点击查看」→点按揭示、`BurnStatusLabel` 每秒倒计时、焚毁消息火焰色描边、归零变焚毁墓碑。
- **验证**：`:app:compileDebugKotlin` 通过（B.3 提交前编译干净）。**双端焚毁联动须两台同 WiFi 真机验**（模拟器单机只能看 UI 遮罩/揭示，看不到对端 BURN 帧落地）；与 M10.3–M10.6 同属真机待验范围。
- **未做 / 后续**：仅文字消息焚（文件/图片焚毁未做，绑文件传输真实化的 M11）；A 侧改备注、阅后即焚之外的联网项不在本节。
- **Commit** B.1 `6647d9a` / B.2 `77b02bc` / B.3 `cb80c9d`

## M10.9 — 二维码规格对齐：压缩公钥（33字节）+ 扫码文案诚实化

> 本节是对照**甲方原始需求文档**的两处规格对齐（详见 [`requirements-traceability.md`](./requirements-traceability.md) 第 4 段 🔶 项），同时修订 M10.2 的一处实现选择。

### 压缩公钥（SEC1，33字节）— 修订 M10.2 的 X.509 编码
- **需求**：二维码携带「secp256r1 压缩格式公钥 33 字节」。M10.2 当时用 `PublicKey.encoded` = X.509/SPKI（P-256 约 91 字节），功能等价但规格不符、二维码偏大。
- **改动**：`P2PCrypto` 新增 `compressPublicKey`（`[0x02|0x03] ‖ X(32B)`，前缀按 Y 奇偶）/ `decompressPublicKey`（由 X 解 `y²=x³+ax+b mod p`；secp256r1 的 `p≡3 mod 4` 故 `y=rhs^((p+1)/4) mod p`，按前缀选 y / p−y）。曲线参数取自标准库 `AlgorithmParameters`，**不硬编码常数、不引第三方库**（守 M8 零依赖）。`deriveSharedKey` 改吃 33 字节压缩公钥。
- **范围**：全程压缩——二维码 `tpk` 与 socket 握手互发的公钥都用压缩编码，编码一致。
- **工程细节**：用 `BigInteger.valueOf(2)` 而非 Java 9 的 `BigInteger.TWO`（minSdk 24 兼容）；解压加 `y²==rhs` 校验拒非曲线点。
- **验证**：`P2PCryptoTest` 新增压缩往返（50 轮覆盖两种前缀）+ 33 字节长度/前缀 + 压缩公钥 ECDH 派生用例，`testDebugUnitTest` 全过。

### 扫码文案诚实化 — 不再承诺未实现的「签名校验」
- **问题**：ScanTab 文案称「将验证来源、IPv6地址及签名」，但实现**无签名校验**，仅解析连接信息后直连。需求虽列「签名校验」，但真校验需安全卡身份密钥作**信任根**——无根的软件自签名是「假安全」（任何人可造自签名二维码、防不住篡改 tpk 的 MITM），故不提前做。
- **改动**：文案改为「识别后将解析连接信息并建立端到端加密连接（设备身份签名校验将随安全卡接入启用）」，把签名校验明确钉到 M11。
- **决策**：用户 2026-06-10 拍板走「诚实降级文案」而非「软件签名演示版」。

- **Commit** 压缩公钥 `beba969` / 文案 `eb4d5a8`

## M11 — 真 FSShell SDK 接入

> **大前提**：v4「里程碑 10」是项目最初期设想（RealXxx 同接口**编译期替换** Mock + 只给 armeabi-v7a + Manifest extractNativeLibs），**与后期实际架构多处不符**。本里程碑以当前实现为准重定路线，并据真实 SDK 文档（深圳数组科技 FSShell，`E:\AndroidStudioProjects\SDK`）审计。SDK 与硬件均已到位（2026-06-10）。

### SDK 审计核心结论（决定后续所有偏离）
- **FSShell = 加密存储 + 认证 SDK，不是通信加密 SDK**：全部密钥能力（《密钥管理》）只服务卡内存储加密，**不提供 P2P 通信密钥 / ECDH 接口**；「安全层」二次开发只能自定义「卡内落盘加解密算法」+「隐藏区密码管理」，且 Android JNI 未暴露、官方「不对普通开发者提供详细技术支持」。
- **由此推翻 M10.2 / M10.9 的前瞻假设**：那两节写过「M11 把软件 ECDH 换成 FSShell 硬件密钥」——**SDK 做不到、也无必要**。M10 的软件 ECDH+AES-GCM **就是 P2P 通信加密的最终形态**；M11 只把 `P2PSessionManager.deviceSn` 从随机值换成真实 `SFDiskGetSN`（M11.6）。
- **二维码签名校验**：SDK 无设备身份密钥 / 密码学签名能力 → 用户定用 `SFDiskGetSN` 真 SN 做「弱来源标识」（M11.6），非密码学（接续 M10.9 的「钉 M11」结论）。

### M11.1 — SDK 集成（偏离 v4 的 ABI / 打包方式）
- v4 只给 armeabi-v7a + Manifest `extractNativeLibs` → 实际用 **SZU113 / android_4.0**（arm64-v8a + v7a 全有），gradle `fileTree libs` + `ndk.abiFilters` + **`packaging.jniLibs.useLegacyPackaging=true`**（AGP 现代写法替代 Manifest extractNativeLibs，解 Android 11+ 未对齐 so 崩），Manifest 加 `HARDWARE_TEST`。~15 MB 二进制 vendored 进仓库（SDK 无 maven 坐标）。
- **Commit** `38b08fd`

### M11.2 — RealUsbManager 骨架 + 临时验证（已并入 M11.3）
- 建 `data/real/RealUsbManager.kt`：`USBStorageHelper`（GetList / Open / 外部设备 diskName）+ `SFOpenDiskEx`。类全名 / 签名经 `javap` 反编译核实（`USBStorageHelper` 在 `seczure.device.usb` 包、`BusNum/DevAddr` 是 String、`FileHandle` 是 int）。
- **关键发现**：真 SDK「打开盘」=「密码认证」是同一个 `SFOpenDiskEx` 调用（区别于 Mock 分离的 simulateInsert / authenticate）。
- 临时 DevControlPanel 入口真机实测「打开成功 + 读出 SN」→ **M11 头号风险（SDK 真机能否跑：arm64 ABI / USB 权限 / OTG）消除**。
- **Commit** `42b73a6`（骨架）/ `ef99446`（临时入口，M11.3 已删）

### M11.3 — facade 运行时切换 + 认证闭环（偏离 v4「编译期替换」）
- **接入策略**（用户 2026-06-10 定）：**不照 v4 编译期替换**——项目仍在开发、大量 UI 靠 mock 模拟卡状态、模拟器无卡，故用 **facade 运行时切换**保留 mock 开发流。新增 `data/UsbCardOps.kt`（8 业务方法接口）+ `data/SecurityCardManager.kt`（持 mock+real + `useRealCard` 开关，按开关路由方法 / 设备状态 / USB 插拔，默认模拟模式）；`MockUsbManager` 加 `: UsbCardOps`（仅 override、零逻辑改）；`AuthViewModel`/`DeviceViewModel` 改注入 facade；DevControlPanel 换「模拟 / 真卡」开关（切换重走 Splash 路由）。
- **认证闭环**：`connectUsb`（USBStorageHelper.Open + 外部设备 diskName）→ `initDevice`（`SFDiskSetPassword(sha256(新密码))`）→ `authenticate`（`SFOpenDiskEx(diskName, sha256(密码))`），**打开盘 = 认证合一**，复用现有 DISCONNECTED→CONNECTED→AUTHENTICATED 状态机。
- **SHA256**（需求要求「密码哈希后传卡」）：App 层 `sha256(明文)` 当密码传卡。**巧妙副作用**：探测默认密码用**明文 `123456`**、用户密码走 `sha256(...)`，两空间天然隔离 → 即便用户密码字面是 `123456` 也不会误判初始化态（6 字符明文 ≠ 64 位 hex）。
- **初始化探测**：试 `SFOpenDiskEx("123456")` 能开 = 未初始化（走 Init）、开不了 = 已初始化（走 Login），**免卡内文件**。⚠️ 真机风险：若卡有硬件失败次数锁定，该探测在已初始化卡上耗一次尝试 → 有锁定则 M11.4 改卡内标记文件。
- **诚实降级 / 延后**：`updateKey` SDK **无密钥轮换接口**→ 真卡返回 NotImplemented（需求「密钥更新」做不了真轮换，只有改密码）；`updateBinding`（写卡内 `.bind`）、`wipeAll/wipeUserData`（SFFormat / 遍历删）依赖文件系统 → 占位，M11.4/5/6。
- **Commit** `5ca6ae7`。assembleDebug 干净；**真卡 init + login 闭环待真机验证**（卡是否有硬件失败次数锁定一并确认）。

### M11.4 — RealFileSystem：隐藏区文件系统 + 文件层 facade 路由
- **文件层 facade**（沿用 M11.3 同构设计）：新增 `data/FileSystemOps.kt`（10 个 FileViewModel 所需方法的接口）+ `data/real/RealFileSystem.kt`（真卡实现）+ `data/FileRepository.kt`（门面，按 `SecurityCardManager.useRealCard` 路由 mock/real，**与认证层共用同一开关**）；`MockFileSystem` 加 `: FileSystemOps`（仅 override、零逻辑改）；`FileViewModel` 改注入 `FileRepository`。
- **读方法提升为 `suspend`**（偏离原同步签名）：真卡读列表 / 大小是阻塞原生 IO（`SFGetFileList`/`SFGetSize`），不能在主线程同步调用 → `getFolders`/`getFilesInFolder`/`getTotalFileCount` 连同 Mock 一并改 `suspend`；`FileViewModel.loadFolders/loadFiles` 改 `viewModelScope.launch`。Mock 实现里只是直接返回，无行为变化。
- **id = 隐藏区完整路径**（偏离 Mock 的 `folder_x`/`file_x` 合成 id）：文件夹 id = `0:/工作文件`、文件 id = `0:/工作文件/a.pdf`、`parentId` = 文件夹路径。上层只当不透明字符串用，无需改 UI。
- **元数据落卡**：原生 FS 不存「拷贝策略」等 App 概念 → 落到隐藏区**侧车文件** `0:/.midun_meta.json`（JSON：文件夹路径 → CopyPolicy.ordinal，懒加载缓存 + 改动即写）。`type` 按扩展名推断、`size` 用 `SFOpen+SFGetSize+SFClose` 读、`source` 默认 import。侧车以 `.` 开头 → 列表自动跳过、不进 UI。
- **API 性质（javap 核实）**：`SFNewDir/SFRemoveDir/SFCreate/SFOpen/SFClose/SFDelete/SFRename/SFGetSize/SFRead/SFWrite` 是**静态**（`LibJniFSShell.X`）、`GetFileList(path, ArrayList)`/`SFGetFileList` 是实例方法；句柄 `>0` 有效；读 / 写返回字节数（`<0` 失败、`0` EOF）；列表项 `0=`(目录)/`1=`(文件) 前缀、`;` 分隔，用官方 `GetFileList` 包装（UTF-16LE 解析）。隐藏区单例盘所有原生序列在 `fsShell` 上 `synchronized`（对齐官方 Demo）。
- **64KB 分块原语**：`writeFile(path, InputStream)`/`readFile(path, OutputStream)` 已建，但 M11.4 不接 UI——`importFile` 当前只 `SFCreate` 空文件条目；**真实字节流式导入 / 导出（选取器 `GetContent` + 进度 + 100MB 限制）= M11.5** 复用这两个原语。
- **Commit** `55d8c7a`。compileDebugKotlin 干净；**未做真机验证**（真卡文件 IO 攒到 M11.5/6 联调），中文文件名直传 String 的编码兼容性留真机确认。

### M11.5 — 真实文件传输 + 落卡持久化（子拆分逐个提交）
M11.5 较大，按子子阶段拆分逐个提交（[[feedback-commit-per-substage]]）。

**M11.5.1 真实文件导入**（`ffbd730`）
- `FileSystemOps.importFile` 改**流式签名**（`openStream: () -> InputStream` + `onProgress`，偏离原 `(folderId,fileName,fileSize)` 占位签名）：真卡 `RealFileSystem.writeFile` 64KB 分块落隐藏区；Mock 消费流驱动真实进度但仍只记内存元数据。
- `FileViewModel.importFromUri` 经 `OpenableColumns` 取文件名/大小，**100MB 上限**双层校验（VM 预检 + Real 层流式累计中止 + 删半成品）；进度以 `ImportProgress` StateFlow 暴露。
- `FilesScreen` 接 `GetContent` 系统选取器（「手机存储 / 普通U盘」两入口都开同一选取器——OTG U盘也在其中可见，偏离原「两条硬编码占位通路」）+ 不可取消的进度对话框。

**M11.5.2 真实单文件导出**（`a9f5798`）
- `FileSystemOps.exportFile(fileId, fileName, output)`：真卡 `readFile` 流式写出到 `CreateDocument` 选的位置；Mock 写占位说明。
- **加密拷贝策略诚实降级**：卡内本就加密存储、`SFRead` 已解密、App 层无独立密钥 → `COPY_ENCRYPTED` 实际也导**明文**（无法在 App 侧产出独立加密副本）。文件夹/批量导出仍占位（多文件打包后续）。

**M11.5.3 真实文件传输（P2P 收发文件落卡）= 已实现**（2026-06-14，commit 前缀 `[file-transfer]`，非 `M11.5.3.x`——四层编号太深，用户定改功能名前缀）。子阶段 `96b8819`/`d044094`/`642e7d2`/`dfa07df`/`0f10473`/`7ce2bda`/`40af5c8`。
- **协议**：文件**单开一条二进制 socket**（端口 8889 = 聊天端口+1），不动聊天的文本行协议（`BufferedReader` 预读会吞二进制）。帧 = `writeInt(type)‖writeInt(len)‖payload`（`FileTransferChannel`），握手后立即建立（A accept / B connect，TCP 全双工 → 双向复用一条），断开/对端断开时拆除。
- **分块加密**（`P2PCrypto.encryptChunk/decryptChunk`）：nonce = `fileNonce(8B 随机/文件)‖chunkIndex(4B BE)`，AAD = `fileNonce‖chunkIndex‖isLast` → 重排/重放/截断块过不了 GCM tag；输出仅密文+tag（不含 IV，省膨胀）。`isLast` 由 totalChunks（据 size 算）确定，两端一致。FILE_BEGIN(加密元数据)→N×FILE_CHUNK→FILE_END(加密 sha256 整文件校验)。
- **接收 UX（⚠️偏离原文档「自动落 `0:/接收文件/`」——用户 2026-06-14 改）**：收端流式解密先落**卡内隐藏暂存 `0:/.recv_<msgId>`**（根级 `.` 前缀、列表不可见、卡内加密、**明文不落手机**），双方聊天显 FILE 气泡（进度条→待保存）；**接收方点气泡 → 选已有/新建隐私文件夹 → `saveReceivedFile` 把暂存移动到 `0:/<folder>/<原名>`**（优先 `SFRename`，失败回退卡内流式 `copyWithinCard`+删），冲突加序号；保存后图/视频气泡可点开 `FilePreviewDialog` 预览。
- **发送来源**（用户选 B）：手机存储（`GetContent`）+ 隐私文件夹（`RealFileSystem.openCardStream` 卡内流式读，不整文件进内存）。
- **真卡专属**：收端落卡需已认证真卡 → 模拟模式发送按钮诚实降级（灰显+提示需真卡）、收端 FILE_BEGIN 在模拟模式退化为 FAILED 气泡。
- **取消/清理**：发送方点在途文件可取消（发 FILE_CANCEL，收端删半成品）；解密失败/断开自动 abort 删半成品。100MB 上限；FILE 不参与阅后即焚（仅 TEXT 焚）。新增 `ChatMessage.savedFolderId`（落卡持久化）。
- **耦合**：`P2PSessionManager` 直接注入 `RealFileSystem` 叶子（同 ChatStore 选型避 DI 环）；`ChatViewModel` 注入 `FileRepository`（文件夹列表/选取）+ `SecurityCardManager`（真卡模式门控）。
- ⚠️ **端到端未真机验证**（需两机两卡同 WiFi；本地仅编译 + 5.3.1 分块加密单测 + 单机 UI 不崩）。已知 v1 限制：接收中途断开可能残留 `0:/.recv_*` 暂存到下次 wipe；断点续传 v1 砍。

**聊天文件预览扩展（`[file-transfer]` 阶段1–4，2026-06-14；v4 无此设计）**
> v4 doc + patch 对「聊天里发的图片/视频」只有 FILE 气泡（图标+名+大小），**无任何预览/缓存语义**。本扩展按用户 2026-06-14 拍板，把双端媒体做成「即点即预览 + 卡内缓存 + 定时清理」一套机制，故整体记为偏离。commit `7f39f21`/`cab95c6`/`2f8bcfb`/`771bc97`。
- **阶段1 接收方免保存预览**：点收到的图/视频气泡**直接从暂存 `0:/.recv_<id>` 预览**（原须先「保存到文件夹」才可看）；`saveReceivedFile` 由「移动」改「**复制**」、暂存保留作缓存（保证对话内即点即看的丝滑度）；预览弹框加可选「保存到文件夹」按钮。
- **阶段2 发送方预览自己发的媒体**：手机来源发送时边发边把明文流**另写卡内副本 `0:/.sent_<id>`**（仅真卡模式，取消/失败删半成品），隐私文件夹来源复用源路径不另占空间；新增 `ChatMessage.localPath`（发送方预览路径，持久化进 `ChatStore`）。**明文副本只落卡（加密）、不落手机存储**，守住安全模型。
- **阶段3 7天缓存 TTL + 过期降级**：`.recv_`/`.sent_` 缓存按 `ChatMessage.timestamp` **超 7 天即清**（安全 App 无常驻定时器 → `MockChatRepository` 每次认证后扫一遍）；只删缓存前缀、绝不碰隐私文件夹/聊天记录；已保存到文件夹的过期后仍可从永久副本预览。预览前 `cardFileExists` 验在否，缺失则「缓存已过期」降级、不开空白预览。新增 `data/FileCachePaths`（`.recv_`/`.sent_` 前缀唯一来源，发送侧与清理侧共用避免漂移；`MockChatRepository` 不能反依赖 network 层否则 DI 环）。
- **阶段4 手动清缓存**：设置页「清除文件缓存」项显占用（`MockChatRepository.cacheStats`）+ 确认弹框（警示未保存文件可能不可再预览、已保存的不受影响）→ `clearCache` 只删 `.recv_`/`.sent_` 暂存、返回释放字节。
- **接收方未保存文件 = 唯一副本**：纯 P2P 无服务器，7 天 TTL 或手动清后**永久丢失**（已在保存弹窗/确认弹框话术告知，「保存到文件夹」是留存通路）。⚠️ 全部需两机两卡同 WiFi 真机验。

**M11.5.4 / M11.5.5 落卡持久化**（`bd55faa` / `23fc8a4`）
- 操作日志 → `0:/.midun_oplog.json`、聊天（联系人 + 消息）→ `0:/.midun_chat.json`，经新增 `data/real/OperationLogStore.kt` / `ChatStore.kt` 用 `RealFileSystem` 读写 JSON。
- **活动判据 = `RealUsbManager` AUTHENTICATED**（真卡模式 + 盘已打开的精确代理）。**有意依赖 `RealUsbManager` 叶子而非 `SecurityCardManager`**——后者经 `MockUsbManager` 反向依赖 `MockOperationLog`/`MockChatRepository`，注入会成 Hilt DI 环。
- `MockOperationLog`/`MockChatRepository` 注入对应 store + `RealUsbManager`：`init` 里 collect `deviceStatus` → 认证成功即从卡加载（替换内存种子）；每次变更 `scope.launch { store.save(...) }` 写穿。模拟模式 store 非活动 → no-op、保留内存种子开发流。
- **内存清除-on-锁定的安全加固未做**（认证后明文聊天/日志留在内存）→ 归 M11.6「自动锁定持久化」一并处理。
- 全部 compileDebugKotlin 干净、Hilt 图无环；**未做真机验证**（真卡 JSON IO 攒到真机阶段）。

**M11.5.6 文件夹导出到指定目录**（`f9aee03`）
- 语义（用户 2026-06-10 定）：导出文件夹 = `OpenDocumentTree` 选目标目录 → `DocumentFile` 在其下建**同名子目录** → 文件夹内下一级文件**原样（不压缩、保留文件名）逐个流式写入**（复用 5.2 `exportFile`）。否决了「打包 ZIP」与「多选导出」两方案。
- 「导出文件夹」（列表项）与「导出全部文件」（详情页菜单）两入口统一走此路径，共用 `ExportProgress` StateFlow + `FolderExportDialog`（进行中进度条+计数、完成显结果）。删除原 `exportMessage`/`folderExportMessage` 占位提示对话框。
- 新增 `androidx.documentfile:documentfile:1.0.1`（catalog + build.gradle）。`createFile` 用 `application/octet-stream`（避免 SAF 按 mime 改名；文件名已带扩展名）。**多选文件导出未做**（单文件 5.2 + 整文件夹 5.6 已覆盖需求）。compileDebugKotlin 干净；**真卡读取段未真机验**。

### M11.6 — 真卡收尾：容量 / SN / 锁定清理 / 持久化 / wipe / 绑定（子拆分逐个提交）

**M11.6.1 真实容量**（`de66b55`）`DeviceInfo` 加 `totalBytes/freeBytes`；`RealUsbManager` 认证/初始化成功后 `SFGetCapacity("0:/", long[2])`→[总,空闲] 填入；新增 `util/formatStorage`（GB/MB/KB 自动）。首页「存储容量/已用空间」、设置「存储使用」真值化（已用=总-空闲），模拟模式 0→显占位。**容量认证时读一次、导入/删除后不实时刷新**（后续细化）。

**M11.6.2 真 SN → P2P deviceSn + 二维码弱标识**（`e93ae40`）`P2PSessionManager` 注入 `SecurityCardManager`，`currentDeviceSn()` 真卡模式取 `SFDiskGetSN`（全球唯一公开标识）、否则回退随机 `DEV-xxxx`。二维码 `ConnectionInfo.deviceSn` + IDENTITY 帧据此 → 扫码联系人按真 SN 标识（M11 审计的「弱来源标识」，非密码学签名）。无 DI 环（数据层无人注入 P2PSessionManager）。

**M11.6.3 内存清除-on-锁定**（`295e19a`）补 M11.5.4/5.5 留的安全加固：聊天/日志仓库响应 `RealUsbManager.deviceStatus` 离开 AUTHENTICATED → 清内存明文（已写穿到卡、重认证重载）。`wasAuthed` 标志门控，避免模拟模式初始 DISCONNECTED 误清种子。`SFCloseDisk` 本就由 M11.3 logout/closeDevice 完成。

**M11.6.4 自动锁定持久化**（`9cfb535`）新增 `data/SettingsStore.kt`（Jetpack DataStore，依赖早已预置），自动锁定时长改存**手机本地**、重启不丢。**偏离计划「写卡」**：时长是非敏感 UI 偏好、需在认证前/时生效，存卡会「读设置需认证、认证需设置」时序倒挂 → 落手机本地，卡只放隐私数据。

**M11.6.5 真卡 wipe**（`02b7b08`，用户定「试 SFFormat 强擦」）`RealUsbManager.wipeAll = SFFormat("0:/")`（静态强制格式化隐藏区）+ 关盘 + 状态退回未初始化，失败诚实提示用 PC 串口工具；`wipeUserData = RealFileSystem.clear()`（保留密码/登录）。`SecurityCardManager` 在真卡 wipe 成功后补清共享聊天/日志仓库（注入两仓库，无 DI 环）。**SFFormat 语义全未知**（是否需开盘 / 能否忘记密码免密执行 / 格式化后密码是否回出厂默认）→ **真机必验**。
- **重要发现**：真卡「忘记密码→整卡擦除」原设计在 App 内本不可行（擦卡/改密码都需先开盘=需密码；SDK 无 App 层免密格式化接口，按审计属 PC 串口工具范围）。用户选择「试 SFFormat 看能否免密强擦」，结果待真机验证。

**M11.6.6 设备绑定**（`d7ea22e`，用户定「写 .bind 且强制校验」）`updateBinding` 改 `suspend`（卡 IO）：bind 写卡内 `0:/.bind`=本机 androidId、unbind 删之；`initDevice(bindDevice)` 同写。`authenticate` 开盘后读 `.bind`，存在且 != 本机 → **拒登 + 关盘**（绑定 A 机的卡在 B 机登不了——安全设计；解绑须在原机或 PC 串口工具）。无 `.bind`=未绑定放行。`UsbCardOps`/`SecurityCardManager`/`MockUsbManager` 的 `updateBinding` 同步改 suspend。**真机必验**（绑定读写 + 锁死行为）。

**M11.6.5 修订 ①「SFFormat 真机未实现 → 降级」**（`2727ab2`）M11.6.5 上线后真机实测：原 SDK 的 `.so` **没有把 `SFFormat` 的 JNI 实现编进库**（`Java_..._SFFormat` 符号缺失，所有文件操作符号都在、唯独缺它），调用即 `UnsatisfiedLinkError` 崩进程——用户实测「恢复出厂闪退、卡没擦」根因。故**弃用 SFFormat**：`wipeAll` 改为 `RealFileSystem.clear()`（逐文件删）+ 删 chat/oplog/.bind 侧车 + `SFDiskSetPassword("123456")`（重置回出厂默认明文 → connectUsb 探测默认密码能开 = 视为未初始化 = 等效恢复出厂）。**需盘已打开（已登录）**；忘记密码（盘未开）App 内无解 → 诚实失败指向 PC 串口工具（坐实原审计结论）。另修 `DeviceViewModel.factoryReset/wipeAndReset` 原**忽略 wipeAll Result 无条件 onSuccess** → 改按 Result 分流（失败显错不导航）。

**M11.6.5 修订 ②「厂商补 .so → 恢复真擦」=M11.6.7**（换库 `1da206c` + 逻辑 `本次提交`）厂商交付了**实现 `SFFormat` JNI 符号的新 `libjniFSShell.so`**（已替换 `app/src/main/jniLibs/` 下各 ABI；`javap` 核实 jar 侧 `SFFormat(String):int` 签名一致）。`wipeAll` 据此恢复真擦，策略「真擦 + 兜底 + 回未初始化态」：
- **A. 已登录（AUTHENTICATED，盘已打开 = SFFormat 合法用法）**：`SFFormat("0:/")` 强擦隐藏区（比逐文件删更彻底、抗取证恢复）→ 返回非 0 则**降级**为 `RealFileSystem.clear()` + 删三侧车（保证数据至少被清空）→ 显式 `SFDiskSetPassword("123456")` 回出厂默认密码（SFFormat 是否自动重置密码未知，兜底确保未初始化态）→ `finishReset()`（关盘 / 清会话密码 / 状态退回 CONNECTED → connectUsb 重探测走 Init 向导）。
- **B. 忘记密码（CONNECTED，盘未打开）**：**不调 `SFFormat`**——真机实测盘未开时调用会原生崩溃（SIGSEGV，`runCatching` 抓不住 .so 崩溃）或返回 -1 → 一律诚实失败、指向 PC 串口工具。
- **真机待验**：① `SFFormat` 返回码 / 是否真需开盘；② 格式化后开盘句柄是否仍可 `SFDiskSetPassword`（若 SFFormat 关盘，第 3 步可能失败——此时数据已擦、UI 报错但卡是干净的）。

**M11.6.6 设备绑定** 见上（顺序按提交时间）。

- M11.6 全部 compileDebugKotlin + assembleDebug 干净、Hilt 图无环；**真卡相关全未真机验证**（容量/SN/SFFormat 真擦/绑定锁死攒到真机阶段）。剩 M11.7（混淆 SDK keep + 正式签名 + 多机型验收）+ M11.5.3 真实文件传输（真机阶段连写带验）。

### 隐私文件夹「文件移动」（`[file-move]`，2026-06-15；v4 无此设计）
> v4 doc + patch + 需求追溯表对隐私文件夹文件只给「导入/导出/重命名/删除」，**无跨文件夹移动**。本功能为用户 2026-06-15 提出的增量，与重命名/导出同属文件管理。commit `e066ca7`。
- **接口**：`FileSystemOps.moveFile(fileId, targetFolderId): Result<FileItem>`，`FileRepository` 按 `SecurityCardManager.useRealCard` 路由（同认证/文件层开关）。
- **真卡**（`RealFileSystem`）：保留原文件名，目标路径 = `目标文件夹路径/原名`；优先隐藏区**跨目录 `SFRename`**（原子、不搬字节），失败回退卡内流式 `copyWithinCard` + `SFDelete` 删源。目标已存在同名文件 / 目标即当前文件夹 → `Result.failure` 友好提示。文件自动继承目标文件夹的拷贝策略（策略本就是文件夹级，无需迁移）。
- **Mock**（`MockFileSystem`）：改 `parentId`，同名/同夹同样守卫。
- **UI**（`FileDetailScreen`）：文件 ⋮ 菜单加「移动到…」→ `MoveToFolderDialog` 列出当前文件夹之外的全部文件夹（带拷贝策略标签），点选即移动；无其他文件夹时菜单项灰显「无其他文件夹」。结果走底部 Snackbar，并记一条 `OperationType.FILE_MOVE`（新增枚举值「文件移动」+ 首页操作日志图标 `DriveFileMove`）。
- ⚠️ 真卡跨目录 `SFRename` 行为本地无法验证 → **真机待验**（移动后源/目标列表正确、移动后文件仍可预览/导出）；模拟模式交互可直接验。

### 离线文件发送（`[file-transfer]`，2026-06-15；行为变更）
> 原文件发送**连接门控**：未与对方建链时发送按钮灰显/拦截。用户 2026-06-15 决定让文件发送**与文字发送对齐**——未连接时不再拦截，而是「发出去 = 本地标未送达 + 发送方留缓存可预览，接收方完全收不到」。commit `da62eb6`。
- **新路径** `P2PSessionManager.sendFileOffline()`：**不走 socket**。本地插一条 `isMine` FILE 气泡标 `MessageStatus.FAILED`（下方复用文字那条共享「未送达」红字行）；手机来源在真卡模式下仍把明文流写一份卡内副本 `0:/.sent_<msgId>`（带进度、可取消、复用 `RealFileSystem.writeFile` 的 100MB 上限），供发送方自己预览；隐私文件夹来源文件已在卡上 → 直接用源路径作预览路径、不另留副本。>100MB 在插消息前即失败返回（同在线路径）。
- **分流** `ChatViewModel.sendFile`：按 `p2pManager.activeSession` 是否匹配当前联系人 → 有则真发送、无则 `sendFileOffline`（与 `sendMessage` 文字分流同构）。
- **UI**（`ChatDetailScreen`）：发送按钮**去掉连接门控、仅保留真卡门控**（模拟模式无卡写副本仍灰显提示）。
- **诚实模型不破**：纯 P2P 无服务器、无离线队列、无上线补发 → 接收方收不到、发送方也不会自动重发，仅发送方侧 UX 变化。**代价**：离线发的文件会在卡上落整份副本（单份 ≤100MB），受 7 天 TTL + 设置页清缓存约束（用户已知悉接受）。⚠️ 真机待验。

### 聊天气泡：去预览提示文字 + 长文件名限宽（`[file-transfer]`，2026-06-15）
> 用户 2026-06-15 两处体感修复。commit `4d94c0b`。
- **删「👁 点击预览」文字**：发送方自己的图/视频文件气泡不再显该 hint（点击预览功能保留，仅去文字）。`FileBubbleContent` 中 `msg.isMine → null` 短路，不再据 SENT/FAILED 显提示。
- **长文件名限宽**：原气泡 `Card` 无宽度上限，长文件名撑满 `fillMaxWidth` 的行、把头像顶出屏幕。给消息 `Column` 加 `weight(1f, fill = false)`（最多占「行宽 − 头像」剩余空间、内容短时仍贴合），文件名 `Text` 加 `maxLines = 2 + TextOverflow.Ellipsis`。该限宽对图/视频/语音/文本气泡同样生效（同一受限 Column）。

### 未送达消息重发 + 「去建立连接」提示（`[file-transfer]`，2026-06-15；v4 无此设计）
> v4 仅有"消息发送"，**无重发、无失败引导**。本功能为用户 2026-06-15 提出、参照微信做的增量，但**适配纯 P2P 无服务器模型**：在没有活动会话时给出可操作的失败反馈，而非微信式"服务器兜底重发"。commit `14f5944`。
- **架构前提（重要）**：纯 P2P、无服务器、无在线状态、无离线队列；每次通信都需**扫码现场交换地址+临时公钥**重建会话（临时 ECDH、断开即抹、"不复用旧会话"是 v4 验收项）。⇒ "未送达"几乎等于"当前无活动会话"，盲目重发在无会话时必然再失败。故不照搬微信，而是"有会话→真重发；无会话→引导去建立连接"。
- **可点「未送达」重发**：气泡下红色调小药丸（`Danger.copy(0.1f)` 背景，示意可点）→ 确认弹窗「是否重新发送该消息？」→ 重发。**重发 = 删旧消息 + 作为新消息重发**（复用 `sendMessage`/`sendFile` 的在线/离线分流），使该消息变为最新（置于会话底部）。文件从卡内副本 `ChatMessage.localPath`（`.sent_<id>` 或隐私文件夹源路径）重新流式发；无副本则提示无法重发。
- **「去建立连接」系统提示行**：未建立会话发消息后，在**当前断连段末尾**插一条 `MessageType.SYSTEM` 提示（`ChatMessage.connectPrompt=true`，落卡持久化）：「当前未建立会话，消息无法送达 · [去建立连接]」，链接点击经新增 `onGoConnect` 回调跳 `QrCodeScreen`，连上后 `onScanConnected` 返回、会话转活动态。
  - **去重 + 始终在最新之下**：`addConnectPromptIfNeeded` 从末尾回扫，遇「已送达/已收到」真实消息即停（保留更早历史段的提示），途中移除本段旧提示 → 末尾新插一条。效果 = 每个断连段始终只有一条、且永远在最新消息下方。
  - **历史保留**（用户参照微信定）：重连后该提示**不删除**，作为历史系统消息留在记录里；后续新的断连段会再生成一条新的。
- **预览不泄漏**：`updateContactPreview` 改为取最后一条**非 SYSTEM** 消息，避免「去建立连接」提示（及焚毁开关行）漏进会话列表预览。
- **滚动跟随**：重发是"删旧+插新"、`messages.size` 不变，故自动滚到底的 `LaunchedEffect` 追加以末条消息 id 为 key。
- ⚠️ 真重发/双端送达需两机两卡同 WiFi 验；单机可验 UI/提示去重/导航/样式。

### 扫码建联流程：连接后再备注、双端进会话（`[file-transfer]`，2026-06-15；改 M6.8 设计）
> 原 M6.8/patch §M6：B 扫码后**先弹备注框**、填完点「确认建链」才连接；A（出码方）被扫连上后**自动以对端 SN 当备注**建联系人、停在二维码页、不跳转。用户 2026-06-15 改为微信式「**先连接成功 → （新建才）弹备注 → 进会话**」，两端一致。commit `8344ee8`。
- **B（扫码方）**：扫到码**立即连接**（占位备注=二维码里的 deviceSn），显示「正在连接」遮罩；成功后——新建联系人→弹备注框（可跳过）、已是好友→直接进会话；失败显原因、可重扫。**取代**原「先备注后连接 + 强制填写」。
- **A（出码方）**：新增 `P2PSessionManager.peerIdentified: SharedFlow<(contactId, isNew)>`，在 A 首次收到对端 IDENTITY 帧、`session.contactId` 由 UNKNOWN 绑定为真实 id 时发出；QR 屏监听→新建弹备注、已是好友直接进会话。**补齐**原 A 侧「无备注、不跳转」的缺口。
- **新建 vs 重连判定**：`bindContact` 返回 `(contactId, isNew)`；`connectToContact` 连接前 `findContactByDevice` 预判 isNew。**已是好友的重连不再弹备注**（用户定，对齐微信）。
- **占位备注**：保持用 deviceSn（mock 下 DEV-xxxx；真卡为真实 SN）——用户定不改。跳过备注=保留该占位、仍进会话（连接已成、联系人已建，不再像旧流程那样取消=断开重扫）。
- **导航**：QR 屏 `onScanConnected()`（popBackStack）→ `onOpenChat(contactId)`；NavGraph `navigate(ChatDetail)` + `popUpTo(QrCode, inclusive)`，从会话返回回到上一层而非二维码页。
- ⚠️ 真双端流程（A 弹框/B 弹框/重连不弹）需两机同 WiFi 验；单机仅验不崩。

## 清理：砍掉模拟（mock）模式，收成真卡-only（2026-06-24，前缀 `[cleanup]`）

M11 之前 App 一直保留「模拟模式」开发流（模拟器/无卡也能跑 UI），经 `SecurityCardManager.useRealCard`
运行时开关在 Mock/Real 两套实现间路由，DevControlPanel 悬浮菜单切换。M11 真卡跑通、开发已全程插真卡后，
该模式只剩维护负担与「双重实现」认知噪音，用户拍板删除。分 4 个 `[cleanup]` commit：

- **S1 改名**（`7c90a97`）：`data.mock.MockChatRepository`→`data.ChatRepository`、`MockOperationLog`→
  `OperationLogRepository`。这两个并非「mock」——它们是**生产**聊天/日志仓库（delegate 到真 `ChatStore`/
  `OperationLogStore`、双模式都用），只是历史命名误导。纯改名 + 移包，零行为变更。
- **S2 收 facade**（`0086570`）：`SecurityCardManager`/`FileRepository` 删 Mock/Real 双路由 → 全转发真卡；
  删 DevControlPanel 悬浮菜单（模拟插拔 / 模式切换）、`DeviceViewModel.debug*`/`setUseRealCard`、MainActivity
  里「无卡时 `onUsbAttached(null)` 假连」分支。启动连卡沿用既有逻辑（onCreate 检测 `usbManager.deviceList`
  非空 → `onUsbAttached` → `connectUsb`）；无卡则保持 DISCONNECTED + 拔卡遮罩。
- **S3 删死桩 + 剥种子**（`b6d696e`，−489 行）：删 `MockUsbManager`/`MockFileSystem`/`MockData`（后者含 M0
  遗留重复类型 `SecureFolder`/`SecureFile`/同名 `Contact`/`FileType` 等，均已无引用）；`data.mock` 包清空删除。
  `ChatRepository` 去掉演示种子联系人/消息（内存态空启动、认证后从卡加载，行为同前）。
- **S4 清恒真标志**（`eedceb3`）：删模拟模式后 `useRealCard` 恒 true，移除该标志并简化所有分支调用者
  （P2P 设备 SN / sent 副本 / 接收落卡、ChatViewModel.realCardMode + 发文件门控弹框、PreviewViewModel.isRealCard
  + 视频预览门控）——全是「always-true 分支」的无行为变更简化。

**诚实小字**：删模拟模式不改变任何保密性，只是去掉一条开发期辅助路径；App 行为对真卡用户完全不变。
唯一新约束 = **必须插真卡才能跑**（无卡只能看到拔卡遮罩）。

## M12 — App 层二级密钥（DEK/KEK）加密（v4 外增量，前缀 `[M12.x]` / `[files]`）

**World-1 设计**（2026-06-22 拍板）：登录/认证/卡硬件 AES 那套**完全不动**（卡 + 密码才是真正的保密门）；App 在隐私文件夹**用户文件**内容上再叠一层 DEK（随机 AES-256），DEK 被 KEK 包成 `wrappedDEK`，三者组 keystore blob 落卡隐藏区 `0:/.midun_keystore`。**诚实定位**：这一层**不增加保密性**，只为「控制/功能」——加密导出、可重置密钥；**UI 不得宣称"双重加密更安全"**。无管理员托管、无旧文件迁移（用户走恢复出厂 + 重新 init）。

### M12.1 — 密钥库 + 加密原语（不碰文件 I/O）
- 新增 `crypto/FileCrypto`（AES-256-GCM 流式分块，纯 `java.security`，可 JVM 测）+ `data/crypto/CardKeystore`（@Singleton；`createNew` 生成 DEK/KEK、`load` 解包、`rewrap` 重包、`lock` 清零；keystore blob = `MAGIC"MDK1"(4)‖VER(1)‖KEK(32)‖wrappedDEK(60)` = 97 字节定长二进制，避开 `java.util.Base64` 的 API26 门槛）。
- 接 init/auth/lock 生命周期（`RealUsbManager`：init 生成存盘、auth 加载解 DEK 驻内存、logout/拔卡/恢复出厂 lock 清零）。`CardKeystore` 无卡依赖（避 DI 环），落卡读卡由 `RealUsbManager` 经 `RealFileSystem` raw I/O 完成。JVM 单测 `FileCryptoTest`/`CardKeystoreTest`。
- **Commit** `4a8f08e`。

### M12.2 — 写路径加密 + 文件头/大小记账
- `RealFileSystem.writeFile` 按 `isUserFilePath`（`0:/<夹>/<名>` 且名非 `.` 前缀）分流：用户文件 + 密钥库已解锁 → `writeEncrypted`（写 `FileHeader` + 逐 16KB 明文块 DEK 加密落卡）；元数据/缓存侧车/未解锁 → 原始字节写。
- `FileHeader` v2 = 22 字节：`MAGIC"MDF1"(4)‖VER(1,=2)‖plaintextSize(8 BE)‖fileNonce(8)‖typeCode(1)`。`FileItem.size`/进度读**明文大小**（卡 `SFGetSize` 给密文大小）。
- **加密范围 = 仅隐私文件夹用户文件**：`.recv_`/`.sent_` 传输缓存、`0:/.midun_*` 侧车保持明文（本就受卡 AES，不叠 DEK，不动已跑通的 P2P 传输）；`keystore` 永不被 DEK 加密（鸡生蛋）。
- **Commit** `d65727c`。

### M12.3 — 读路径解密 + 预览随机读 + chunk 调优
- `readFile`/`openCardStream` 对称解密；视频预览 `media/CardFileDataSource` 做「明文偏移→密文块→解块」随机读映射。
- **crypto chunk = 16KB**（`FileCrypto.CHUNK_PLAIN_BYTES`，单一来源，为视频 seek 粒度从 64KB 缩小）；**与卡 I/O 块解耦**：`SFWrite/SFRead` 批量按 `IO_CALL = 128KB`（用户实测导入导出明显更快），容器格式只认 16KB chunk。
- **Commit** `0ebf407` / `7cecdf1` / `496c433` / `a453a37`。

### `[files]` 三点文件增量（M12.3 后，用户提）
- **内容魔数判类型**：导入时 `FileTypes.detect`（魔数优先、扩展名兜底）→ 结果存进 `FileHeader` typeCode，列表/预览读头不再靠文件名后缀（解决 `xxx.mp4(3)` 坏后缀）。**Commit** `bb0fb25`。
- **字节级导出进度**（同导入款）：`FileByteProgress`、`exportFileToUri`、`ByteProgressDialog` 复用、文件夹导出报每文件字节。**Commit** `6a82db4`。
- **导入/导出取消**：cooperative `isCancelled` 每 chunk 检 → `TransferCancelledException`；清残留（导入半文件 `SFDelete`、单/夹导出删 target）；用 VM `@Volatile transferCancelled` 标志 + `cancelTransfer()`（**不用 `job.cancel()`**——否则 cancel 后 `.onFailure` 里 suspend `emit` 抛 CancellationException 致弹框卡死）。**Commit** `9e978b9`。

### M12.4 — keystore 生命周期硬化（撤掉旧文件迁移）
- 原计划「旧卡（有文件无 keystore）清旧文件 + 建 keystore」**作废**——用户确认走「恢复出厂（SFFormat→未初始化）→ 重新 init」，init 必建 keystore，旧卡场景不复存在。
- 落地两条硬化：① `initDevice` 校验 `saveKeystoreRaw` 返回，失败 → 关盘 + lock + `failure("密钥库写入失败，请恢复出厂后重试")`（不再丢返回值留下「已初始化却无 keystore」的卡）；② `authenticate` 若 `cardKeystore.load()` false（缺失/损坏）→ 关盘 + lock + `failure("密钥库缺失或损坏…")`，`sessionPwdHash` 移到 keystore 校验通过后才设（**不静默跑明文**）。
- **Commit** `8df7d6b`。

### M12.5 — 真·加密导出（导出口令）
- `COPY_ENCRYPTED` 策略落地成真容器：导出时用户设一个**导出口令**，文件加密成便携 `.midun` 容器，拿口令在别处也能解。替换 M11.5.2「加密拷贝也导明文」的诚实降级占位。
- **Commit** `9fe3c39`。

### M12.6 — 真·密钥更新（App 层 KEK 轮换）+ 修假实现
- **改对 v4「密钥更新」的理解**：FSShell 确无密钥轮换接口（《密钥管理》只有改密码），但有了 App 层 DEK/KEK 后，「密钥更新」= **重生成 KEK、重包不变的 DEK、覆盖 keystore**——真实、可做、且 DEK 不变所以**文件不丢、不必重加密**（安全收益有限，文案如实写）。
- `CardKeystore.rewrap()`（12.1 预留）产新 blob；`RealFileSystem.rewriteKeystoreRaw()` **崩溃安全覆盖**：写临时文件 → 旧文件改名 `.bak` → 临时就位 → 删 `.bak`，任一步崩溃后卡上都有一份完整 keystore，`loadKeystoreRaw` 从 `.bak` 自愈（新旧 blob 包同一 DEK，哪份生效都能解）。
- **修假实现 bug**：`DeviceViewModel.updateKey` 原忽略 `cardManager.updateKey()` 的 Result、密码对就无条件报「成功」（真卡模式吞掉失败 = 对用户撒谎，违背诚实降级）→ 改按 `.onSuccess/.onFailure` 分流；`RealUsbManager.updateKey` 替换旧 `NotImplementedError` 为真轮换；`SettingsScreen` 文案诚实化（「重新生成文件封装密钥 KEK」「不改变安全卡硬件加密强度」「真保护来自卡 + 密码」，不吹更安全）。
- **Commit** `e6bf15f`。

## 连接保活 — TCP keepalive + 应用层心跳（v4 外增量，`[network]`，2026-06-24）

- **背景**：P2P 会话是裸 TCP socket，**无 keepalive、无心跳**。App 退后台/空闲时 NAT/OS 回收空闲连接 → 接收循环 `readLine()` 返回/抛异常 → `onPeerDisconnected` 断连。纯 P2P 临时会话（断开即抹、不复用旧会话）掉了只能重扫码，无自动重连。
- **方案 A（用户 2026-06-24 拍板，零新权限、不引前台服务）**：① 四个 session socket（聊天监听/连接 + 文件通道两端）开 `SO_KEEPALIVE`；② 聊天 socket 上加应用层心跳（单例 scope）：每 20s 发 `PING` 帧、对端回 `PONG`；任意入站帧刷新 `lastInboundAt`，超 60s 无入站 = 判死 → 关 socket 打断 `readLine`（含半开连接检测）→ 走既有 `onPeerDisconnected` 归位；`disconnect`/`onPeerDisconnected` 取消心跳 job。
- **诚实边界**：能扛**前台 + 进程存活时的快速切换/空闲**（心跳维持 NAT 映射）；**扛不住**长时间息屏（WiFi 省电/Doze）或 OEM 杀后台（进程死了任何应用层手段都无效，尤其华为/小米）。要后台/息屏也保持须前台服务 + 常驻通知——用户权衡后不做。
- **Commit** `94745cb`。

## 聊天语音消息（v4 外增量，`[chat-voice]`，按住说话，2026-06-24）

v4 即时通信只设计了文字 + 文件；语音是 v4 外新增。**复用文件传输管线**（不另造协议）：语音本质 = 小音频文件 + 时长。
- **数据**：`ChatMessage` 加 `audioDurationSec`（`ChatStore` 持久化）；`addFileMessage` 加 `type`+`audioDurationSec` 参；会话列表预览 `[语音]`。**Commit** `60dae65`。
- **收发**：`audio/VoiceRecorder`（MediaRecorder→cache `.m4a`/AAC，60s 硬封顶 `setMaxDuration`，<1s 误触丢弃）；`sendFile/doSendFile/sendFileOffline` 穿 `durationSec` 进 `FILE_BEGIN` 元数据，两端按 mime `audio/*` → `MessageType.AUDIO`（否则 FILE）。**接收落 `.recv_` 缓存即收即播、不进「选文件夹保存」流程**（语音是即时媒体非文件）；离线语义同文字/文件（留 `.sent_` 副本可回放、标未送达、对方收不到）。**Commit** `cb91de5`。
- **UI**：输入栏麦克风/键盘切换 + 微信式「按住说话·上滑取消」（`pointerInput.awaitEachGesture`：按下录、松手发、上滑 120px 取消；RECORD_AUDIO 首按运行时申请；录音秒数/取消态反馈直接显示在按钮内，未做单独浮层）；`audio/VoicePlayer`（MediaPlayer 单段播放）；AUDIO 气泡可点播放/暂停 + 时长 + 宽度随时长递增；播放经 `ChatViewModel.readVoiceBytes` 读卡内缓存（mine=`.sent_`/源、received=`.recv_`）→ temp 文件，缓存过期（7 天 TTL）优雅降级；离开会话停播 + 弃在录音。**Commit** `e719862`。
- **诚实边界**：语音端到端收发**未装机验**（须两机两卡同 WiFi）；语音 clip 不做单独加密（同 `.recv_/.sent_` 缓存约定，受卡 AES，不叠 DEK）。

## 无卡测试模式（v4 外增量，`[nocard-test]`，2026-06-25）

给手中暂无安全卡的客户测通信/文件/语音。在「砍模拟模式→真卡-only」（2026-06-24）之后**重新引入测试态**，但形态与旧 `useRealCard` 双实现完全不同：不是运行时切换两套设备实现，而是**默认真卡不动 + 一个进程内 testMode 标志 + 一层 staging 存储抽象**。

- **默认真卡、入口常态、不持久化**：`TestModeManager`（@Singleton，`isTestMode` 初值 false、`enable()` 不可逆、重启即失效——避免污染正式真卡客户）。MainActivity 左上角常态三点面板（画在 NavGraph 之外，故无卡 DISCONNECTED 白屏时也可见）点「无卡测试」→ 置位 + 种「测试1」联系人。testMode 时 MainActivity 无视卡状态渲染 NavGraph、屏蔽拔卡遮罩；Splash 跳过卡检测/动画直跳 Main（绕过 Init/Login——认证要卡）。**Commit** `948ff78`。
- **关键事实**：通信链路本就不依赖卡（`currentDeviceSn()` 有 fallbackSn、ECDH+AES 全软件）；真正绑卡的是**收文件/语音的暂存**（`handleFileBegin` 要在卡上建 `.recv_`，没卡收不下来），不只是「保存到隐私文件夹」。
- **staging 存储抽象**：`data/staging/StagingStore.kt`（@Singleton）。**按每次调用**读 testMode 选后端（而非 DI 期固定——testMode 可能 App 启动后才开）：真卡→`RealFileSystem`（逐字委托、行为不变），测试→`LocalStagingStore`（落手机 `filesDir/nocard_staging/`）。句柄用 `LOCAL_HANDLE_BASE=0x40000000` 命名空间区分，create 出的句柄在后续 write/close 必回同一后端。改造：P2PSessionManager 收发暂存（`.recv_`/`.sent_`/焚毁删/离线副本）、ChatRepository 缓存 sweep/stats/clear、FileRepository 读门面（openFileStream/cardFileExists/readFileBytes 按 `FileCachePaths.isCachePath` 分流）、视频预览（PreviewViewModel.videoFactory/videoUri，本地走 media3 `FileDataSource` + `file://`）。**保存到隐私文件夹（copyWithinCard）仍卡-only**，测试模式 UI 禁用、不触发。**Commit** `0babb0b`。
- **无卡 UI**：ChatDetailScreen 隐藏「从隐私文件夹」发送来源、媒体预览不给「保存到文件夹」按钮、非媒体收文件提示「无卡测试版不支持保存」、气泡 hint 随 testMode 变。**Commit** `cdc3d34`。
- **诚实边界**：测试模式明文落手机本地（无卡保密能力，仅供功能测试）；端到端收发**未装机验**（须两台无卡机同 WiFi 互扫）；真卡机回归亦待复验（StagingStore 真卡路径理论上零行为变化）。

## 阅后即焚扩展到文件（图片/视频/文档，v4 外增量，2026-07-01）

M10.8 焚毁初版「仅文字消息焚」，`[chat-voice]` 又特例支持语音焚（听完才起倒计时）。本次按用户拍板把焚毁扩展到**其余文件类型**（图片/视频/文档，均为 `MessageType.FILE`，按 `fileTypeOf(fileName)` 分媒体/文档）。**复用既有焚毁机制**（`P2PSessionManager` 的 `revealBurnMessage`/`burnTimers`/`burnMessage`/`deleteBurnedMedia`），只改「何时登记倒计时」，不动数据模型/协议帧/ViewModel 焚毁方法。全部改在 `ChatDetailScreen.kt`。

- **消费后才计时（对齐语音模型）**：接收方点焚毁图/视频 → 直接全屏预览、**此刻不计时**，可无限期停留、缩放/拖动/横屏/进度条等预览内操作全支持；**退出预览**才 `revealBurnMessage` 登记 ttl 倒计时（气泡显「N秒后焚毁」），到点双端墓碑 + 删缓存。焚毁文档（文档不支持预览，见 `FilePreviewDialog` 仅 IMAGE/VIDEO）→ 弹**只读卡片**（名+大小+「我知道了」，`BurnDocDialog`），关闭卡片即登记倒计时。
- **一次性预览**（用户 2026-07-01 追加）：焚毁文件预览过一次并退出后（`burnTimers[id] != null`），再次点击**不再打开预览**，Snackbar 提示「阅后即焚文件不支持二次预览」。
- **不可保存**（焚毁语义）：焚毁图/视频预览**不给「保存到文件夹」按钮**（`openMediaPreview` saveTarget=null）；焚毁文档卡片**不给下载/保存**——避免落永久副本架空焚毁。
- **实现要点**：气泡点击路由改为「FILE 类型（含遮罩态）一律走 `onFileTap`」（原本遮罩态走即时 `onReveal`，会在没打开预览时就起计时——即此前文件焚毁失效/错乱的根因）；`onFileTap` 内按 `burnAfterRead && !isMine && type==FILE` 分「二次拦截 / 焚毁媒体预览 / 焚毁文档卡片」三支；预览关闭回调经新增 `previewOnClose` 状态穿回（普通预览为 null，行为不变）。
- **诚实边界**：双端焚毁联动 + 缓存删除**须两机两卡同 WiFi 真机验**（同 M10.8/`[chat-voice]` 待验范围）；本地仅编译 + 单机 UI 自测。发送方侧不变（焚毁由接收方读触发；发送方预览自己副本不起计时）。

### 移除「从普通U盘导入」入口（2026-07-01）

M11.5.1 曾把隐私文件夹导入的「手机存储 / 普通U盘」两个按钮都接到**同一个** SAF 系统选取器（`GetContent`）——「普通U盘」其实无专属逻辑，仅靠系统是否把 U盘 挂进选取器，且本安全卡的明文公开区是 PC 侧（USB-A）功能、手机端（Type-C 加密侧）本就看不到。故按用户 2026-07-01 决定**删掉「从普通U盘导入」按钮**：导入来源只剩手机存储一项，随之**去掉「选择导入来源」弹窗**，「导入」图标 / 空态「导入第一个文件」直接打开系统选取器。导入机制（`importFromUri` 流式加密导入、100MB 校验、字节进度、可取消）不变。

<!-- 后续里程碑的偏离继续在下面追加 -->
