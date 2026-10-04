package com.aurix.agent.features.missions

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurix.agent.core.agent.cleanTitle
import com.aurix.agent.core.mission.MissionEntity
import com.aurix.agent.core.mission.MissionStatus
import com.aurix.agent.core.mission.StepStatus
import com.aurix.agent.core.mission.ToolCallEntity
import com.aurix.agent.ui.AurixGreen
import com.aurix.agent.ui.AurixSpark
import com.aurix.agent.ui.AurixGreenBg
import com.aurix.agent.ui.AurixRedBg
import org.json.JSONObject

private val Mono = FontFamily.Monospace

private fun toolLabel(t: String) = when (t) {
    "WEB_SEARCH" -> "Search"; "WEB_BROWSER" -> "Fetch"; "FILE_WRITE" -> "Write"; "FILE_READ" -> "Read"
    "FILE_EDIT" -> "Update"; "FILE_LIST" -> "List"; "CALCULATOR" -> "Calc"
    else -> t.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun inputJson(c: ToolCallEntity): JSONObject? = try { JSONObject(c.input) } catch (e: Exception) { null }

private fun argSummary(c: ToolCallEntity): String {
    val j = inputJson(c)
    val v = when (c.tool) {
        "WEB_SEARCH" -> j?.optString("query")
        "WEB_BROWSER" -> j?.optString("url")
        "CALCULATOR" -> j?.optString("expression")
        "FILE_WRITE", "FILE_READ", "FILE_EDIT" -> j?.optString("path")
        else -> listOf("query", "name", "text", "key", "direction", "destination", "number", "question", "path", "paths", "from", "id", "url", "expression")
            .firstNotNullOfOrNull { k -> j?.opt(k)?.toString()?.takeIf { it.isNotBlank() } }
    }
    return (v?.takeIf { it.isNotBlank() } ?: c.input.take(60)).take(70)
}

private fun resultSummary(c: ToolCallEntity): String {
    val first = c.output.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    if (!c.ok) return "✗ ${c.errorType ?: "ERROR"}: ${first.take(110)}"
    return when (c.tool) {
        "WEB_SEARCH" -> "${Regex("(?m)^\\d+\\. ").findAll(c.output).count()} results"
        "WEB_BROWSER" -> c.output.lineSequence().firstOrNull { it.startsWith("TITLE:") }?.let { "Read: " + it.removePrefix("TITLE:").trim().take(90) } ?: first.take(100)
        else -> first.take(110)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptScreen(onBack: () -> Unit, onOpenMission: (String) -> Unit, onVoice: () -> Unit, vm: TranscriptViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val m = st.mission
    val listState = rememberLazyListState()
    val ctx = LocalContext.current
    LaunchedEffect(st.items.size, m?.currentAction) { if (st.items.isNotEmpty()) listState.animateScrollToItem(st.items.lastIndex) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(m?.objective ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹", fontSize = 28.sp, color = MaterialTheme.colorScheme.onBackground) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = { BottomPanel(m, st.model, vm, onOpenMission, onVoice) },
    ) { pad ->
        LazyColumn(
            state = listState, modifier = Modifier.padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            items(st.items, key = { it.key }) { item -> RenderItem(item, vm, ctx, onOpenMission) }
        }
    }
}

@Composable
private fun BottomPanel(m: MissionEntity?, model: String?, vm: TranscriptViewModel, onOpenMission: (String) -> Unit, onVoice: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(cs.background).navigationBarsPadding().imePadding()) {
        if (m != null) {
            val showStatus = (!m.status.isTerminal() && m.status != MissionStatus.PAUSED) || m.status == MissionStatus.FAILED
            if (showStatus) Text(
                "${statusGlyph(m.status)} ${m.status.name.lowercase().replace('_', ' ')} · ${formatTokens(m.tokensUsed)} tok · ${elapsedText(m)}" + (model?.let { " · $it" } ?: ""),
                fontFamily = Mono, fontSize = 10.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        if (m != null && m.status == MissionStatus.PAUSED) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::resume, modifier = Modifier.weight(1f)) { Text("Resume") }
                OutlinedButton(onClick = vm::cancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            }
        }
        val busy = m != null && m.status.isActive()
        Composer(
            text, { text = it }, { vm.followUp(text, onOpenMission); text = "" }, "Reply to AURIX",
            busy = busy, onStop = vm::pause, onVoice = onVoice,
        )
    }
}

@Composable
private fun RenderItem(item: TItem, vm: TranscriptViewModel, ctx: android.content.Context, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    when (item) {
        is UserMsg -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceVariant, modifier = Modifier.widthIn(max = 320.dp)) {
                Text(item.text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
        is PlanCard -> PlanView(item)
        is Note -> Text(item.text, style = MaterialTheme.typography.bodyLarge)
        is Info -> Text(item.text, fontFamily = Mono, fontSize = 12.sp, color = if (item.warn) cs.tertiary else cs.onSurfaceVariant)
        is ToolRow -> ToolRowView(item.call)
        is LiveRow -> LiveView(item)
        is ResultMsg -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionContainer { MarkdownText(item.text) }
            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                AurixSpark(18.dp)
                TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(item.text)) }) { Text("Copy", color = cs.onSurfaceVariant) }
                TextButton(onClick = { vm.retry(onOpen) }) { Text("Retry", color = cs.onSurfaceVariant) }
            }
        }
        is ErrorMsg -> Surface(shape = RoundedCornerShape(10.dp), color = AurixRedBg) {
            Text(item.text, color = cs.error, modifier = Modifier.padding(12.dp), fontFamily = Mono, fontSize = 12.sp)
        }
        is ApprovalCard -> Surface(
            shape = RoundedCornerShape(14.dp), color = cs.surfaceVariant, border = androidx.compose.foundation.BorderStroke(1.dp, cs.tertiary),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("⚠  Approval needed", style = MaterialTheme.typography.titleSmall, color = cs.tertiary)
                Text(item.summary, fontFamily = Mono, fontSize = 13.sp)
                if (item.reason.isNotBlank()) Text(item.reason, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.decide(item.approvalKey, false) }, modifier = Modifier.weight(1f)) { Text("Deny") }
                    Button(onClick = { vm.decide(item.approvalKey, true) }, modifier = Modifier.weight(1f)) { Text("Allow") }
                }
            }
        }
        is QuestionCard -> Surface(
            shape = RoundedCornerShape(14.dp), color = cs.surfaceVariant, border = androidx.compose.foundation.BorderStroke(1.dp, cs.primary),
            modifier = Modifier.fillMaxWidth(),
        ) {
            var answer by remember { mutableStateOf("") }
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("?  " + item.question, style = MaterialTheme.typography.bodyLarge)
                item.options.forEach { o -> OutlinedButton(onClick = { vm.answer(item.qkey, o) }, modifier = Modifier.fillMaxWidth()) { Text(o) } }
                OutlinedTextField(answer, { answer = it }, label = { Text("Your answer") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.answer(item.qkey, answer) }, enabled = answer.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Send") }
            }
        }
        is FilesBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Files", style = MaterialTheme.typography.titleSmall, color = cs.primary)
            item.files.forEach { f ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.path, fontFamily = Mono, fontSize = 13.sp)
                        Text("${f.sizeBytes} bytes · verified", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                    TextButton(onClick = { shareFile(ctx, vm.fileFor(f.path)) }) { Text("Share") }
                }
            }
        }
    }
}

