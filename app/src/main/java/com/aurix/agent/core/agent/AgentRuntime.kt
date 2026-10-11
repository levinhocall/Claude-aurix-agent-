package com.aurix.agent.core.agent

import com.aurix.agent.core.approval.ApprovalManager
import com.aurix.agent.core.approval.Decision
import com.aurix.agent.core.background.MissionNotifier
import com.aurix.agent.core.ai.AiError
import com.aurix.agent.core.ai.AiErrorType
import com.aurix.agent.core.ai.AiMessage
import com.aurix.agent.core.ai.AiProviderManager
import com.aurix.agent.core.ai.AiRequest
import com.aurix.agent.core.ai.AiResponse
import com.aurix.agent.core.ai.AiTool
import com.aurix.agent.core.mission.MissionDao
import com.aurix.agent.core.mission.MissionEntity
import com.aurix.agent.core.mission.MissionStatus
import com.aurix.agent.core.mission.StepEntity
import com.aurix.agent.core.mission.StepStatus
import com.aurix.agent.core.approval.QuestionManager
import com.aurix.agent.core.memory.MemoryRepository
import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.ToolException
import com.aurix.agent.core.tools.device.ContactResolver
import com.aurix.agent.core.tools.device.foldText
import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolExecutor
import com.aurix.agent.core.tools.ToolResult
import com.aurix.agent.core.tools.ToolRegistry
import com.aurix.agent.core.tools.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task -> Plan -> Execute (ReAct tool loop per step) -> Observe -> Verify -> Recover -> Complete.
 * Every transition is checkpointed in Room, so run(id) can resume an interrupted mission.
 */
