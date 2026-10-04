package com.aurix.agent.features.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurix.agent.core.voice.VoicePhase
import com.aurix.agent.ui.VoiceOrb

@Composable
fun VoiceScreen(onClose: () -> Unit, onOpenMission: (String) -> Unit, vm: VoiceViewModel = hiltViewModel()) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val phase by vm.phase.collectAsStateWithLifecycle()
    val level by vm.level.collectAsStateWithLifecycle()
    val partial by vm.partial.collectAsStateWithLifecycle()
    val caption by vm.caption.collectAsStateWithLifecycle()
    val missionId by vm.missionId.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.start() else vm.setCaption("Microphone permission is needed for voice")
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.start()
        else launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
    DisposableEffect(Unit) { onDispose { vm.stop() } }

    Column(
        Modifier.fillMaxSize().background(cs.background).statusBarsPadding().navigationBarsPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("✕", fontSize = 22.sp, color = cs.onBackground) }
            val mid = missionId
            if (mid != null) TextButton(onClick = { onOpenMission(mid) }) { Text("Open task ›", color = cs.primary) }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { vm.onOrbTap() }) {
                VoiceOrb(phase, level)
            }
        }
        Text(
            if (phase == VoicePhase.LISTENING && partial.isNotBlank()) partial else caption,
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when (phase) {
                VoicePhase.LISTENING -> "Listening"; VoicePhase.THINKING -> "Thinking"; VoicePhase.SPEAKING -> "Speaking · tap the orb to interrupt"; VoicePhase.IDLE -> "Say “stop” to finish"
            },
            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}
