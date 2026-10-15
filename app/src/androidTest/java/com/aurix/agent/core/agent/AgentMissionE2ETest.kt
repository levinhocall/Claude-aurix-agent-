package com.aurix.agent.core.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aurix.agent.core.ai.AiProviderManager
import com.aurix.agent.core.ai.routing.KeyPool
import com.aurix.agent.core.ai.routing.ProviderEntry
import com.aurix.agent.core.ai.routing.ProviderStore
import com.aurix.agent.core.ai.routing.ProviderType
import com.aurix.agent.core.ai.routing.UsageTracker
import com.aurix.agent.core.approval.AgentSettings
import com.aurix.agent.core.approval.ApprovalManager
import com.aurix.agent.core.approval.QuestionManager
import com.aurix.agent.core.background.MissionNotifier
import com.aurix.agent.core.durability.CheckpointStore
import com.aurix.agent.core.durability.SideEffectJournal
import com.aurix.agent.core.durability.SideEffectReconciler
import com.aurix.agent.core.memory.MemoryRepository
import com.aurix.agent.core.mission.AppDatabase
import com.aurix.agent.core.mission.MissionDao
import com.aurix.agent.core.mission.MissionEntity
import com.aurix.agent.core.mission.MissionStatus
import com.aurix.agent.core.observability.ModelCallRecorder
import com.aurix.agent.core.security.SecureSettings
import com.aurix.agent.core.tools.CalculatorTool
import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.Tool
import com.aurix.agent.core.tools.ToolContext
import com.aurix.agent.core.tools.ToolResult
import com.aurix.agent.core.tools.FileReadTool
import com.aurix.agent.core.tools.FileWriteTool
import com.aurix.agent.core.tools.ToolExecutor
import com.aurix.agent.core.tools.ToolRegistry
import com.aurix.agent.core.tools.Workspace
import com.aurix.agent.core.tools.device.ContactResolver
import com.aurix.agent.core.verification.VerificationService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real AgentRuntime + real Room + real CALCULATOR / FILE_WRITE tools + real ToolExecutor/verification, on a device.
 * Only the LLM is scripted (an OkHttp interceptor answering the OpenAI-compatible endpoint), so this proves the orchestration:
 * plan -> tool request -> real execution -> result fed back into the next model request -> next tool -> file checked -> completion.
 */
