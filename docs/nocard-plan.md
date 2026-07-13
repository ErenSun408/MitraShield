# 波波 · 无卡版（bobo-nocard）实施计划

> 分支：`bobo-nocard`（从 `bobo` 真卡版切出）。定位：**两个产品基本独立**——真卡版留在 `bobo`，
> 无卡版**彻底清除真卡逻辑**，只留纯软件实现。共享层（UI/ViewModel/导航/加密原语/P2P/聊天/语音）
> 两分支逐字相同，靠保留的 `FileSystemOps`/`UsbCardOps` 接缝支持从 `bobo` cherry-pick 共享层修复。
>
> 提交：沿用 `[NCx.y]` 前缀、英文、每子阶段一提交、无 Claude co-author。**暂不 push，全部完成后一起 push。**

## 0. 决策基线（已拍板）

| 议题 | 决定 |
|---|---|
| 加密强度 | **Android Keystore 硬件包裹 KEK**，DEK 密文落 App 私有目录；文件逐块 AES-GCM（复用 `FileCrypto`/`FileHeader`） |
| 代码结构 | 接口保留、**只留 `Local*` 一个实现**；真卡实现删除（历史存 `bobo`） |
| 保留的卡特性 | 设备绑定、恢复出厂/一键清理、退出自动清理 |
| 移除 | 插拔卡 UI / 等待遮罩 / USB 广播 / FSShell SDK / `.so` |

## 1. 依赖现状（要替换/删除的清单）

**认证/生命周期** `UsbCardOps` → `RealUsbManager`（`SFOpenDiskEx` 开隐藏盘=登录、`.bind` 绑定、SN、SFFormat 恢复出厂）
**文件存储** `FileSystemOps` → `RealFileSystem`（卡隐藏区 CRUD + DEK 逐块加密）
**密钥库** `CardKeystore`（DEK/KEK 落卡 `0:/.midun_keystore`）
**聊天/日志** `ChatStore`/`OperationLogStore`（侧车 JSON 落卡，注入具体 `RealFileSystem`+`RealUsbManager`）
**身份** `P2PSessionManager.currentDeviceSn()` = 卡 `SFDiskGetSN`
**门面** `SecurityCardManager`（注入 `RealUsbManager`）、`FileRepository`（注入 `RealFileSystem`）
**UI/系统** `MainActivity` USB 广播 + `device_filter.xml`、`UsbDisconnectedOverlay`、无卡等待背景、`SplashScreen` 路由
**构建** `app/libs/seczure.fsudisk.*.jar`、`app/src/main/jniLibs/**`（6 so × 2 ABI）、`AndroidManifest` usb 权限/feature/intent-filter、`build.gradle` jniLibs legacy 打包

**零改动复用**：`FileCrypto`、`FileHeader`、`FileContainer`、`P2PCrypto`、全部 screen/viewmodel/navigation/audio/theme、`ChatRepository`/`OperationLogRepository`（生产仓库，非 Mock）。

## 2. 新增的本地后端（4 个类）

| 新类 | 替代 | 要点 |
|---|---|---|
| `LocalDeviceIdentity` | 卡 SN | 首次生成 UUID 存 `filesDir/.device_id`（或 `ANDROID_ID`），供 P2P `deviceSn` |
| `LocalAuthManager : UsbCardOps` | `RealUsbManager` | 密码 PBKDF2/Argon2 哈希 + salt 存 `filesDir/.auth`；登录=校验哈希→解锁 `LocalKeystore`；绑定 `ANDROID_ID` 存本地；恢复出厂=清 vault+.auth+keystore；退出自动清理照旧 |
| `LocalKeystore` | `CardKeystore` | Android Keystore（TEE/StrongBox）生成 KEK→包裹 DEK，密文存 `filesDir/.keystore`；`dek()`/`lock()`/`createNew()`/`rewrap()` 对齐现有接口 |
| `LocalFileSystem : FileSystemOps` | `RealFileSystem` | vault=`filesDir/vault/<folder>/<file>`；两层路径模型不变；`SFOpen/SFRead/SFWrite/SFSeek64` → `RandomAccessFile`；**加密写/读/预览随机读逻辑整段复用**，只换 IO 原语；侧车（meta/chat/oplog/exitclear/bind）落 `filesDir` |

