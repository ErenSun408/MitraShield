# 需求追溯表（Requirements Traceability）

> 把甲方原始需求文档《USB安全卡产品需求以及功能说明》逐条对照**当前代码实际实现**的追溯记录。
>
> - **基准代码**：`main` @ `5ca6ae7`（M11.3 真卡认证闭环完成）。
> - **生成日期**：2026-06-10。
> - **部分刷新**：2026-06-24 @ `e719862` 起，**M11–M12 + 语音/保活相关行已按真实实现更新**（密钥更新、消息类型、文件传输、keepalive 等）；其余行可能仍反映 Mock 期基准，详见 [`design-deviations.md`](./design-deviations.md) 的 M11/M12 节。
> - **前提**：本项目按基于该需求的 **v4 设计文档**实现，当前处于 **Mock 阶段（M0–M10.8）**。真实安全卡 SDK（FSShell）、真实加密存储、真实文件传输统一规划在 **M11**。因此"加密/认证/存储落在安全卡"的需求，当前多为软件模拟——这是设计内的阶段安排，本表对照**原始需求**如实标注，与 [`design-deviations.md`](./design-deviations.md)（对照 v4 设计文档的偏离）互补。

## 图例

| 标记 | 含义 |
|---|---|
| ✅ | 已真实实现且与需求一致 |
| 🟡 | Mock 占位 / 部分实现，真实化计划在 M11 |
| 🔶 | 已实现但与需求规格存在偏差（需关注 / 决策） |
| ❌ | 未实现 |

---

## 1. 初始化 / 登录模块

| 需求 | 状态 | 核查说明 | 落点 |
|---|---|---|---|
| 未插卡打开 APP 提示操作异常 | ✅ | `UsbDisconnectedOverlay` 全屏遮罩 + 倒计时退出 | `MainActivity` / `UsbDisconnectedOverlay.kt` |
| 上电初始化：输入密码 / 确认 / 生成根密钥 | ✅(真卡) | **真卡(M11.3)** `RealUsbManager.initDevice` = `SFDiskSetPassword(sha256(密码))` 把默认 123456 改成用户密码（根密钥出厂烧录、非 App 生成）；模拟仍 `MockUsbManager.initDevice` | `RealUsbManager` |
| 提示绑定设备 / 可解绑 | 🔶 | facade 已接，但**真卡绑定写卡内 `.bind` 文件依赖 RealFileSystem → 延后 M11.4**；当前仅内存状态 | `RealUsbManager`(占位) |
| 登录：密码 **SHA256 hash** 传卡校验 | ✅(真卡) | **真卡(M11.3)** `authenticate` = `SFOpenDiskEx(diskName, sha256(密码))`，密码 SHA256 后才传卡；模拟仍明文比较 | `RealUsbManager.authenticate` |
| 校验设备绑定关系一致才放行 | 🔶 | 同绑定项，校验逻辑随 `.bind` 文件落地（M11.4）补 | `RealUsbManager`(占位) |

## 2. 设备管理 / 一键清理 / 出厂

| 需求 | 状态 | 核查说明 | 落点 |
|---|---|---|---|
| 一键清理（清文件 + 聊天，不可恢复） | ✅ | `wipeUserData` 清 file+chat+log，保留登录态；走密码确认 | `MockUsbManager.wipeUserData` |
| 恢复出厂 / 一键还原（擦根密钥 + 全清） | ✅ | `wipeAll` 整卡擦除 | `MockUsbManager.wipeAll` |
| 密钥更新 | ✅(App 层) | **M12.6** 改为 **App 层 KEK 轮换**：`CardKeystore.rewrap()` 重生成 KEK、重包**不变的 DEK**、`rewriteKeystoreRaw` 崩溃安全覆盖 keystore（DEK 不变 → 文件不丢/不必重加密）。修了旧假实现（`DeviceViewModel.updateKey` 吞 Result 无条件报成功）。**诚实**：只换 App 层封装密钥、不提升卡硬件加密强度。FSShell 仍无数据密钥轮换接口 | `RealUsbManager.updateKey` / `CardKeystore` |
| 串口管理（一键还原 / 密码重置 / 审计日志） | ❌ | 未实现。属硬件侧 / PC 串口工具，不在 APP 范围，但需求有列 | — |

