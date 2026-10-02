package com.aurix.agent.core.ai.routing

import javax.inject.Inject
import javax.inject.Singleton

data class KeyState(
    var cooldownUntil: Long = 0,
    var disabled: Boolean = false,
    var requests: Long = 0,
    var errors: Long = 0,
    var tokens: Long = 0,
    var lastUsed: Long = 0,
    var lastError: String? = null,
)

/** Tracks per-key health. Keys are spread least-recently-used first; failing keys cool down and return automatically. */
@Singleton
class KeyPool(private val clock: () -> Long) {
    @Inject constructor() : this({ System.currentTimeMillis() })

    private val states = HashMap<String, KeyState>()
    private fun st(id: String) = states.getOrPut(id) { KeyState() }

    @Synchronized fun available(keys: List<KeyEntry>): List<KeyEntry> {
        val now = clock()
        return keys.filter { val s = st(it.id); !s.disabled && s.cooldownUntil <= now }.sortedBy { st(it.id).lastUsed }
    }

    /** Milliseconds until the first non-disabled key is usable again (0 = now), null if every key is disabled. */
    @Synchronized fun nextAvailableIn(keys: List<KeyEntry>): Long? {
        val now = clock()
        return keys.filter { !st(it.id).disabled }.minOfOrNull { (st(it.id).cooldownUntil - now).coerceAtLeast(0) }
    }

    @Synchronized fun markUsed(id: String) { val s = st(id); s.lastUsed = clock(); s.requests++ }
    @Synchronized fun success(id: String, tokens: Int) { val s = st(id); s.tokens += tokens; s.lastError = null }
    @Synchronized fun cooldown(id: String, ms: Long, reason: String) { val s = st(id); s.cooldownUntil = clock() + ms; s.errors++; s.lastError = reason }
    @Synchronized fun disable(id: String, reason: String) { val s = st(id); s.disabled = true; s.errors++; s.lastError = reason }
    @Synchronized fun reset(id: String) { val s = st(id); s.disabled = false; s.cooldownUntil = 0; s.lastError = null }

    @Synchronized fun status(id: String): String {
        val s = st(id)
        val now = clock()
        val state = when {
            s.disabled -> "DISABLED (${s.lastError ?: "error"}) — run Test after fixing"
            s.cooldownUntil > now -> "cooldown ${(s.cooldownUntil - now) / 1000}s (${s.lastError ?: ""})"
            else -> "ready"
        }
        return "$state · ${s.requests} req · ${s.errors} err · ${s.tokens} tok"
    }
}
