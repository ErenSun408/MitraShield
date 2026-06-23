package com.example.midun.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M12.1 密钥库自测（纯 JVM）。覆盖：init→落卡 blob→重载得同一 DEK、旧卡/损坏 blob 拒载、锁定清 DEK、
 * 密钥更新（重包后 blob 变但 DEK 不变）。
 */
class CardKeystoreTest {

    @Test
    fun createNew_thenLoadBlob_recoversSameDek() {
        val ks = CardKeystore()
        val blob = ks.createNew()
        val dekAfterInit = ks.dek()!!.copyOf()
        ks.lock()
        assertNull("锁定后 DEK 应清空", ks.dek())

        // 模拟「拔卡重登」：用新实例从落卡 blob 重载。
        val reloaded = CardKeystore()
        assertTrue("应能从 blob 解出 DEK", reloaded.load(blob))
        assertArrayEquals("重载 DEK 应与 init 时一致", dekAfterInit, reloaded.dek())
    }

    @Test
    fun load_nullBlob_returnsFalse_legacyCard() {
        val ks = CardKeystore()
        assertFalse("旧卡无 keystore → load(null) 应失败", ks.load(null))
        assertFalse("失败后应保持锁定", ks.isUnlocked)
    }

    @Test
    fun load_corruptBlob_returnsFalse() {
        val ks = CardKeystore()
        val blob = ks.createNew().also { it[10] = (it[10] + 1).toByte() } // 篡改 KEK 区
        CardKeystore().let { fresh ->
            assertFalse("篡改后的 blob 应解包失败", fresh.load(blob))
        }
        // 长度不符也应拒。
        assertFalse(CardKeystore().load(ByteArray(10)))
        // magic 不符也应拒。
        assertFalse(CardKeystore().load(ByteArray(97)))
    }

    @Test
    fun lock_clearsDek() {
        val ks = CardKeystore()
        ks.createNew()
        assertTrue(ks.isUnlocked)
        ks.lock()
        assertFalse(ks.isUnlocked)
        assertNull(ks.dek())
    }

    @Test
    fun rewrap_keepsDek_butChangesBlob() {
        val ks = CardKeystore()
        val blob1 = ks.createNew()
        val dek = ks.dek()!!.copyOf()

        val blob2 = ks.rewrap()
        assertNotNull("已解锁应能重包", blob2)
        assertNotEquals("重包后 blob 应不同（新 KEK）", blob1.toList(), blob2!!.toList())
        assertArrayEquals("DEK 不应改变", dek, ks.dek())

        // 新 blob 仍能解出同一 DEK。
        CardKeystore().let { fresh ->
            assertTrue(fresh.load(blob2))
            assertArrayEquals("重包后的 blob 解出的 DEK 不变", dek, fresh.dek())
        }
    }

    @Test
    fun rewrap_whenLocked_returnsNull() {
        assertNull("未解锁无 DEK 可重包", CardKeystore().rewrap())
    }
}
