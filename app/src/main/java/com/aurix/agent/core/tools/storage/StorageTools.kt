package com.aurix.agent.core.tools.storage

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.StatFs
import androidx.core.content.FileProvider
import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.Tool
import com.aurix.agent.core.tools.ToolContext
import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolException
import com.aurix.agent.core.tools.ToolResult
import com.aurix.agent.core.tools.Workspace
import com.aurix.agent.core.tools.device.foldText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private val DATE = SimpleDateFormat("yyyy-MM-dd", Locale.US)
private fun fmt(f: File) = if (f.isDirectory) f.name + "/" else "${f.name} (${humanSize(f.length())}, ${DATE.format(Date(f.lastModified()))})"
private fun rel(root: File, f: File) = f.relativeTo(root.canonicalFile).path

private fun pathsOf(input: JSONObject): List<String> {
    val arr = input.optJSONArray("paths")
    val list = if (arr != null) (0 until arr.length()).map { arr.optString(it) } else listOf(input.optString("path"))
    return list.map { it.trim() }.filter { it.isNotEmpty() }
}

private abstract class StorageTool(protected val ctx: Context) : Tool {
    override val permissions = listOf("storage")
    override val timeoutMs = 40_000L
    protected val root: File get() = storageRoot()
    protected fun access() = requireStorageAccess(ctx)
    protected suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    /** "workspace:file" refers to a file the agent created inside the mission workspace. */
    protected fun resolveSrc(ws: Workspace, missionId: String, raw: String): File =
        if (raw.startsWith("workspace:")) ws.resolve(missionId, raw.removePrefix("workspace:")) else resolveStorage(root, raw)
    protected fun safeResolve(raw: String): File? = try { resolveStorage(root, raw) } catch (e: Exception) { null }
}

private class StorageListTool(ctx: Context) : StorageTool(ctx) {
    override val name = "STORAGE_LIST"
    override val description = "List files/folders on the phone's storage (aliases: Downloads, Pictures, DCIM, Documents, Music...)."
    override val inputSchema = """{"path":"Download","limit":40,"sort":"date|name|size"}"""
    override val outputSchema = "entries"
    override val risk = RiskLevel.MEDIUM
    override fun describe(input: JSONObject) = "List folder: ${input.optString("path").ifBlank { "/" }}"
    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult = io {
        access()
        val dir = resolveStorage(root, input.optString("path"))
        if (!dir.isDirectory) throw ToolException(ToolErrorType.INVALID_INPUT, "Not a folder: ${input.optString("path")}")
        val all = dir.listFiles()?.toList() ?: throw ToolException(ToolErrorType.TOOL_ERROR, "Cannot read this folder")
        val sorted = when (input.optString("sort")) {
            "name" -> all.sortedBy { it.name.lowercase() }
            "size" -> all.sortedByDescending { it.length() }
            else -> all.sortedByDescending { it.lastModified() }
        }
        val limit = input.optInt("limit", 40).coerceIn(1, 100)
        ToolResult.ok("${rel(root, dir).ifEmpty { "/" }}: ${all.size} items\n" + sorted.take(limit).joinToString("\n") { fmt(it) } + if (all.size > limit) "\n…${all.size - limit} more" else "")
    }
}

private class StorageSearchTool(ctx: Context) : StorageTool(ctx) {
    override val name = "STORAGE_SEARCH"
    override val description = "Find files by name part and/or extension, newest first."
    override val inputSchema = """{"query":"invoice","ext":"pdf","path":"","limit":30}"""
    override val outputSchema = "matching paths"
    override val risk = RiskLevel.MEDIUM
    override fun describe(input: JSONObject) = "Search files: ${input.optString("query")} ${input.optString("ext")}"
    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult = io {
        access()
        val base = resolveStorage(root, input.optString("path"))
        val q = foldText(input.optString("query")); val ext = input.optString("ext").trim().trimStart('.').lowercase()
        if (q.isEmpty() && ext.isEmpty()) throw ToolException(ToolErrorType.INVALID_INPUT, "Give query and/or ext")
        val hits = ArrayList<File>(); var visited = 0
        val end = System.currentTimeMillis() + 15_000
        for (f in base.walkTopDown().maxDepth(8).onEnter { !(it.path.contains("/Android/data") || it.path.contains("/Android/obb")) }) {
            if (++visited > 60_000 || System.currentTimeMillis() > end) break
            if (!f.isFile) continue
            if (ext.isNotEmpty() && f.extension.lowercase() != ext) continue
            if (q.isNotEmpty() && !foldText(f.name).contains(q)) continue
            hits += f
        }
        val top = hits.sortedByDescending { it.lastModified() }.take(input.optInt("limit", 30).coerceIn(1, 80))
        ToolResult.ok(if (top.isEmpty()) "No files found" else "${hits.size} found:\n" + top.joinToString("\n") { rel(root, it) + " (${humanSize(it.length())}, ${DATE.format(Date(it.lastModified()))})" })
    }
}

