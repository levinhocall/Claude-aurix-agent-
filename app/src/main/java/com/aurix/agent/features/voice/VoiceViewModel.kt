package com.aurix.agent.features.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurix.agent.core.agent.MissionManager
import com.aurix.agent.core.approval.ApprovalManager
import com.aurix.agent.core.approval.QuestionManager
import com.aurix.agent.core.mission.MissionDao
import com.aurix.agent.core.mission.MissionEntity
import com.aurix.agent.core.mission.MissionStatus
import com.aurix.agent.core.voice.VoiceEngine
import com.aurix.agent.core.voice.VoiceException
import com.aurix.agent.core.voice.VoicePhase
import com.aurix.agent.core.voice.isStopPhrase
import com.aurix.agent.core.voice.parseYesNo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

/** Hands-free loop: listen -> run a mission -> read approvals/questions aloud and take yes/no by voice -> speak the result -> listen again. */
@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val engine: VoiceEngine,
    private val manager: MissionManager,
    private val dao: MissionDao,
    private val approvals: ApprovalManager,
    private val questions: QuestionManager,
) : ViewModel() {
    val phase: StateFlow<VoicePhase> = engine.phase
    val level: StateFlow<Float> = engine.level
    val partial: StateFlow<String> = engine.partial
    private val _caption = MutableStateFlow("Tap the orb and speak")
    val caption: StateFlow<String> = _caption.asStateFlow()
    private val _missionId = MutableStateFlow<String?>(null)
    val missionId: StateFlow<String?> = _missionId.asStateFlow()
    private var loop: Job? = null
    private var lastSummary: String? = null

    fun setCaption(s: String) { _caption.value = s }

    fun start() {
        if (loop?.isActive == true) return
        engine.uiActive = true
        engine.interruptWake()
        loop = viewModelScope.launch {
            try {
                delay(350)
                converse()
            } catch (e: CancellationException) {
                throw e
            } catch (e: VoiceException) {
                _caption.value = e.message ?: "Voice error"
            } catch (e: Exception) {
                _caption.value = "Voice error: ${e.message?.take(80)}"
            } finally {
                engine.setPhase(VoicePhase.IDLE)
                engine.uiActive = false
            }
        }
    }

    fun stop() { loop?.cancel(); engine.stopSpeaking(); engine.uiActive = false }

    fun onOrbTap() {
        when {
            loop?.isActive != true -> start()
            engine.phase.value == VoicePhase.SPEAKING -> engine.stopSpeaking()
        }
    }

    override fun onCleared() { engine.uiActive = false }

    private suspend fun converse() {
        if (!engine.available()) throw VoiceException("Speech recognition is not available on this phone")
        var misses = 0
        while (true) {
            engine.setPhase(VoicePhase.LISTENING); _caption.value = "Listening…"
            val heard = engine.listen(9_000)
            if (heard.isNullOrBlank()) {
                if (++misses >= 3) { _caption.value = "I didn't catch anything. Tap the orb to try again."; return }
                continue
            }
            misses = 0
            _caption.value = heard
            if (isStopPhrase(heard)) { engine.speak("Okay."); return }
            engine.setPhase(VoicePhase.THINKING); _caption.value = "Working on it…"
            val id = manager.create(heard, lastSummary)
            _missionId.value = id
            val reply = awaitMission(id)
            lastSummary = "Previous request: ${heard.take(100)}. Result: ${reply.take(300)}"
            _caption.value = reply.take(160)
            engine.speak(reply)
        }
    }

    private suspend fun awaitMission(id: String): String {
        while (true) {
            val m: MissionEntity? = dao.observeMission(id).first { it == null || it.status.isTerminal() || it.status == MissionStatus.PAUSED || it.status == MissionStatus.WAITING_FOR_APPROVAL }
            if (m == null) return "Sorry, that task disappeared."
            when (m.status) {
                MissionStatus.COMPLETED -> return m.finalResult?.takeIf { it.isNotBlank() } ?: "Done."
                MissionStatus.FAILED -> return "Sorry, that did not work. " + (m.error ?: "").take(120)
                MissionStatus.CANCELLED -> return "Okay, cancelled."
                MissionStatus.PAUSED -> return "The task is paused. Open it on screen to continue."
                else -> if (!handlePending(id)) return "I need your answer on screen. Open the task to continue."
            }
        }
    }

    /** Reads the pending approval/question aloud and resolves it by voice. Returns false if it could not be resolved. */
    private suspend fun handlePending(id: String): Boolean {
        val approvalEvents = dao.getApprovalEvents(id)
        val decided = approvalEvents.filter { it.type != "APPROVAL_REQUESTED" }.map { it.detail.substringBefore('|') }.toSet()
        val pendingApproval = approvalEvents.lastOrNull { e ->
            e.type == "APPROVAL_REQUESTED" && (try { JSONObject(e.detail).optString("key") !in decided } catch (x: Exception) { false })
        }
        if (pendingApproval != null) {
            val j = JSONObject(pendingApproval.detail)
            repeat(2) {
                engine.speak("Do you allow this? " + j.optString("summary") + ". Say yes or no.")
                engine.setPhase(VoicePhase.LISTENING); _caption.value = "Say yes or no"
                val ans = engine.listen(7_000)
                val yn = ans?.let { parseYesNo(it) }
                if (yn != null) { approvals.resolve(id, j.optString("key"), yn); engine.setPhase(VoicePhase.THINKING); return true }
            }
            return false
        }
        val qEvents = dao.getQuestionEvents(id)
        val answered = qEvents.filter { it.type == "QUESTION_ANSWERED" }.map { it.detail.substringBefore('|') }.toSet()
        val pendingQ = qEvents.lastOrNull { e ->
            e.type == "QUESTION_ASKED" && (try { JSONObject(e.detail).optString("key") !in answered } catch (x: Exception) { false })
        } ?: return false
        val q = JSONObject(pendingQ.detail)
        val opts = q.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        repeat(2) {
            engine.speak(q.optString("question") + if (opts.isNotEmpty()) " Options: " + opts.joinToString(", ") else "")
            engine.setPhase(VoicePhase.LISTENING); _caption.value = "Listening for your answer…"
            val ans = engine.listen(8_000)
            if (!ans.isNullOrBlank()) { questions.answer(id, q.optString("key"), ans); engine.setPhase(VoicePhase.THINKING); return true }
        }
        return false
    }
}
