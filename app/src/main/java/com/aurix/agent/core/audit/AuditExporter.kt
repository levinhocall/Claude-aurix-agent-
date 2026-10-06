package com.aurix.agent.core.audit

import android.content.Context
import com.aurix.agent.core.mission.MissionDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Writes a CSV audit trail (tool executions, approvals, questions, mission lifecycle). Created only when the user asks; API keys are never part of it. */
@Singleton
class AuditExporter @Inject constructor(@ApplicationContext private val ctx: Context, private val dao: MissionDao) {
    suspend fun export(): File = withContext(Dispatchers.IO) {
        val rows = ArrayList<Pair<Long, List<String>>>()
        dao.getAllToolCalls().forEach { c ->
            rows += c.ts to listOf(c.missionId.take(8), "tool", c.tool, if (c.ok) "ok" else c.errorType.orEmpty(), c.input.take(300), "${c.durationMs}ms")
        }
        dao.getAuditEvents().forEach { e -> rows += e.ts to listOf(e.missionId.take(8), "event", e.type, "", e.detail.take(300), "") }
        rows.sortBy { it.first }
        val dir = File(ctx.cacheDir, "audit").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 24 * 3600 * 1000L) it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
        val f = File(dir, "aurix-audit-${stamp.format(Date())}.csv")
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        f.bufferedWriter().use { w ->
            w.appendLine("time,mission,kind,item,result,detail,extra")
            rows.takeLast(5000).forEach { (ts, cols) -> w.appendLine((listOf(iso.format(Date(ts))) + cols).joinToString(",") { csv(it) }) }
        }
        f
    }

    private fun csv(s: String) = "\"" + s.replace("\"", "\"\"").replace("\n", " ") + "\""
}