## 3. 隐私文件夹

| 需求 | 状态 | 核查说明 | 落点 |
|---|---|---|---|
| 文件夹创建，选 不可拷贝 / 明文 / 密文 策略 | ✅ | `CopyPolicy { NO_COPY, COPY_PLAIN, COPY_ENCRYPTED }`，创建时可选 | `FileItem.kt` / `MockFileSystem` |
| 文件列表（名 / 时间 / 来源：导入 or 聊天） | ✅ | `FileItem.source`（import/chat），列表展示 | `FilesScreen` |
| 导入 / 导出 / 拷贝 / 删除 | ✅(真卡) | **M11.5** 真实流式导入（100MB 校验 + 字节进度 + 可取消）、单文件/整夹导出；**M12.5** 加密导出 = 真 `.midun` 口令容器（替换早期「加密拷贝也导明文」占位）；卡内移动/删除真实 | `RealFileSystem` |
| 无索引 / 加密存储 / 文件管理器不可见 | 🟡 | 概念正确，依赖真安全卡 EMMC（M11） | — |
| 文件不支持预览，视频 / 语音可播放 | 🟡 | UI 区分类型，真实播放 / 解密留 M11 | `FileDetailScreen` |

## 4. 即时通信 / 文件传输（差异最集中段）

| 需求 | 状态 | 核查说明 | 落点 |
|---|---|---|---|
| 端到端加密、无服务器、实时建链、需同时在线 | ✅ | 真 `ServerSocket/Socket` P2P，无中转。**架构正确** | `P2PSessionManager` |
| ECDH 协商 + AES 加密通信 | ✅ | 真 **ECDH(secp256r1/P-256) + HKDF-SHA256 + AES-256-GCM**（已超出 v4 的 Base64 假加密），软件密钥，M11 换硬件 | `P2PCrypto` |
| 二维码：曲线 secp256r1 | ✅ | `EC_CURVE = "secp256r1"` | `P2PCrypto` |
| 二维码：**压缩格式公钥 33 字节** | ✅ | M10.9 改为 **SEC1 压缩点编码（33 字节）**：`compressPublicKey`/`decompressPublicKey`（标准库曲线参数，无第三方依赖），二维码 tpk + socket 公钥全程压缩，单测覆盖 | `P2PCrypto` / `P2PSessionManager` |
| 二维码：sid 8字节 / JSON / base64 / 120s 失效 | ✅ | `generateRandomHex(8)`、org.json、`exp = now + 120_000`、120s 倒计时，均符合 | `ConnectionInfo` / `QrCodeScreen` |
| 扫码校验 **来源 / IPv6 / 签名** | 🔶 | M10.9 已把虚假文案诚实化（不再承诺签名校验）；**真签名校验仍未做**，需安全卡身份密钥作信任根，钉 M11 | `QrCodeScreen.ScanTab` |
| 连接不重用，断开需重新建链 | ✅ | `disconnect`/`onPeerDisconnected` 抹密钥置零，每次新 ECDH 临时对（**验收第 3、4 条**） | `P2PSessionManager` |
| 备注对端、聊天记录绑定双方设备 ID | ✅ | `bindContact(deviceSn, remark)` + IDENTITY 帧双向交换身份 | `P2PSessionManager` |
| 消息类型：文字 / 图片 / 视频 / 文件 / 语音 | ✅ | 文字真实联网收发；图片/视频/文件走 **M11.5.3** 文件通道真实分块加密收发 + 预览；**语音（`[chat-voice]`）** = 按住说话录制、复用文件通道、即收即播。均已实现（端到端待两机两卡装机验） | `P2PSessionManager` / `ChatDetailScreen` / `VoiceRecorder`·`VoicePlayer` |
| 文件传输：进度显示、**≤100MB 限制** | ✅ | **M11.5.3** 单开二进制 socket(8889) 长度前缀分帧 + 分块 GCM + 流式落卡 + sha256 校验；双方进度气泡；100MB 上限校验。端到端待两机两卡装机验 | `P2PSessionManager` / `FileTransferChannel` |
| 双向撤回（双方在线） | ✅ | `recallMessage` → RECALL 帧 → 双端 `markRecalled` 抹原文 + 墓碑 | `P2PSessionManager` / `MockChatRepository` |
| 阅后即焚：**可针对某个消息单独设置** | 🔶 | 实现为**会话级模式开关**（开后该会话所有文字消息焚），**非需求的"单条设置"**。语义偏差，已记 deviations | M10.8 全套 |
| 消息删除仅本端、聊天记录完整清除 | ✅ | 删除仅本机；联系人级清除 | `MockChatRepository` |
| keepalive、断线后重连 | 🟡(前台) | **`[network]`** 加 `SO_KEEPALIVE` + 应用层 PING/PONG 心跳（20s）维持 NAT、60s 静默判死。**仅扛前台/快速切换**；息屏久或 OEM 杀后台仍断（不引前台服务，用户决策）。断线后无自动重连（纯 P2P 临时会话，须重扫码） | `P2PSessionManager` |

