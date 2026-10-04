package com.aurix.agent.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.aurix.agent.core.voice.VoicePhase

/** The voice orb: glows and swells with your voice while listening, pulses while speaking, spins an arc while thinking. */
@Composable
fun VoiceOrb(phase: VoicePhase, level: Float, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "orb")
    val breathe by t.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "spin")
    val speak by t.animateFloat(0.2f, 0.9f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "speak")
    val target = when (phase) {
        VoicePhase.LISTENING -> level
        VoicePhase.SPEAKING -> speak
        VoicePhase.THINKING -> 0.25f
        VoicePhase.IDLE -> 0.1f
    }
    val amp by animateFloatAsState(target, tween(120), label = "amp")
    val primary = cs.primary
    Canvas(modifier.size(280.dp)) {
        val c = center
        val r = size.minDimension * 0.27f * breathe * (1f + amp * 0.35f)
        listOf(2.15f, 1.75f, 1.38f).forEachIndexed { i, k ->
            drawCircle(primary.copy(alpha = (0.05f + 0.05f * (2 - i) * (0.4f + amp)).coerceAtMost(0.3f)), radius = r * k, center = c)
        }
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0xFFFFC2A0), primary, Color(0xFFB4452A)), center = c, radius = r),
            radius = r, center = c,
        )
        if (phase == VoicePhase.THINKING) {
            rotate(spin, c) {
                drawArc(
                    Color.White.copy(alpha = 0.85f), 0f, 100f, false,
                    topLeft = Offset(c.x - r * 1.14f, c.y - r * 1.14f), size = Size(r * 2.28f, r * 2.28f),
                    style = Stroke(width = 7f, cap = StrokeCap.Round),
                )
            }
        }
    }
}
