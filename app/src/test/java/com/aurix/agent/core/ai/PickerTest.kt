package com.aurix.agent.core.ai

import com.aurix.agent.core.ai.routing.KeyDetector
import com.aurix.agent.core.ai.routing.ModelPicker
import com.aurix.agent.core.ai.routing.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerTest {
    @Test fun detectsProviders() {
        assertEquals("Anthropic", KeyDetector.detect("sk-ant-api03-abc")?.name)
        assertEquals("OpenRouter", KeyDetector.detect("sk-or-v1-abc")?.name)
        assertEquals("Gemini", KeyDetector.detect("AIzaSyABC")?.name)
        assertEquals("Groq", KeyDetector.detect("gsk_abc")?.name)
        assertEquals("OpenAI", KeyDetector.detect("sk-proj-abc")?.name)
        assertNull(KeyDetector.detect("something-else"))
        assertEquals("Custom", KeyDetector.detect("anything", "https://my.host/v1")?.name)
    }

    @Test fun anthropicPrefersFableThenOpusThenSonnet() {
        val ids = listOf("claude-haiku-4-5-20251001", "claude-sonnet-4-5-20250929", "claude-opus-4-1-20250805")
        val p = ModelPicker.pick(ProviderType.ANTHROPIC, ids, "d-strong", "d-fast")
        assertEquals("claude-opus-4-1-20250805", p.strong)
        assertEquals("claude-haiku-4-5-20251001", p.fast)
        val withFable = ModelPicker.pick(ProviderType.ANTHROPIC, ids + "claude-fable-5-1", "d", "d")
        assertEquals("claude-fable-5-1", withFable.strong)
    }

    @Test fun openAiPicksNewestStableAndMiniForFast() {
        val ids = listOf("gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-5", "gpt-5-mini", "text-embedding-3-large", "whisper-1", "gpt-4o-audio-preview", "o1-pro", "gpt-5-2025-08-07")
        val p = ModelPicker.pick(ProviderType.OPENAI_COMPATIBLE, ids, "d", "d")
        assertEquals("gpt-5", p.strong)
        assertEquals("gpt-5-mini", p.fast)
    }

    @Test fun geminiPrefersProAndFlash() {
        val ids = listOf("models/gemini-2.5-pro", "models/gemini-2.5-flash", "models/gemini-2.5-flash-lite", "models/text-embedding-004")
        val p = ModelPicker.pick(ProviderType.OPENAI_COMPATIBLE, ids, "d", "d")
        assertEquals("gemini-2.5-pro", p.strong)
        assertEquals("gemini-2.5-flash", p.fast)
    }

    @Test fun emptyListFallsBackToDefaults() {
        val p = ModelPicker.pick(ProviderType.OPENAI_COMPATIBLE, emptyList(), "S", "F")
        assertEquals("S", p.strong); assertEquals("F", p.fast)
    }
}

class GroqPickerTest {
    @Test fun groqNeverPicksGptOss() {
        val ids = listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile", "llama-3.1-8b-instant", "qwen/qwen3-32b", "groq/compound")
        val p = ModelPicker.pick(ProviderType.OPENAI_COMPATIBLE, ids, "d", "d", "https://api.groq.com/openai/v1")
        assertEquals("llama-3.3-70b-versatile", p.strong)
        assertEquals("llama-3.1-8b-instant", p.fast)
    }

    @Test fun genericExcludesToolHijackers() {
        val p = ModelPicker.pick(ProviderType.OPENAI_COMPATIBLE, listOf("openai/gpt-oss-120b", "some-model-2"), "d", "d")
        assertEquals("some-model-2", p.strong)
    }
}

class OpenRouterPickerTest {
    @Test fun skipsBatchVariantsAndPicksMainLineModels() {
        val ids = listOf(
            "mistralai/mistral-small-2603:batch", "mistralai/mistral-small-2603", "anthropic/claude-sonnet-4.5", "anthropic/claude-haiku-4.5",
            "openai/gpt-5", "openai/gpt-5-mini", "qwen/qwen3.8-27b", "openai/gpt-oss-120b:free",
        )
        val p = ModelPicker.pick(ProviderType.OPENAI_COMPATIBLE, ids, "d", "d", "https://openrouter.ai/api/v1")
        assertEquals("anthropic/claude-sonnet-4.5", p.strong)
        assertEquals("anthropic/claude-haiku-4.5", p.fast)
    }

    @Test fun batchErrorIsModelSpecific() {
        val e = mapHttpError(404, null, """{"error":{"message":"mistralai/mistral-small-2603:batch cannot be used with the chat/completions endpoint (adapter MistralBatchAdapter)."}}""", "k")
        assertEquals(AiErrorType.MODEL_ERROR, e.type)
        assertTrue(e.modelSpecific)
    }
}
