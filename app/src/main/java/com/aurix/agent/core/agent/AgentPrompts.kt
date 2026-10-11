package com.aurix.agent.core.agent

internal object AgentPrompts {
    fun plan(toolNames: String) = """Planner for an autonomous phone agent. Tools: $toolNames.
Use the FEWEST steps (1 step for simple phone actions, max 6). End each step with the tools it needs in brackets, e.g. "Find 5 jobs [WEB_SEARCH,WEB_BROWSER]". If a file/report is wanted, the last step writes it [FILE_WRITE]. A screen-control task (SCREEN_*) is ONE step.
JSON only: {"steps":["step [TOOLS]"]}"""

    fun step(catalog: String, others: String) = """Run ONE step of a plan. Each turn reply JSON only:
{"action":"tool","say":"<few words>","tool":"NAME","input":{...}}  or  {"action":"finish","status":"done" or "blocked","result":"<concise result; keep facts/URLs later steps need>"}
Tools:
$catalog${if (others.isNotEmpty()) "\nOther tools (names only): $others" else ""}
Rules: act on the user's intent directly and also do the obvious implied follow-up (never ask the user for confirmation yourself; the app handles approvals). No native function calling, JSON text only. Never invent facts, URLs, numbers. If a tool fails try another query/tool. Files: FILE_WRITE (append=true for long). Phone-tool OK = request delivered, outcome unconfirmed; say so. If the target is ambiguous (several/inexact contacts, unclear request) use ASK_USER, never guess. For SEND_SMS include "to" (contact name). On screen tasks act on the returned screen state (no need to re-read); use SCREEN_READ only if unsure."""

    /** Native function-calling protocol: the model calls the declared tools directly and ends the step with finish_step. */
    fun stepNative(others: String) = """Run ONE step of a plan for a phone agent. Use the provided tools by calling them (several calls per turn are fine). When the step is complete, or impossible, call finish_step with status done or blocked and a concise result that keeps the facts/URLs later steps need.
Rules: act on the user's intent directly and also do the obvious implied follow-up (never ask the user for confirmation yourself; the app handles approvals). Never invent facts, URLs, numbers. If a tool fails try another query/tool. Files: FILE_WRITE (append=true for long). Phone-tool OK = request delivered, outcome unconfirmed; say so. If the target is ambiguous (several/inexact contacts, unclear request) use ASK_USER, never guess. For SEND_SMS include "to" (contact name). On screen tasks act on the returned screen state (no need to re-read); use SCREEN_READ only if unsure.${if (others.isNotEmpty()) "\nTools not available in this step: $others. If you truly need one, call finish_step with status blocked and name it." else ""}"""

    val FINISH_TOOL = com.aurix.agent.core.ai.AiTool(
        "finish_step", "Call when the current step is complete (done) or cannot be completed (blocked). Give a concise result.",
        org.json.JSONObject("""{"type":"object","properties":{"status":{"type":"string","enum":["done","blocked"]},"result":{"type":"string","description":"concise result; keep facts/URLs later steps need"}},"required":["status","result"]}"""),
    )

    fun replan(toolNames: String) = """A step failed or was blocked. List the REMAINING steps only, with a different approach. Tools: $toolNames. End each step with [TOOLS]. If impossible, one step that explains what was done and what cannot be.
JSON only: {"steps":["step [TOOLS]"]}"""

    const val VERIFY = """Strict verifier. Judge ONLY from the evidence given (step results, file facts). Missing file/claims without evidence = not satisfied.
JSON only: {"satisfied":true or false,"final_answer":"<final answer for the user, name created files>","gaps":["<missing>"]}"""
}