## 5. 安全加固

| 需求 | 状态 | 核查说明 | 落点 |
|---|---|---|---|
| 防录屏（使用时防止录屏） | ✅ | `FLAG_SECURE` 全局 | `MainActivity` |
| 防反编译 / 防暴破 / 混淆 | 🟡 | R8 minify + proguard（M8）已开；SDK 加密调用留 M11 | `build.gradle.kts` / proguard |
| 后台 5 分钟无操作退出登录 | ✅ | `DEFAULT_TIMEOUT_MIN = 5`，后台启计时 → logout（且可调） | `DeviceViewModel` |
| 拔卡自动退出 + 清内存数据 | 🔶 | 拔卡 → 退出 App ✅，但 `clearSensitiveMemory()` 为**空 hook**（M11 接 `SFCloseDisk`）。"清内存数据"未真实做 | `DeviceViewModel.onUsbDetached` |
| APP 退出清连接与内存、收不完文件丢弃 | 🟡 | 连接 `disconnect` 会清；"收不完文件丢弃"暂无意义（文件传输未实现） | — |

## 6. 加密U盘 / 存储

| 需求 | 状态 | 核查说明 |
|---|---|---|
| 操作日志保存 | ✅(真卡) | **M11.5.4** `OperationLogStore` → `0:/.midun_oplog.json` 落卡持久化（认证后加载、变更写穿）；记录登录/文件/建联 |
| APP 设置 / 密码 / 聊天记录存安全卡加密 | ✅(真卡) | 聊天记录 **M11.5.5** `ChatStore` → `0:/.midun_chat.json` 落卡（受卡 AES）；密码不存卡（验证=能否解密，《密钥管理》原理）；自动锁定时长存手机本地 DataStore（非敏感偏好） |
| 隐私文件夹用户文件 App 层二级密钥加密 | ✅(真卡) | **M12** 在卡硬件 AES 之上叠 App 层 DEK（KEK 包、存 `0:/.midun_keystore`），仅加密隐私文件夹用户文件。**诚实**：不增保密性，为「加密导出 + 可重置密钥」功能；UI 不吹更安全 | `FileCrypto` / `CardKeystore` / `RealFileSystem` |
| Type-C 加密U盘对 PC 不可见 | 硬件 | 硬件特性，不在 APP 范围 |

## 7. 指标 / 交付要求

