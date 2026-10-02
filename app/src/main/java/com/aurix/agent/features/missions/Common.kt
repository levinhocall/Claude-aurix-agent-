package com.aurix.agent.features.missions

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aurix.agent.core.mission.MissionEntity
import com.aurix.agent.core.mission.MissionStatus
import kotlinx.coroutines.delay

fun formatElapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun elapsedText(m: MissionEntity): String {
    val now by produceState(System.currentTimeMillis(), m.status) {
        while (m.status.isActive()) { value = System.currentTimeMillis(); delay(1000) }
    }
    val end = if (m.status.isActive()) now else m.updatedAt
    return formatElapsed(end - m.createdAt)
}

@Composable
fun StatusChip(status: MissionStatus) {
    val color = when (status) {
        MissionStatus.COMPLETED -> Color(0xFF66BB6A)
        MissionStatus.FAILED, MissionStatus.CANCELLED -> MaterialTheme.colorScheme.error
        MissionStatus.PAUSED, MissionStatus.WAITING_FOR_APPROVAL -> MaterialTheme.colorScheme.tertiary
        MissionStatus.RECOVERING -> Color(0xFFFFB74D)
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(50)) {
        Text(status.name.replace('_', ' '), color = color, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}