@Composable
private fun PlanView(p: PlanCard) {
    val cs = MaterialTheme.colorScheme
    val done = p.steps.count { it.status == StepStatus.DONE }
    Surface(shape = RoundedCornerShape(12.dp), color = cs.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Plan  $done/${p.steps.size}", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
            p.steps.forEach { s ->
                val (glyph, color) = when (s.status) {
                    StepStatus.DONE -> "☒" to AurixGreen
                    StepStatus.RUNNING -> "◐" to cs.primary
                    StepStatus.FAILED -> "✗" to cs.error
                    StepStatus.SKIPPED -> "–" to cs.onSurfaceVariant
                    StepStatus.PENDING -> "☐" to cs.onSurfaceVariant
                }
                Row {
                    Text(glyph, color = color, fontFamily = Mono)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        cleanTitle(s.title), style = MaterialTheme.typography.bodyMedium,
                        color = if (s.status == StepStatus.DONE) cs.onSurfaceVariant else cs.onSurface,
                        textDecoration = if (s.status == StepStatus.DONE) TextDecoration.LineThrough else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveView(l: LiveRow) {
    val cs = MaterialTheme.colorScheme
    val text = if (l.tool) toolLabel(l.text.removePrefix("Using ").trim()) + "…" else l.text
    Row(verticalAlignment = Alignment.CenterVertically) {
        AurixSpark(22.dp, spinning = true)
        Spacer(Modifier.width(10.dp))
        Text(text, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ToolRowView(c: ToolCallEntity) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val dot = if (c.ok) AurixGreen else cs.error
    Column(Modifier.fillMaxWidth().clickable { open = !open }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            Text("●", color = dot, fontFamily = Mono)
            Spacer(Modifier.width(8.dp))
            Text(
                buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(toolLabel(c.tool)) }; append("(" + argSummary(c) + ")") },
                fontFamily = Mono, fontSize = 13.sp, maxLines = if (open) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Row {
            Text("  ⎿ ", color = cs.onSurfaceVariant, fontFamily = Mono, fontSize = 12.sp)
            Text(resultSummary(c), color = if (c.ok) cs.onSurfaceVariant else cs.error, fontFamily = Mono, fontSize = 12.sp, maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
        }
        if (open) ToolDetail(c)
    }
}

@Composable
private fun ToolDetail(c: ToolCallEntity) {
    val cs = MaterialTheme.colorScheme
    val j = inputJson(c)
    val lines: List<Pair<Char, String>> = when (c.tool) {
        "FILE_WRITE" -> (j?.optString("content") ?: c.input).lines().map { '+' to it }
        "FILE_EDIT" -> (j?.optString("find").orEmpty().lines().map { '-' to it }) + (j?.optString("replace").orEmpty().lines().map { '+' to it })
        else -> emptyList()
    }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).background(cs.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp)) {
        if (lines.isNotEmpty()) {
            lines.take(14).forEach { (sign, t) ->
                Text(
                    "$sign $t", fontFamily = Mono, fontSize = 11.sp, color = if (sign == '+') AurixGreen else cs.error,
                    modifier = Modifier.fillMaxWidth().background(if (sign == '+') AurixGreenBg else AurixRedBg),
                )
            }
            if (lines.size > 14) Text("… ${lines.size - 14} more lines", fontFamily = Mono, fontSize = 11.sp, color = cs.onSurfaceVariant)
        } else {
            Text(c.output, fontFamily = Mono, fontSize = 11.sp, color = cs.onSurfaceVariant)
        }
        Text("${c.durationMs} ms", fontFamily = Mono, fontSize = 10.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}
