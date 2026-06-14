package com.example.midun.network

import android.util.Base64
import com.example.midun.data.FileCachePaths
import com.example.midun.data.SecurityCardManager
import com.example.midun.data.mock.MockChatRepository
import com.example.midun.data.mock.MockOperationLog
import com.example.midun.data.real.RealFileSystem
import com.example.midun.data.model.Contact
import com.example.midun.data.model.MessageStatus
import com.example.midun.data.model.MessageType
import com.example.midun.data.model.OperationType
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.security.MessageDigest
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * IPv6 P2P 会话管理器（v4 §9 / 里程碑 M10）。
 *
 * - M10.1：传输骨架（真实 IPv6 发现 + ServerSocket/Socket 建链 + MessageFrame org.json 编解码）。
 * - M10.2：真实 ECDH(P-256) 握手 + AES-256-GCM（[P2PCrypto]）。会话密钥由软件 ECDH 协商，
 *   替换 v4 写死的 `ByteArray(32){it}`；与 ChatViewModel/QR 的接线仍留 M10.3。
 *
 * **握手模型（QR 绑定式 ECDH，偏离 v4 把 tpk/握手脱节的写法）**：
 * - A（监听/出码方）：[generateConnectionInfo] 生成临时 EC 对，公钥写入二维码 `tpk`，私钥暂存
 *   [listenerKeyPair]；[startListening] 接入后用「暂存私钥 + 对端经 socket 发来的公钥」派生。
 * - B（扫码/连接方）：[connectTo] 读二维码里的 A 公钥（tpk），生成自己临时对、把公钥经 socket
 *   发给 A，用「自己私钥 + A 的 tpk」派生。
 * - 双方得同一 ECDH 共享密钥 → HKDF → AES-256。A 的公钥走二维码带外通道，对 TCP 路径上替换 A
 *   密钥的 MITM 免疫（**单向认证**）；完整双向认证 + 硬件密钥归 M11/FSShell。
 *
 * 偏离 v4：序列化用 org.json（非 kotlinx.serialization，M10 决策2）；类统一收入 network/ 包。
 */
