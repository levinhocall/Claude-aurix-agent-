package com.aurix.agent.core.tools

class ToolRegistry(tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }
    fun get(name: String): Tool? = byName[name]
    fun all(): List<Tool> = byName.values.toList()
    fun names(): String = byName.keys.joinToString(", ")

    private fun line(t: Tool) = "${t.name} ${t.inputSchema} - ${t.description.substringBefore(". ").take(110)}"

    /** Compact catalog (one line per tool). `only` limits it to the tools a step actually needs, which saves tokens on every model call. */
    fun catalog(only: Set<String>? = null): String = all().filter { only == null || it.name in only }.joinToString("\n") { line(it) }

    /** Native function definitions for the given tools (null = all). */
    fun specs(only: Set<String>? = null): List<com.aurix.agent.core.ai.AiTool> = all().filter { only == null || it.name in only }.map { ToolSchemas.spec(it) }

    fun others(shown: Set<String>): String = byName.keys.filter { it !in shown }.joinToString(", ")

    /** Hinted tools plus their siblings (SCREEN_*, STORAGE_*), so multi-tool flows have everything they need. */
    fun expand(names: Collection<String>): Set<String> {
        val valid = names.filter { byName.containsKey(it) }.toSet()
        val prefixes = valid.map { it.substringBefore('_') }.filter { it == "SCREEN" || it == "STORAGE" }.toSet()
        return valid + byName.keys.filter { it.substringBefore('_') in prefixes }
    }
}