private class StorageReadTool(ctx: Context) : StorageTool(ctx) {
    override val name = "STORAGE_READ"
    override val description = "Read a text file from phone storage (not binary; use OCR_IMAGE for images)."
    override val inputSchema = """{"path":"Download/notes.txt","max_chars":4000}"""
    override val outputSchema = "text"
    override val required = listOf("path")
    override val risk = RiskLevel.MEDIUM
    override fun describe(input: JSONObject) = "Read file: ${input.optString("path")}"
    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult = io {
        access()
        val f = resolveStorage(root, input.optString("path"))
        if (!f.isFile) throw ToolException(ToolErrorType.INVALID_INPUT, "File not found")
        if (f.length() > 2_000_000) throw ToolException(ToolErrorType.INVALID_INPUT, "File too large (${humanSize(f.length())})")
        val buf = ByteArray(1024)
        val got = f.inputStream().use { it.read(buf) }
        val head = buf.copyOf(maxOf(got, 0))
        if (head.any { it.toInt() == 0 }) throw ToolException(ToolErrorType.INVALID_INPUT, "Binary file; cannot read as text")
        val max = input.optInt("max_chars", 4000).coerceIn(200, 12_000)
        val t = f.readText()
        ToolResult.ok(if (t.length > max) t.take(max) + "\n[truncated ${t.length - max} chars]" else t)
    }
}

private class StorageInfoTool(ctx: Context) : StorageTool(ctx) {
    override val name = "STORAGE_INFO"
    override val description = "Storage analyzer: free/total space, biggest files and usage by type under a folder."
    override val inputSchema = """{"path":""}"""
    override val outputSchema = "summary"
    override val risk = RiskLevel.LOW
    override val timeoutMs = 45_000L
    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult = io {
        access()
        val base = resolveStorage(root, input.optString("path"))
        val st = StatFs(root.path)
        val total = st.totalBytes; val free = st.availableBytes
        val kinds = linkedMapOf("images" to 0L, "video" to 0L, "audio" to 0L, "docs" to 0L, "apk" to 0L, "other" to 0L)
        val big = ArrayList<File>(); var visited = 0
        val end = System.currentTimeMillis() + 20_000
        for (f in base.walkTopDown().onEnter { !(it.path.contains("/Android/data") || it.path.contains("/Android/obb")) }) {
            if (++visited > 80_000 || System.currentTimeMillis() > end) break
            if (!f.isFile) continue
            val len = f.length()
            val k = when (f.extension.lowercase()) {
                "jpg", "jpeg", "png", "webp", "heic", "gif" -> "images"; "mp4", "mkv", "mov", "avi", "webm" -> "video"
                "mp3", "m4a", "wav", "flac", "ogg", "aac" -> "audio"; "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv" -> "docs"
                "apk" -> "apk"; else -> "other"
            }
            kinds[k] = kinds.getValue(k) + len
            if (len > 20_000_000) { big += f }
        }
        val top = big.sortedByDescending { it.length() }.take(8)
        ToolResult.ok(
            "Phone storage: ${humanSize(total - free)} used of ${humanSize(total)} (${humanSize(free)} free)\n" +
                "Under ${rel(root, base).ifEmpty { "/" }}: " + kinds.entries.joinToString(", ") { "${it.key} ${humanSize(it.value)}" } +
                (if (top.isNotEmpty()) "\nBiggest:\n" + top.joinToString("\n") { rel(root, it) + " (${humanSize(it.length())})" } else "") +
                if (visited > 80_000) "\n(scan capped)" else "",
        )
    }
}

