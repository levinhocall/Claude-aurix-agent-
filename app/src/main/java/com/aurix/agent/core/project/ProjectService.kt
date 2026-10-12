package com.aurix.agent.core.project

import com.aurix.agent.core.durability.Hashing
import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolException
import com.aurix.agent.core.tools.resolveSafe
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ProjectGuard {
    private val secretName = Regex("""(?i)^(local\.properties|keystore\.properties|\.env(\..*)?|id_rsa.*|id_ed25519.*|.*\.(jks|keystore|pem|p12|pfx|key)|secrets?\..*|.*credentials.*\.json|\.netrc|\.npmrc|\.pypirc)$""")
    val skipDirs = setOf(".git", ".gradle", "build", "node_modules", ".idea", "__pycache__", ".venv", "target", "dist", ".aurix")
    private val sensitivePath = Regex("""(?i)(androidmanifest\.xml|network_security_config|\.github/|\.gradle(\.kts)?$|proguard|/security/|/auth/|/crypto/|gradle\.properties|libs\.versions\.toml)""")

    fun isSecret(rel: String): Boolean {
        val parts = rel.replace('\\', '/').split('/')
        return parts.any { it == ".ssh" || it == ".gnupg" } || secretName.matches(parts.last())
    }
    fun inSkippedDir(rel: String): Boolean = rel.replace('\\', '/').split('/').dropLast(1).any { it in skipDirs }
    fun isSensitive(rel: String): Boolean = sensitivePath.containsMatchIn(rel.replace('\\', '/'))
    fun looksBinary(bytes: ByteArray): Boolean = bytes.take(4096).any { it == 0.toByte() }

    private val forbiddenRoots = listOf("/proc", "/sys", "/dev", "/system", "/etc", "/vendor", "/data/data", "/data/user")
    fun rootAllowed(f: File, forbidden: List<File>, allowedInside: List<File>): Boolean {
        val p = f.canonicalPath
        if (f.parentFile == null || p == "/data") return false
        if (forbiddenRoots.any { p == it || p.startsWith("$it/") } && allowedInside.none { p.startsWith(it.canonicalPath) }) return false
        val inForbidden = forbidden.any { p == it.canonicalPath || p.startsWith(it.canonicalPath + File.separator) }
        val inAllowed = allowedInside.any { p == it.canonicalPath || p.startsWith(it.canonicalPath + File.separator) }
        return !inForbidden || inAllowed
    }
}

