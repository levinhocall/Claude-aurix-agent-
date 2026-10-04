package com.aurix.agent.core.tools.storage

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolException
import java.io.File

private val ALIASES = mapOf(
    "downloads" to "Download", "download" to "Download", "pictures" to "Pictures", "photos" to "DCIM", "camera" to "DCIM/Camera",
    "documents" to "Documents", "music" to "Music", "movies" to "Movies", "videos" to "Movies", "dcim" to "DCIM", "screenshots" to "Pictures/Screenshots",
)

/** Resolves a user/model path (absolute, relative or alias like "Downloads") inside shared storage; throws if it escapes. */
fun resolveStorage(root: File, raw: String): File {
    var p = raw.trim().replace('\\', '/')
    val rootPath = root.path
    p = when {
        p.startsWith(rootPath) -> p.removePrefix(rootPath)
        p.startsWith("/sdcard") -> p.removePrefix("/sdcard")
        else -> p
    }.trimStart('/')
    if (p.split('/').any { it == ".." }) throw ToolException(ToolErrorType.INVALID_INPUT, "Invalid path")
    if (p.isNotEmpty()) {
        val first = p.substringBefore('/')
        ALIASES[first.lowercase()]?.let { p = it + p.drop(first.length) }
    }
    val f = File(root, p).canonicalFile
    val rc = root.canonicalFile
    if (f != rc && !f.path.startsWith(rc.path + File.separator)) throw ToolException(ToolErrorType.INVALID_INPUT, "Path is outside shared storage")
    val rel = f.relativeTo(rc).path
    if (rel.startsWith("Android/data") || rel.startsWith("Android/obb")) throw ToolException(ToolErrorType.INVALID_INPUT, "Other apps' private folders are not accessible")
    return f
}

fun storageRoot(): File = Environment.getExternalStorageDirectory()

fun storageAccessGranted(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

fun requireStorageAccess(ctx: Context) {
    if (!storageAccessGranted(ctx)) throw ToolException(ToolErrorType.PERMISSION_REQUIRED, "Files access is off. Open AURIX → Settings → Phone permissions → All files access.")
}

fun humanSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}
