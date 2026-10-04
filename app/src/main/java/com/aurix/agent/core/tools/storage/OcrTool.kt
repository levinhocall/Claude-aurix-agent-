package com.aurix.agent.core.tools.storage

import android.content.Context
import android.net.Uri
import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.Tool
import com.aurix.agent.core.tools.ToolContext
import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolException
import com.aurix.agent.core.tools.ToolResult
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { if (c.isActive) c.resume(it) }
    addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
}

/** On-device text recognition (Latin + Devanagari) for photos and screenshots. Nothing leaves the phone. */
class OcrTool(private val ctx: Context) : Tool {
    override val name = "OCR_IMAGE"
    override val description = "Read text from an image file (photo/screenshot) on the phone, English + Hindi/Devanagari. Runs on-device."
    override val inputSchema = """{"path":"Pictures/Screenshots/a.png"}"""
    override val outputSchema = "extracted text"
    override val required = listOf("path")
    override val permissions = listOf("storage")
    override val risk = RiskLevel.MEDIUM
    override val timeoutMs = 45_000L
    override fun describe(input: JSONObject) = "Read text from image: ${input.optString("path")}"

    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        requireStorageAccess(ctx)
        val f: File = resolveStorage(storageRoot(), input.optString("path"))
        if (!f.isFile) throw ToolException(ToolErrorType.INVALID_INPUT, "Image not found")
        val img = try { InputImage.fromFilePath(ctx, Uri.fromFile(f)) } catch (e: Exception) { throw ToolException(ToolErrorType.INVALID_INPUT, "Not a readable image") }
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val deva = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        val a = try { latin.process(img).awaitResult().text } finally { latin.close() }
        val b = try { deva.process(img).awaitResult().text } finally { deva.close() }
        val text = listOf(a, b).filter { it.isNotBlank() }.distinctBy { it.take(40) }.joinToString("\n---\n").trim()
        return ToolResult.ok(if (text.isEmpty()) "No text found in the image" else text.take(3000))
    }
}
