package com.aurix.agent.features.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aurix.agent.core.mission.MissionDao
import com.aurix.agent.core.mission.MissionEntity
import com.aurix.agent.core.mission.MissionStatus
import com.aurix.agent.core.voice.VoiceEngine
import com.aurix.agent.core.voice.VoicePhase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ChromeViewModel @Inject constructor(engine: VoiceEngine, dao: MissionDao) : ViewModel() {
    val phase: StateFlow<VoicePhase> = engine.phase
    /** The newest mission (active, or finished a moment ago) drives the task card and the glow colour. */
    val latest: StateFlow<MissionEntity?> = dao.observeMissions().map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

private val Purple = Color(0xFFB026FF)
private val Green = Color(0xFF39FF6A)
private val Blue = Color(0xFF2D7BFF)
private val Red = Color(0xFFFF2D3B)
private val Amber = Color(0xFFFFC21A)

/** Glowing screen edge (colour = what AURIX is doing) plus a floating task card with step progress. */
@Composable
fun AurixChrome(content: @Composable () -> Unit, vm: ChromeViewModel = hiltViewModel()) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val latest by vm.latest.collectAsStateWithLifecycle()
    val busy = latest?.status?.isActive() == true
    val target = when {
        phase == VoicePhase.LISTENING -> Green
        phase == VoicePhase.SPEAKING -> Red
        phase == VoicePhase.THINKING -> Amber
        busy -> Blue
        else -> Purple
    }
    val color by animateColorAsState(target, tween(500), label = "glow")
    val pulse by rememberInfiniteTransition(label = "p").animateFloat(0.45f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "pulse")
    // show the card while active and for a few seconds after it finishes
    val showCard by produceState(false, latest?.id, latest?.status) {
        val m = latest
        if (m == null) { value = false; return@produceState }
        value = true
        if (!m.status.isActive()) { delay(6_000); value = false }
    }
    Box(Modifier.fillMaxSize()) {
        content()
        Box(Modifier.fillMaxSize().border(3.dp, color.copy(alpha = pulse), RoundedCornerShape(26.dp)))
        AnimatedVisibility(
            showCard && latest != null,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
        ) { latest?.let { TaskCard(it) } }
    }
}

@Composable
private fun TaskCard(m: MissionEntity) {
    val cs = MaterialTheme.colorScheme
    val done = m.status == MissionStatus.COMPLETED
    val failed = m.status == MissionStatus.FAILED
    Column(
        Modifier.statusBarsPadding().padding(top = 8.dp, start = 24.dp, end = 24.dp).fillMaxWidth()
            .background(cs.surface.copy(alpha = 0.96f), RoundedCornerShape(16.dp)).padding(14.dp),
    ) {
        Text(
            (if (done) "✓ Task completed" else if (failed) "✕ Task failed" else "● Working") +
                if (m.totalSteps > 0) "  ·  ${minOf(m.currentStep + if (done) 0 else 1, m.totalSteps)}/${m.totalSteps} steps" else "",
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (failed) cs.error else if (done) Green else cs.primary,
        )
        Text(
            if (m.status.isActive()) m.currentAction.ifBlank { m.objective } else m.objective,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurface, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp),
        )
        if (m.status.isActive() && m.totalSteps > 0) {
            LinearProgressIndicator(progress = { m.currentStep.toFloat() / m.totalSteps }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = cs.primary)
        }
    }
}
