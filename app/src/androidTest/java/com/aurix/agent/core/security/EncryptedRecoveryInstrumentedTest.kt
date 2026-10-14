package com.aurix.agent.core.security

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aurix.agent.core.durability.SideEffectPolicy
import com.aurix.agent.core.durability.SideEffectState
import com.aurix.agent.core.mission.AppDatabase
import com.aurix.agent.core.mission.CheckpointEntity
import com.aurix.agent.core.mission.Migrations
import com.aurix.agent.core.mission.MissionEntity
import com.aurix.agent.core.mission.MissionStatus
import com.aurix.agent.core.mission.SideEffectEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs on a device/emulator (the real SQLCipher native library is needed):
 * encryption at rest, plaintext -> encrypted conversion without data loss, crash recovery state surviving a
 * restart of the encrypted database, and the lost-key path.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedRecoveryInstrumentedTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val name = "recovery-test.db"
    private val keyA = DbEncryption.newPassphrase()
    private val keyB = DbEncryption.newPassphrase()
    private val dbFile get() = ctx.getDatabasePath(name)

    @Before fun clean() { wipe() }
    @After fun cleanup() { wipe() }

    private fun wipe() {
        dbFile.parentFile?.listFiles { f -> f.name.startsWith(name) }?.forEach { it.delete() }
    }

    private fun open(pass: String?, outcome: DbEncryption.Outcome = DbEncryption.Outcome.ALREADY_ENCRYPTED): AppDatabase {
        val b = Room.databaseBuilder(ctx, AppDatabase::class.java, name).addMigrations(*Migrations.ALL)
        if (pass != null) DbEncryption.factoryFor(outcome, pass)?.let { b.openHelperFactory(it) }
        return b.build()
    }

    private fun mission(id: String, status: MissionStatus) =
        MissionEntity(id = id, objective = "objective $id", createdAt = 1, updatedAt = 1, status = status, conversationId = id)

    @Test fun encryptedFileIsNotPlaintextAndWrongKeyFails() = runBlocking {
        assertEquals(DbEncryption.Outcome.FRESH, DbEncryption.prepare(ctx, name, keyA))
        val db = open(keyA)
        db.missionDao().insertMission(mission("m1", MissionStatus.COMPLETED))
        db.close()
        assertFalse(DbEncryption.isPlaintext(dbFile))
        try { DbEncryption.inspectOrThrow(dbFile, keyA) } catch (e: Throwable) { throw AssertionError("inspect threw " + e + " cause=" + e.cause, e) }
        assertNull(DbEncryption.inspect(dbFile, keyB))
    }

    @Test fun plaintextDatabaseIsConvertedWithoutLosingData() = runBlocking {
        System.loadLibrary("sqlcipher")
        val plain = open(null)
        plain.missionDao().insertMission(mission("m1", MissionStatus.RUNNING))
        plain.missionDao().upsertCheckpoint(CheckpointEntity("m1", 1, 7, 2, "RUNNING", "h", "{}"))
        val version = plain.openHelper.writableDatabase.version
        plain.close()
        assertTrue(DbEncryption.isPlaintext(dbFile))

        assertEquals(DbEncryption.Outcome.MIGRATED, DbEncryption.prepare(ctx, name, keyA))
        assertFalse(DbEncryption.isPlaintext(dbFile))
        assertFalse(File(dbFile.path + ".enc").exists())
        assertEquals(DbEncryption.Outcome.ALREADY_ENCRYPTED, DbEncryption.prepare(ctx, name, keyA)) // idempotent

        val db = open(keyA)
        assertEquals(version, db.openHelper.writableDatabase.version)
        assertEquals("objective m1", db.missionDao().getMission("m1")?.objective)
        assertEquals(7L, db.missionDao().getCheckpoint("m1")?.seq)
        db.close()
    }

    @Test fun interruptedMissionStateSurvivesRestartOfEncryptedDatabase() = runBlocking {
        DbEncryption.prepare(ctx, name, keyA)
        val first = open(keyA)
        val dao = first.missionDao()
        dao.insertMission(mission("m1", MissionStatus.RUNNING))
        dao.upsertCheckpoint(CheckpointEntity("m1", 1, 3, 2, "RUNNING", "h", "{}"))
        dao.insertSideEffect(SideEffectEntity(missionId = "m1", stepIdx = 0, tool = "SEND_SMS", inputHash = "x", summary = "text mom", state = SideEffectState.INTENDED, startedAt = 5))
        first.close() // process "dies" here: no clean mission shutdown happened

        assertEquals(DbEncryption.Outcome.ALREADY_ENCRYPTED, DbEncryption.prepare(ctx, name, keyA))
        val second = open(keyA)
        val d2 = second.missionDao()
        assertEquals(1, d2.getActiveMissions().size)
        assertEquals(1, d2.markInterrupted("Interrupted", 10))
        assertEquals(MissionStatus.PAUSED, d2.getMission("m1")?.status)
        assertEquals(3L, d2.getCheckpoint("m1")?.seq)
        assertEquals(1, SideEffectPolicy.unresolved(d2.getSideEffects("m1")).size) // never silently repeated
        second.close()
    }

    @Test fun lostKeyMovesDatabaseAsideAndStartsFresh() = runBlocking {
        DbEncryption.prepare(ctx, name, keyA)
        val db = open(keyA)
        db.missionDao().insertMission(mission("m1", MissionStatus.COMPLETED))
        db.close()

        assertEquals(DbEncryption.Outcome.RESET_UNREADABLE, DbEncryption.prepare(ctx, name, keyB))
        assertTrue(dbFile.parentFile!!.listFiles { f -> f.name.startsWith("$name.unreadable-") }!!.isNotEmpty())
        val fresh = open(keyB)
        assertNull(fresh.missionDao().getMission("m1"))
        fresh.missionDao().insertMission(mission("m2", MissionStatus.CREATED))
        assertNotNull(fresh.missionDao().getMission("m2"))
        fresh.close()
    }
}