private class StorageCopyMoveTool(ctx: Context, private val ws: Workspace, private val move: Boolean) : StorageTool(ctx) {
    override val name = if (move) "STORAGE_MOVE" else "STORAGE_COPY"
    override val description = (if (move) "Move/rename" else "Copy") + " a file or folder. `from` may be \"workspace:file.md\" (a file the agent created) to save it onto the phone, e.g. to Documents/."
    override val inputSchema = """{"from":"Download/a.pdf","to":"Documents/a.pdf"}"""
    override val outputSchema = "confirmation"
    override val required = listOf("from", "to")
    override val risk = RiskLevel.MEDIUM
    override fun riskFor(input: JSONObject): RiskLevel = if (safeResolve(input.optString("to"))?.exists() == true) RiskLevel.HIGH else risk
    override fun describe(input: JSONObject) = (if (move) "Move " else "Copy ") + "${input.optString("from")} → ${input.optString("to")}" + if (riskFor(input) == RiskLevel.HIGH) " (OVERWRITES existing)" else ""
    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult = io {
        access()
        val src = resolveSrc(ws, ctx0.missionId, input.optString("from"))
        var dst = resolveStorage(root, input.optString("to"))
        if (!src.exists()) throw ToolException(ToolErrorType.INVALID_INPUT, "Source not found")
        if (dst.isDirectory && src.isFile) dst = File(dst, src.name)
        dst.parentFile?.mkdirs()
        if (move) { if (!src.renameTo(dst)) { src.copyRecursively(dst, true); src.deleteRecursively() } }
        else src.copyRecursively(dst, true)
        MediaScannerConnection.scanFile(ctx, arrayOf(dst.path), null, null)
        ToolResult.ok((if (move) "Moved" else "Copied") + " to ${rel(root, dst)} (${humanSize(dst.length())})")
    }
}

private class StorageWriteTool(ctx: Context) : StorageTool(ctx) {
    override val name = "STORAGE_WRITE"
    override val description = "Write a text file directly onto phone storage (e.g. Documents/notes.txt)."
    override val inputSchema = """{"path":"Documents/notes.txt","content":"..."}"""
    override val outputSchema = "confirmation"
    override val required = listOf("path", "content")
    override val risk = RiskLevel.MEDIUM
    override fun riskFor(input: JSONObject): RiskLevel = if (safeResolve(input.optString("path"))?.exists() == true) RiskLevel.HIGH else risk
    override fun describe(input: JSONObject) = "Write ${input.optString("path")} (${input.optString("content").length} chars)" + if (riskFor(input) == RiskLevel.HIGH) " (OVERWRITES existing)" else ""
    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult = io {
        access()
        val f = resolveStorage(root, input.optString("path"))
        f.parentFile?.mkdirs()
        f.writeText(input.optString("content"))
        MediaScannerConnection.scanFile(ctx, arrayOf(f.path), null, null)
        ToolResult.ok("Wrote ${rel(root, f)} (${humanSize(f.length())})")
    }
}

private val PROTECTED_TOP = setOf("DCIM", "Pictures", "Download", "Documents", "Music", "Movies", "Android", "Alarms", "Notifications", "Ringtones", "Podcasts")

private class StorageDeleteTool(ctx: Context) : StorageTool(ctx) {
    override val name = "STORAGE_DELETE"
    override val description = "Permanently delete files/folders (always asks the user first). Use for 'delete photos/files'. Not reversible."
    override val inputSchema = """{"paths":["Download/old.zip","DCIM/Camera/IMG_1.jpg"]}"""
    override val outputSchema = "count deleted"
    override val risk = RiskLevel.HIGH
    override fun describe(input: JSONObject): String {
        val p = pathsOf(input)
        val size = p.mapNotNull { safeResolve(it) }.filter { it.exists() }.sumOf { if (it.isFile) it.length() else it.walkTopDown().filter { f -> f.isFile }.take(5000).sumOf { f -> f.length() } }
        return "DELETE ${p.size} item(s) (${humanSize(size)}): ${p.take(4).joinToString(", ")}" + if (p.size > 4) " …" else ""
    }
    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult = io {
        access()
        val p = pathsOf(input)
        if (p.isEmpty()) throw ToolException(ToolErrorType.INVALID_INPUT, "No paths given")
        if (p.size > 200) throw ToolException(ToolErrorType.INVALID_INPUT, "Too many items (max 200 per call)")
        var deleted = 0; val failed = ArrayList<String>(); val gone = ArrayList<String>()
        for (raw in p) {
            val f = resolveStorage(root, raw)
            if (f == root.canonicalFile || (f.parentFile == root.canonicalFile && f.name in PROTECTED_TOP))
                throw ToolException(ToolErrorType.PERMISSION_REQUIRED, "Refusing to delete the whole folder '${f.name}'. Delete specific files inside it.")
            if (!f.exists()) { failed += "$raw (not found)"; continue }
            val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
            if (ok) { deleted++; gone += f.path } else failed += raw
        }
        if (gone.isNotEmpty()) MediaScannerConnection.scanFile(ctx, gone.toTypedArray(), null, null)
        ToolResult.ok("Deleted $deleted of ${p.size}" + if (failed.isNotEmpty()) "; failed: ${failed.take(5).joinToString()}" else "")
    }
}

