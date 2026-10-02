package com.aurix.agent.core.tools

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Per-mission sandbox directory inside the app's private storage. Tools can never leave it. */
@Singleton
class Workspace @Inject constructor(@ApplicationContext context: Context) {
    private val root = File(context.filesDir, "workspace")
    fun dir(missionId: String): File = File(root, missionId).also { it.mkdirs() }
    fun resolve(missionId: String, relPath: String): File = resolveSafe(dir(missionId), relPath)
    fun relative(missionId: String, file: File): String = file.relativeTo(dir(missionId).canonicalFile).path
}

fun resolveSafe(base: File, rel: String): File {
    val clean = rel.trim().replace('\\', '/').trimStart('/')
    if (clean.isEmpty() || clean.split('/').any { it == ".." }) throw ToolException(ToolErrorType.INVALID_INPUT, "Invalid path")
    val f = File(base, clean).canonicalFile
    val b = base.canonicalFile
    if (!f.path.startsWith(b.path + File.separator)) throw ToolException(ToolErrorType.INVALID_INPUT, "Path escapes workspace")
    return f
}