## 3. 阶段拆分（每子阶段一提交、保证可编译）

> 策略：**先加 `Local*`（App 编译时并存、Real 暂留不被引用）→ 切 DI → 最后统一删 Real**。
> 这样每个中间提交都能编译，末态完全干净。执行顺序按依赖调整：认证后端要调 `LocalFileSystem.clear()`，
> 故文件系统先于认证建（NC1 = 存储基座，认证顺延 NC2）。

### NC1 — 本地存储基座 ✅（均已编译通过、未引用）
- **NC1.1** `LocalDeviceIdentity`：UUID 持久化 → P2P deviceSn ✅ `70915b0`
- **NC1.2** `LocalKeystore`：Android Keystore 硬件 KEK（StrongBox→TEE→模拟器软件后端）包裹 DEK ✅ `8e4cf6d`
- **NC1.3** `LocalFileSystem : FileSystemOps`：`RealFileSystem` 全量 drop-in（CRUD+加密+stream+keystore raw IO+容器）✅ `8a5fd0d`

### NC2 — 认证后端 + DI 切换
- **NC2.1** `LocalAuthManager : UsbCardOps`：init/登录/校验/登出/绑定/恢复出厂/一键清理/退出偏好 ✅ `f7d26f0`
- **NC2.2** DI 切换：`SecurityCardManager` 门面路由到 `LocalAuthManager`；`FileRepository` 路由到 `LocalFileSystem`
- **NC2.3** DI 切换：`StagingStore`/`P2PSessionManager`/`CardFileDataSource`/`PreviewViewModel`/`FileViewModel` 改指本地
- **NC2.4** DI 切换：`ChatStore`/`OperationLogStore` 改指本地（判活「AUTHENTICATED」语义保留=已登录+DEK 解锁）

### NC3 — 身份接线 + 启动路径
- **NC3.1** `P2PSessionManager` deviceSn → `LocalDeviceIdentity`（经门面）
- **NC3.2** `SplashScreen` 路由按 `.auth`；`DeviceViewModel`/`SecurityCardManager` 用 `connect()` 替 onUsbAttached

### NC4 — 移除插拔 UI / 广播
- **NC4.1** `MainActivity`：删 USB 广播注册/接收、`UsbDisconnectedOverlay` 挂载
- **NC4.2** 删无卡等待背景页、`UsbDisconnectedOverlay`、相关 drawable 引用

### NC5 — 彻底清除真卡代码与依赖
- **NC5.1** 删 `RealUsbManager`/`RealFileSystem`/`CardKeystore`/`data/real`+`data/staging` 卡分支/`CardFileDataSource` 卡专用/`TestModeManager`
- **NC5.2** 删 `app/libs/seczure.*.jar`、`jniLibs/**`、`device_filter.xml`；清 `build.gradle` jniLibs 打包 + `AndroidManifest` usb 条目
- **NC5.3** 全量编译 + lint，清理孤儿 import/字符串

### NC6 — 装机冒烟（模拟器 + 真机双机）
- 两机 P2P 建联、文本/文件/语音收发、阅后即焚、视频预览、导入/导出加密容器、恢复出厂/一键清理/退出自动清理、Android Keystore 在无 StrongBox 机型的降级路径

## 4. 关注点 / 风险
- **Keystore 降级**：无 StrongBox 机型退 TEE；再无则记录并按产品要求决定是否允许软件 KEK（NC6 前确认）。
- **判活语义**：卡侧「AUTHENTICATED（盘已打开）」在无卡下无「盘」概念——统一定义为「已登录会话 + DEK 已解锁」，`ChatStore.active()` 等据此改。
- **绑定语义弱化**：`ANDROID_ID` 重装/恢复出厂会变（与真卡 `.bind` 随卡走不同）——文档如实标注，写入 `docs/design-deviations.md`。
- **恢复出厂彻底性**：本地删目录不抗取证恢复（不如卡 SFFormat）——产品话术不夸大，deviations 记录。
- **cherry-pick 纪律**：共享层（UI/crypto/P2P）改动优先在 `bobo` 落，再 pick 到本分支；后端层各自维护。
