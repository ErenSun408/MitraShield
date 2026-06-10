# 需求追溯表（Requirements Traceability）

> 把甲方原始需求文档《USB安全卡产品需求以及功能说明》逐条对照**当前代码实际实现**的追溯记录。
>
> - **基准代码**：`main` @ `5ca6ae7`（M11.3 真卡认证闭环完成）。
> - **生成日期**：2026-06-10。
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
| 密钥更新 | 🔴 | **SDK 无密钥轮换接口**（《密钥管理》只有 `SFDiskSetPassword` 改密码、非轮换数据密钥）→ M11.3 真卡返回 NotImplemented，诚实降级；真轮换需安全层（不对开发者开放） | `RealUsbManager.updateKey` |
| 串口管理（一键还原 / 密码重置 / 审计日志） | ❌ | 未实现。属硬件侧 / PC 串口工具，不在 APP 范围，但需求有列 | — |

## 3. 隐私文件夹

| 需求 | 状态 | 核查说明 | 落点 |
|---|---|---|---|
| 文件夹创建，选 不可拷贝 / 明文 / 密文 策略 | ✅ | `CopyPolicy { NO_COPY, COPY_PLAIN, COPY_ENCRYPTED }`，创建时可选 | `FileItem.kt` / `MockFileSystem` |
| 文件列表（名 / 时间 / 来源：导入 or 聊天） | ✅ | `FileItem.source`（import/chat），列表展示 | `FilesScreen` |
| 导入 / 导出 / 拷贝 / 删除 | 🟡 | UI 流程齐全，但 `importFile` **不接收真实文件字节**（只存元数据），导出 / 拷贝按策略校验为 mock 演示 | `MockFileSystem` |
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
| 消息类型：文字 / 图片 / 视频 / 文件 / 语音 | 🔶 | `MessageType` 枚举齐全，但**仅 TEXT 真实联网收发**；图片 / 视频 / 文件 / 语音为 mock 占位 | `ChatMessage` / `ChatDetailScreen` |
| 文件传输：进度显示、**≤100MB 限制** | ❌ | 未实现。无真实分块 / 进度 / 重组 / 落盘，无 100MB 校验（`MockFileSystem` 无真实存储） | — |
| 双向撤回（双方在线） | ✅ | `recallMessage` → RECALL 帧 → 双端 `markRecalled` 抹原文 + 墓碑 | `P2PSessionManager` / `MockChatRepository` |
| 阅后即焚：**可针对某个消息单独设置** | 🔶 | 实现为**会话级模式开关**（开后该会话所有文字消息焚），**非需求的"单条设置"**。语义偏差，已记 deviations | M10.8 全套 |
| 消息删除仅本端、聊天记录完整清除 | ✅ | 删除仅本机；联系人级清除 | `MockChatRepository` |
| keepalive、断线后重连 | ❌ | 指标项要求 keepalive，未实现（不做后台保活）。断线只能重新扫码建链 | — |

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
| 操作日志保存 | 🟡 | `MockOperationLog` 内存版（上限 50，不持久化），记录登录 / 文件 / 建联；真卡持久化 M11 |
| APP 设置 / 密码 / 聊天记录存安全卡加密 | 🟡 | 全在内存 mock，真存储 M11 |
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

1. **阅后即焚做成"会话模式"而非需求的"单条消息设置"** — 语义级偏差（M10.8 刚完成）。若甲方坚持"针对某条消息"，需改交互为逐条长按设置焚毁。
2. **文件传输整体未实现**（含进度、≤100MB 限制） — 即时通信的大块需求，目前仅文字真实联网。属 M11，但体量大，需对齐甲方进度预期。
3. **扫码"签名校验"** — ⚠️ 文案已诚实化（M10.9，不再虚假承诺），但**真签名校验仍未做**：需安全卡身份密钥作信任根（无根的软件自签名是假安全），钉 M11。
4. ~~**二维码公钥非"压缩 33 字节"**~~ — ✅ **已解决（M10.9）**：改 SEC1 压缩点编码（33 字节），全程压缩，单测覆盖。
5. **keepalive 未实现** — 指标项明列，当前断线即需重新扫码建链。
6. **SHA256 密码 / 设备绑定校验 / 真实加密存储** — 均为 mock 明文，M11 接 FSShell 才真实。属设计内安排，但是安全核心，验收前必须补齐。
7. **跨网络不可用** — 纯 P2P 仅同 WiFi 局域网通；跨蜂窝 / 公网受国内运营商 NAT 限制，需 STUN/TURN 中转（破坏无服务器安全模型），**须甲方决策**。

---

## 总体结论

- **流程 / 交互层**：初始化、登录、设备管理、隐私文件夹、即时通信骨架、撤回、阅后即焚、安全加固——**基本齐全**。
- **真实加密通信**：P2P + ECDH + AES-GCM 已真实落地并超出 v4 设计，同 WiFi 局域网双机验证通过。
- **主要缺口**：真实文件传输（含 100MB / 进度）、安全卡真加密存储 / SHA256 / 绑定校验、签名校验、keepalive、跨网络可达性——分别归属 **M11 真 SDK** 或 **待甲方决策**。
