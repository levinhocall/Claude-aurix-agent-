package com.aurix.agent.core.security

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.SecureRandom

/**
 * SQLCipher encryption for the Room database.
 *  - The passphrase is random (256 bit), created once and kept in [SecureSettings] (Keystore-backed), never in code or logs.
 *  - An existing plaintext database (every install before this change) is converted once with sqlcipher_export into a
 *    temp file, verified (opens, same user_version and table count), then swapped in. A crash at any point leaves either the
 *    untouched original or a complete encrypted copy; the next start finishes or redoes the job.
 *  - If the database cannot be opened with the key (key lost, e.g. Keystore reset) it is moved aside (never deleted) and a
 *    fresh database is created, so the app never crash-loops.
 *  - If conversion itself fails the app keeps working on the plaintext file and retries on the next start.
 */
object DbEncryption {
    private const val TAG = "DbEncryption"
    private const val K_DB_KEY = "db_key_v1"
    private val PLAIN_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    enum class Outcome { FRESH, ALREADY_ENCRYPTED, MIGRATED, RESET_UNREADABLE, PLAINTEXT_FALLBACK }

    fun passphrase(secure: SecureSettings): String {
        secure.getString(K_DB_KEY)?.takeIf { it.length == 64 }?.let { return it }
        val fresh = newPassphrase()
        check(secure.putStringDurable(K_DB_KEY, fresh)) { "could not persist the database key" }
        return fresh
    }

    internal fun newPassphrase(random: SecureRandom = SecureRandom()): String =
        ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** A plaintext SQLite file starts with "SQLite format 3\0"; an encrypted one looks like random bytes. */
    internal fun isPlaintext(file: File): Boolean {
        if (!file.isFile || file.length() < PLAIN_HEADER.size) return false
        val head = ByteArray(PLAIN_HEADER.size)
        file.inputStream().use { if (it.read(head) != head.size) return false }
        return head.contentEquals(PLAIN_HEADER)
    }

    /** Call before building Room. Idempotent. */
    fun prepare(context: Context, dbName: String, pass: String): Outcome {
        System.loadLibrary("sqlcipher")
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".enc")
        // an interrupted swap: the original is gone but a complete encrypted copy exists
        if (!file.exists() && tmp.exists() && inspect(tmp, pass) != null) tmp.renameTo(file)
        if (!file.exists() || file.length() == 0L) { file.delete(); tmp.delete(); return Outcome.FRESH }
        if (isPlaintext(file)) {
            return try { encryptInPlace(file, tmp, pass); Outcome.MIGRATED } catch (e: Exception) {
                Log.e(TAG, "conversion failed, keeping the plaintext database for now: ${e.javaClass.simpleName}")
                tmp.delete(); Outcome.PLAINTEXT_FALLBACK
            }
        }
        tmp.delete()
        if (inspect(file, pass) != null) return Outcome.ALREADY_ENCRYPTED
        val aside = File(file.path + ".unreadable-" + System.currentTimeMillis())
        Log.e(TAG, "database cannot be opened with the stored key; moved aside as ${aside.name}")
        file.renameTo(aside)
        listOf("-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        return Outcome.RESET_UNREADABLE
    }

    /** Null = keep Room's default (plaintext) helper; only after a failed conversion. */
    fun factoryFor(outcome: Outcome, pass: String): SupportSQLiteOpenHelper.Factory? =
        if (outcome == Outcome.PLAINTEXT_FALLBACK) null else SupportOpenHelperFactory(pass.toByteArray(Charsets.UTF_8))

    /** (user_version, table count) if the file opens with [pass], else null. */
    internal fun inspect(file: File, pass: String): Pair<Int, Int>? = try {
        val db = SQLiteDatabase.openOrCreateDatabase(file, pass, null, null)
        try {
            val tables = db.rawQuery("SELECT count(*) FROM sqlite_master WHERE type = 'table'", arrayOf<Any>()).use { c -> c.moveToFirst(); c.getInt(0) }
            db.version to tables
        } finally { db.close() }
    } catch (e: Exception) { null }

    private fun encryptInPlace(file: File, tmp: File, pass: String) {
        tmp.delete()
        val before = inspect(file, "") ?: error("plaintext database does not open")
        val db = SQLiteDatabase.openOrCreateDatabase(file, "", null, null)
        try {
            db.rawExecSQL("ATTACH DATABASE '${tmp.path.replace("'", "''")}' AS encrypted KEY '$pass';")
            db.rawExecSQL("SELECT sqlcipher_export('encrypted');")
            db.rawExecSQL("PRAGMA encrypted.user_version = ${before.first};")
            db.rawExecSQL("DETACH DATABASE encrypted;")
        } finally { db.close() }
        val after = inspect(tmp, pass)
        check(after == before) { "encrypted copy does not match the original ($after vs $before)" }
        listOf("-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        check(tmp.renameTo(file)) { "could not swap in the encrypted database" }
    }
}
