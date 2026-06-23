package com.example.midun.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * M12.3 落卡容器往返自测（纯 JVM，模拟卡内字节）：在内存里按 RealFileSystem 写路径拼出
 * `header ‖ encChunk*` 的字节，再分别模拟①顺序读（readFile/openCardStream）②随机读（CardFileDataSource
 * 的明文偏移→密文块映射）。校验解密重组 == 原文、任意 seek 取到正确明文——把最易错的偏移算术 JVM 化验证，
 * 不依赖真卡。
 */
class FileContainerRoundTripTest {

    private val chunk = FileCrypto.CHUNK_PLAIN_BYTES
    private val tag = FileCrypto.GCM_TAG_BYTES

    /** 按写路径拼出卡内字节布局。 */
    private fun buildCardBytes(dek: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(FileHeader.build(plain.size.toLong(), nonce))
        val total = ((plain.size + chunk - 1) / chunk).coerceAtLeast(1)
        for (i in 0 until total) {
            val start = i * chunk
            val len = minOf(chunk, plain.size - start).coerceAtLeast(0)
            out.write(FileCrypto.encryptChunk(dek, nonce, i, i == total - 1, plain.copyOfRange(start, start + len)))
        }
        return out.toByteArray()
    }

    @Test
    fun sequentialReadReassemblesPlaintext() {
        val dek = FileCrypto.randomKey()
        val nonce = FileCrypto.newFileNonce()
        val plain = Random(7).nextBytes(2 * chunk + 1234) // 3 块，末块不足整块
        val card = buildCardBytes(dek, nonce, plain)

        val parsed = FileHeader.parse(card.copyOfRange(0, FileHeader.BYTES))!!
        assertEquals(plain.size.toLong(), parsed.plaintextSize)

        // 从头长处开始逐块解密。
        val total = ((parsed.plaintextSize + chunk - 1) / chunk).toInt().coerceAtLeast(1)
        val out = ByteArrayOutputStream()
        var pos = FileHeader.BYTES
        for (i in 0 until total) {
            val plainLen = minOf(chunk.toLong(), parsed.plaintextSize - i.toLong() * chunk).toInt()
            val cipher = card.copyOfRange(pos, pos + plainLen + tag)
            pos += plainLen + tag
            out.write(FileCrypto.decryptChunk(dek, parsed.fileNonce, i, i == total - 1, cipher))
        }
        assertArrayEquals(plain, out.toByteArray())
        assertEquals("密文文件大小应与布局一致", card.size, pos)
    }

    @Test
    fun randomAccessMapsPlaintextOffsetToChunk() {
        val dek = FileCrypto.randomKey()
        val nonce = FileCrypto.newFileNonce()
        val plain = Random(11).nextBytes(3 * chunk + 777)
        val card = buildCardBytes(dek, nonce, plain)
        val parsed = FileHeader.parse(card.copyOfRange(0, FileHeader.BYTES))!!
        val total = ((parsed.plaintextSize + chunk - 1) / chunk).toInt().coerceAtLeast(1)

        // 落在第 2 块（index=2）内部的一个明文偏移，复刻 CardFileDataSource 的映射。
        val offset = 2L * chunk + 4096
        val index = (offset / chunk).toInt()
        val within = (offset % chunk).toInt()
        val cipherOffset = FileHeader.BYTES + index * (chunk + tag)
        val plainLen = minOf(chunk.toLong(), parsed.plaintextSize - index.toLong() * chunk).toInt()
        val cipher = card.copyOfRange(cipherOffset, cipherOffset + plainLen + tag)
        val decrypted = FileCrypto.decryptChunk(dek, parsed.fileNonce, index, index == total - 1, cipher)

        // 从 within 起的一段应与原文对应区间一致。
        val sliceLen = 5000
        assertArrayEquals(
            plain.copyOfRange(offset.toInt(), offset.toInt() + sliceLen),
            decrypted.copyOfRange(within, within + sliceLen)
        )
    }
}
