package com.aurix.agent.core.proactive

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aurix.agent.core.agent.MissionManager
import com.aurix.agent.core.approval.AgentSettings
import com.aurix.agent.core.security.SecureSettings
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class TriggerEvent(val label: String, val paramHint: String?) {
    CHARGER_ON("Charger connected", null),
    CHARGER_OFF("Charger removed", null),
    BLUETOOTH("Bluetooth connects", "device name"),
    BATTERY_LOW("Battery low", null),
    TIME("Every day at", "HH:mm"),
}

/** "When [event] (matching [param]) → run [command] as a normal mission." Risky tools still ask for approval, as for any mission. */
data class TriggerRule(
    val id: String, val name: String, val event: TriggerEvent, val param: String, val command: String,
    val enabled: Boolean = true, val lastFired: Long = 0L, val fired: Int = 0, val cooldownMin: Int = 10,
)

object TriggerLogic {
    const val MAX_RULES = 20
    private val hhmm = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")

    /** Null when the rule is acceptable, else a short reason. */
    fun validate(event: TriggerEvent, param: String, command: String): String? = when {
        command.isBlank() -> "Command is empty"
        command.length > 300 -> "Command too long"
        event == TriggerEvent.BLUETOOTH && param.isBlank() -> "Enter the Bluetooth device name"
        event == TriggerEvent.TIME && !hhmm.matches(param.trim()) -> "Time must be HH:mm (e.g. 07:30)"
        else -> null
    }

    fun matches(rule: TriggerRule, event: TriggerEvent, param: String): Boolean =
        rule.enabled && rule.event == event && when (event) {
            TriggerEvent.BLUETOOTH -> matchesCar(param, rule.param)
            else -> true
        }

    fun cooldownOk(rule: TriggerRule, now: Long): Boolean = rule.lastFired == 0L || now - rule.lastFired >= rule.cooldownMin * 60_000L || now < rule.lastFired

    /** Next wall-clock moment (strictly after [now]) at HH:mm, or null for a bad time. */
    fun nextTime(param: String, now: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
        val m = hhmm.find(param.trim()) ?: return null
        val c = Calendar.getInstance(zone).apply {
            timeInMillis = now; set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt()); set(Calendar.MINUTE, m.groupValues[2].toInt()); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    fun describe(r: TriggerRule): String = when (r.event) {
        TriggerEvent.BLUETOOTH -> "When Bluetooth \"${r.param}\" connects"
        TriggerEvent.TIME -> "Every day at ${r.param}"
        else -> r.event.label
    } + " → ${r.command}"

    fun toJson(rules: List<TriggerRule>): String = JSONArray().also { a ->
        rules.forEach { r ->
            a.put(JSONObject().put("id", r.id).put("name", r.name).put("event", r.event.name).put("param", r.param).put("command", r.command)
                .put("enabled", r.enabled).put("lastFired", r.lastFired).put("fired", r.fired).put("cooldownMin", r.cooldownMin))
        }
    }.toString()

    /** Tolerant: unknown events and malformed entries are skipped, never thrown. */
    fun fromJson(s: String?): List<TriggerRule> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull { i ->
                try {
                    val o = a.getJSONObject(i)
                    TriggerRule(
                        o.getString("id"), o.optString("name"), TriggerEvent.valueOf(o.getString("event")), o.optString("param"), o.getString("command"),
                        o.optBoolean("enabled", true), o.optLong("lastFired", 0L), o.optInt("fired", 0), o.optInt("cooldownMin", 10).coerceIn(0, 1440),
                    )
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) { emptyList() }
    }
}

@Singleton
class TriggerStore @Inject constructor(private val secure: SecureSettings) {
    private val key = "trigger_rules_v1"
    private val lock = Any()
    fun list(): List<TriggerRule> = synchronized(lock) { TriggerLogic.fromJson(secure.getString(key)) }
    private fun save(l: List<TriggerRule>) = secure.putString(key, TriggerLogic.toJson(l))

    /** Returns an error text, or null when added. */
    fun add(name: String, event: TriggerEvent, param: String, command: String): String? = synchronized(lock) {
        TriggerLogic.validate(event, param, command)?.let { return it }
        val l = list()
        if (l.size >= TriggerLogic.MAX_RULES) return "Maximum ${TriggerLogic.MAX_RULES} triggers"
        save(l + TriggerRule(UUID.randomUUID().toString(), name.trim().take(40).ifBlank { event.label }, event, param.trim().take(40), command.trim()))
        null
    }
    fun setEnabled(id: String, on: Boolean) = synchronized(lock) { save(list().map { if (it.id == id) it.copy(enabled = on) else it }) }
    fun delete(id: String) = synchronized(lock) { save(list().filterNot { it.id == id }) }
    fun markFired(id: String, now: Long) = synchronized(lock) { save(list().map { if (it.id == id) it.copy(lastFired = now, fired = it.fired + 1) else it }) }
}

@Singleton
class TriggerEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val store: TriggerStore,
    private val settings: AgentSettings,
    private val manager: MissionManager,
) {
    /** An event happened: start a mission for every enabled, matching rule that is not cooling down. Returns how many started. */
    suspend fun fire(event: TriggerEvent, param: String = ""): Int {
        if (!settings.proactive()) return 0
        val now = System.currentTimeMillis()
        var n = 0
        for (r in store.list()) if (TriggerLogic.matches(r, event, param) && TriggerLogic.cooldownOk(r, now)) { start(r, now); n++ }
        return n
    }

    suspend fun fireRule(id: String) {
        if (!settings.proactive()) return
        val now = System.currentTimeMillis()
        store.list().firstOrNull { it.id == id && it.enabled && it.event == TriggerEvent.TIME && TriggerLogic.cooldownOk(it, now) }?.let { start(it, now) }
    }

    private suspend fun start(r: TriggerRule, now: Long) {
        store.markFired(r.id, now)
        try { manager.create(r.command) } catch (e: Exception) { }
    }

    /** One pending job per enabled TIME rule at its next HH:mm; call after any change and at app start. */
    fun reschedule() {
        val wm = try { WorkManager.getInstance(ctx) } catch (e: Exception) { return }
        wm.cancelAllWorkByTag(TAG)
        val now = System.currentTimeMillis()
        for (r in store.list()) {
            if (!r.enabled || r.event != TriggerEvent.TIME) continue
            val at = TriggerLogic.nextTime(r.param, now) ?: continue
            wm.enqueueUniqueWork(
                "trigger_time_${r.id}", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TriggerTimeWorker>().setInitialDelay(at - now, TimeUnit.MILLISECONDS).addTag(TAG).setInputData(workDataOf("id" to r.id)).build(),
            )
        }
    }

    companion object { const val TAG = "aurix_trigger_time" }
}

@HiltWorker
class TriggerTimeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: TriggerEngine,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        try { engine.fireRule(id) } finally { engine.reschedule() }
        return Result.success()
    }
}
