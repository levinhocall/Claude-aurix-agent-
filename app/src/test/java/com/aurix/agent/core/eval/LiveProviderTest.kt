package com.aurix.agent.core.eval

import com.aurix.agent.core.ai.AiMessage
import com.aurix.agent.core.ai.AiRequest
import com.aurix.agent.core.ai.providers.openai.OpenAiCompatibleProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * LIVE checks against a real provider. They are skipped (not failed, not faked) unless the environment provides
 * AURIX_LIVE_BASE_URL, AURIX_LIVE_API_KEY and AURIX_LIVE_MODEL. CI has no credentials, so these never run there.
 */
class LiveProviderTest {
    private fun env(n: String) = System.getenv(n)?.takeIf { it.isNotBlank() }

    @Test fun liveCompletionReturnsText() = runBlocking {
        val url = env("AURIX_LIVE_BASE_URL"); val key = env("AURIX_LIVE_API_KEY"); val model = env("AURIX_LIVE_MODEL")
        assumeTrue("live credentials not provided; skipping", url != null && key != null && model != null)
        val p = OpenAiCompatibleProvider("live", url!!, OkHttpClient())
        val t0 = System.currentTimeMillis()
        val r = p.complete(AiRequest(listOf(AiMessage("user", "Reply with the single word OK.")), model = model!!, maxTokens = 16, purpose = "test"), key!!)
        println("LIVE latency=${System.currentTimeMillis() - t0}ms model=${r.model} tokens=${r.usage.total}")
        assertTrue(r.text.isNotBlank())
    }
}
