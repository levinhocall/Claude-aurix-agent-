package com.aurix.agent.core.memory

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aurix.agent.core.approval.AgentSettings
import com.aurix.agent.core.mission.MissionDao
import com.aurix.agent.core.tools.device.foldText
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val kind: String,
    val createdAt: Long,
    val lastUsedAt: Long,
    val uses: Int = 0,
)

/** Keyword-overlap ranking (accent-insensitive); ties broken by how often a memory was useful. */
fun rankMemories(items: List<MemoryEntity>, query: String): List<MemoryEntity> {
    val q = foldText(query).split(' ').filter { it.length > 2 }.toSet()
    if (q.isEmpty()) return emptyList()
    return items.map { m -> m to foldText(m.text).split(' ').toSet().let { w -> q.count { it in w } } }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<MemoryEntity, Int>> { it.second }.thenByDescending { it.first.uses })
        .map { it.first }
}

/** User-controlled memory: stored only when enabled (emergency contacts are always allowed), viewable and deletable in Settings. */
@Singleton
class MemoryRepository @Inject constructor(private val dao: MissionDao, private val settings: AgentSettings) {
    fun enabled() = settings.memoryEnabled()
    fun observe() = dao.observeMemories()
    suspend fun all(): List<MemoryEntity> = dao.allMemories()
    suspend fun ofKind(kind: String): List<MemoryEntity> = dao.memoriesOfKind(kind)
    suspend fun delete(id: Long) = dao.deleteMemory(id)
    suspend fun clear() = dao.clearMemories()

    suspend fun save(text: String, kind: String = "fact"): Long? {
        if (!enabled() && kind != "emergency") return null
        val t = text.trim().take(300)
        if (t.length < 3) return null
        val dup = dao.allMemories().firstOrNull { foldText(it.text) == foldText(t) && it.kind == kind }
        if (dup != null) { dao.touchMemories(listOf(dup.id), System.currentTimeMillis()); return dup.id }
        val now = System.currentTimeMillis()
        return dao.insertMemory(MemoryEntity(text = t, kind = kind, createdAt = now, lastUsedAt = now))
    }

    suspend fun search(query: String, limit: Int = 5): List<MemoryEntity> =
        if (!enabled()) emptyList() else rankMemories(dao.allMemories().filter { it.kind != "emergency" }, query).take(limit)

    /** A few memories relevant to this request, for the prompt (never emergency contacts). */
    suspend fun relevant(query: String, limit: Int = 3): List<String> {
        val hits = search(query, limit)
        if (hits.isNotEmpty()) dao.touchMemories(hits.map { it.id }, System.currentTimeMillis())
        return hits.map { it.text }
    }
}
