package com.aurix.agent.core.tools

class ToolRegistry(tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }
    fun get(name: String): Tool? = byName[name]
    fun all(): List<Tool> = byName.values.toList()
    fun names(): String = byName.keys.joinToString(", ")

    /** Compact catalog shown to the model. */
    fun catalog(): String = all().joinToString("\n") { "- ${it.name} (risk ${it.risk}): ${it.description}\n  input: ${it.inputSchema}" }
}
