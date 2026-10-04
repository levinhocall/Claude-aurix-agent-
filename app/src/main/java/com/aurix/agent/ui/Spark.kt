package com.aurix.agent.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import kotlin.math.cos
import kotlin.math.sin

/** AURIX's own radial "spark" mark. Spins and breathes while the agent is working. */
@Composable
fun AurixSpark(size: Dp, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, spinning: Boolean = false) {
    val t = rememberInfiniteTransition(label = "spark")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "rot")
    val pulse by t.animateFloat(0.88f, 1.06f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    Canvas(
        modifier.size(size).graphicsLayer {
            rotationZ = if (spinning) rot else 0f
            val s = if (spinning) pulse else 1f
            scaleX = s; scaleY = s
        },
    ) {
        val r = this.size.minDimension / 2f
        val c = center
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0 - 90.0).toFloat()
            val outer = if (i % 2 == 0) 0.98f else 0.72f
            drawLine(
                color, Offset(c.x + cos(a) * r * 0.24f, c.y + sin(a) * r * 0.24f),
                Offset(c.x + cos(a) * r * outer, c.y + sin(a) * r * outer), strokeWidth = r * 0.17f, cap = StrokeCap.Round,
            )
        }
    }
}

/** Tiny "voice bars" glyph used on the voice-mode button. */
@Composable
fun VoiceBars(size: Dp, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier.size(size)) {
        val heights = listOf(0.35f, 0.9f, 0.55f, 0.75f, 0.3f)
        val gap = this.size.width / (heights.size * 2f - 1f)
        heights.forEachIndexed { i, h ->
            val x = gap * (2 * i) + gap / 2f
            drawLine(color, Offset(x, this.size.height * (0.5f - h / 2f)), Offset(x, this.size.height * (0.5f + h / 2f)), strokeWidth = gap * 0.9f, cap = StrokeCap.Round)
        }
    }
}
