package com.aurix.agent.core.ai

import com.aurix.agent.core.ai.providers.anthropic.anthropicMessages
import com.aurix.agent.core.ai.providers.anthropic.anthropicTools
import com.aurix.agent.core.ai.providers.anthropic.parseAnthropicContent
import com.aurix.agent.core.ai.providers.openai.openAiMessages
import com.aurix.agent.core.ai.providers.openai.openAiTools
import com.aurix.agent.core.ai.providers.openai.parseOpenAiMessage
import com.aurix.agent.core.ai.providers.openai.parseToolArguments
import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.Tool
import com.aurix.agent.core.tools.ToolContext
import com.aurix.agent.core.tools.ToolResult
import com.aurix.agent.core.tools.ToolSchemas
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeToolsTest {
    private fun tool(schema: String, req: List<String> = emptyList()) = object : Tool {
        override val name = "T"; override val description = "desc"; override val inputSchema = schema; override val outputSchema = ""
        override val required = req; override val risk = RiskLevel.LOW; override val timeoutMs = 1L
        override suspend fun execute(input: JSONObject, ctx: ToolContext) = ToolResult.ok("")
    }
    private val calls = listOf(AiToolCall("c1", "SET_ALARM", JSONObject("""{"hour":7}""")), AiToolCall("c2", "SET_ALARM", JSONObject("""{"hour":8}""")))

    @Test fun schemaFromExample() {
        val p = ToolSchemas.parameters(tool("""{"hour":7,"minute":30,"label":"optional","on":true,"x":1.5}""", listOf("hour", "minute")))
        val props = p.getJSONObject("properties")
        assertEquals("integer", props.getJSONObject("hour").getString("type"))
        assertEquals("string", props.getJSONObject("label").getString("type"))
        assertEquals("boolean", props.getJSONObject("on").getString("type"))
        assertEquals("number", props.getJSONObject("x").getString("type"))
        assertEquals(2, p.getJSONArray("required").length())
    }

    @Test fun schemaFromAlternativeForms() {
        val p = ToolSchemas.parameters(tool("""{"id":3} | {"text":"Play"} | {"x":540,"y":1200} (+ "long":true)"""))
        val props = p.getJSONObject("properties")
        listOf("id", "text", "x", "y").forEach { assertTrue(props.has(it)) }
        assertTrue(ToolSchemas.spec(tool("""{"id":3} | {"text":"Play"}""")).description.contains("Input forms"))
        assertEquals(0, ToolSchemas.parameters(tool("{}")).getJSONObject("properties").length())
    }

    @Test fun openAiRoundTrip() {
        val msgs = openAiMessages(listOf(AiMessage("system", "s"), AiMessage("user", "u"), AiMessage("assistant", "", toolCalls = calls), AiMessage("tool", "OK", toolCallId = "c1")))
        assertEquals(4, msgs.length())
        val a = msgs.getJSONObject(2)
        assertTrue(a.isNull("content"))
        assertEquals("c2", a.getJSONArray("tool_calls").getJSONObject(1).getString("id"))
        assertEquals("""{"hour":8}""", a.getJSONArray("tool_calls").getJSONObject(1).getJSONObject("function").getString("arguments"))
        assertEquals("c1", msgs.getJSONObject(3).getString("tool_call_id"))
        val tools = openAiTools(listOf(AiTool("N", "d", JSONObject("""{"type":"object","properties":{}}"""))))
        assertEquals("function", tools.getJSONObject(0).getString("type"))
    }

    @Test fun openAiParsing() {
        val m = JSONObject("""{"content":null,"tool_calls":[{"id":"x","type":"function","function":{"name":"SET_ALARM","arguments":"{\"hour\":6}"}},{"function":{"name":"B","arguments":"not json"}}]}""")
        val (text, c) = parseOpenAiMessage(m)
        assertEquals("", text)
        assertEquals(2, c.size)
        assertEquals(6, c[0].arguments.getInt("hour"))
        assertTrue(c[1].arguments.has("__invalid_arguments"))
        assertEquals("call_1", c[1].id)
        assertEquals(0, parseToolArguments("").length())
    }

    @Test fun anthropicMergesToolResults() {
        val msgs = anthropicMessages(listOf(
            AiMessage("system", "s"), AiMessage("user", "u"),
            AiMessage("assistant", "doing it", toolCalls = calls),
            AiMessage("tool", "r1", toolCallId = "c1"), AiMessage("tool", "r2", toolCallId = "c2"),
        ))
        assertEquals(3, msgs.length())
        val asst = msgs.getJSONObject(1).getJSONArray("content")
        assertEquals("text", asst.getJSONObject(0).getString("type"))
        assertEquals("tool_use", asst.getJSONObject(1).getString("type"))
        val results = msgs.getJSONObject(2)
        assertEquals("user", results.getString("role"))
        assertEquals(2, results.getJSONArray("content").length())
        assertEquals("c2", results.getJSONArray("content").getJSONObject(1).getString("tool_use_id"))
        assertEquals("input_schema", anthropicTools(listOf(AiTool("N", "d", JSONObject("{}")))).getJSONObject(0).keys().asSequence().first { it == "input_schema" })
    }

    @Test fun anthropicParsing() {
        val (t, c) = parseAnthropicContent(JSONArray("""[{"type":"text","text":"ok "},{"type":"tool_use","id":"u1","name":"WEATHER","input":{"city":"Delhi"}}]"""))
        assertEquals("ok ", t)
        assertEquals("Delhi", c.single().arguments.getString("city"))
    }
}
