package com.aurix.agent.core.tools

import com.aurix.agent.core.ai.AiTool
import org.json.JSONArray
import org.json.JSONObject

/** Builds JSON-Schema function definitions from the tools' example-style inputSchema strings (e.g. {"hour":7,"label":"optional"}). */
object ToolSchemas {
    fun parameters(t: Tool): JSONObject {
        val props = JSONObject()
        Regex("\\{[^{}]*\\}").findAll(t.inputSchema).forEach { m ->
            val o = try { JSONObject(m.value) } catch (e: Exception) { return@forEach }
            o.keys().forEach { k -> if (!props.has(k)) props.put(k, propFor(o.opt(k))) }
        }
        val schema = JSONObject().put("type", "object").put("properties", props)
        val req = t.required.filter { props.has(it) }
        if (req.isNotEmpty()) schema.put("required", JSONArray(req))
        return schema
    }

    private fun propFor(v: Any?): JSONObject = when (v) {
        is Int, is Long, is java.math.BigInteger -> JSONObject().put("type", "integer")
        is Number -> JSONObject().put("type", "number")
        is Boolean -> JSONObject().put("type", "boolean")
        is JSONArray -> JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
        is JSONObject -> JSONObject().put("type", "object")
        is String -> JSONObject().put("type", "string").put("description", v.take(80))
        else -> JSONObject().put("type", "string")
    }

    fun spec(t: Tool): AiTool {
        val extra = if (t.inputSchema.contains('|') || t.inputSchema.contains("(+")) " Input forms: ${t.inputSchema.take(160)}" else ""
        return AiTool(t.name, t.description.take(300) + extra, parameters(t))
    }
}
