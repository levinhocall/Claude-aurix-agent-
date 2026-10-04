package com.aurix.agent.features.missions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurix.agent.core.agent.MissionManager
import com.aurix.agent.core.approval.ApprovalManager
import com.aurix.agent.core.approval.QuestionManager
import com.aurix.agent.core.ai.routing.ProviderStore
import com.aurix.agent.core.mission.MissionDao
import com.aurix.agent.core.mission.MissionEntity
import com.aurix.agent.core.tools.Workspace
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    dao: MissionDao,
    private val manager: MissionManager,
    private val store: ProviderStore,
) : ViewModel() {
    val missions: StateFlow<List<MissionEntity>> =
        dao.observeMissions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _hasKey = MutableStateFlow(true)
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    fun refreshKey() { viewModelScope.launch(Dispatchers.IO) { _hasKey.value = store.providers().any { it.keys.isNotEmpty() } } }

    fun submit(objective: String, onCreated: (String) -> Unit) {
        viewModelScope.launch { onCreated(manager.create(objective.trim())) }
    }
}

@HiltViewModel
class TranscriptViewModel @Inject constructor(
    savedState: SavedStateHandle,
    dao: MissionDao,
    private val manager: MissionManager,
    private val workspace: Workspace,
    private val approvals: ApprovalManager,
    private val questions: QuestionManager,
) : ViewModel() {
    private val id: String = checkNotNull(savedState["id"])

    val state: StateFlow<DetailState> = combine(
        dao.observeMission(id), dao.observeSteps(id), dao.observeEventsAsc(id), dao.observeToolCallsAsc(id), dao.observeFiles(id),
    ) { m, steps, events, calls, files -> buildDetailState(m, steps, events, calls, files) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState())

    fun fileFor(path: String): File = workspace.resolve(id, path)
    fun answer(key: String, text: String) { viewModelScope.launch { questions.answer(id, key, text) } }
    fun decide(key: String, allow: Boolean) { viewModelScope.launch { approvals.resolve(id, key, allow) } }
    fun pause() = manager.pause(id)
    fun resume() = manager.resume(id)
    fun cancel() = manager.cancel(id)
}