/** File operations for the coding workflow. Everything is rooted in one opened project directory; edits are snapshotted, verified by read-back and capped. */
class ProjectService(
    private val state: File,
    private val forbidden: List<File> = emptyList(),
    private val allowedInside: List<File> = emptyList(),
) {
    companion object {
        const val MAX_FILES_CHANGED = 12
        const val MAX_EDITS = 40
        const val MAX_READ_BYTES = 200_000L
        const val MAX_TREE_ENTRIES = 300
    }

    private fun bad(msg: String): Nothing = throw ToolException(ToolErrorType.INVALID_INPUT, msg)
    private fun rootFile() = File(state, "root.txt")
    private fun manifestFile() = File(state, "manifest.json")
    private fun readsFile() = File(state, "reads.json")
    private fun dirtyFile() = File(state, "dirty.json")

    private fun loadObj(f: File): JSONObject = try { if (f.isFile) JSONObject(f.readText()) else JSONObject() } catch (e: Exception) { JSONObject() }
    private fun saveObj(f: File, o: JSONObject) { state.mkdirs(); f.writeText(o.toString()) }

    fun open(path: String): File {
        val f = try { File(path.trim()).canonicalFile } catch (e: Exception) { bad("Invalid path") }
        if (!f.isDirectory) bad("Not a directory: $path")
        if (!ProjectGuard.rootAllowed(f, forbidden, allowedInside)) bad("This location cannot be used as a project (system or app-private data).")
        state.mkdirs()
        rootFile().writeText(f.path)
        return f
    }

    fun root(): File {
        val p = rootFile().takeIf { it.isFile }?.readText()?.trim().orEmpty()
        val f = File(p)
        if (p.isEmpty() || !f.isDirectory) bad("No project is open. Call PROJECT_OPEN first.")
        return f
    }

    fun setDirty(files: Collection<String>) = saveObj(dirtyFile(), JSONObject().put("files", JSONArray(files.take(200))))
    fun dirty(): Set<String> = loadObj(dirtyFile()).optJSONArray("files")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()

    private fun rel(f: File): String = f.relativeTo(root().canonicalFile).path.replace('\\', '/')

    fun detect(): String {
        val r = root()
        val marks = listOf("gradlew" to "Gradle wrapper", "build.gradle.kts" to "Gradle (Kotlin DSL)", "build.gradle" to "Gradle", "pom.xml" to "Maven", "package.json" to "npm", "pyproject.toml" to "Python", "requirements.txt" to "Python", "go.mod" to "Go", "Cargo.toml" to "Rust", "Makefile" to "Make", ".git" to "Git repository")
        return marks.filter { File(r, it.first).exists() }.joinToString(", ") { it.second }.ifEmpty { "no known build system files found" }
    }

    fun tree(relPath: String, depth: Int): String {
        val base = if (relPath.isBlank() || relPath == ".") root().canonicalFile else resolveSafe(root(), relPath)
        if (!base.isDirectory) bad("Not a directory")
        val out = ArrayList<String>()
        val maxDepth = depth.coerceIn(1, 4)
        fun walk(d: File, level: Int) {
            if (out.size >= MAX_TREE_ENTRIES) return
            val kids = d.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: return
            for (k in kids) {
                if (out.size >= MAX_TREE_ENTRIES) { out += "… (truncated at $MAX_TREE_ENTRIES entries)"; return }
                if (k.isDirectory && k.name in ProjectGuard.skipDirs) continue
                val indent = "  ".repeat(level)
                if (k.isDirectory) { out += "$indent${k.name}/"; if (level + 1 < maxDepth) walk(k, level + 1) }
                else out += "$indent${k.name} (${k.length()}B)"
            }
        }
        walk(base, 0)
        return out.joinToString("\n").ifEmpty { "(empty)" }
    }

    fun read(relPath: String, from: Int, lines: Int): String {
        val f = resolveSafe(root(), relPath)
        val r = rel(f)
        if (ProjectGuard.isSecret(r)) bad("Refusing to read a credentials/secret file ($r).")
        if (!f.isFile) bad("File not found: $r")
        if (f.length() > MAX_READ_BYTES) bad("File too large to read whole (${f.length()} bytes); use PROJECT_SEARCH.")
        val bytes = f.readBytes()
        if (ProjectGuard.looksBinary(bytes)) bad("Binary file: $r")
        val text = String(bytes)
        val reads = loadObj(readsFile()); reads.put(r, Hashing.sha256Hex(text)); saveObj(readsFile(), reads)
        val all = text.lines()
        val start = (from - 1).coerceIn(0, maxOf(0, all.size - 1))
        val slice = all.drop(start).take(lines.coerceIn(1, 400))
        return slice.mapIndexed { i, l -> "${start + i + 1}| $l" }.joinToString("\n") + (if (start + slice.size < all.size) "\n… (${all.size - start - slice.size} more lines)" else "")
    }

    fun search(query: String, max: Int = 50): String {
        if (query.length < 2) bad("Query too short")
        val hits = ArrayList<String>(); var scanned = 0
        fun walk(d: File) {
            if (hits.size >= max || scanned > 5000) return
            for (k in d.listFiles().orEmpty()) {
                if (hits.size >= max || scanned > 5000) return
                if (k.isDirectory) { if (k.name !in ProjectGuard.skipDirs) walk(k); continue }
                val r = rel(k)
                if (ProjectGuard.isSecret(r) || k.length() > 1_000_000) continue
                scanned++
                val b = try { k.readBytes() } catch (e: Exception) { continue }
                if (ProjectGuard.looksBinary(b)) continue
                String(b).lineSequence().forEachIndexed { i, l -> if (hits.size < max && l.contains(query, ignoreCase = true)) hits += "$r:${i + 1}: ${l.trim().take(160)}" }
            }
        }
        walk(root().canonicalFile)
        return if (hits.isEmpty()) "No matches ($scanned files scanned)" else hits.joinToString("\n")
    }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, ".aurix.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    fun edit(relPath: String, old: String, new: String, create: Boolean, replaceAll: Boolean, allowDirty: Boolean): String {
        val f = resolveSafe(root(), relPath)
        val r = rel(f)
        if (ProjectGuard.isSecret(r)) bad("Refusing to modify a credentials/secret file ($r).")
        if (ProjectGuard.inSkippedDir(r)) bad("Refusing to modify generated/VCS directories ($r).")
        val manifest = loadObj(manifestFile())
        val files = manifest.optJSONObject("files") ?: JSONObject()
        val edits = manifest.optInt("edits", 0)
        if (edits >= MAX_EDITS) bad("Edit budget reached ($MAX_EDITS edits). Stop and report what remains.")
        if (!files.has(r) && files.length() >= MAX_FILES_CHANGED) bad("Change budget reached ($MAX_FILES_CHANGED files). Keep the change small; stop and report.")

        val existed = f.isFile
        val before: String? = if (existed) {
            if (f.length() > MAX_READ_BYTES) bad("File too large to edit safely.")
            val b = f.readBytes()
            if (ProjectGuard.looksBinary(b)) bad("Binary file: $r")
            String(b)
        } else null
        val after: String
        if (create) {
            if (existed) bad("File already exists: $r (use an edit instead).")
            if (old.isNotEmpty()) bad("When creating a file, `old` must be empty.")
            after = new
        } else {
            if (before == null) bad("File not found: $r")
            if (old.isEmpty()) bad("`old` must not be empty.")
            val readSha = loadObj(readsFile()).optString(r)
            if (readSha.isEmpty()) bad("Read $r with PROJECT_READ before editing it.")
            if (readSha != Hashing.sha256Hex(before)) bad("$r changed since you read it (maybe the user edited it). Read it again.")
            if (r in dirty() && !allowDirty) bad("$r has UNCOMMITTED user changes. Do not overwrite them silently: ask the user with ASK_USER, then retry with allow_dirty=true.")
            val count = before.split(old).size - 1
            if (count == 0) bad("`old` was not found in $r.")
            if (count > 1 && !replaceAll) bad("`old` matches $count times in $r; include more context or set replace_all=true.")
            after = before.replace(old, new)
        }
        // snapshot the ORIGINAL once, so the diff and any revert are against what the user had
        if (!files.has(r)) {
            val snaps = File(state, "snaps"); snaps.mkdirs()
            val id = "s${files.length() + 1}"
            if (before != null) File(snaps, id).writeText(before)
            files.put(r, JSONObject().put("snap", id).put("existed", existed))
        }
        if (create) f.parentFile?.mkdirs()
        writeAtomic(f, after)
        val back = String(f.readBytes())
        if (back != after) bad("Read-back verification FAILED for $r; the file on disk differs from the intended content.")
        manifest.put("files", files).put("edits", edits + 1)
        saveObj(manifestFile(), manifest)
        val reads = loadObj(readsFile()); reads.put(r, Hashing.sha256Hex(after)); saveObj(readsFile(), reads)
        val added = after.lines().size - (before?.lines()?.size ?: 0)
        return "EDITED $r READBACK=OK (${if (added >= 0) "+" else ""}$added lines net; ${files.length()} file(s) changed, ${edits + 1}/$MAX_EDITS edits)"
    }

    fun revert(relPath: String): String {
        val f = resolveSafe(root(), relPath)
        val r = rel(f)
        val manifest = loadObj(manifestFile())
        val entry = manifest.optJSONObject("files")?.optJSONObject(r) ?: bad("$r was not changed by this mission.")
        if (!entry.optBoolean("existed")) bad("$r was created by this mission; it is not deleted automatically. Ask the user.")
        val snap = File(File(state, "snaps"), entry.getString("snap"))
        if (!snap.isFile) bad("Snapshot missing for $r.")
        writeAtomic(f, snap.readText())
        val reads = loadObj(readsFile()); reads.put(r, Hashing.sha256Hex(snap.readText())); saveObj(readsFile(), reads)
        return "REVERTED $r to its state before this mission"
    }

    fun changedFiles(): List<String> = loadObj(manifestFile()).optJSONObject("files")?.keys()?.asSequence()?.toList().orEmpty()

    fun diff(): String {
        val files = loadObj(manifestFile()).optJSONObject("files") ?: return "No files were changed by this mission."
        val out = StringBuilder(); var changed = 0
        for (r in files.keys().asSequence().toList()) {
            val e = files.getJSONObject(r)
            val snapFile = File(File(state, "snaps"), e.getString("snap"))
            val old = if (e.optBoolean("existed") && snapFile.isFile) snapFile.readText() else null
            val cur = File(root(), r).takeIf { it.isFile }?.readText()
            val d = UnifiedDiff.diff(r, old, cur)
            if (d.isNotEmpty()) { out.append(d); changed++ }
        }
        return if (changed == 0) "Files were edited but now match their originals." else "(diff against the files as they were before this mission; $changed file(s))\n$out"
    }
}
