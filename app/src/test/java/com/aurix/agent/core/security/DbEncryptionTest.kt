package com.aurix.agent.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

class DbEncryptionTest {
    @Test fun passphraseIsRandom256BitHex() {
        val a = DbEncryption.newPassphrase(); val b = DbEncryption.newPassphrase()
        assertEquals(64, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertNotEquals(a, b)
    }

    @Test fun detectsPlaintextSqliteByHeader() {
        val dir = java.nio.file.Files.createTempDirectory("dbenc").toFile()
        try {
            val plain = File(dir, "plain.db")
            DriverManager.getConnection("jdbc:sqlite:${plain.path}").use { it.createStatement().use { st -> st.execute("CREATE TABLE t (a INTEGER)") } }
            assertTrue(DbEncryption.isPlaintext(plain))
            val noise = File(dir, "enc.db").apply { writeBytes(ByteArray(4096) { (it * 31 + 7).toByte() }) }
            assertFalse(DbEncryption.isPlaintext(noise))
            assertFalse(DbEncryption.isPlaintext(File(dir, "missing.db")))
            assertFalse(DbEncryption.isPlaintext(File(dir, "tiny.db").apply { writeBytes(ByteArray(5)) }))
        } finally { dir.deleteRecursively() }
    }
}
