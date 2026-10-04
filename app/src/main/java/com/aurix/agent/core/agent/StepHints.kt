package com.aurix.agent.core.agent

/** The planner ends each step with the tools it needs, e.g. "Find jobs [WEB_SEARCH,WEB_BROWSER]". Only those tools are shown to the model for that step. */
private val HINT = Regex("\\s*\\[([A-Z][A-Z0-9_ ,]*)]\\s*$")

fun toolHints(title: String): Set<String> =
    HINT.find(title)?.groupValues?.get(1)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

fun cleanTitle(title: String): String = title.replace(HINT, "").trim()
