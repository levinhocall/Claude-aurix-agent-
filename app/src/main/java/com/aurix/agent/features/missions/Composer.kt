package com.aurix.agent.features.missions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Claude-style rounded input pill with a circular send button. */
@Composable
fun Composer(value: String, onChange: (String) -> Unit, onSend: () -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(26.dp), color = cs.surfaceVariant, border = BorderStroke(1.dp, cs.outline),
    ) {
        Row(Modifier.padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
            TextField(
                value = value, onValueChange = onChange, modifier = Modifier.weight(1f), maxLines = 6,
                placeholder = { Text(placeholder, color = cs.onSurfaceVariant) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                ),
            )
            Box(
                Modifier.padding(bottom = 6.dp).size(40.dp).clip(CircleShape)
                    .background(if (value.isNotBlank()) cs.primary else cs.outline)
                    .clickable(enabled = value.isNotBlank(), onClick = onSend),
                contentAlignment = Alignment.Center,
            ) { Text("↑", color = cs.onPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
        }
    }
}
