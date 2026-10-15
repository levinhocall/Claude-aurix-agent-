package com.aurix.agent.core.tools.screen

import android.graphics.Bitmap
import android.graphics.Rect
import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One recognized text line with its centre in screen pixels and the screenshot size it was measured in. */
data class LookItem(val text: String, val cx: Int, val cy: Int, val w: Int, val h: Int, val screenW: Int, val screenH: Int)

/** Pure formatting of OCR results for the model (unit-tested without Android). */
object LookFormat {
    private const val MAX_ITEMS = 40
    private const val MAX_TEXT = 60

    fun format(pkg: String, raw: List<LookItem>): String {
        if (raw.isEmpty()) return "app: ${pkg.ifEmpty { "unknown" }}\n(no readable text in the screenshot; the screen may be an image/video, blank, or protected)"
        val items = dedupe(raw).sortedWith(compareBy({ it.cy / 24 }, { it.cx })).take(MAX_ITEMS)
        val sw = raw.first().screenW; val sh = raw.first().screenH
        val lines = items.mapIndexed { i, it -> "[${i + 1}] \"${it.text.take(MAX_TEXT)}\" @${it.cx},${it.cy}" }
        return "app: ${pkg.ifEmpty { "unknown" }} screen ${sw}x$sh (OCR; tap with SCREEN_TAP {x,y})\n" + lines.joinToString("\n") + if (raw.size > items.size) "\n…(more text; scroll)" else ""
    }

    /** The Latin and Devanagari recognizers both report some lines: keep one per (same text, nearby position). */
    internal fun dedupe(items: List<LookItem>): List<LookItem> {
        val out = ArrayList<LookItem>()
        for (it in items) {
            val t = it.text.trim()
            if (t.isEmpty() || t.none { c -> c.isLetterOrDigit() }) continue
            val dup = out.any { o -> o.text.trim().equals(t, ignoreCase = true) && kotlin.math.abs(o.cx - it.cx) < 30 && kotlin.math.abs(o.cy - it.cy) < 30 }
            if (!dup) out += it.copy(text = t)
        }
        return out
    }
}

internal object ScreenVision {
    private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { if (c.isActive) c.resume(it) }
        addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
    }

    suspend fun recognize(svc: AurixAccessibilityService): List<LookItem> {
        val bmp: Bitmap = svc.screenshot()
        try {
            val img = InputImage.fromBitmap(bmp, 0)
            val found = ArrayList<LookItem>()
            fun add(lines: List<com.google.mlkit.vision.text.Text.Line>) = lines.forEach { l ->
                val r: Rect = l.boundingBox ?: return@forEach
                found += LookItem(l.text, r.centerX(), r.centerY(), r.width(), r.height(), bmp.width, bmp.height)
            }
            val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try { latin.process(img).await().textBlocks.forEach { add(it.lines) } } finally { latin.close() }
            val deva = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            try { deva.process(img).await().textBlocks.forEach { add(it.lines) } } finally { deva.close() }
            return found
        } finally { bmp.recycle() }
    }
}
