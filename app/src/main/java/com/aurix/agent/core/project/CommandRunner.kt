package com.aurix.agent.core.project

import java.io.File
import java.util.concurrent.TimeUnit

/** Runs an already-approved argv (no shell). Reports real exit code and real output; a missing toolchain is reported as such, never faked. */
class CommandRunner(private val timeoutMs: Long = 10 * 60_000L) {
    data class Result(val exit: Int, val output: String, val timedOut: Boolean, val notFound: Boolean)

    fun findExecutable(name: String, cwd: File): File? {
        if (name.contains('/')) return File(cwd, name).takeIf { it.isFile && it.canExecute() }
        if (name == "gradlew") File(cwd, name).takeIf { it.isFile && it.canExecute() }?.let { return it }
        val path = System.getenv("PATH").orEmpty().split(':').filter { it.isNotBlank() }
        return path.map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }
    }

    fun run(argv: List<String>, cwd: File, logFile: File? = null): Result {
        val exe = findExecutable(argv[0], cwd) ?: return Result(127, "", false, true)
        val pb = ProcessBuilder(listOf(exe.path) + argv.drop(1)).directory(cwd).redirectErrorStream(true)
        val keep = setOf("PATH", "JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "LANG")
        pb.environment().keys.retainAll(keep) // nothing else (tokens, keys, proxy credentials) is handed to project code
        pb.environment()["CI"] = "true"
        val p = pb.start()
        val sb = StringBuilder()
        val reader = Thread {
            try { p.inputStream.bufferedReader().use { r -> val buf = CharArray(4096); while (true) { val n = r.read(buf); if (n < 0) break; synchronized(sb) { sb.append(buf, 0, n); if (sb.length > 400_000) sb.delete(0, sb.length - 300_000) } } } } catch (e: Exception) { }
        }.apply { isDaemon = true; start() }
        val finished = try { p.waitFor(timeoutMs, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { p.destroyForcibly(); throw e }
        if (!finished) p.destroyForcibly()
        reader.join(2000)
        val text = synchronized(sb) { sb.toString() }
        try { logFile?.let { it.parentFile?.mkdirs(); it.writeText(text) } } catch (e: Exception) { }
        return Result(if (finished) p.exitValue() else -1, text, !finished, false)
    }
}
