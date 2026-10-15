package com.aurix.agent.features.glow

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurix.agent.core.mission.MissionDao
import com.aurix.agent.core.mission.MissionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 0 = idle (nothing drawn), 1 = working, 2 = waiting for the user's approval. */
internal fun glowLevel(statuses: List<MissionStatus>): Int = when {
    statuses.any { it == MissionStatus.WAITING_FOR_APPROVAL } -> 2
    statuses.any { it == MissionStatus.PLANNING || it == MissionStatus.RUNNING || it == MissionStatus.WAITING_FOR_TOOL || it == MissionStatus.RECOVERING } -> 1
    else -> 0
}

@HiltViewModel
class GlowViewModel @Inject constructor(dao: MissionDao) : ViewModel() {
    val level: StateFlow<Int> = dao.observeMissions().map { l -> glowLevel(l.map { it.status }) }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

/** Thin pulsing border around the screen while AURIX works (red) or waits for approval (amber). Draws nothing when idle; never takes touches. */
@Composable
fun EdgeGlow() {
    val vm: GlowViewModel = hiltViewModel()
    val level by vm.level.collectAsState()
    if (level == 0) return
    val pulse by rememberInfiniteTransition(label = "glow").animateFloat(0.35f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "a")
    val base = if (level == 2) Color(0xFFFFB300) else Color(0xFFE11D48)
    Box(Modifier.fillMaxSize().border(3.dp, base.copy(alpha = pulse)))
}
