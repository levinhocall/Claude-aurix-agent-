package com.aurix.agent.core.durability

import com.aurix.agent.core.mission.CheckpointEntity
import com.aurix.agent.core.mission.EventEntity
import com.aurix.agent.core.mission.MissionDao
import com.aurix.agent.core.mission.MissionEntity
import com.aurix.agent.core.mission.SideEffectEntity
import com.aurix.agent.core.mission.StepEntity
import com.aurix.agent.core.mission.StepStatus
import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.ToolErrorType
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

object Hashing {
    fun sha256Hex(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    fun short(s: String): String = sha256Hex(s).take(16)
}

// ------------------------------------------------------------------ side effects
object SideEffectState {
    const val INTENDED = "INTENDED"                 // written before the tool ran; if we only see this after a crash, the outcome is unknown
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"                     // the tool reported a failure that proves nothing happened
    const val UNCERTAIN = "UNCERTAIN"               // timeout / network / unknown: it may have happened
    const val RECONCILED_DONE = "RECONCILED_DONE"   // the user confirmed it happened
    const val RECONCILED_NOT_DONE = "RECONCILED_NOT_DONE"
    const val SKIPPED = "SKIPPED"
}

object SideEffectPolicy {
    private val exact = setOf(
        "SEND_SMS", "CALL_PHONE", "WHATSAPP_MESSAGE", "EMAIL_COMPOSE", "EMERGENCY_SOS", "SET_ALARM", "SET_TIMER",
        "PLACE_SAVE", "FILE_EDIT", "FILE_WRITE", "CLEAR_NOTIFICATIONS", "NOTIFICATION_CLEAR", "MEMORY_SAVE",
    )
    private val readOnlyStorage = setOf("STORAGE_LIST", "STORAGE_SEARCH", "STORAGE_READ", "STORAGE_INFO")
    private val readOnlyScreen = setOf("SCREEN_READ", "SCREEN_WAIT", "SCREEN_LOOK")

    /** Repeating these twice has a visible effect (double SMS, second alarm, repeated tap/submit, second file write). Unknown risky tools count too. */
    fun isNonIdempotent(tool: String, risk: RiskLevel): Boolean = when {
        tool in exact -> true
        tool.startsWith("SEND_") -> true
        tool.startsWith("SCREEN_") -> tool !in readOnlyScreen
        tool.startsWith("STORAGE_") -> tool !in readOnlyStorage
        tool.startsWith("PROJECT_") -> false // snapshotted, read-back verified and repeatable by design
        else -> risk.ordinal >= RiskLevel.HIGH.ordinal
    }

    /** Outcome state from what the tool reported. Timeouts and network errors prove nothing: the effect may have happened. */
    fun stateAfter(ok: Boolean, error: ToolErrorType?): String = when {
        ok -> SideEffectState.COMPLETED
        error == ToolErrorType.TIMEOUT || error == ToolErrorType.NETWORK_ERROR || error == ToolErrorType.UNKNOWN_ERROR || error == ToolErrorType.RATE_LIMIT -> SideEffectState.UNCERTAIN
        else -> SideEffectState.FAILED
    }

    private val blocking = setOf(SideEffectState.INTENDED, SideEffectState.UNCERTAIN, SideEffectState.COMPLETED, SideEffectState.RECONCILED_DONE)

    /** An earlier identical call that did, or may have, happened. Repeating it blindly is refused. */
    fun duplicateOf(entries: List<SideEffectEntity>, tool: String, inputHash: String): SideEffectEntity? =
        entries.lastOrNull { it.tool == tool && it.inputHash == inputHash && it.state in blocking }

    fun unresolved(entries: List<SideEffectEntity>): List<SideEffectEntity> = entries.filter { it.state == SideEffectState.INTENDED || it.state == SideEffectState.UNCERTAIN }
}

@Singleton
class SideEffectJournal @Inject constructor(private val dao: MissionDao) {
    suspend fun begin(missionId: String, stepIdx: Int, tool: String, input: JSONObject, summary: String): Long =
        dao.insertSideEffect(
            SideEffectEntity(
                missionId = missionId, stepIdx = stepIdx, tool = tool, inputHash = Hashing.short(input.toString()),
                summary = summary.take(160), state = SideEffectState.INTENDED, startedAt = System.currentTimeMillis(),
            )
        )

    suspend fun finish(id: Long, ok: Boolean, error: ToolErrorType?) {
        val e = dao.getSideEffect(id) ?: return
        dao.updateSideEffect(e.copy(state = SideEffectPolicy.stateAfter(ok, error), finishedAt = System.currentTimeMillis(), note = error?.name))
    }

