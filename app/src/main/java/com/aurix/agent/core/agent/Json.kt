package com.aurix.agent.core.agent

import org.json.JSONException
import org.json.JSONObject

/** Models often wrap JSON in prose or code fences; take the outermost {...}. */
fun extractJson(text: String): JSONObject? {
    val s = text.indexOf('{')
    val e = text.lastIndexOf('}')
    if (s < 0 || e <= s) return null
    return try { JSONObject(text.substring(s, e + 1)) } catch (_: JSONException) { null }
}

fun parseStepList(raw: String, max: Int = 12): List<String>? {
    val arr = extractJson(raw)?.optJSONArray("steps") ?: return null
    val list = (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }.take(max)
    return list.ifEmpty { null }
}