| 需求 | 状态 | 核查说明 |
|---|---|---|
| 硬件（16/32GB、读写速率、PCB、T620 芯片） | 硬件 | 硬件交付项，不在 APP 代码范围 |
| **Android 8.0+ 兼容** | ✅ | `minSdk = 24`（Android 7.0），`targetSdk/compileSdk = 35`。8.0 = API 26 > 24，所有 8.0+ 设备均覆盖（实际更宽，连 7.x 也支持）。若要"严格最低 8.0"可收紧 `minSdk = 26` |
| 鸿蒙 4.0 兼容（Android 兼容层） | ✅ | 兼容层 API 30+ 在覆盖范围内 |
| 主流机型（荣耀 / OPPO / Vivo / 华为 / 小米 / 三星） | 🟡 | 未做多机型真机矩阵测试，仅同 WiFi 局域网双机验过建链 + 收发 |
| 交付：源码 + 安装包 + 设计文档 | 🟡 | 源码 ✅、设计 / 偏离文档 ✅、安装包未签名（M11） |
| 联调无闪退 / OOM / 延迟 <10ms | 🟡 | 仅同 WiFi 局域网真机验过，未做完整联调验收 |

---

## 🔴 需重点关注的硬偏差（非单纯 Mock 待补）

按优先级：

1. **阅后即焚做成"会话模式"而非需求的"单条消息设置"** — 语义级偏差（M10.8）。若甲方坚持"针对某条消息"，需改交互为逐条长按设置焚毁。
2. ~~**文件传输整体未实现**~~ — ✅ **已实现（M11.5.3）**：真实分块加密收发 + 进度 + 100MB 校验 + 流式落卡；语音消息复用同管线（`[chat-voice]`）。**端到端待两机两卡装机验**。
3. **扫码"签名校验"** — ⚠️ 文案已诚实化（M10.9，不再虚假承诺），但**真签名校验仍未做**：需安全卡身份密钥作信任根（无根的软件自签名是假安全），钉 M11。
4. ~~**二维码公钥非"压缩 33 字节"**~~ — ✅ **已解决（M10.9）**：改 SEC1 压缩点编码（33 字节），全程压缩，单测覆盖。
5. **keepalive 仅前台** — `[network]` 已加 keepalive + 心跳，扛前台/快速切换；**息屏久/OEM 杀后台仍断**（不引前台服务，用户决策），断线后无自动重连（须重扫码）。
6. ~~**SHA256 密码 / 设备绑定校验 / 真实加密存储**~~ — ✅ **已真实化（M11.3/M11.6.6/M11.5）**：密码 SHA256 后传 `SFOpenDiskEx`、绑定写卡内 `.bind` 强校验、聊天/日志/文件落卡（受卡 AES）+ M12 App 层 DEK。仍待两机两卡系统装机验收。
7. **跨网络不可用** — 纯 P2P 仅同 WiFi 局域网通；跨蜂窝 / 公网受国内运营商 NAT 限制，需 STUN/TURN 中转（破坏无服务器安全模型），**须甲方决策**。

---

## 总体结论

- **流程 / 交互层**：初始化、登录、设备管理、隐私文件夹、即时通信骨架、撤回、阅后即焚、安全加固——**基本齐全**。
- **真实加密通信**：P2P + ECDH + AES-GCM 已真实落地并超出 v4 设计，同 WiFi 局域网双机验证通过。
- **真实存储/传输/语音**：M11 接 FSShell 真卡（认证/CRUD/导入导出/落卡持久化/容量/绑定）、M11.5.3 文件传输、M12 App 层二级密钥加密 + 加密导出、`[chat-voice]` 语音消息均已落地（**多数待两机两卡系统装机验收**）。
- **剩余缺口/待决**：扫码真签名校验（需硬件信任根）、keepalive 仅前台（息屏/杀后台不保）、跨网络 NAT 可达性（须 STUN/TURN 中转，破坏无服务器模型，**待甲方决策**）。
