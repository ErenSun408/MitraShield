package com.example.midun.network

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
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
 * M10.1 仅建立**传输骨架**：真实 IPv6 地址发现 + 真实 ServerSocket/Socket 建链 +
 * MessageFrame 的 org.json 编解码。握手仍是 v4 的 mock 明文（sessionKey/tpk 为占位），
 * 真实 ECDH(P-256)+AES-GCM 在 M10.2 接入；与 ChatViewModel/QR 的接线在 M10.3。
 *
 * 偏离 v4：
 * - 序列化用 org.json 而非 kotlinx.serialization（M10 决策2，零依赖、不破坏 M8 R8 干净）。
 * - 包归属 com.example.midun.network（v4 把类散在示例里，本项目统一收入 network/）。
 */
@Singleton
class P2PSessionManager @Inject constructor() {

    private val serverSocket = MutableStateFlow<ServerSocket?>(null)

    private val _activeSession = MutableStateFlow<P2PSession?>(null)
    /** 当前会话；M10.3 由 ChatViewModel 观察以驱动连接状态。 */
    val activeSession: StateFlow<P2PSession?> = _activeSession.asStateFlow()

    data class P2PSession(
        val socket: Socket,
        val contactId: String,
        val sessionKey: ByteArray,
        val reader: BufferedReader,
        val writer: PrintWriter
    )

    /**
     * 生成本机连接信息（写入二维码）。M10.1：ipv6 与 sessionId 真实，
     * deviceSn / tempPublicKey 仍为占位（真 SN 来自安全卡=M11，真 tpk 来自 ECDH=M10.2）。
     */
    suspend fun generateConnectionInfo(): ConnectionInfo = withContext(Dispatchers.IO) {
        ConnectionInfo(
            version = 1,
            deviceSn = "DEVICE_SN_001",
            ipv6 = getLocalIPv6Address(),
            sessionId = generateRandomHex(8),
            tempPublicKey = "MOCK_TEMP_PK_${System.currentTimeMillis()}",
            expiresAt = System.currentTimeMillis() + 120_000
        )
    }

    /** 监听方（A，生成二维码）：阻塞等待对端连入并完成握手。 */
    suspend fun startListening(port: Int = 8888, onConnected: (P2PSession) -> Unit) {
        withContext(Dispatchers.IO) {
            val server = ServerSocket(port)
            serverSocket.value = server
            val socket = server.accept()
            val session = performHandshake(socket, isInitiator = false)
            _activeSession.value = session
            onConnected(session)
        }
    }

    /** 连接方（B，扫码）：主动连接监听方并完成握手。 */
    suspend fun connectTo(ipv6: String, port: Int = 8888): Result<P2PSession> =
        withContext(Dispatchers.IO) {
            try {
                val socket = Socket(ipv6, port)
                val session = performHandshake(socket, isInitiator = true)
                _activeSession.value = session
                Result.success(session)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * M10.1：mock 明文握手——仅建立 reader/writer，会话密钥为占位。
     * M10.2 替换为真实 ECDH 协商 + 派生 AES-256 会话密钥。
     */
    private fun performHandshake(socket: Socket, isInitiator: Boolean): P2PSession {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val writer = PrintWriter(socket.getOutputStream(), true)
        val mockSessionKey = ByteArray(32) { it.toByte() } // TODO M10.2: 真实 ECDH 协商
        return P2PSession(socket, contactId = "unknown", sessionKey = mockSessionKey, reader = reader, writer = writer)
    }

    /** 断开连接并清理。M10.2 起此处一并销毁会话密钥（v4 验收：断开后密钥清除）。 */
    fun disconnect() {
        _activeSession.value?.socket?.close()
        serverSocket.value?.close()
        _activeSession.value = null
        serverSocket.value = null
    }

    /** 遍历网卡取第一个非回环全局 IPv6 地址；取不到回退 ::1。 */
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
 * 网络消息帧（v4 §9.3）。payload 在 M10.2 起为 AES-GCM 密文的 Base64，M10.1 阶段为明文占位。
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