    suspend fun duplicate(missionId: String, tool: String, input: JSONObject): SideEffectEntity? =
        SideEffectPolicy.duplicateOf(dao.getSideEffects(missionId), tool, Hashing.short(input.toString()))

    suspend fun unresolved(missionId: String): List<SideEffectEntity> = SideEffectPolicy.unresolved(dao.getSideEffects(missionId))

    suspend fun resolve(e: SideEffectEntity, state: String, note: String) =
        dao.updateSideEffect(e.copy(state = state, finishedAt = System.currentTimeMillis(), note = note.take(100)))

    suspend fun performed(missionId: String): List<SideEffectEntity> =
        dao.getSideEffects(missionId).filter { it.state == SideEffectState.COMPLETED || it.state == SideEffectState.RECONCILED_DONE || it.state == SideEffectState.UNCERTAIN || it.state == SideEffectState.INTENDED }.takeLast(12)

    suspend fun doneFor(missionId: String, stepIdx: Int): List<SideEffectEntity> =
        dao.getSideEffects(missionId).filter { it.stepIdx == stepIdx && (it.state == SideEffectState.COMPLETED || it.state == SideEffectState.RECONCILED_DONE) }
}

object ReconcileAnswers {
    const val DONE = "It happened, don't repeat"
    const val NOT_DONE = "It did NOT happen, retry"
    const val SKIP = "Skip it"
    val OPTIONS = listOf(DONE, NOT_DONE, SKIP)

    /** Unknown or free-text answers fail safe: the action is NOT repeated. */
    fun stateFor(answer: String): String {
        val a = answer.trim().lowercase()
        return when {
            a.contains("not happen") || a.contains("did not") || a.contains("didn't") || a.endsWith("retry") -> SideEffectState.RECONCILED_NOT_DONE
            a.contains("happened") || a.contains("don't repeat") || a.contains("done") -> SideEffectState.RECONCILED_DONE
            else -> SideEffectState.SKIPPED
        }
    }
}

// ------------------------------------------------------------------ checkpoints
data class StepSnap(val idx: Int, val title: String, val status: String, val attempts: Int)

data class CheckpointBody(
    val version: Int, val seq: Long, val savedAt: Long, val status: String,
    val replans: Int, val verifyRounds: Int, val recoveries: Int, val iterations: Int, val tokensUsed: Int,
    val steps: List<StepSnap>, val resumeNote: String,
)

object CheckpointCodec {
    const val VERSION = 1

    fun planHash(steps: List<StepSnap>): String = Hashing.short(steps.joinToString("\n") { "${it.idx}|${it.title}" })

    fun toJson(b: CheckpointBody): String = JSONObject()
        .put("v", b.version).put("seq", b.seq).put("at", b.savedAt).put("status", b.status)
        .put("replans", b.replans).put("verifyRounds", b.verifyRounds).put("recoveries", b.recoveries)
        .put("iterations", b.iterations).put("tokens", b.tokensUsed).put("note", b.resumeNote)
        .put("steps", JSONArray(b.steps.map { JSONObject().put("i", it.idx).put("t", it.title.take(200)).put("s", it.status).put("a", it.attempts) }))
        .toString()

    fun fromJson(s: String): CheckpointBody? = try {
        val j = JSONObject(s)
        val a = j.getJSONArray("steps")
        CheckpointBody(
            j.getInt("v"), j.getLong("seq"), j.getLong("at"), j.getString("status"),
            j.getInt("replans"), j.getInt("verifyRounds"), j.getInt("recoveries"), j.optInt("iterations"), j.optInt("tokens"),
            (0 until a.length()).map { val o = a.getJSONObject(it); StepSnap(o.getInt("i"), o.getString("t"), o.getString("s"), o.getInt("a")) },
            j.optString("note"),
        )
    } catch (e: Exception) { null }

    fun snapshot(steps: List<StepEntity>) = steps.map { StepSnap(it.idx, it.title, it.status.name, it.attempts) }
}

enum class ResumeAction { RESUME, REBUILD, MANUAL, REJECT }

/** REBUILD = the checkpoint cannot be trusted, so the Room step table is the only truth and counters restart at zero. */
data class ResumeDecision(val action: ResumeAction, val replans: Int = 0, val verifyRounds: Int = 0, val issues: List<String> = emptyList())

object CheckpointValidator {
    const val MAX_AUTO_RESUME_AGE_MS = 24L * 3600 * 1000
    private const val MAX_CLOCK_SKEW_MS = 5L * 60 * 1000

