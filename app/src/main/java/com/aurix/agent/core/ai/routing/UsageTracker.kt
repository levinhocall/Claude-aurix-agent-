package com.aurix.agent.core.ai.routing

import android.content.Context
import com.aurix.agent.core.ai.AiError
import com.aurix.agent.core.ai.AiErrorType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Daily token counter + optional daily budget (0 = unlimited). Not secret, so plain prefs. */
@Singleton
class UsageTracker @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("aurix_usage", Context.MODE_PRIVATE)
    private fun dayKey() = "tokens_" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    @Synchronized fun today(): Long = prefs.getLong(dayKey(), 0L)
    @Synchronized fun add(tokens: Int) { prefs.edit().putLong(dayKey(), today() + tokens).apply() }
    fun budget(): Long = prefs.getLong("daily_budget", 0L)
    fun setBudget(v: Long) { prefs.edit().putLong("daily_budget", v.coerceAtLeast(0)).apply() }

    fun checkBudget() {
        val b = budget()
        if (b > 0 && today() >= b) throw AiError(AiErrorType.BUDGET_EXCEEDED, "Daily token budget reached ($b). Raise it in Settings or resume tomorrow.")
    }
}
