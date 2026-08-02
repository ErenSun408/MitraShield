package com.example.midun.network

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/**
 * P2P 文件传输的二进制通道（M11.5.3 / `[file-transfer]`）。
 *
 * **为什么单开一条 socket**：聊天走的是「一帧一行文本」协议（`PrintWriter.println` / `BufferedReader.readLine`，
 * payload 是 Base64）。`BufferedReader` 会预读缓冲，在同一条流上裸切二进制必丢字节；且 Base64 套文本帧有
 * +33% 膨胀。故文件另起一条 TCP（端口 [FILE_PORT]），用**长度前缀二进制分帧**，与聊天流物理隔离、各跑各的
 * 接收循环。复用握手协商出的同一 `sessionKey`（加解密在 [P2PSessionManager]，本类只管纯传输 framing）。
 *
 * **全双工复用**：A（出码方）监听 [FILE_PORT] 并 accept、B（扫码方）connect；TCP 是全双工，所以**一条已建立的
 * 通道双向都能发文件**——无需各发一条。
 *
 * **帧格式**：`writeInt(type) ‖ writeInt(len) ‖ payload[len]`。type 见 [FILE_BEGIN]/[FILE_CHUNK]/[FILE_END]/
 * [FILE_CANCEL]；len 上限 [MAX_FRAME_BYTES]（防恶意长度撑爆内存）。
 */
class FileTransferChannel(private val socket: Socket) {

    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = DataOutputStream(socket.getOutputStream().buffered())

    /** 写一帧（线程安全：发送端可能与控制帧并发）。payload 已是最终字节（密文/JSON 字节）。 */
    fun sendFrame(type: Int, payload: ByteArray) {
        require(payload.size <= MAX_FRAME_BYTES) { "帧过大：${payload.size}" }
        synchronized(output) {
            output.writeInt(type)
            output.writeInt(payload.size)
            output.write(payload)
            output.flush()
        }
    }

    /**
     * 常驻接收循环：逐帧读出并交给 [onFrame]。对端关闭 socket 时 `readInt` 抛 `EOFException`/`IOException`，
     * 循环结束、异常向上抛给启动协程（由其做拆除/状态归位）。
     */
    suspend fun receiveLoop(onFrame: suspend (type: Int, payload: ByteArray) -> Unit) {
        while (true) {
            val type = input.readInt()
            val len = input.readInt()
            require(len in 0..MAX_FRAME_BYTES) { "非法帧长度：$len" }
            val payload = ByteArray(len)
            input.readFully(payload)
            onFrame(type, payload)
        }
    }

    fun close() {
        runCatching { socket.close() }
    }

    companion object {
        /** 文件通道端口 = 聊天端口(8888) + 1。 */
        const val FILE_PORT = 8889

        // 帧类型（控制语义在 5.3.3/5.3.4 填充）。
        /** 文件起始：payload = AES-GCM 加密的元数据 JSON（msgId/fileName/fileSize/mime/fileNonce/totalChunks）。 */
        const val FILE_BEGIN = 1
        /** 文件块：payload = 4B 大端 chunkIndex ‖ 该块密文（[P2PCrypto.encryptChunk] 输出）。 */
        const val FILE_CHUNK = 2
        /** 文件结束：payload = AES-GCM 加密的 JSON（msgId/sha256），收端校验通过才收尾。 */
        const val FILE_END = 3
        /** 取消传输：payload = AES-GCM 加密的 msgId，收端删半成品。 */
        const val FILE_CANCEL = 4

        /**
         * 收妥回执（`[network]` 2026-08-02）：payload = AES-GCM 加密的 JSON`{msgId, ok}`，收端在
         * [FILE_END] 校验完 sha256 后发出，发端收到才把气泡标「已送达」。
         *
         * 没有它，「发送成功」只等于「写进了本机内核缓冲区」——对端进程死了、连接烂在对端 backlog 里没人
         * 读，write 一样立刻返回成功。现场即：B 报「发送文件结束：成功，耗时 0s」，A 侧一条接收记录都没有。
         *
         * 老版本对端不认识本类型，落到 `handleFileFrame` 的 `else -> {}` 被忽略（发它无害）；发端只在
         * [P2PSessionManager.PROTO_V2] 对端上**等待**它，故不影响与老版本互通。
         */
        const val FILE_ACK = 5

        /**
         * 单帧字节上限：文件块密文 = 64KB 明文 + 16B GCM tag ≈ 65552；元数据帧很小。留足余量取 1MB，
         * 既容下最大块又能挡住恶意超大 len 撑爆内存。
         */
        const val MAX_FRAME_BYTES = 1024 * 1024
    }
}