private class StorageZipTool(ctx: Context) : StorageTool(ctx) {
    override val name = "STORAGE_ZIP"
    override val description = "Zip files/folders into one .zip file."
    override val inputSchema = """{"paths":["Documents/a","Download/b.pdf"],"out":"Download/archive.zip"}"""
    override val outputSchema = "confirmation"
    override val required = listOf("paths", "out")
    override val risk = RiskLevel.MEDIUM
    override fun riskFor(input: JSONObject): RiskLevel = if (safeResolve(input.optString("out"))?.exists() == true) RiskLevel.HIGH else risk
    override fun describe(input: JSONObject) = "Zip ${pathsOf(input).size} item(s) → ${input.optString("out")}"
    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult = io {
        access()
        val out = resolveStorage(root, input.optString("out"))
        out.parentFile?.mkdirs()
        var count = 0
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (raw in pathsOf(input)) {
                val src = resolveStorage(root, raw)
                if (!src.exists()) throw ToolException(ToolErrorType.INVALID_INPUT, "Not found: $raw")
                val base = src.parentFile ?: root
                for (f in src.walkTopDown().filter { it.isFile }) {
                    if (f.canonicalPath == out.canonicalPath) continue
                    if (++count > 5000) throw ToolException(ToolErrorType.INVALID_INPUT, "Too many files (max 5000)")
                    zip.putNextEntry(ZipEntry(f.relativeTo(base).path))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        MediaScannerConnection.scanFile(ctx, arrayOf(out.path), null, null)
        ToolResult.ok("Created ${rel(root, out)} with $count files (${humanSize(out.length())})")
    }
}

private class StorageUnzipTool(ctx: Context) : StorageTool(ctx) {
    override val name = "STORAGE_UNZIP"
    override val description = "Extract a .zip into a folder."
    override val inputSchema = """{"path":"Download/a.zip","to":"Download/a"}"""
    override val outputSchema = "confirmation"
    override val required = listOf("path")
    override val risk = RiskLevel.MEDIUM
    override fun describe(input: JSONObject) = "Unzip ${input.optString("path")}"
    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult = io {
        access()
        val zip = resolveStorage(root, input.optString("path"))
        if (!zip.isFile) throw ToolException(ToolErrorType.INVALID_INPUT, "Zip not found")
        val dest = if (input.optString("to").isBlank()) File(zip.parentFile, zip.nameWithoutExtension) else resolveStorage(root, input.optString("to"))
        dest.mkdirs()
        val destCanon = dest.canonicalFile
        var n = 0; var total = 0L
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val target = File(destCanon, e.name).canonicalFile
                if (!target.path.startsWith(destCanon.path + File.separator)) throw ToolException(ToolErrorType.INVALID_INPUT, "Unsafe zip entry blocked: ${e.name}")
                if (e.isDirectory) target.mkdirs() else {
                    if (++n > 5000) throw ToolException(ToolErrorType.INVALID_INPUT, "Too many entries")
                    target.parentFile?.mkdirs()
                    target.outputStream().use { o -> total += zin.copyTo(o); if (total > 500L * 1024 * 1024) throw ToolException(ToolErrorType.INVALID_INPUT, "Archive too large") }
                }
                e = zin.nextEntry
            }
        }
        MediaScannerConnection.scanFile(ctx, arrayOf(dest.path), null, null)
        ToolResult.ok("Extracted $n files to ${rel(root, dest)} (${humanSize(total)})")
    }
}

private class StorageShareTool(ctx: Context, private val ws: Workspace) : StorageTool(ctx) {
    override val name = "STORAGE_SHARE"
    override val description = "Open the share sheet for a file (WhatsApp, email, etc.). `path` may be \"workspace:file.md\"."
    override val inputSchema = """{"path":"Download/a.pdf"}"""
    override val outputSchema = "confirmation"
    override val required = listOf("path")
    override val risk = RiskLevel.MEDIUM
    override fun describe(input: JSONObject) = "Share file: ${input.optString("path")}"
    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        val f = withContext(Dispatchers.IO) {
            val raw = input.optString("path")
            if (!raw.startsWith("workspace:")) access()
            resolveSrc(ws, ctx0.missionId, raw)
        }
        if (!f.isFile) throw ToolException(ToolErrorType.INVALID_INPUT, "File not found")
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        com.aurix.agent.core.tools.device.openOtherApp(ctx, Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult.ok("Share sheet opened for ${f.name} (user picks the target)")
    }
}

object StorageTools {
    fun all(ctx: Context, ws: Workspace): List<Tool> = listOf(
        StorageListTool(ctx), StorageSearchTool(ctx), StorageReadTool(ctx), StorageInfoTool(ctx),
        StorageCopyMoveTool(ctx, ws, false), StorageCopyMoveTool(ctx, ws, true), StorageWriteTool(ctx), StorageDeleteTool(ctx),
        StorageZipTool(ctx), StorageUnzipTool(ctx), StorageShareTool(ctx, ws), OcrTool(ctx),
    )
}