@Singleton
class P2PSessionManager @Inject constructor(
    private val chatRepo: MockChatRepository,
    private val operationLog: MockOperationLog,
    private val cardManager: SecurityCardManager,
    // 文件接收落卡（M11.5.3）：直接依赖叶子 RealFileSystem（无 DI 环，同 ChatStore/OperationLogStore 选型）。
    private val realFileSystem: RealFileSystem
) {

    /** 接收循环 / 身份发送的常驻协程作用域（单例，独占 socket，跨屏存活）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val serverSocket = MutableStateFlow<ServerSocket?>(null)

    private val _activeSession = MutableStateFlow<P2PSession?>(null)
    /** 当前会话；M10.3 由 ChatViewModel 观察以驱动连接状态。 */
    val activeSession: StateFlow<P2PSession?> = _activeSession.asStateFlow()

    /** 连接状态（单例=唯一真相，跨屏一致）。M10.3 由 ChatViewModel 转发给 UI。 */
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    enum class ConnectionState { DISCONNECTED, LISTENING, CONNECTING, CONNECTED, FAILED }

    /** 收到对端消息后发出的 contactId 信号，ChatViewModel 收到即重载列表/会话（M10.4）。 */
    private val _incomingMessages = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val incomingMessages: SharedFlow<String> = _incomingMessages.asSharedFlow()

    /**
     * 文件传输进度（M11.5.3）：messageId → 0f..1f。发送/接收共用，UI 观察以画进度条；完成/失败即移除。
     * 进度是瞬时态、不入库（消息本体只存 SENDING/SENT/RECEIVED/FAILED 状态）。
     */
    private val _transferProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val transferProgress: StateFlow<Map<String, Float>> = _transferProgress.asStateFlow()

    private fun setProgress(messageId: String, fraction: Float) {
        _transferProgress.value = _transferProgress.value + (messageId to fraction.coerceIn(0f, 1f))
    }

    private fun clearProgress(messageId: String) {
        _transferProgress.value = _transferProgress.value - messageId
    }

    /**
     * 本端阅后即焚模式（B 阶段）：开启后本端发出的消息均为焚毁消息，并经 BURN_MODE 帧通知对端。
     * 是「活动会话」概念——断开/对端离线即清回 off（同会话密钥清除时机）。
     */
    private val _burnMode = MutableStateFlow(BurnMode(enabled = false, ttlSeconds = 0))
    val burnMode: StateFlow<BurnMode> = _burnMode.asStateFlow()

    data class BurnMode(val enabled: Boolean, val ttlSeconds: Int)

    /**
     * 进行中的焚毁倒计时（B 阶段）：messageId → 焚毁截止时刻(epoch ms)。接收方点开焚毁消息后写入，
     * 供 UI 显示剩余秒数；倒计时跑在单例 scope（活过会话页导航——读过即注定焚毁）。
     */
    private val _burnTimers = MutableStateFlow<Map<String, Long>>(emptyMap())
    val burnTimers: StateFlow<Map<String, Long>> = _burnTimers.asStateFlow()

    /** 模拟模式 / 真 SN 不可用时的回退 SN：每进程随机一个，保证两机可区分。 */
    private val fallbackSn: String = "DEV-${generateRandomHex(4)}"

    /**
     * 本机设备 SN（M11.6.2）：真卡模式下取安全卡真实 SN（`SFDiskGetSN`，全球唯一公开标识）→ 二维码据此
     * 做「弱来源标识」；模拟模式 / 未认证 / 读取失败回退 [fallbackSn]。每次取用即读，避免认证前后过期。
     */
    private fun currentDeviceSn(): String {
        if (cardManager.useRealCard.value) {
            cardManager.realSerialNumber()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return fallbackSn
    }

    /** A 侧临时密钥对：generateConnectionInfo 生成、startListening 握手时消费。 */
    private var listenerKeyPair: KeyPair? = null

    /**
     * 文件传输通道（M11.5.3 / `[file-transfer]`）：与聊天 socket 物理隔离的第二条二进制 TCP。
     * [fileServerSocket] 仅 A 侧用（提前绑定 [FileTransferChannel.FILE_PORT]）；[fileChannel] 是握手后
     * 建立的双工通道（A accept / B connect），TCP 全双工 → 双向发文件复用同一条。建立失败不影响聊天会话。
     */
    private var fileServerSocket: ServerSocket? = null
    @Volatile private var fileChannel: FileTransferChannel? = null

    /** 文件发送串行化锁（M11.5.3）：一条通道上文件帧不能交错，收端单文件状态机才成立。 */
    private val fileSendMutex = Mutex()

    /** 发送取消标志（M11.5.3 收尾）：用户取消在途发送时置位，发送循环检测到即发 FILE_CANCEL 并中止。 */
    @Volatile private var sendCancelled = false

    /** 取消当前在途文件发送（接收端会收到 FILE_CANCEL 删半成品）。 */
    fun cancelFileSend() { sendCancelled = true }

    /** 当前正在接收的文件（单文件状态机）。@Volatile：接收循环写、teardown 读。 */
    @Volatile private var incoming: IncomingFile? = null

    /** 接收中文件的状态（流式落卡，不在内存攒整文件）。 */
    private class IncomingFile(
        val msgId: String, val contactId: String, val fileName: String, val fileSize: Long,
        val fileNonce: ByteArray, val totalChunks: Int, val stagingPath: String,
        val handle: Int, val digest: MessageDigest, var written: Long = 0
    )

    data class P2PSession(
        val socket: Socket,
        val contactId: String,
        val sessionKey: ByteArray,
        val reader: BufferedReader,
        val writer: PrintWriter
    )

    /**
     * 生成本机连接信息（写入二维码）。M10.2：ipv6/sessionId 真实，`tempPublicKey` 为真实 ECDH
     * 临时公钥的 Base64（替换 v4 的 `MOCK_TEMP_PK_...`）；私钥暂存供 startListening 握手。
     * deviceSn 仍占位（真 SN 来自安全卡 = M11）。
     */
    suspend fun generateConnectionInfo(): ConnectionInfo = withContext(Dispatchers.IO) {
        val keyPair = P2PCrypto.generateEcKeyPair()
        listenerKeyPair = keyPair
        ConnectionInfo(
            version = 1,
            deviceSn = currentDeviceSn(),
            ipv6 = getLocalReachableAddress(), // 可能是 WiFi 局域网 IPv4 或公网 IPv6（字段名沿用 ipv6）
            sessionId = generateRandomHex(8),
            // 压缩公钥（33 字节，需求规格）→ Base64，替换原 X.509(SPKI ~91 字节)，二维码更小。
            tempPublicKey = Base64.encodeToString(P2PCrypto.compressPublicKey(keyPair.public), Base64.NO_WRAP),
            expiresAt = System.currentTimeMillis() + 120_000
        )
    }

    /** 监听方（A，生成二维码）：阻塞等待对端连入并完成 ECDH 握手。 */
    suspend fun startListening(port: Int = 8888, onConnected: (P2PSession) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                val server = ServerSocket(port)
                serverSocket.value = server
                // 文件通道端口提前绑定：B 握手后会立即 connect，提前 bind 让其入 backlog、避免 accept 时序竞态。
                fileServerSocket = ServerSocket(FileTransferChannel.FILE_PORT)
                _connectionState.value = ConnectionState.LISTENING
                val socket = server.accept() // 阻塞；disconnect() 关闭 server 会以异常打断
                val session = performListenerHandshake(socket)
                _activeSession.value = session
                _connectionState.value = ConnectionState.CONNECTED
                onConnected(session)
                // A 侧联系人在收到 B 的 IDENTITY 帧后才建（A 事先不知对端身份）。
                onSessionEstablished(session)
                establishFileChannelAsListener() // A 侧：accept 文件通道连接
            } catch (e: Exception) {
                // accept 被 disconnect() 主动关闭打断属正常拆除，不标 FAILED
                if (_connectionState.value != ConnectionState.CONNECTED) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
                throw e
            }
        }
    }

    /**
     * 连接方（B，扫码）：用二维码里的连接信息主动连接监听方并完成 ECDH 握手。
     * B 事先知道对端身份（二维码的 deviceSn）+ 用户备注，故连上即建联系人并绑定会话。
     */
    suspend fun connectTo(info: ConnectionInfo, remark: String, port: Int = 8888): Result<P2PSession> =
        withContext(Dispatchers.IO) {
            _connectionState.value = ConnectionState.CONNECTING
            try {
                // 显式连接超时：不可达/对方未监听时快速失败（默认无超时会卡到系统级 ~分钟）。
                val socket = Socket().apply {
                    connect(InetSocketAddress(info.ipv6, port), CONNECT_TIMEOUT_MS)
                }
                val handshaken = performConnectorHandshake(socket, info.tempPublicKey)
                val contactId = bindContact(info.deviceSn, remark)
                val session = handshaken.copy(contactId = contactId)
                _activeSession.value = session
                _connectionState.value = ConnectionState.CONNECTED
                onSessionEstablished(session)
                establishFileChannelAsConnector(info.ipv6) // B 侧：连接文件通道端口
                Result.success(session)
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.FAILED
                Result.failure(e)
            }
        }

    /**
     * A 侧握手：用暂存的临时私钥 + 对端经 socket 发来的公钥派生会话密钥。
     * A 不向 socket 发公钥——其公钥已通过二维码（tpk）带外送达 B。
     */
    private fun performListenerHandshake(socket: Socket): P2PSession {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val writer = PrintWriter(socket.getOutputStream(), true)
        val myKeyPair = listenerKeyPair
            ?: throw IllegalStateException("握手失败：未先调用 generateConnectionInfo 生成临时密钥")
        val peerPubLine = reader.readLine() ?: throw IOException("握手失败：对端未发送公钥")
        val peerPubBytes = Base64.decode(peerPubLine.trim(), Base64.NO_WRAP)
        val sessionKey = P2PCrypto.deriveSharedKey(myKeyPair.private, peerPubBytes)
        return P2PSession(socket, contactId = UNKNOWN_CONTACT, sessionKey = sessionKey, reader = reader, writer = writer)
    }

    /**
     * B 侧握手：生成临时对，把公钥经 socket 发给 A，用自己私钥 + A 的 tpk（二维码）派生。
     * [peerTempPublicKey] = 二维码里 A 的临时公钥 Base64。
     */
    private fun performConnectorHandshake(socket: Socket, peerTempPublicKey: String): P2PSession {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val writer = PrintWriter(socket.getOutputStream(), true)
        val myKeyPair = P2PCrypto.generateEcKeyPair()
        // 自己的临时公钥也走压缩编码（33 字节），与二维码 tpk 一致。
        writer.println(Base64.encodeToString(P2PCrypto.compressPublicKey(myKeyPair.public), Base64.NO_WRAP))
        val peerPubBytes = Base64.decode(peerTempPublicKey, Base64.NO_WRAP)
        val sessionKey = P2PCrypto.deriveSharedKey(myKeyPair.private, peerPubBytes)
        return P2PSession(socket, contactId = UNKNOWN_CONTACT, sessionKey = sessionKey, reader = reader, writer = writer)
    }

    /** AES-GCM 加密消息内容 → Base64（替换 v4 §9.3 的 `mockEncrypt`）。供 M10.4 发送用。 */
    fun encryptMessage(session: P2PSession, plaintext: String): String =
        Base64.encodeToString(P2PCrypto.encrypt(session.sessionKey, plaintext), Base64.NO_WRAP)

    /** AES-GCM 解密 frame payload（Base64 → 明文）。供 M10.4 接收用。 */
    fun decryptMessage(session: P2PSession, payload: String): String =
        P2PCrypto.decrypt(session.sessionKey, Base64.decode(payload, Base64.NO_WRAP))

    /**
     * 发送文字消息（M10.4）：AES-GCM 加密 → MessageFrame(org.json) → socket，并本地入库展示（isMine=true）。
     * 无活动会话 / 未绑定联系人 / socket 写错 → Result.failure。
     */
    suspend fun sendText(content: String, type: MessageType): Result<Unit> = withContext(Dispatchers.IO) {
        val session = _activeSession.value
            ?: return@withContext Result.failure(IllegalStateException("无活动连接"))
        val contactId = session.contactId.takeIf { it != UNKNOWN_CONTACT }
            ?: return@withContext Result.failure(IllegalStateException("连接尚未就绪"))
        val messageId = generateMessageId()
        // 阅后即焚模式（B 阶段）：开启态下仅文字消息打焚毁标记（文件等暂不焚）。
        val burning = _burnMode.value.enabled && type == MessageType.TEXT
        val ttl = if (burning) _burnMode.value.ttlSeconds else 0
        try {
            writeFrame(session, messageId, type.name, content, burning, ttl) // 帧带稳定 id（可能抛 IOException）
            chatRepo.sendMessage(contactId, content, type, messageId, MessageStatus.SENT, burning, ttl)
            Result.success(Unit)
        } catch (e: Exception) {
            // 写 socket 失败（连接已断）：本地入库标 FAILED，让气泡显「未送达」。
            chatRepo.sendMessage(contactId, content, type, messageId, MessageStatus.FAILED, burning, ttl)
            Result.failure(e)
        }
    }

    /**
     * 发送一个文件（M11.5.3）。来源无关：UI 传入文件名/大小/mime + 打开输入流的 lambda（手机选取器 URI 流
     * 或隐私文件夹卡内读流）。走文件通道分块加密发送，本地插一条 FILE 气泡（SENDING→SENT/FAILED）。
     *
     * 协议：`FILE_BEGIN`(加密元数据 JSON) → N×`FILE_CHUNK`(4B chunkIndex ‖ [P2PCrypto.encryptChunk] 密文) →
     * `FILE_END`(加密 JSON{msgId, sha256})。每块 nonce/AAD 绑 chunkIndex/isLast，收端可验重排/截断。
     * `isLast` 由 totalChunks（据 [size] 算）确定，收端用同一 totalChunks 复算 → 两端一致。
     */
    suspend fun sendFile(
        fileName: String, size: Long, mime: String, sourceCardPath: String? = null, openStream: () -> InputStream
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val session = _activeSession.value
            ?: return@withContext Result.failure(IllegalStateException("无活动连接"))
        val contactId = session.contactId.takeIf { it != UNKNOWN_CONTACT }
            ?: return@withContext Result.failure(IllegalStateException("连接尚未就绪"))
        val channel = fileChannel
            ?: return@withContext Result.failure(IllegalStateException("文件通道未建立，无法发送文件"))
        if (size > MAX_FILE_BYTES) {
            return@withContext Result.failure(IllegalStateException("文件超过 100MB 上限，无法发送"))
        }
        // 串行化文件发送：一条通道上 BEGIN/CHUNK/END 必须不被另一文件穿插，收端单文件状态机才成立。
        fileSendMutex.withLock { doSendFile(channel, session, contactId, fileName, size, mime, sourceCardPath, openStream) }
    }

    /**
     * @param sourceCardPath 隐私文件夹来源=源文件卡内路径（已在卡上、发送方直接据此预览，不另留副本）；
     *   手机来源=null（真卡模式下边发边把明文流另写一份 `0:/.sent_<msgId>` 作发送方预览副本）。
     */
    private suspend fun doSendFile(
        channel: FileTransferChannel, session: P2PSession, contactId: String,
        fileName: String, size: Long, mime: String, sourceCardPath: String?, openStream: () -> InputStream
    ): Result<Unit> {
        val key = session.sessionKey
        val msgId = generateMessageId()
        val fileNonce = P2PCrypto.newFileNonce()
        val totalChunks = ((size + FILE_CHUNK_BYTES - 1) / FILE_CHUNK_BYTES).toInt().coerceAtLeast(1)

        // 发送方预览副本：手机来源在真卡模式下边发边落 `.sent_<msgId>`；落不成则不留（localPath 保持 null）。
        val sentCopyPath = if (sourceCardPath == null && cardManager.useRealCard.value) FileCachePaths.sent(msgId) else null
        val copyHandle = sentCopyPath?.let { realFileSystem.streamCreate(it).takeIf { h -> h > 0 } }

        // 隐私文件夹来源即刻可预览（文件已在卡上）→ 起始就带 localPath；手机来源成功后再补。
        chatRepo.addFileMessage(contactId, msgId, isMine = true, fileName, size, MessageStatus.SENDING, localPath = sourceCardPath)
        setProgress(msgId, 0f)
        _incomingMessages.emit(contactId)
        sendCancelled = false
        return try {
            val beginJson = JSONObject().apply {
                put("msgId", msgId)
                put("fileName", fileName)
                put("fileSize", size)
                put("mime", mime)
                put("nonce", Base64.encodeToString(fileNonce, Base64.NO_WRAP))
                put("chunks", totalChunks)
            }.toString()
            channel.sendFrame(FileTransferChannel.FILE_BEGIN, P2PCrypto.encrypt(key, beginJson))

            val digest = MessageDigest.getInstance("SHA-256")
            openStream().use { input ->
                var sent = 0L
                for (index in 0 until totalChunks) {
                    if (sendCancelled) {
                        // 用户取消：通知对端删半成品后中止（落入 catch 标 FAILED）。
                        runCatching { channel.sendFrame(FileTransferChannel.FILE_CANCEL, P2PCrypto.encrypt(key, msgId)) }
                        throw IOException("已取消发送")
                    }
                    val want = minOf(FILE_CHUNK_BYTES.toLong(), size - index.toLong() * FILE_CHUNK_BYTES)
                        .toInt().coerceAtLeast(0)
                    val chunk = ByteArray(want)
                    if (want > 0) readFully(input, chunk)
                    if (copyHandle != null && want > 0) realFileSystem.streamWrite(copyHandle, chunk, 0, want)
                    digest.update(chunk)
                    val isLast = index == totalChunks - 1
                    val cipher = P2PCrypto.encryptChunk(key, fileNonce, index, isLast, chunk)
                    channel.sendFrame(FileTransferChannel.FILE_CHUNK, intToBytes(index) + cipher)
                    sent += want
                    setProgress(msgId, if (size > 0) sent.toFloat() / size else 1f)
                }
            }

            val endJson = JSONObject().apply {
                put("msgId", msgId)
                put("sha256", toHex(digest.digest()))
            }.toString()
            channel.sendFrame(FileTransferChannel.FILE_END, P2PCrypto.encrypt(key, endJson))

            // 副本写全 → 记 localPath（发送方此后可预览自己发的图/视频）。
            if (copyHandle != null) {
                realFileSystem.streamClose(copyHandle)
                sentCopyPath?.let { chatRepo.setFileLocalPath(msgId, contactId, it) }
            }
            chatRepo.updateFileStatus(msgId, contactId, MessageStatus.SENT)
            clearProgress(msgId)
            _incomingMessages.emit(contactId)
            Result.success(Unit)
        } catch (e: Exception) {
            // 失败/取消：关副本句柄并删半成品（localPath 仍为 null，发送方不会预览到残片）。
            if (copyHandle != null) {
                runCatching { realFileSystem.streamClose(copyHandle) }
                sentCopyPath?.let { runCatching { realFileSystem.streamDelete(it) } }
            }
            chatRepo.updateFileStatus(msgId, contactId, MessageStatus.FAILED)
            clearProgress(msgId)
            _incomingMessages.emit(contactId)
            Result.failure(e)
        }
    }

    /** 从 [input] 精确读满 [buf]（InputStream.read 可能短读）；流提前结束抛 EOFException。 */
    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("文件流提前结束 @${off}/${buf.size}")
            off += n
        }
    }

    /** Int → 4 字节大端（文件块帧的 chunkIndex 前缀）。 */
    private fun intToBytes(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * 开/关阅后即焚模式（B 阶段，仅在活动会话内有效）：发 BURN_MODE 帧通知对端 → 对端插系统行；
     * 本端同步更新 [burnMode] 状态、本地插一条系统行。payload="on:<秒>" / "off"。
     */
    suspend fun setBurnMode(enabled: Boolean, ttlSeconds: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val session = _activeSession.value
            ?: return@withContext Result.failure(IllegalStateException("无活动连接"))
        val contactId = session.contactId.takeIf { it != UNKNOWN_CONTACT }
            ?: return@withContext Result.failure(IllegalStateException("连接尚未就绪"))
        try {
            writeFrame(session, generateMessageId(), BURN_MODE_TYPE, if (enabled) "on:$ttlSeconds" else "off")
            _burnMode.value = BurnMode(enabled, if (enabled) ttlSeconds else 0)
            val text = if (enabled) "🔥 你开启了阅后即焚（${formatTtl(ttlSeconds)}）" else "🔥 你关闭了阅后即焚"
            chatRepo.addSystemMessage(contactId, text)
            _incomingMessages.emit(contactId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 接收方点开焚毁消息（B 阶段）：登记倒计时（ttl 秒），到点触发双端焚毁。重复点开忽略（已在倒计时）。
     * 定时器跑在单例 scope，故离开会话页也照常焚毁。contactId 透传给 [burnMessage] 以便无活动会话时仍能本地焚。
     */
    fun revealBurnMessage(messageId: String, contactId: String, ttlSeconds: Int) {
        if (_burnTimers.value.containsKey(messageId)) return
        _burnTimers.value = _burnTimers.value + (messageId to System.currentTimeMillis() + ttlSeconds * 1000L)
        scope.launch {
            delay(ttlSeconds * 1000L)
            runCatching { burnMessage(messageId, contactId) }
            _burnTimers.value = _burnTimers.value - messageId
        }
    }

    /**
     * 焚毁一条阅后即焚消息（B 阶段）：若该联系人为当前活动会话，发 BURN 帧令对端按 id 焚毁；
     * 本端无论是否在线都标记 burned（焚毁墓碑）。对端收到 BURN 帧由 handleIncoming 焚毁。
     */
    suspend fun burnMessage(messageId: String, contactId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val session = _activeSession.value
        if (session != null && session.contactId == contactId) {
            runCatching { writeFrame(session, generateMessageId(), BURN_TYPE, messageId) }
        }
        chatRepo.markBurned(messageId, contactId)
        _incomingMessages.emit(contactId)
        Result.success(Unit)
    }

    /** 焚毁 TTL 秒数 → 人类可读（5→5秒，60→1分钟）。 */
    private fun formatTtl(seconds: Int): String =
        if (seconds < 60) "${seconds}秒" else "${seconds / 60}分钟"

    /**
     * 撤回自己发的消息（M10.5）：发 RECALL 控制帧（payload=目标消息 id）→ 对端按 id 删；本地也删。
     * 无活动会话 → Result.failure（调用方回退本地删除）。
     */
    suspend fun recallMessage(messageId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val session = _activeSession.value
            ?: return@withContext Result.failure(IllegalStateException("无活动连接"))
        val contactId = session.contactId.takeIf { it != UNKNOWN_CONTACT }
            ?: return@withContext Result.failure(IllegalStateException("连接尚未就绪"))
        try {
            writeFrame(session, generateMessageId(), RECALL_TYPE, messageId) // payload=目标 id
            chatRepo.markRecalled(messageId, contactId) // 本地标记已撤回（抹原文）
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 握手完成后：启动接收循环 + 主动发送本机身份帧（供对端建联系人/绑定会话）。 */
    private fun onSessionEstablished(session: P2PSession) {
        startReceiveLoop(session)
        scope.launch { runCatching { writeFrame(session, generateMessageId(), IDENTITY_TYPE, currentDeviceSn()) } }
    }

    // —— 文件传输通道（M11.5.3 / `[file-transfer]`）——

    /** A 侧：accept 文件通道连接（[fileServerSocket] 已在 startListening 提前绑定），起常驻接收循环。 */
    private fun establishFileChannelAsListener() {
        scope.launch {
            var channel: FileTransferChannel? = null
            try {
                val sock = fileServerSocket?.accept() ?: return@launch
                channel = FileTransferChannel(sock).also { fileChannel = it }
                channel.receiveLoop { type, payload -> handleFileFrame(type, payload) }
            } catch (_: Exception) {
                // 文件通道建立/中断不影响聊天会话；仅清空，后续发文件会失败提示。
            } finally {
                channel?.close()
                if (fileChannel === channel) fileChannel = null
            }
        }
    }

    /** B 侧：连接 A 的文件通道端口（[FileTransferChannel.FILE_PORT]），起常驻接收循环。 */
    private fun establishFileChannelAsConnector(host: String) {
        scope.launch {
            var channel: FileTransferChannel? = null
            try {
                val sock = Socket().apply {
                    connect(InetSocketAddress(host, FileTransferChannel.FILE_PORT), CONNECT_TIMEOUT_MS)
                }
                channel = FileTransferChannel(sock).also { fileChannel = it }
                channel.receiveLoop { type, payload -> handleFileFrame(type, payload) }
            } catch (_: Exception) {
            } finally {
                channel?.close()
                if (fileChannel === channel) fileChannel = null
            }
        }
    }

    /**
     * 文件帧分发（M11.5.3 接收管线）：FILE_BEGIN→卡内建暂存、FILE_CHUNK→解密流式落卡、
     * FILE_END→校验 sha256、FILE_CANCEL→删半成品。接收为单文件状态机（发送端已串行化保证帧不交错）。
     */
    private suspend fun handleFileFrame(type: Int, payload: ByteArray) {
        val session = _activeSession.value ?: return
        val key = session.sessionKey
        when (type) {
            FileTransferChannel.FILE_BEGIN -> handleFileBegin(session, key, payload)
            FileTransferChannel.FILE_CHUNK -> handleFileChunk(key, payload)
            FileTransferChannel.FILE_END -> handleFileEnd(key, payload)
            FileTransferChannel.FILE_CANCEL -> abortIncoming(MessageStatus.FAILED)
            else -> {}
        }
    }

    /** FILE_BEGIN：解密元数据 → 真卡建暂存文件 `0:/.recv_<msgId>` → 插「接收中」FILE 气泡。 */
    private suspend fun handleFileBegin(session: P2PSession, key: ByteArray, payload: ByteArray) {
        val contactId = session.contactId.takeIf { it != UNKNOWN_CONTACT } ?: return
        val json = runCatching { JSONObject(P2PCrypto.decrypt(key, payload)) }.getOrNull() ?: return
        val msgId = json.getString("msgId")
        val fileName = json.getString("fileName")
        val fileSize = json.getLong("fileSize")
        val fileNonce = Base64.decode(json.getString("nonce"), Base64.NO_WRAP)
        val totalChunks = json.getInt("chunks")
        // 文件接收=真卡专属：无真卡无法落卡 → 诚实降级为一条 FAILED 文件气泡。
        if (!cardManager.useRealCard.value) {
            chatRepo.addFileMessage(contactId, msgId, isMine = false, fileName, fileSize, MessageStatus.FAILED)
            _incomingMessages.emit(contactId)
            return
        }
        val stagingPath = FileCachePaths.recv(msgId)
        val handle = realFileSystem.streamCreate(stagingPath)
        if (handle <= 0) {
            chatRepo.addFileMessage(contactId, msgId, isMine = false, fileName, fileSize, MessageStatus.FAILED)
            _incomingMessages.emit(contactId)
            return
        }
        incoming = IncomingFile(
            msgId, contactId, fileName, fileSize, fileNonce, totalChunks, stagingPath, handle,
            MessageDigest.getInstance("SHA-256")
        )
        chatRepo.addFileMessage(contactId, msgId, isMine = false, fileName, fileSize, MessageStatus.RECEIVED)
        setProgress(msgId, 0f)
        _incomingMessages.emit(contactId)
    }

    /** FILE_CHUNK：`4B chunkIndex ‖ 密文` → 解密（验重排/截断）→ SFWrite 流式落卡（不攒内存）→ 进度。 */
    private suspend fun handleFileChunk(key: ByteArray, payload: ByteArray) {
        val f = incoming ?: return
        if (payload.size < 4) return
        val index = bytesToInt(payload, 0)
        val cipher = payload.copyOfRange(4, payload.size)
        val isLast = index == f.totalChunks - 1
        val plain = runCatching { P2PCrypto.decryptChunk(key, f.fileNonce, index, isLast, cipher) }.getOrNull()
        if (plain == null) { abortIncoming(MessageStatus.FAILED); return } // 块被重排/重放/篡改 → GCM 校验失败
        realFileSystem.streamWrite(f.handle, plain, 0, plain.size)
        f.digest.update(plain)
        f.written += plain.size
        setProgress(f.msgId, if (f.fileSize > 0) f.written.toFloat() / f.fileSize else 1f)
    }

    /** FILE_END：关句柄 → 校验整文件 sha256，过则标 RECEIVED（待保存）、不过删半成品标 FAILED。 */
    private suspend fun handleFileEnd(key: ByteArray, payload: ByteArray) {
        val f = incoming ?: return
        incoming = null
        realFileSystem.streamClose(f.handle)
        val expect = runCatching { JSONObject(P2PCrypto.decrypt(key, payload)).getString("sha256") }.getOrNull()
        if (expect != null && expect == toHex(f.digest.digest())) {
            chatRepo.updateFileStatus(f.msgId, f.contactId, MessageStatus.RECEIVED)
        } else {
            realFileSystem.streamDelete(f.stagingPath)
            chatRepo.updateFileStatus(f.msgId, f.contactId, MessageStatus.FAILED)
        }
        clearProgress(f.msgId)
        _incomingMessages.emit(f.contactId)
    }

    /** 接收文件的卡内暂存路径（供 UI 免保存预览直接读卡 `0:/.recv_<msgId>`）。 */
    fun stagingPathFor(msgId: String): String = FileCachePaths.recv(msgId)

    /**
     * 接收方保存暂存文件到隐私文件夹（M11.5.3）：把卡内暂存 `0:/.recv_<msgId>` **复制**到 `0:/<folderId>/<fileName>`，
     * 成功后记 savedFolderId（气泡转「已保存」、可预览）。**暂存不删**——保留作 7 天预览缓存，保证对话内即点即看的丝滑度，
     * 到期由 TTL 清理。仍是卡内操作、明文不出卡。文件名冲突时加序号避让。
     */
    suspend fun saveReceivedFile(
        msgId: String, contactId: String, fileName: String, folderId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val staging = FileCachePaths.recv(msgId)
        if (realFileSystem.streamOpen(staging).let { h -> if (h > 0) { realFileSystem.streamClose(h); false } else true }) {
            return@withContext Result.failure(IllegalStateException("暂存文件不存在，可能已被清理"))
        }
        val dest = uniqueDestPath(folderId, fileName)
        if (!realFileSystem.copyWithinCard(staging, dest)) {
            return@withContext Result.failure(IllegalStateException("保存到文件夹失败"))
        }
        chatRepo.setFileSaved(msgId, contactId, folderId, dest.substringAfterLast('/'))
        _incomingMessages.emit(contactId)
        Result.success(Unit)
    }

    /** 目标文件夹内文件名冲突则加序号（`a.pdf`→`a(1).pdf`）。 */
    private fun uniqueDestPath(folderId: String, fileName: String): String {
        fun exists(path: String): Boolean {
            val h = realFileSystem.streamOpen(path)
            return if (h > 0) { realFileSystem.streamClose(h); true } else false
        }
        val base = "$folderId/$fileName"
        if (!exists(base)) return base
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var i = 1
        while (exists("$folderId/$stem($i)$ext")) i++
        return "$folderId/$stem($i)$ext"
    }

    /** 中止当前接收（解密失败/取消/断开）：关句柄、删半成品、消息标 [status]、清进度。 */
    private fun abortIncoming(status: MessageStatus) {
        val f = incoming ?: return
        incoming = null
        runCatching { realFileSystem.streamClose(f.handle) }
        runCatching { realFileSystem.streamDelete(f.stagingPath) }
        chatRepo.updateFileStatus(f.msgId, f.contactId, status)
        clearProgress(f.msgId)
        _incomingMessages.tryEmit(f.contactId)
    }

    private fun bytesToInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    /** 关闭文件通道 + A 侧监听 socket（disconnect / 对端断开时）；中止在途接收、删半成品。 */
    private fun teardownFileChannel() {
        abortIncoming(MessageStatus.FAILED)
        runCatching { fileChannel?.close() }
        fileChannel = null
        runCatching { fileServerSocket?.close() }
        fileServerSocket = null
    }

    /** 后台读 socket：逐行解析 MessageFrame → 解密 → 分发（身份帧 / 普通消息）。对端关闭则归位状态。 */
    private fun startReceiveLoop(session: P2PSession) {
        scope.launch {
            try {
                while (true) {
                    val line = session.reader.readLine() ?: break // null = 对端关闭
                    handleIncoming(line)
                }
            } catch (_: Exception) {
                // socket 被 disconnect() 关闭或断网，正常结束循环
            } finally {
                onPeerDisconnected()
            }
        }
    }

    private suspend fun handleIncoming(line: String) {
        val session = _activeSession.value ?: return
        val frame = runCatching { MessageFrame.fromJson(line) }.getOrNull() ?: return
        val plaintext = runCatching { decryptMessage(session, frame.payload) }.getOrNull() ?: return
        when (frame.type) {
            IDENTITY_TYPE -> {
                // 对端身份帧：建/找联系人并绑定到会话（A 侧由此首次建联系人）。
                val contactId = bindContact(plaintext, remark = plaintext)
                if (session.contactId == UNKNOWN_CONTACT) {
                    _activeSession.value = session.copy(contactId = contactId)
                }
                _incomingMessages.emit(contactId)
            }
            RECALL_TYPE -> {
                // 对端撤回：plaintext = 目标消息 id，标记本地对应消息为已撤回（M10.5）。
                val contactId = session.contactId.takeIf { it != UNKNOWN_CONTACT } ?: return
                chatRepo.markRecalled(plaintext, contactId)
                _incomingMessages.emit(contactId)
            }
            BURN_MODE_TYPE -> {
                // 对端开/关阅后即焚（B 阶段）：plaintext="on:<秒>" / "off"，插一条「对方…」系统行。
                val contactId = session.contactId.takeIf { it != UNKNOWN_CONTACT } ?: return
                val text = if (plaintext.startsWith("on")) {
                    val ttl = plaintext.substringAfter(':', "").toIntOrNull() ?: 0
                    "🔥 对方开启了阅后即焚（${formatTtl(ttl)}）"
                } else {
                    "🔥 对方关闭了阅后即焚"
                }
                chatRepo.addSystemMessage(contactId, text)
                _incomingMessages.emit(contactId)
            }
            BURN_TYPE -> {
                // 对端焚毁（B 阶段）：plaintext = 目标消息 id，本地焚毁对应消息为焚毁墓碑。
                val contactId = session.contactId.takeIf { it != UNKNOWN_CONTACT } ?: return
                chatRepo.markBurned(plaintext, contactId)
                _incomingMessages.emit(contactId)
            }
            else -> {
                val contactId = session.contactId.takeIf { it != UNKNOWN_CONTACT } ?: return
                val type = runCatching { MessageType.valueOf(frame.type) }.getOrDefault(MessageType.TEXT)
                // 焚毁消息带 burn/ttl 入库，接收端先遮罩、点开后倒计时焚毁（B 阶段）。
                chatRepo.receiveMessage(contactId, plaintext, type, frame.id, frame.burn, frame.burnTtl)
                _incomingMessages.emit(contactId)
            }
        }
    }

    /** AES-GCM 加密 content → MessageFrame(带 id) → 写 socket（一帧一行）。写失败抛 IOException。 */
    private fun writeFrame(
        session: P2PSession, id: String, type: String, content: String,
        burn: Boolean = false, burnTtl: Int = 0
    ) {
        val frame = MessageFrame(id, type, encryptMessage(session, content), System.currentTimeMillis(), burn, burnTtl)
        session.writer.println(frame.toJson())
        if (session.writer.checkError()) throw IOException("发送失败：socket 写入错误")
    }

    private fun generateMessageId(): String = "msg_${System.currentTimeMillis()}_${generateRandomHex(3)}"

    /** 按 deviceId 找联系人，没有则按备注新建并记 CONNECT 日志；返回 contactId。 */
    private suspend fun bindContact(deviceSn: String, remark: String): String {
        chatRepo.findContactByDevice(deviceSn)?.let { return it.id }
        val id = "c_${System.currentTimeMillis()}"
        chatRepo.addContact(
            Contact(id = id, deviceId = deviceSn, remark = remark, lastMessageTime = System.currentTimeMillis())
        )
        operationLog.record(OperationType.CONNECT, "与「$remark」建立加密连接")
        return id
    }

    /** 接收循环结束（对端断开）：若非主动 disconnect，归位为 DISCONNECTED 并抹密钥。 */
    private fun onPeerDisconnected() {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            _activeSession.value?.sessionKey?.fill(0)
            _activeSession.value = null
            _connectionState.value = ConnectionState.DISCONNECTED
            _burnMode.value = BurnMode(enabled = false, ttlSeconds = 0) // 焚毁模式随会话清除
            teardownFileChannel()
        }
    }

    /** 断开连接并清理：销毁会话密钥（v4 验收「断开后会话密钥清除」）+ 清临时私钥。 */
    fun disconnect() {
        _activeSession.value?.let { session ->
            session.sessionKey.fill(0) // 抹掉内存中的会话密钥
            runCatching { session.socket.close() }
        }
        runCatching { serverSocket.value?.close() }
        teardownFileChannel()
        _activeSession.value = null
        serverSocket.value = null
        listenerKeyPair = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _burnMode.value = BurnMode(enabled = false, ttlSeconds = 0) // 焚毁模式随会话清除
    }

    /**
     * 取本机最适合 P2P 直连的地址。优先级（M10.6 真机验收强化，偏离 v4 纯 IPv6）：
     * 1. **同 WiFi/有线局域网私网 IPv4**（`wlan*`/`eth*` 接口上的 192.168/10/172.16 段）——两机连同一
     *    路由器时恒可达，绕开「国内运营商不路由 subscriber 间公网 IPv6」这堵墙，用于验证握手/加密/收发。
     * 2. **公网全局单播 IPv6**（2000::/3）——v4 设计的跨网络路径（运营商允许时才通）。
     * 3. 回退 `::1`（明确表示「本机无可用直连地址」，而非塞不可路由地址误导对端）。
     *
     * 按接口名区分 WiFi 私网 IPv4 与蜂窝 CGNAT IPv4：蜂窝(`rmnet*`/`ccmni*`)的 10.x 是运营商 NAT 后地址，
     * 对端不可达，故只认 WiFi/有线接口上的私网 IPv4。
     */
    private fun getLocalReachableAddress(): String {
        val s = scanAddresses()
        return s.wifiV4 ?: s.globalV6 ?: "::1"
    }

    /** 接口名是否为 WiFi/有线（其私网 IPv4 在同局域网内对端可达）。 */
    private fun isLanInterface(name: String): Boolean =
        name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap")

    /** 全局单播 IPv6 = 2000::/3（首字节高 3 位为 001，即 0x20..0x3F）。 */
    private fun isGlobalUnicast(addr: Inet6Address): Boolean =
        (addr.address.firstOrNull()?.toInt() ?: 0) and 0xE0 == 0x20

    private data class AddressScan(val wifiV4: String?, val globalV6: String?, val all: List<String>)

    /** 单次枚举所有接口，挑出首个 WiFi 私网 IPv4、首个公网 IPv6，并收集全部地址供诊断。 */
    private fun scanAddresses(): AddressScan {
        var wifiV4: String? = null
        var globalV6: String? = null
        val all = mutableListOf<String>()
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
            val name = iface.name ?: ""
            iface.inetAddresses?.toList()?.forEach { addr ->
                val host = stripZoneId(addr.hostAddress ?: "?")
                all += "$name $host"
                if (addr is Inet6Address && globalV6 == null &&
                    !addr.isLoopbackAddress && !addr.isLinkLocalAddress &&
                    !addr.isSiteLocalAddress && !addr.isMulticastAddress &&
                    !addr.isAnyLocalAddress && isGlobalUnicast(addr)
                ) {
                    globalV6 = host
                }
                if (addr is Inet4Address && wifiV4 == null &&
                    addr.isSiteLocalAddress && isLanInterface(name)
                ) {
                    wifiV4 = host
                }
            }
        }
        return AddressScan(wifiV4, globalV6, all)
    }

    /**
     * 本机网络诊断快照（M10 真机排障）：UI 在出码/连接失败时显示，一眼定位用哪条路径、对端能否到达。
     */
    data class NetworkDiagnostics(
        /** [getLocalReachableAddress] 选中、写进二维码/出站用的地址；`::1` = 本机无可用直连地址。 */
        val selectedAddress: String,
        /** 选中地址的类型描述（WiFi 局域网直连 / 公网 IPv6 / 无可用直连地址）。 */
        val kind: String,
        /** 全部接口地址（`iface 地址` 形式，含 IPv4/IPv6），供进一步人工排查。 */
        val allAddresses: List<String>
    )

    /** 采集本机网络诊断信息（同步、轻量，可在主线程调用）。 */
    fun networkDiagnostics(): NetworkDiagnostics {
        val s = scanAddresses()
        val (addr, kind) = when {
            s.wifiV4 != null -> s.wifiV4!! to "WiFi 局域网直连（同路由器可达）"
            s.globalV6 != null -> s.globalV6!! to "公网 IPv6（跨网络，运营商允许时才通）"
            else -> "::1" to "无可用直连地址"
        }
        return NetworkDiagnostics(addr, kind, s.all)
    }

    /** 去掉 IPv6 的 zone id（如 fe80::1%wlan0 → fe80::1），跨设备连接时 scope 无意义。 */
    private fun stripZoneId(address: String): String = address.substringBefore('%')

    private fun generateRandomHex(bytes: Int): String =
        ByteArray(bytes).also { Random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    companion object {
        /** 握手后、绑定联系人前的占位 contactId。 */
        private const val UNKNOWN_CONTACT = "unknown"
        /** 身份交换帧的 type 值（与 MessageType 枚举名不冲突）。 */
        private const val IDENTITY_TYPE = "IDENTITY"
        /** 撤回控制帧的 type 值（payload=目标消息 id）。 */
        private const val RECALL_TYPE = "RECALL"
        /** 阅后即焚「开/关模式」广播帧（payload="on:30" / "off"），对端据此插系统行（B 阶段）。 */
        const val BURN_MODE_TYPE = "BURN_MODE"
        /** 阅后即焚「焚毁」控制帧（payload=目标消息 id）：读方倒计时到点 → 两端焚毁（B 阶段）。 */
        const val BURN_TYPE = "BURN"
        /** TCP 连接超时（ms）：不可达/对方未监听时快速失败。 */
        private const val CONNECT_TIMEOUT_MS = 10_000
        /** 文件分块大小（64KB，与 RealFileSystem.writeFile 分块一致）。 */
        private const val FILE_CHUNK_BYTES = 64 * 1024
        /** 文件传输上限（100MB，需求）。 */
        private const val MAX_FILE_BYTES = 100L * 1024 * 1024
        // 卡内缓存路径前缀（.recv_/.sent_）的唯一来源在 data.FileCachePaths（与 TTL 清理共用）。
    }
}

/**
 * 二维码承载的连接信息。M10.3 由 generateConnectionInfo 产出、扫码端解析驱动 connectTo。
 * 编解码用 org.json（与 MessageFrame 一致）。
 */
data class ConnectionInfo(
    val version: Int,
    val deviceSn: String,
    val ipv6: String,
    val sessionId: String,
    val tempPublicKey: String,
    val expiresAt: Long
) {
    fun toJson(): String = JSONObject().apply {
        put("ver", version)
        put("sn", deviceSn)
        put("ipv6", ipv6)
        put("sid", sessionId)
        put("tpk", tempPublicKey)
        put("exp", expiresAt)
    }.toString()

    companion object {
        fun fromJson(text: String): ConnectionInfo = JSONObject(text).run {
            ConnectionInfo(
                version = getInt("ver"),
                deviceSn = getString("sn"),
                ipv6 = getString("ipv6"),
                sessionId = getString("sid"),
                tempPublicKey = getString("tpk"),
                expiresAt = getLong("exp")
            )
        }
    }
}

/**
 * 网络消息帧（v4 §9.3）。payload 为 AES-GCM 密文的 Base64（M10.2 起，见 [P2PSessionManager.encryptMessage]）。
 * `id` 为消息稳定 ID（M10.5）：发送方生成、两端按此 ID 入库，使「撤回」能引用对端的同一条消息。
 * IDENTITY/RECALL 控制帧的 `id` 为一次性占位（接收方不据此入库）。序列化用 org.json（偏离 v4 kotlinx）。
 */
data class MessageFrame(
    val id: String,
    val type: String,
    val payload: String,
    val timestamp: Long,
    // 阅后即焚（B 阶段）：该消息是否为焚毁消息 + 读后倒计时时长（秒）。
    // 可选字段——旧帧/普通消息不含，fromJson 用 optBoolean/optInt 退化为 false/0。
    val burn: Boolean = false,
    val burnTtl: Int = 0
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("payload", payload)
        put("ts", timestamp)
        if (burn) {
            put("burn", true)
            put("ttl", burnTtl)
        }
    }.toString()

    companion object {
        fun fromJson(text: String): MessageFrame = JSONObject(text).run {
            MessageFrame(
                id = getString("id"),
                type = getString("type"),
                payload = getString("payload"),
                timestamp = getLong("ts"),
                burn = optBoolean("burn", false),
                burnTtl = optInt("ttl", 0)
            )
        }
    }
}
