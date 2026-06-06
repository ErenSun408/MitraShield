package com.example.midun.network

import android.util.Base64
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
class P2PSessionManager @Inject constructor() {

    private val serverSocket = MutableStateFlow<ServerSocket?>(null)

    private val _activeSession = MutableStateFlow<P2PSession?>(null)
    /** 当前会话；M10.3 由 ChatViewModel 观察以驱动连接状态。 */
    val activeSession: StateFlow<P2PSession?> = _activeSession.asStateFlow()

    /** A 侧临时密钥对：generateConnectionInfo 生成、startListening 握手时消费。 */
    private var listenerKeyPair: KeyPair? = null

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
            deviceSn = "DEVICE_SN_001",
            ipv6 = getLocalIPv6Address(),
            sessionId = generateRandomHex(8),
            tempPublicKey = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP),
            expiresAt = System.currentTimeMillis() + 120_000
        )
    }

    /** 监听方（A，生成二维码）：阻塞等待对端连入并完成 ECDH 握手。 */
    suspend fun startListening(port: Int = 8888, onConnected: (P2PSession) -> Unit) {
        withContext(Dispatchers.IO) {
            val server = ServerSocket(port)
            serverSocket.value = server
            val socket = server.accept()
            val session = performListenerHandshake(socket)
            _activeSession.value = session
            onConnected(session)
        }
    }

    /** 连接方（B，扫码）：用二维码里的连接信息主动连接监听方并完成 ECDH 握手。 */
    suspend fun connectTo(info: ConnectionInfo, port: Int = 8888): Result<P2PSession> =
        withContext(Dispatchers.IO) {
            try {
                val socket = Socket(info.ipv6, port)
                val session = performConnectorHandshake(socket, info.tempPublicKey)
                _activeSession.value = session
                Result.success(session)
            } catch (e: Exception) {
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
        return P2PSession(socket, contactId = "unknown", sessionKey = sessionKey, reader = reader, writer = writer)
    }

    /**
     * B 侧握手：生成临时对，把公钥经 socket 发给 A，用自己私钥 + A 的 tpk（二维码）派生。
     * [peerTempPublicKey] = 二维码里 A 的临时公钥 Base64。
     */
    private fun performConnectorHandshake(socket: Socket, peerTempPublicKey: String): P2PSession {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val writer = PrintWriter(socket.getOutputStream(), true)
        val myKeyPair = P2PCrypto.generateEcKeyPair()
        writer.println(Base64.encodeToString(myKeyPair.public.encoded, Base64.NO_WRAP))
        val peerPubBytes = Base64.decode(peerTempPublicKey, Base64.NO_WRAP)
        val sessionKey = P2PCrypto.deriveSharedKey(myKeyPair.private, peerPubBytes)
        return P2PSession(socket, contactId = "unknown", sessionKey = sessionKey, reader = reader, writer = writer)
    }

    /** AES-GCM 加密消息内容 → Base64（替换 v4 §9.3 的 `mockEncrypt`）。供 M10.4 发送用。 */
    fun encryptMessage(session: P2PSession, plaintext: String): String =
        Base64.encodeToString(P2PCrypto.encrypt(session.sessionKey, plaintext), Base64.NO_WRAP)

    /** AES-GCM 解密 frame payload（Base64 → 明文）。供 M10.4 接收用。 */
    fun decryptMessage(session: P2PSession, payload: String): String =
        P2PCrypto.decrypt(session.sessionKey, Base64.decode(payload, Base64.NO_WRAP))

    /** 断开连接并清理：销毁会话密钥（v4 验收「断开后会话密钥清除」）+ 清临时私钥。 */
    fun disconnect() {
        _activeSession.value?.let { session ->
            session.sessionKey.fill(0) // 抹掉内存中的会话密钥
            runCatching { session.socket.close() }
        }
        runCatching { serverSocket.value?.close() }
        _activeSession.value = null
        serverSocket.value = null
        listenerKeyPair = null
    }

    /** 遍历网卡取第一个非回环、非 link-local 的 IPv6 地址；取不到回退 ::1。 */
    private fun getLocalIPv6Address(): String {
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
            iface.inetAddresses?.toList()?.forEach { addr ->
                if (addr is Inet6Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    return stripZoneId(addr.hostAddress ?: "")
                }
            }
        }
        return "::1"
    }

    /** 去掉 IPv6 的 zone id（如 fe80::1%wlan0 → fe80::1），跨设备连接时 scope 无意义。 */
    private fun stripZoneId(address: String): String = address.substringBefore('%')

    private fun generateRandomHex(bytes: Int): String =
        ByteArray(bytes).also { Random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
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
 * 序列化用 org.json（偏离 v4 的 kotlinx.serialization）。
 */
data class MessageFrame(
    val type: String,
    val payload: String,
    val timestamp: Long
) {
    fun toJson(): String = JSONObject().apply {
        put("type", type)
        put("payload", payload)
        put("ts", timestamp)
    }.toString()

    companion object {
        fun fromJson(text: String): MessageFrame = JSONObject(text).run {
            MessageFrame(
                type = getString("type"),
                payload = getString("payload"),
                timestamp = getLong("ts")
            )
        }
    }
}