@Singleton
class AgentRuntime @Inject constructor(
    private val dao: MissionDao,
    private val ai: AiProviderManager,
    private val events: AgentEvents,
    private val registry: ToolRegistry,
    private val tools: ToolExecutor,
    private val workspace: Workspace,
    private val approvals: ApprovalManager,
    private val notifier: MissionNotifier,
    private val contacts: ContactResolver,
    private val questions: QuestionManager,
    private val memory: MemoryRepository,
    private val settings: com.aurix.agent.core.approval.AgentSettings,
) {
    private val limits = AgentLimits()
    private val stopRequests = ConcurrentHashMap<String, MissionStatus>()
    private val running: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun isRunning(id: String) = running.contains(id)

    fun requestStop(id: String, status: MissionStatus) { stopRequests[id] = status }

    private class Run(var m: MissionEntity) {
        val startedAt = System.currentTimeMillis()
        var replans = 0
        var verifyRounds = 0
        var ctx: String? = null
        var mem: List<String> = emptyList()
        val recent = ArrayDeque<Int>()
        var noNative = false
    }

    private class StopMission(val status: MissionStatus, message: String) : Exception(message)

    suspend fun run(missionId: String) {
        if (!running.add(missionId)) return // already executing in this process
        try { runGuarded(missionId) } finally { running.remove(missionId) }
    }

    private suspend fun runGuarded(missionId: String) {
        val loaded = dao.getMission(missionId) ?: return
        if (loaded.status.isTerminal()) return
        val wasPaused = loaded.status == MissionStatus.PAUSED
        val r = Run(loaded)
        r.ctx = dao.getContext(missionId)
        r.mem = try { memory.relevant(loaded.objective) } catch (e: Exception) { emptyList() }
        try {
            stopRequests.remove(missionId)
            r.m = save(r.m.copy(status = MissionStatus.RUNNING, error = null, currentAction = "Starting"))
            if (wasPaused) events.emit(missionId, AgentEventType.MISSION_RESUMED)
            if (dao.getSteps(missionId).isEmpty()) {
                val cmd = if (forceAi(r.ctx)) null else IntentRouter.route(r.m.objective)
                if (cmd != null && runLocal(r, cmd)) return
                if (!ai.hasProvider()) throw StopMission(
                    MissionStatus.FAILED,
                    "This request needs an AI model. Add an API key or a local model in Settings. (Simple commands like flashlight, alarm, timer, volume, battery, open app work without one.)",
                )
                plan(r)
            }
            execute(r)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val target = stopRequests.remove(missionId) ?: MissionStatus.PAUSED
                end(missionId, target, if (target == MissionStatus.CANCELLED) "Cancelled" else "Paused", null)
            }
            throw e
        } catch (e: StopMission) {
            end(missionId, e.status, if (e.status == MissionStatus.PAUSED) "Paused" else "Failed", e.message)
        } catch (e: AiError) {
            val userFixable = e.type == AiErrorType.AUTH_ERROR || e.type == AiErrorType.NETWORK_ERROR ||
                e.type == AiErrorType.RATE_LIMIT || e.type == AiErrorType.TIMEOUT || e.type == AiErrorType.BUDGET_EXCEEDED
            if (userFixable) end(missionId, MissionStatus.PAUSED, "Paused (${e.type.name}) — fix and tap Resume", e.message)
            else end(missionId, MissionStatus.FAILED, "Failed", "${e.type.name}: ${e.message}")
        } catch (e: Exception) {
            end(missionId, MissionStatus.FAILED, "Failed", "UNKNOWN_ERROR: ${e.message?.take(300)}")
        }
    }

    private fun objectiveText(r: Run) = "Objective: ${r.m.objective}" + (if (r.mem.isEmpty()) "" else "\nKnown about the user: ${r.mem.joinToString("; ")}") + (r.ctx?.let { "\nContext from the previous task: $it" } ?: "")

    // ---------------------------------------------------------------- planning
    private suspend fun plan(r: Run) {
        r.m = save(r.m.copy(status = MissionStatus.PLANNING, currentAction = "Creating plan"))
        var titles: List<String>? = null
        for (attempt in 1..limits.maxPlanAttempts) {
            titles = parseStepList(model(r, "plan", AgentPrompts.plan(registry.names()), objectiveText(r), 1024))
            if (titles != null) break
            events.emit(r.m.id, AgentEventType.RECOVERY_STARTED, "Plan was not valid JSON (attempt $attempt)")
        }
        val steps = titles ?: throw StopMission(MissionStatus.FAILED, "INVALID_INPUT: could not produce a valid plan")
        dao.upsertSteps(steps.mapIndexed { i, t -> StepEntity(r.m.id, i, t, StepStatus.PENDING) })
        r.m = save(r.m.copy(status = MissionStatus.RUNNING, totalSteps = steps.size, currentAction = "Plan ready"))
        events.emit(r.m.id, AgentEventType.PLAN_CREATED, "${steps.size} steps")
    }

    // ---------------------------------------------------------------- loop
    private suspend fun execute(r: Run) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val steps = dao.getSteps(r.m.id)
            val step = steps.firstOrNull { it.status == StepStatus.PENDING || it.status == StepStatus.RUNNING }
            if (step == null) {
                if (verify(r, steps)) return
            } else {
                runStep(r, steps, step)
            }
        }
    }

    private suspend fun runStep(r: Run, steps: List<StepEntity>, step: StepEntity) {
        val id = r.m.id
        val attempt = step.attempts + 1
        dao.upsertSteps(listOf(step.copy(status = StepStatus.RUNNING, attempts = attempt)))
        val doneCount = steps.count { it.status == StepStatus.DONE }
        r.m = save(r.m.copy(currentStep = doneCount, totalSteps = steps.size, currentAction = step.title))
        events.emit(id, AgentEventType.STEP_STARTED, "${doneCount + 1}/${steps.size}: ${step.title}")

        val base = buildString {
            append(objectiveText(r)).append('\n')
            val done = steps.filter { it.status == StepStatus.DONE }
            if (done.isNotEmpty()) append("Done:\n").append(done.joinToString("\n") { "- ${cleanTitle(it.title)}: ${it.result.orEmpty().take(900)}" }).append('\n')
            val later = steps.filter { it.idx > step.idx && it.status == StepStatus.PENDING }
            if (later.isNotEmpty()) append("Later steps: ").append(later.joinToString("; ") { cleanTitle(it.title).take(60) }).append('\n')
            append("Current step: ${cleanTitle(step.title)}")
            if (step.attempts > 0 && !step.result.isNullOrBlank()) append("\nPrevious attempt failed: ${step.result.take(300)}")
        }
        val shown = registry.expand(toolHints(step.title)).let { if (it.isEmpty()) it else it + "ASK_USER" }
        val system = AgentPrompts.step(registry.catalog(if (shown.isEmpty()) null else shown), if (shown.isEmpty()) "" else registry.others(shown))
        if (settings.nativeTools() && ai.nativeAllowed() && !r.noNative) {
            if (runStepNative(r, steps, step, attempt, doneCount, base, shown)) return
            r.noNative = true // the provider rejected native tools before anything ran: use the JSON protocol for the rest of this mission
        }
        val scratch = StringBuilder()
        val seen = HashMap<String, Int>()
        var calls = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            val prompt = if (scratch.isEmpty()) base else base + "\n\nTool observations so far in this step:" + scratch
            val o = extractJson(model(r, "step", system, prompt, 4096, attempt > 1))
            if (o == null) {
                recover(r, steps, step, attempt, "Model returned invalid or truncated JSON (keep file chunks small)")
                return
            }
            if (o.optString("action") == "tool") {
                calls += 1
                if (calls > limits.maxToolCallsPerStep) {
                    recover(r, steps, step, attempt, "Too many tool calls without finishing the step")
                    return
                }
                val say = o.optString("say").trim()
                if (say.isNotEmpty()) events.emit(id, AgentEventType.ASSISTANT_NOTE, say)
                val name = o.optString("tool").trim()
                val input = o.optJSONObject("input") ?: JSONObject()
                val key = "$name|$input"
                val n = (seen[key] ?: 0) + 1
                seen[key] = n
                if (n >= limits.loopRepeatThreshold) {
                    recover(r, steps, step, attempt, "Loop detected: identical tool call repeated")
                    return
                }
                val outcome = gatedExecute(r, step.idx, step.title, name, input, say, true)
                if (outcome is ToolOutcome.Denied) {
                    scratch.append("\n[#").append(calls).append(' ').append(name).append("] -> ERROR PERMISSION_REQUIRED\nThe user DENIED this action. Do not retry it; use another approach or finish with status blocked.\n")
                    continue
                }
                val res = (outcome as ToolOutcome.Ran).res
                scratch.append("\n[#").append(calls).append(' ').append(name).append(' ').append(input.toString().take(300)).append("] -> ")
                scratch.append(if (res.ok) "OK\n" else "ERROR ${res.errorType}\n").append(res.output.take(1800)).append('\n')
                if (scratch.length > 6000) scratch.delete(0, scratch.length - 6000)
                continue
            }
            val result = o.optString("result")
            if (o.optString("status") == "done") {
                val h = (step.title + "|" + result).hashCode()
                r.recent.addLast(h)
                if (r.recent.size > 6) r.recent.removeFirst()
                dao.upsertSteps(listOf(step.copy(status = StepStatus.DONE, attempts = attempt, result = result)))
                if (r.recent.count { it == h } >= limits.loopRepeatThreshold)
                    throw StopMission(MissionStatus.FAILED, "Loop detected: identical step output repeated")
                events.emit(id, AgentEventType.STEP_COMPLETED, step.title)
                r.m = save(r.m.copy(currentStep = doneCount + 1))
            } else {
                recover(r, steps, step, attempt, result.ifBlank { "Step blocked" })
            }
            return
        }
    }

    // ---------------------------------------------------------------- native function calling
    private fun toolSupportError(e: AiError): Boolean =
        e.modelSpecific || ((e.type == AiErrorType.INVALID_INPUT || e.type == AiErrorType.MODEL_ERROR) && Regex("tool|function", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty()))

    private suspend fun modelNative(r: Run, msgs: List<AiMessage>, tools: List<AiTool>, escalate: Boolean): AiResponse {
        val m = r.m
        if (m.iterations >= limits.maxIterations) throw StopMission(MissionStatus.FAILED, "Iteration limit reached (${limits.maxIterations})")
        if (System.currentTimeMillis() - r.startedAt > limits.maxMissionMillis) throw StopMission(MissionStatus.FAILED, "Time limit reached for this run")
        if (m.tokensUsed >= limits.maxTokens) throw StopMission(MissionStatus.FAILED, "Token budget reached (${limits.maxTokens})")
        events.emit(m.id, AgentEventType.MODEL_REQUEST, "step (native tools)")
        val acc = StringBuilder(); var lastAt = 0L
        val live: suspend (String) -> Unit = { d ->
            acc.append(d)
            val now = System.currentTimeMillis()
            if (now - lastAt > 600) {
                lastAt = now
                val t = acc.toString().replace(Regex("\\s+"), " ").trim()
                if (t.length >= 3) r.m = save(r.m.copy(currentAction = t.take(110)))
            }
        }
        val resp = ai.complete(AiRequest(msgs, maxTokens = 4096, purpose = "step", escalate = escalate, tools = tools), live)
        val used = if (resp.usage.total > 0) resp.usage.total else (msgs.sumOf { it.content.length } + resp.text.length) / 4
        r.m = save(r.m.copy(iterations = r.m.iterations + 1, tokensUsed = r.m.tokensUsed + used))
        events.emit(m.id, AgentEventType.MODEL_RESPONSE, "step, ~$used tokens, ${resp.model}")
        return resp
    }

    /** Keeps the first two messages and drops the oldest assistant-call + tool-result groups, so the history stays valid and small. */
    private fun trimHistory(msgs: MutableList<AiMessage>) {
        while (msgs.size > 26) {
            var i = 2
            if (i >= msgs.size) return
            msgs.removeAt(i)
            while (i < msgs.size && msgs[i].role == "tool") msgs.removeAt(i)
        }
    }

    private suspend fun finishStep(r: Run, step: StepEntity, attempt: Int, doneCount: Int, result: String) {
        val id = r.m.id
        val h = (step.title + "|" + result).hashCode()
        r.recent.addLast(h)
        if (r.recent.size > 6) r.recent.removeFirst()
        dao.upsertSteps(listOf(step.copy(status = StepStatus.DONE, attempts = attempt, result = result)))
        if (r.recent.count { it == h } >= limits.loopRepeatThreshold)
            throw StopMission(MissionStatus.FAILED, "Loop detected: identical step output repeated")
        events.emit(id, AgentEventType.STEP_COMPLETED, step.title)
        r.m = save(r.m.copy(currentStep = doneCount + 1))
    }

    /** Returns true when the step was finished or recovered; false when the provider does not support native tools (nothing was executed). */
    private suspend fun runStepNative(r: Run, steps: List<StepEntity>, step: StepEntity, attempt: Int, doneCount: Int, base: String, shown: Set<String>): Boolean {
        val id = r.m.id
        val declared = registry.specs(if (shown.isEmpty()) null else shown) + AgentPrompts.FINISH_TOOL
        val system = AgentPrompts.stepNative(if (shown.isEmpty()) "" else registry.others(shown))
        val msgs = ArrayList<AiMessage>()
        msgs += AiMessage("system", system)
        msgs += AiMessage("user", base)
        val seen = HashMap<String, Int>()
        var calls = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val resp = try { modelNative(r, msgs, declared, attempt > 1) } catch (e: AiError) {
                if (calls == 0 && toolSupportError(e)) { ai.noteNativeFailure(); return false }
                throw e
            }
            val text = resp.text.trim()
            if (resp.toolCalls.isEmpty()) {
                if (text.isBlank()) { recover(r, steps, step, attempt, "Model returned nothing"); return true }
                finishStep(r, step, attempt, doneCount, text) // plain answer without a tool call = the step's result
                return true
            }
            if (text.isNotEmpty()) events.emit(id, AgentEventType.ASSISTANT_NOTE, text.take(200))
            msgs += AiMessage("assistant", text, toolCalls = resp.toolCalls)
            for (c in resp.toolCalls) {
                if (c.name == "finish_step") {
                    val result = c.arguments.optString("result").ifBlank { text }
                    if (c.arguments.optString("status") == "done") finishStep(r, step, attempt, doneCount, result)
                    else recover(r, steps, step, attempt, result.ifBlank { "Step blocked" })
                    return true
                }
                calls += 1
                if (calls > limits.maxToolCallsPerStep) { recover(r, steps, step, attempt, "Too many tool calls without finishing the step"); return true }
                val input = c.arguments
                val key = "${c.name}|$input"
                val n = (seen[key] ?: 0) + 1
                seen[key] = n
                if (n >= limits.loopRepeatThreshold) { recover(r, steps, step, attempt, "Loop detected: identical tool call repeated"); return true }
                val reply = when {
                    input.has("__invalid_arguments") -> "ERROR INVALID_INPUT\nThe arguments were not valid JSON. Call ${c.name} again with a proper JSON object."
                    registry.get(c.name) == null -> "ERROR INVALID_INPUT\nUnknown tool ${c.name}. Use only the provided tools."
                    else -> when (val o = gatedExecute(r, step.idx, step.title, c.name, input, text.take(80), true)) {
                        is ToolOutcome.Denied -> "ERROR PERMISSION_REQUIRED\nThe user DENIED this action. Do not retry it; use another approach or finish with status blocked."
                        is ToolOutcome.Ran -> (if (o.res.ok) "OK\n" else "ERROR ${o.res.errorType}\n") + o.res.output.take(1800)
                    }
                }
                msgs += AiMessage("tool", reply, toolCallId = c.id)
            }
            trimHistory(msgs)
        }
    }

    // ---------------------------------------------------------------- tool gate (approval -> execute)
    private sealed interface ToolOutcome {
        class Ran(val res: ToolResult) : ToolOutcome
        object Denied : ToolOutcome
    }

    private suspend fun gatedExecute(r: Run, stepIdx: Int, actionTitle: String, name: String, input: JSONObject, say: String, cloud: Boolean): ToolOutcome {
        val id = r.m.id
        val tool = registry.get(name)
        var approved = true // the policy below decides; if no approval is needed (or it was granted) the executor may run HIGH tools
        if (tool != null && approvals.needsApproval(tool, input)) {
            r.m = save(r.m.copy(status = MissionStatus.WAITING_FOR_APPROVAL, currentAction = "Waiting for your approval: $name"))
            notifier.approvalNeeded(r.m, tool.describe(input))
            val decision = approvals.request(id, tool, name, input, say)
            r.m = save(r.m.copy(status = MissionStatus.RUNNING, currentAction = actionTitle))
            if (decision == Decision.TIMED_OUT) throw StopMission(MissionStatus.PAUSED, "Approval timed out. Tap Resume to ask again.")
            if (decision == Decision.DENIED) return ToolOutcome.Denied
            approved = true
        }
        r.m = save(r.m.copy(status = MissionStatus.WAITING_FOR_TOOL, currentAction = "Using $name"))
        val res = tools.execute(id, stepIdx, name, input, approved, cloud)
        r.m = save(r.m.copy(status = MissionStatus.RUNNING, currentAction = actionTitle))
        return ToolOutcome.Ran(res)
    }

    // ---------------------------------------------------------------- local (no AI) path
    /** Simple phone commands run straight through the same approval/executor path: no model call, no key, no internet. Returns false to hand over to the AI planner. */
    private suspend fun runLocal(r: Run, cmd: RoutedCommand): Boolean {
        val id = r.m.id
        var title = "${cmd.title} [${cmd.tool}]"
        dao.upsertSteps(listOf(StepEntity(id, 0, title, StepStatus.RUNNING, null, 1)))
        r.m = save(r.m.copy(totalSteps = 1, currentStep = 0, currentAction = cmd.title))
        events.emit(id, AgentEventType.PLAN_CREATED, "1 step (local, no AI needed)")
        var input = cmd.input
        var shownTitle = cmd.title
        val who = cmd.contact
        if (who != null) {
            val chosen = resolveContact(r, who) ?: return true
            input = JSONObject(cmd.input.toString()).put("number", chosen.second).put("to", chosen.first)
            shownTitle = cmd.title.replace(who, chosen.first, ignoreCase = true)
            title = "$shownTitle [${cmd.tool}]"
            dao.upsertSteps(listOf(StepEntity(id, 0, title, StepStatus.RUNNING, null, 1)))
        }
        return when (val o = gatedExecute(r, 0, shownTitle, cmd.tool, input, "", false)) {
            is ToolOutcome.Denied -> {
                dao.upsertSteps(listOf(StepEntity(id, 0, title, StepStatus.FAILED, "Denied by the user", 1)))
                r.m = save(r.m.copy(finalResult = "Okay, I did not do it."))
                end(id, MissionStatus.COMPLETED, "Completed", null)
                true
            }
            is ToolOutcome.Ran -> {
                val first = o.res.output.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                if (o.res.ok) {
                    dao.upsertSteps(listOf(StepEntity(id, 0, title, StepStatus.DONE, o.res.output.take(300), 1)))
                    var summary = first
                    for ((t2, in2) in cmd.extra) {
                        val o2 = gatedExecute(r, 0, shownTitle, t2, in2, "", false)
                        summary += "; " + ((o2 as? ToolOutcome.Ran)?.res?.let { rr -> if (rr.ok) rr.output.lineSequence().first() else "${t2.lowercase()}: skipped (${rr.errorType})" } ?: "skipped")
                    }
                    r.m = save(r.m.copy(currentStep = 1, finalResult = summary))
                    end(id, MissionStatus.COMPLETED, "Completed", null)
                    true
                } else if (o.res.errorType == ToolErrorType.PERMISSION_REQUIRED || !ai.hasProvider()) {
                    dao.upsertSteps(listOf(StepEntity(id, 0, title, StepStatus.FAILED, o.res.output.take(300), 1)))
                    r.m = save(r.m.copy(finalResult = first))
                    end(id, MissionStatus.FAILED, "Failed", "${o.res.errorType}: ${first.take(200)}")
                    true
                } else {
                    events.emit(id, AgentEventType.RECOVERY_STARTED, "Local command failed (${o.res.errorType}); asking the AI planner")
                    dao.deleteUnfinishedSteps(id)
                    false
                }
            }
        }
    }

    private suspend fun failLocal(r: Run, msg: String) {
        val id = r.m.id
        dao.upsertSteps(dao.getSteps(id).map { it.copy(status = StepStatus.FAILED, result = msg) })
        r.m = save(r.m.copy(finalResult = msg))
        end(id, MissionStatus.FAILED, "Failed", msg)
    }

    /** (display name, number) of the contact to use, or null after failing the mission. Never guesses: a non-exact or multiple match asks the user. */
    private suspend fun resolveContact(r: Run, name: String): Pair<String, String>? {
        val id = r.m.id
        val matches = try { contacts.search(name) } catch (e: ToolException) { failLocal(r, e.message ?: "Contacts are not accessible."); return null }
        if (matches.isEmpty()) { failLocal(r, "No contact matches \"$name\"."); return null }
        val exact = matches.filter { it.score == 100 }
        if (exact.size == 1) return exact[0].name to exact[0].number
        val pool = (if (exact.size > 1) exact else matches).take(4)
        val labels = pool.map { "${it.name} (…${it.number.filter { c -> c.isDigit() }.takeLast(4)})" }
        r.m = save(r.m.copy(currentAction = "Choosing contact"))
        val answer = try { questions.ask(id, "Which contact do you mean for \"$name\"?", labels) } catch (e: ToolException) { failLocal(r, e.message ?: "No answer."); return null }
        r.m = save(r.m.copy(status = MissionStatus.RUNNING, currentAction = "Working"))
        val a = foldText(answer)
        val hit = pool.indices.firstOrNull { labels[it] == answer }
            ?: pool.indices.firstOrNull { i -> a.isNotEmpty() && foldText(pool[i].name).let { it == a || it.contains(a) } }
            ?: pool.indices.firstOrNull { i -> a.isNotEmpty() && a.all { it.isDigit() } && pool[i].number.filter { it.isDigit() }.endsWith(a) }
        if (hit == null) { failLocal(r, "I could not tell which contact you meant (\"$answer\")."); return null }
        return pool[hit].name to pool[hit].number
    }

    // ---------------------------------------------------------------- recovery
    private suspend fun recover(r: Run, steps: List<StepEntity>, step: StepEntity, attempt: Int, reason: String) {
        val id = r.m.id
        r.m = save(r.m.copy(status = MissionStatus.RECOVERING, recoveries = r.m.recoveries + 1, currentAction = "Recovering: ${step.title}"))
        if (attempt < limits.maxStepAttempts) {
            events.emit(id, AgentEventType.RECOVERY_STARTED, "Retry step ${step.idx + 1}: ${reason.take(150)}")
            dao.upsertSteps(listOf(step.copy(status = StepStatus.PENDING, attempts = attempt, result = reason)))
        } else {
            events.emit(id, AgentEventType.RECOVERY_STARTED, "Replanning after step ${step.idx + 1}: ${reason.take(150)}")
            dao.upsertSteps(listOf(step.copy(status = StepStatus.FAILED, attempts = attempt, result = reason)))
            r.replans += 1
            if (r.replans > limits.maxReplans)
                throw StopMission(MissionStatus.FAILED, "Step '${step.title}' kept failing: ${reason.take(200)}")
            replan(r, steps, step, reason)
        }
        r.m = save(r.m.copy(status = MissionStatus.RUNNING))
    }

    private suspend fun replan(r: Run, steps: List<StepEntity>, failed: StepEntity, reason: String) {
        val id = r.m.id
        val done = steps.filter { it.status == StepStatus.DONE }.joinToString("\n") { "- ${it.title}: ${it.result.orEmpty().take(400)}" }.ifEmpty { "(nothing)" }
        val raw = model(r, "replan", AgentPrompts.replan(registry.names()), "Objective: ${r.m.objective}\nCompleted:\n$done\nFailed step: ${failed.title}\nReason: $reason", 1024)
        val titles = parseStepList(raw) ?: throw StopMission(MissionStatus.FAILED, "INVALID_INPUT: could not revise the plan")
        dao.deleteUnfinishedSteps(id)
        val start = (dao.getSteps(id).maxOfOrNull { it.idx } ?: -1) + 1
        dao.upsertSteps(titles.mapIndexed { i, t -> StepEntity(id, start + i, t, StepStatus.PENDING) })
        r.m = save(r.m.copy(totalSteps = dao.getSteps(id).size))
        events.emit(id, AgentEventType.PLAN_REVISED, "${titles.size} new steps")
    }

    // ---------------------------------------------------------------- verification
    /** Returns true when the mission is finished (COMPLETED), false when new gap-steps were queued. */
    private suspend fun verify(r: Run, steps: List<StepEntity>): Boolean {
        val id = r.m.id
        // Tiny missions without files (e.g. a phone action): the last step result IS the answer; skipping the verifier saves a model call.
        if (steps.size <= 3 && steps.none { it.status == StepStatus.FAILED } && dao.getFiles(id).isEmpty()) {
            val last = steps.lastOrNull { it.status == StepStatus.DONE }?.result.orEmpty()
            if (last.isNotBlank()) {
                r.m = save(r.m.copy(finalResult = last))
                end(id, MissionStatus.COMPLETED, "Completed", null)
                return true
            }
        }
        r.m = save(r.m.copy(currentStep = steps.count { it.status == StepStatus.DONE }, currentAction = "Verifying result"))
        val results = steps.joinToString("\n") { "${it.idx + 1}. ${it.title} [${it.status}]: ${it.result.orEmpty().take(1500)}" }
        val files = dao.getFiles(id).joinToString("\n") { f ->
            val preview = try { workspace.resolve(id, f.path).readText().take(1500) } catch (e: Exception) { "(unreadable)" }
            "- ${f.path} (${f.sizeBytes} bytes, verified to exist). Preview:\n$preview"
        }.ifEmpty { "(no files were created)" }
        val raw = model(r, "verify", AgentPrompts.VERIFY, "Objective: ${r.m.objective}\n\nExecuted steps:\n$results\n\nFiles:\n$files", 3000)
        val o = extractJson(raw)
        val summary = o?.optString("final_answer").orEmpty()
        if (o != null && o.optBoolean("satisfied") && summary.isNotBlank()) {
            r.m = save(r.m.copy(finalResult = summary))
            end(id, MissionStatus.COMPLETED, "Completed", null)
            return true
        }
        val gaps = o?.optJSONArray("gaps")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } }.orEmpty()
        r.verifyRounds += 1
        if (r.verifyRounds > limits.maxVerifyRounds || gaps.isEmpty()) {
            if (summary.isNotBlank()) r.m = save(r.m.copy(finalResult = summary))
            throw StopMission(MissionStatus.FAILED, "Verification failed: " + gaps.joinToString("; ").ifEmpty { "verifier rejected the result" }.take(300))
        }
        events.emit(id, AgentEventType.RECOVERY_STARTED, "Verification found ${gaps.size} gap(s); adding steps")
        val start = (steps.maxOfOrNull { it.idx } ?: -1) + 1
        dao.upsertSteps(gaps.take(4).mapIndexed { i, g -> StepEntity(id, start + i, g, StepStatus.PENDING) })
        r.m = save(r.m.copy(totalSteps = dao.getSteps(id).size))
        return false
    }

    // ---------------------------------------------------------------- helpers
    private suspend fun model(r: Run, purpose: String, system: String, user: String, maxTokens: Int, escalate: Boolean = false): String {
        val m = r.m
        if (m.iterations >= limits.maxIterations) throw StopMission(MissionStatus.FAILED, "Iteration limit reached (${limits.maxIterations})")
        if (System.currentTimeMillis() - r.startedAt > limits.maxMissionMillis) throw StopMission(MissionStatus.FAILED, "Time limit reached for this run")
        if (m.tokensUsed >= limits.maxTokens) throw StopMission(MissionStatus.FAILED, "Token budget reached (${limits.maxTokens})")
        events.emit(m.id, AgentEventType.MODEL_REQUEST, purpose)
        val acc = StringBuilder(); var lastAt = 0L; var shown = ""
        val live: (suspend (String) -> Unit)? = if (purpose != "step") null else { d: String ->
            acc.append(d)
            val now = System.currentTimeMillis()
            if (now - lastAt > 600) {
                lastAt = now
                val say = extractSay(acc.toString())
                if (say != null && say != shown) { shown = say; r.m = save(r.m.copy(currentAction = say.take(110))) }
            }
        }
        val resp = ai.complete(AiRequest(listOf(AiMessage("system", system), AiMessage("user", user)), maxTokens = maxTokens, purpose = purpose, escalate = escalate, json = true), live)
        val used = if (resp.usage.total > 0) resp.usage.total else (user.length + system.length + resp.text.length) / 4
        r.m = save(r.m.copy(iterations = r.m.iterations + 1, tokensUsed = r.m.tokensUsed + used))
        events.emit(m.id, AgentEventType.MODEL_RESPONSE, "$purpose, ~$used tokens, ${resp.model}")
        return resp.text
    }

    private suspend fun save(m: MissionEntity): MissionEntity {
        val u = m.copy(updatedAt = System.currentTimeMillis())
        dao.updateMission(u)
        return u
    }

    private suspend fun end(id: String, status: MissionStatus, action: String, error: String?) {
        val m = dao.getMission(id) ?: return
        val steps = dao.getSteps(id)
        dao.updateMission(
            m.copy(
                status = status, currentAction = action, error = error,
                currentStep = steps.count { it.status == StepStatus.DONE }, totalSteps = steps.size,
                updatedAt = System.currentTimeMillis(),
            )
        )
        val type = when (status) {
            MissionStatus.COMPLETED -> AgentEventType.MISSION_COMPLETED
            MissionStatus.FAILED -> AgentEventType.MISSION_FAILED
            MissionStatus.CANCELLED -> AgentEventType.MISSION_CANCELLED
            else -> AgentEventType.MISSION_PAUSED
        }
        events.emit(id, type, error.orEmpty())
    }
}
