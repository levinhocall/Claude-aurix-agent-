package com.aurix.agent.features.missions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurix.agent.ui.VoiceBars

/** Claude-style composer: rounded card, text on top, voice button bottom-left, send / stop bottom-right. */
@Composable
fun Composer(
    value: String, onChange: (String) -> Unit, onSend: () -> Unit, placeholder: String,
    modifier: Modifier = Modifier, busy: Boolean = false, onStop: () -> Unit = {}, onVoice: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(26.dp), color = cs.surface, border = BorderStroke(1.dp, cs.outline), shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(start = 6.dp, end = 10.dp, top = 4.dp, bottom = 10.dp)) {
            TextField(
                value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), maxLines = 6, enabled = !busy,
                placeholder = { Text(placeholder, color = cs.onSurfaceVariant) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                ),
            )
            Row(Modifier.fillMaxWidth().padding(start = 10.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (onVoice != null) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).border(1.dp, cs.outline, CircleShape).clickable(onClick = onVoice),
                        contentAlignment = Alignment.Center,
                    ) { VoiceBars(18.dp, color = cs.onSurface) }
                } else Box(Modifier.size(38.dp))
                if (busy) {
                    Box(Modifier.size(38.dp).clip(CircleShape).background(cs.primary).clickable(onClick = onStop), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(12.dp).background(cs.onPrimary, RoundedCornerShape(2.dp)))
                    }
                } else {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(if (value.isNotBlank()) cs.primary else cs.outline).clickable(enabled = value.isNotBlank(), onClick = onSend),
                        contentAlignment = Alignment.Center,
                    ) { Text("↑", color = cs.onPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
                }
            }
        }
    }
}