@RunWith(AndroidJUnit4::class)
class AgentMissionE2ETest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var ws: Workspace
    private lateinit var runtime: AgentRuntime
    private class RecordingWhatsApp : Tool {
        val calls = mutableListOf<JSONObject>()
        override val name = "WHATSAPP_MESSAGE"
        override val description = "test double"
        override val inputSchema = """{"to":"contact name","text":"message"}"""
        override val outputSchema = "result"
        override val required = listOf("text")
        override val risk = RiskLevel.HIGH
        override val timeoutMs = 5_000L
        override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult { calls += input; return ToolResult.ok("sent") }
    }
    private val wa = RecordingWhatsApp()
    private val requests = mutableListOf<String>() // every prompt the "model" received

    /** script(systemText, userText, requestIndex) -> raw model reply */
    private var script: (String, String) -> String = { _, _ -> "{}" }

    private fun fakeModel() = Interceptor { chain ->
        val buf = Buffer(); chain.request().body?.writeTo(buf)
        val body = JSONObject(buf.readUtf8())
        val msgs = body.getJSONArray("messages")
        var system = ""; var user = ""
        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            if (m.getString("role") == "system") system += m.optString("content") else user += m.optString("content")
        }
        requests += user
        val reply = script(system, user)
        val text: String; val type: String
        if (body.optBoolean("stream")) {
            val chunk = JSONObject().put("model", "fake").put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", reply))))
            text = "data: $chunk\n\ndata: [DONE]\n\n"; type = "text/event-stream"
        } else {
            val msg = JSONObject().put("role", "assistant").put("content", reply)
            text = JSONObject().put("model", "fake").put("choices", JSONArray().put(JSONObject().put("message", msg))).toString(); type = "application/json"
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(text.toResponseBody(type.toMediaType())).build()
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        val dao: MissionDao = db.missionDao()
        val secure = SecureSettings(ctx)
        secure.putString("native_tools", "0") // exercise the JSON tool protocol the project uses
        val store = ProviderStore(secure)
        store.save(listOf(ProviderEntry("fake", ProviderType.OPENAI_COMPATIBLE, "Fake", "http://127.0.0.1:9/v1", "fake-fast", "fake-strong", local = true)))
        val client = OkHttpClient.Builder().addInterceptor(fakeModel()).build()
        val ai = AiProviderManager(store, KeyPool { System.currentTimeMillis() }, UsageTracker(ctx), client)
        val events = AgentEvents(dao)
        ws = Workspace(ctx)
        val registry = ToolRegistry(listOf(CalculatorTool(), FileWriteTool(ws), FileReadTool(ws), wa))
        val settings = AgentSettings(secure)
        val notifier = MissionNotifier(ctx)
        val questions = QuestionManager(dao, events, notifier)
        val journal = SideEffectJournal(dao)
        runtime = AgentRuntime(
            dao, ai, events, registry, ToolExecutor(registry, dao, events, ws), ws,
            ApprovalManager(dao, events, settings, registry), notifier, ContactResolver(ctx), questions,
            MemoryRepository(dao, settings), settings, CheckpointStore(dao), journal,
            SideEffectReconciler(journal, questions, events), VerificationService(dao, ws, events), ModelCallRecorder(dao),
        )
    }

    @After fun tearDown() { db.close() }

    private fun systemIs(system: String, marker: String) = system.contains(marker)

    private fun startMission(id: String, objective: String): MissionEntity = runBlocking {
        val m = MissionEntity(id = id, objective = objective, createdAt = 1, updatedAt = 1, status = MissionStatus.CREATED, conversationId = id)
        db.missionDao().insertMission(m); m
    }

    private val goal = "Calculate 1250 multiplied by 48, create a text report containing the calculation and result, save it as report.txt, and verify the saved file."

    @Test fun calculateThenWriteReportThenVerifyFile() {
        val id = "e2e-ok"
        script = { system, user ->
            when {
                systemIs(system, "Planner for an autonomous phone agent") ->
                    """{"steps":["Calculate 1250 multiplied by 48 [CALCULATOR]","Write the report with the result to report.txt [FILE_WRITE]"]}"""
                systemIs(system, "Run ONE step") -> when {
                    user.contains("Current step: Calculate") ->
                        if (user.contains("-> ") && user.contains("60000")) """{"action":"finish","status":"done","result":"1250 * 48 = 60000"}"""
                        else """{"action":"tool","say":"calculating","tool":"CALCULATOR","input":{"expression":"1250*48"}}"""
                    user.contains("Current step: Write") ->
                        if (user.contains("Tool observations so far")) """{"action":"finish","status":"done","result":"saved report.txt"}"""
                        else {
                            // the number must come from the previous step's real result, which only reaches the model via the prompt
                            val result = Regex("1250 \\* 48 = (\\d+)").find(user)?.groupValues?.get(1) ?: "RESULT-NOT-FED-BACK"
                            """{"action":"tool","say":"writing","tool":"FILE_WRITE","input":{"path":"report.txt","content":"Calculation: 1250 x 48 = $result\n"}}"""
                        }
                    else -> """{"action":"finish","status":"blocked","result":"unexpected step prompt"}"""
                }
                systemIs(system, "Strict verifier") ->
                    """{"satisfied":true,"final_answer":"Calculated 1250 x 48 = 60000 and saved it to report.txt","gaps":[]}"""
                else -> "{}"
            }
        }
        startMission(id, goal)
        runBlocking { withTimeout(90_000) { runtime.run(id) } }

        val dao = db.missionDao()
        val mission = runBlocking { dao.getMission(id) }!!
        val calls = runBlocking { dao.getAllToolCallsFor(id) }
        assertEquals("status=${mission.status} err=${mission.error}", MissionStatus.COMPLETED, mission.status)
        assertEquals(listOf("CALCULATOR", "FILE_WRITE"), calls.map { it.tool })
        assertTrue("calculator really computed 60000: ${calls[0].output}", calls[0].output.contains("60000"))
        // the saved file is real, with the real number (proves the result travelled tool -> model -> next tool)
        val file = ws.resolve(id, "report.txt")
        assertTrue(file.isFile)
        assertEquals("Calculation: 1250 x 48 = 60000\n", file.readText())
        // the second step's first request already contained the first step's real result
        assertTrue(requests.any { it.contains("Current step: Write") && it.contains("1250 * 48 = 60000") })
        assertTrue(mission.finalResult.orEmpty().contains("report.txt"))
    }

    @Test fun claimingASavedFileThatWasNeverWrittenIsNotCompletion() {
        val id = "e2e-lie"
        script = { system, user ->
            when {
                systemIs(system, "Planner for an autonomous phone agent") -> """{"steps":["Write the report to report.txt [FILE_WRITE]"]}"""
                systemIs(system, "Run ONE step") -> """{"action":"finish","status":"done","result":"report.txt saved"}"""
                systemIs(system, "Strict verifier") -> """{"satisfied":true,"final_answer":"Saved the report to report.txt","gaps":[]}"""
                else -> "{}"
            }
        }
        startMission(id, "Create report.txt with a short note")
        runBlocking { withTimeout(90_000) { runtime.run(id) } }
        val mission = runBlocking { db.missionDao().getMission(id) }!!
        assertFalse("must not complete without the file: ${mission.status}", mission.status == MissionStatus.COMPLETED)
        assertFalse(ws.resolve(id, "report.txt").exists())
    }

    @Test fun hallucinatedWhatsAppNumberNeverReachesTheTool() {
        val id = "e2e-wa"
        script = { system, user ->
            when {
                systemIs(system, "Planner for an autonomous phone agent") -> """{"steps":["Send the letter to nazib on WhatsApp [WHATSAPP_MESSAGE]"]}"""
                systemIs(system, "Run ONE step") ->
                    if (user.contains("RECIPIENT_NOT_VERIFIED")) """{"action":"finish","status":"blocked","result":"could not verify the recipient"}"""
                    else """{"action":"tool","say":"sending","tool":"WHATSAPP_MESSAGE","input":{"to":"nazib","number":"+919876543210","text":"hello"}}"""
                else -> "{}"
            }
        }
        startMission(id, "send hello to nazib on WhatsApp")
        runBlocking { withTimeout(90_000) { runtime.run(id) } }
        assertTrue("the made-up number must never be sent to: ${wa.calls}", wa.calls.isEmpty())
        assertTrue(requests.any { it.contains("RECIPIENT_NOT_VERIFIED") })
        assertFalse(runBlocking { db.missionDao().getMission(id) }!!.status == MissionStatus.COMPLETED)
    }
}
