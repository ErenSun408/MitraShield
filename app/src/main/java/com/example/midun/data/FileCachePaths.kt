package com.example.midun.data

/**
 * 聊天文件传输的卡内缓存路径（file-transfer 阶段3）。唯一来源——
 * [com.example.midun.network.P2PSessionManager] 收发落卡与 [com.example.midun.data.ChatRepository]
 * 的 7 天 TTL 清理共用，避免前缀漂移导致清理对不上路径。
 *
 * 两类都是根级 `.` 前缀的隐藏文件（文件/文件夹列表不可见），属临时预览缓存、受 TTL + 手动清理管辖；
 * 用户「保存到文件夹」是另存永久副本，不在此列。
 */
object FileCachePaths {
    /** 接收文件暂存（接收方免保存预览 / 待保存源）。 */
    const val RECV_PREFIX = "0:/.recv_"
    /** 发送方预览副本（手机来源发送时另留一份，供发送方预览自己发的图/视频）。 */
    const val SENT_PREFIX = "0:/.sent_"

    fun recv(msgId: String) = "$RECV_PREFIX$msgId"
    fun sent(msgId: String) = "$SENT_PREFIX$msgId"

    /** 该路径是否为受 TTL 管辖的缓存文件（清理时只动这两类，绝不碰隐私文件夹）。 */
    fun isCachePath(path: String) = path.startsWith(RECV_PREFIX) || path.startsWith(SENT_PREFIX)
}
