package com.aurix.agent.features.missions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface Block {
    data class Para(val text: String) : Block
    data class Head(val text: String, val level: Int) : Block
    data class Bullet(val text: String) : Block
    data class Code(val text: String) : Block
}

private fun parseBlocks(src: String): List<Block> {
    val out = mutableListOf<Block>()
    var inCode = false
    val code = StringBuilder()
    for (line in src.lines()) {
        if (line.trimStart().startsWith("```")) {
            if (inCode) { out += Block.Code(code.toString().trimEnd()); code.clear() }
            inCode = !inCode
            continue
        }
        if (inCode) { code.appendLine(line); continue }
        val t = line.trim()
        when {
            t.isEmpty() -> {}
            t.startsWith("### ") -> out += Block.Head(t.removePrefix("### "), 3)
            t.startsWith("## ") -> out += Block.Head(t.removePrefix("## "), 2)
            t.startsWith("# ") -> out += Block.Head(t.removePrefix("# "), 1)
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") -> out += Block.Bullet(t.drop(2))
            t.startsWith("|") -> out += Block.Code(t)
            else -> out += Block.Para(t)
        }
    }
    if (inCode && code.isNotEmpty()) out += Block.Code(code.toString().trimEnd())
    return out
}

private fun inline(s: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        if (s.startsWith("**", i)) {
            val e = s.indexOf("**", i + 2)
            if (e > 0) { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, e)) }; i = e + 2; continue }
        }
        if (s[i] == '`') {
            val e = s.indexOf('`', i + 1)
            if (e > 0) { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 13.sp)) { append(s.substring(i + 1, e)) }; i = e + 1; continue }
        }
        append(s[i]); i++
    }
}

/** Small markdown subset: headings, bullets, **bold**, `code`, fenced code, table rows as monospace. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseBlocks(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Block.Para -> Text(inline(b.text, codeBg), style = MaterialTheme.typography.bodyLarge)
                is Block.Head -> Text(inline(b.text, codeBg), style = if (b.level == 1) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                is Block.Bullet -> Text(inline("•  " + b.text, codeBg), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 6.dp))
                is Block.Code -> Text(
                    b.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp,
                    modifier = Modifier.fillMaxWidth().background(codeBg, RoundedCornerShape(8.dp)).padding(10.dp),
                )
            }
        }
    }
}
