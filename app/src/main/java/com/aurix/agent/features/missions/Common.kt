package com.aurix.agent.features.missions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.aurix.agent.core.mission.MissionEntity
import com.aurix.agent.core.mission.MissionStatus
import kotlinx.coroutines.delay

fun formatElapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatTokens(n: Int): String = if (n >= 1000) "%.1fk".format(n / 1000.0) else n.toString()

fun statusGlyph(s: MissionStatus): String = when {
    s.isActive() -> "●"
    s == MissionStatus.COMPLETED -> "✓"
    s == MissionStatus.FAILED || s == MissionStatus.CANCELLED -> "✗"
    else -> "⏸"
}

@Composable
fun elapsedText(m: MissionEntity): String {
    val now by produceState(System.currentTimeMillis(), m.status) {
        while (m.status.isActive()) { value = System.currentTimeMillis(); delay(1000) }
    }
    val end = if (m.status.isActive()) now else m.updatedAt
    return formatElapsed(end - m.createdAt)
}
