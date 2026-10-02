package com.aurix.agent.features.missions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurix.agent.core.agent.MissionManager
import com.aurix.agent.core.mission.EventEntity
import com.aurix.agent.core.mission.MissionDao
import com.aurix.agent.core.mission.MissionEntity
import com.aurix.agent.core.mission.StepEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(dao: MissionDao, private val manager: MissionManager) : ViewModel() {
    val missions: StateFlow<List<MissionEntity>> =
        dao.observeMissions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun submit(objective: String, onCreated: (String) -> Unit) {
        viewModelScope.launch { onCreated(manager.create(objective.trim())) }
    }
}

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    dao: MissionDao,
    private val manager: MissionManager,
) : ViewModel() {
    private val id: String = checkNotNull(savedState["id"])
    val mission: StateFlow<MissionEntity?> = dao.observeMission(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val steps: StateFlow<List<StepEntity>> = dao.observeSteps(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val events: StateFlow<List<EventEntity>> = dao.observeEvents(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun pause() = manager.pause(id)
    fun resume() = manager.resume(id)
    fun cancel() = manager.cancel(id)
}