    fun validate(cp: CheckpointEntity?, steps: List<StepEntity>, mission: MissionEntity, autoResume: Boolean, now: Long): ResumeDecision {
        if (mission.status.isTerminal() && autoResume) return ResumeDecision(ResumeAction.REJECT, issues = listOf("mission already ${mission.status}"))
        if (cp == null) return ResumeDecision(ResumeAction.REBUILD, issues = listOf("no checkpoint"))
        if (cp.missionId != mission.id) return ResumeDecision(ResumeAction.REJECT, issues = listOf("checkpoint belongs to another mission"))
        val body = CheckpointCodec.fromJson(cp.body) ?: return ResumeDecision(ResumeAction.REBUILD, issues = listOf("checkpoint unreadable"))
        if (body.version != CheckpointCodec.VERSION) return ResumeDecision(ResumeAction.REBUILD, issues = listOf("checkpoint version ${body.version} unsupported"))
        if (CheckpointCodec.planHash(body.steps) != cp.planHash) return ResumeDecision(ResumeAction.REBUILD, issues = listOf("checkpoint plan hash mismatch (corrupted)"))
        if (cp.savedAt > now + MAX_CLOCK_SKEW_MS) return ResumeDecision(ResumeAction.REBUILD, issues = listOf("checkpoint timestamp is in the future"))
        val byIdx = steps.associateBy { it.idx }
        val broken = body.steps.filter { it.status == StepStatus.DONE.name && byIdx[it.idx]?.status != StepStatus.DONE }
        if (broken.isNotEmpty()) return ResumeDecision(ResumeAction.REBUILD, issues = listOf("step ${broken.first().idx + 1} was done at checkpoint but is ${byIdx[broken.first().idx]?.status ?: "missing"} now"))
        if (autoResume && now - cp.savedAt > MAX_AUTO_RESUME_AGE_MS)
            return ResumeDecision(ResumeAction.MANUAL, body.replans, body.verifyRounds, listOf("checkpoint is older than 24h"))
        return ResumeDecision(ResumeAction.RESUME, body.replans, body.verifyRounds)
    }
}

@Singleton
class CheckpointStore @Inject constructor(private val dao: MissionDao) {
    suspend fun save(m: MissionEntity, steps: List<StepEntity>, replans: Int, verifyRounds: Int, note: String = "") {
        val snap = CheckpointCodec.snapshot(steps)
        val seq = (dao.getCheckpoint(m.id)?.seq ?: 0L) + 1
        val now = System.currentTimeMillis()
        val body = CheckpointBody(CheckpointCodec.VERSION, seq, now, m.status.name, replans, verifyRounds, m.recoveries, m.iterations, m.tokensUsed, snap, note.take(160))
        dao.upsertCheckpoint(CheckpointEntity(m.id, CheckpointCodec.VERSION, seq, now, m.status.name, CheckpointCodec.planHash(snap), CheckpointCodec.toJson(body)))
    }

    suspend fun load(id: String): CheckpointEntity? = dao.getCheckpoint(id)
}

// ------------------------------------------------------------------ approval freshness
object ApprovalFreshness {
    const val HIGH_TTL_MS = 15L * 60 * 1000
    const val CRITICAL_TTL_MS = 2L * 60 * 1000

    fun ttl(risk: RiskLevel) = if (risk == RiskLevel.CRITICAL) CRITICAL_TTL_MS else HIGH_TTL_MS

    /**
     * Picks the approval key for a call. A grant older than its TTL is never reused (after a crash, a day later, or a changed
     * situation): the call gets a new round key "<key>~<n>" and is asked again. A denial is kept for the whole mission.
     * Returns (effective key, decision if one is already valid: true = approved, false = denied).
     */
    fun resolve(events: List<EventEntity>, baseKey: String, risk: RiskLevel, now: Long): Pair<String, Boolean?> {
        var round = 0
        while (round < 50) {
            val key = if (round == 0) baseKey else "$baseKey~$round"
            val d = events.lastOrNull { (it.type == "APPROVAL_GRANTED" || it.type == "APPROVAL_DENIED") && it.detail.substringBefore('|') == key } ?: return key to null
            if (d.type == "APPROVAL_DENIED") return key to false
            if (now - d.ts <= ttl(risk)) return key to true
            round++
        }
        return "$baseKey~$round" to null
    }
}
