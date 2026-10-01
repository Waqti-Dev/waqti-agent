package com.waqti.agent.loop

import com.waqti.agent.ToolSpec
import com.waqti.agent.model.ChatMessage
import com.waqti.agent.model.LocalModelProvider
import com.waqti.agent.model.ModelRequest
import com.waqti.agent.model.ModelResponse
import com.waqti.agent.model.Role
import com.waqti.agent.model.ToolCall
import com.waqti.agent.runtime.ChatGeneration
import com.waqti.agent.runtime.InferenceException
import com.waqti.agent.runtime.LocalInferenceRuntime
import com.waqti.agent.runtime.ToolCallSpec
import com.waqti.agent.tools.Tool
import com.waqti.agent.tools.ToolRegistry
import com.waqti.agent.tools.ToolResult
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boundary between one local inference turn and the agent loop.
 *
 * The runtime returns what the model produced — text, structured tool calls, or
 * both. These tests pin how that becomes [ModelResponse], that tool definitions
 * reach the runtime from the registry, and that a call travels all the way
 * through execution to a final answer.
 *
 * The runtime here is a stub that returns exactly what a test tells it to. What
 * is under test is Waqti's contract with the runtime, not the model's ability:
 * that the real on-device model really emits these calls is proved on a device.
 */

private class LocalEchoTool : Tool {
    override val spec = ToolSpec(
        name = "Echo",
        description = "Echoes the arguments back.",
        parameters = """{"type":"object","properties":{"text":{"type":"string"}}}"""
    )

    override fun execute(args: JSONObject): ToolResult =
        ToolResult.success("echo:${args.optString("text")}")
}

private class LocalFailingTool : Tool {
    override val spec = ToolSpec(
        name = "Flaky",
        description = "Always fails.",
        parameters = """{"type":"object"}"""
    )

    override fun execute(args: JSONObject): ToolResult =
        ToolResult.failure("backend unavailable")
}

private class StubLocalRuntime : LocalInferenceRuntime {

    override val label = "stub-local"

    /** What each successive generateChat returns. */
    private val turns = ArrayDeque<ChatGeneration>()

    /** Every (transcript, tools) pair the runtime was asked for. */
    val requests = mutableListOf<Pair<List<ChatMessage>, List<ToolSpec>>>()

    var loadCount = 0
        private set
    var contextCount = 0
        private set

    fun next(text: String = "", toolCalls: List<ToolCallSpec> = emptyList()): StubLocalRuntime {
        turns.addLast(ChatGeneration(text = text, toolCalls = toolCalls, stopReason = "eog"))
        return this
    }

    override suspend fun loadModel(modelPath: String) {
        loadCount++
    }

    override suspend fun createContext(nCtx: Int, nBatch: Int) {
        contextCount++
    }

    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        seed: Int
    ): String = throw UnsupportedOperationException("plain generate is not used here")

    override suspend fun generateChat(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        seed: Int
    ): ChatGeneration {
        requests.add(messages to tools)
        return turns.removeFirstOrNull()
            ?: ChatGeneration(text = "stub default answer", stopReason = "eog")
    }

    override suspend fun releaseContext() = Unit

    override suspend fun unloadModel() = Unit

    override suspend fun getChatTemplate(): String = "stub template"
}

private val SEARCH_FILES = ToolSpec(
    name = "SearchFiles",
    description = "Finds files whose contents match a query.",
    parameters = """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""
)

private val LIST_FILES = ToolSpec(
    name = "ListFiles",
    description = "Lists files in a directory.",
    parameters = """{"type":"object","properties":{"dir":{"type":"string"}}}"""
)

private fun providerWith(runtime: StubLocalRuntime) =
    LocalModelProvider(runtime = runtime, modelPath = "model.gguf", maxTokens = 64)

private fun request(tools: List<ToolSpec> = listOf(SEARCH_FILES, LIST_FILES)) = ModelRequest(
    messages = listOf(
        ChatMessage(Role.SYSTEM, "You are Waqti."),
        ChatMessage(Role.USER, "Find Kotlin files containing TODO.")
    ),
    tools = tools
)

class LocalModelToolCallTest {

    // ---- Test A: a plain answer is text, not a call -------------------------

    @Test
    fun `plain answer maps to text`() = runTest {
        val runtime = StubLocalRuntime().next(text = "There are 3 files.")

        val response = providerWith(runtime).respond(request(tools = emptyList()))

        assertTrue(response is ModelResponse.Text)
        assertEquals("There are 3 files.", (response as ModelResponse.Text).text)
        assertEquals(1, runtime.requests.size)
        assertTrue(runtime.requests.single().second.isEmpty())
    }

    @Test
    fun `text answer is still text when tools are available`() = runTest {
        val runtime = StubLocalRuntime().next(text = "I already know that.")

        val response = providerWith(runtime).respond(request())

        assertTrue(response is ModelResponse.Text)
        assertEquals("I already know that.", (response as ModelResponse.Text).text)
    }

    @Test
    fun `a turn with neither text nor calls is not dressed up as a call`() = runTest {
        val runtime = StubLocalRuntime().next(text = "", toolCalls = emptyList())

        val response = providerWith(runtime).respond(request())

        assertTrue(response is ModelResponse.Text)
        assertEquals("", (response as ModelResponse.Text).text)
    }

    // ---- Test B: one tool call ---------------------------------------------

    @Test
    fun `one tool call maps to calls with name and arguments`() = runTest {
        val runtime = StubLocalRuntime().next(
            toolCalls = listOf(
                ToolCallSpec(id = "call_1", name = "SearchFiles", arguments = """{"query":"TODO"}""")
            )
        )

        val response = providerWith(runtime).respond(request())

        assertTrue(response is ModelResponse.Calls)
        val calls = (response as ModelResponse.Calls).calls
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("SearchFiles", calls[0].name)
        assertEquals("""{"query":"TODO"}""", calls[0].arguments)
    }

    @Test
    fun `parallel tool calls are all preserved`() = runTest {
        val runtime = StubLocalRuntime().next(
            toolCalls = listOf(
                ToolCallSpec("call_1", "SearchFiles", """{"query":"TODO"}"""),
                ToolCallSpec("call_2", "ListFiles", """{"dir":"src"}""")
            )
        )

        val calls = (providerWith(runtime).respond(request()) as ModelResponse.Calls).calls

        assertEquals(listOf("SearchFiles", "ListFiles"), calls.map { it.name })
        assertEquals(listOf("call_1", "call_2"), calls.map { it.id })
    }

    @Test
    fun `prose alongside a tool call is kept as the assistant preamble`() = runTest {
        val runtime = StubLocalRuntime().next(
            text = "Let me look for that.",
            toolCalls = listOf(ToolCallSpec("call_1", "SearchFiles", """{"query":"TODO"}"""))
        )

        val response = providerWith(runtime).respond(request()) as ModelResponse.Calls

        assertEquals("Let me look for that.", response.assistantContent)
    }

    @Test
    fun `a model that supplies no tool id still gets distinct ids per call`() = runTest {
        // Qwen2.5's tool-call format has no id field at all, so the runtime
        // reports an empty one. Two empty ids would make the two results
        // impossible to tell apart when they come back.
        val runtime = StubLocalRuntime().next(
            toolCalls = listOf(
                ToolCallSpec("", "SearchFiles", """{"query":"TODO"}"""),
                ToolCallSpec("", "ListFiles", """{"path":"src"}""")
            )
        )

        val calls = (providerWith(runtime).respond(request()) as ModelResponse.Calls).calls

        assertEquals(listOf("call_1", "call_2"), calls.map { it.id })
    }

    @Test
    fun `a model supplied tool id is never overwritten`() = runTest {
        val runtime = StubLocalRuntime().next(
            toolCalls = listOf(ToolCallSpec("call_abc123", "SearchFiles", """{"query":"TODO"}"""))
        )

        val calls = (providerWith(runtime).respond(request()) as ModelResponse.Calls).calls

        assertEquals("call_abc123", calls.single().id)
    }

    // ---- tool definitions must actually reach the model --------------------

    @Test
    fun `tool definitions come from the request and reach the runtime`() = runTest {
        val runtime = StubLocalRuntime().next(text = "ok")

        providerWith(runtime).respond(request())

        val sent = runtime.requests.single().second
        assertEquals(listOf("SearchFiles", "ListFiles"), sent.map { it.name })
        // Parameters travel as the tool's own schema text, not a rephrasing.
        assertTrue(sent[0].parameters.contains("\"query\""))
        assertTrue(sent[0].description.contains("query"))
    }

    @Test
    fun `full transcript including tool turns reaches the runtime`() = runTest {
        val runtime = StubLocalRuntime().next(text = "done")
        val transcript = listOf(
            ChatMessage(Role.SYSTEM, "You are Waqti."),
            ChatMessage(Role.USER, "Find TODO."),
            ChatMessage(
                Role.ASSISTANT,
                content = "",
                toolCalls = listOf(ToolCall("call_1", "SearchFiles", """{"query":"TODO"}"""))
            ),
            ChatMessage(
                Role.TOOL,
                content = "3 matches",
                toolCallId = "call_1",
                toolName = "SearchFiles"
            )
        )

        providerWith(runtime).respond(ModelRequest(transcript, listOf(SEARCH_FILES)))

        val sent = runtime.requests.single().first
        assertEquals(4, sent.size)
        assertEquals(Role.ASSISTANT, sent[2].role)
        assertEquals("SearchFiles", sent[2].toolCalls.single().name)
        assertEquals(Role.TOOL, sent[3].role)
        assertEquals("call_1", sent[3].toolCallId)
        assertEquals("3 matches", sent[3].content)
    }

    @Test
    fun `model and context are loaded once and reused across turns`() = runTest {
        val runtime = StubLocalRuntime().next(text = "a").next(text = "b")
        val provider = providerWith(runtime)

        provider.respond(request())
        provider.respond(request())

        assertEquals(1, runtime.loadCount)
        assertEquals(1, runtime.contextCount)
    }

    @Test
    fun `an inference failure becomes a visible error message`() = runTest {
        val failing = object : LocalInferenceRuntime {
            override val label = "failing"
            override suspend fun loadModel(modelPath: String) = Unit
            override suspend fun createContext(nCtx: Int, nBatch: Int) = Unit
            override suspend fun generate(
                prompt: String, maxTokens: Int, temperature: Float,
                topK: Int, topP: Float, seed: Int
            ): String = throw InferenceException("no model loaded")

            override suspend fun generateChat(
                messages: List<ChatMessage>, tools: List<ToolSpec>, maxTokens: Int,
                temperature: Float, topK: Int, topP: Float, seed: Int
            ): ChatGeneration = throw InferenceException("no context created")

            override suspend fun releaseContext() = Unit
            override suspend fun unloadModel() = Unit
            override suspend fun getChatTemplate(): String = ""
        }

        val response = LocalModelProvider(failing, "model.gguf").respond(request())

        assertTrue(response is ModelResponse.Text)
        assertTrue((response as ModelResponse.Text).text.contains("no context created"))
    }
}

/**
 * Tests C, D and E: the tool layer is the security boundary. A call the model
 * invents, or one whose arguments do not parse, must come back as a controlled
 * failure the model can read — never a crash, never an execution.
 */
class LocalModelToolExecutionTest {

    // ---- Test C: unknown tool name -----------------------------------------

    @Test
    fun `unknown tool name is rejected and nothing is executed`() = runTest {
        val runtime = StubLocalRuntime().next(
            toolCalls = listOf(ToolCallSpec("call_1", "DeleteEverything", """{"path":"/"}"""))
        )

        val outcome = AgentLoop(
            providerWith(runtime),
            ToolRegistry(listOf(LocalEchoTool())),
            onEvent = {}
        ).run("delete the disk")

        // The invented tool is never registered, so there is nothing to allow.
        assertFalse(outcome.traces.any { it.name == "DeleteEverything" && it.ok })
        assertTrue(outcome.traces.any { it.name == "DeleteEverything" && !it.ok })
    }

    @Test
    fun `unknown tool name is reported back to the model on the next turn`() = runTest {
        val runtime = StubLocalRuntime()
            .next(toolCalls = listOf(ToolCallSpec("call_1", "NoSuchTool", "{}")))
            .next(text = "I cannot do that.")

        AgentLoop(providerWith(runtime), ToolRegistry(listOf(LocalEchoTool())), onEvent = {})
            .run("do something unknown")

        val second = runtime.requests[1].first
        val toolTurn = second.first { it.role == Role.TOOL }
        assertEquals("NoSuchTool", toolTurn.toolName)
        assertTrue(toolTurn.content.contains("Unknown tool"))
    }

    @Test
    fun `a tool outside the allowlist is refused even when registered`() = runTest {
        val runtime = StubLocalRuntime().next(
            toolCalls = listOf(ToolCallSpec("call_1", "Echo", """{"text":"x"}"""))
        )

        val outcome = AgentLoop(
            providerWith(runtime),
            ToolRegistry(listOf(LocalEchoTool())),
            policy = AgentPolicy(allowedTools = emptySet()),
            onEvent = {}
        ).run("echo x")

        assertFalse(outcome.traces.single().ok)
    }

    // ---- Test D: malformed arguments ---------------------------------------

    @Test
    fun `malformed arguments become a controlled tool error`() = runTest {
        val registry = ToolRegistry(listOf(LocalEchoTool()))

        val result = registry.invoke("Echo", "{not valid json")

        assertFalse(result.ok)
        assertTrue(result.error!!.contains("Invalid JSON arguments"))
    }

    @Test
    fun `malformed arguments from the model reach the model as a failure`() = runTest {
        val runtime = StubLocalRuntime()
            .next(toolCalls = listOf(ToolCallSpec("call_1", "Echo", "{not valid json")))
            .next(text = "Sorry, I passed bad arguments.")

        val outcome = AgentLoop(
            providerWith(runtime), ToolRegistry(listOf(LocalEchoTool())), onEvent = {}
        ).run("echo something")

        assertTrue(outcome.traces.any { !it.ok })
        val toolTurn = runtime.requests[1].first.first { it.role == Role.TOOL }
        assertTrue(toolTurn.content.contains("Invalid JSON arguments"))
        // The run still completes: the model got to answer from the failure.
        assertTrue(outcome.ok)
    }

    // ---- Test E: full loop integration -------------------------------------

    @Test
    fun `tool call executes and the result feeds the final answer`() = runTest {
        val runtime = StubLocalRuntime()
            .next(toolCalls = listOf(ToolCallSpec("call_1", "Echo", """{"text":"hello"}""")))
            .next(text = "The tool said hello.")
        val registry = ToolRegistry(listOf(LocalEchoTool()))
        val events = mutableListOf<AgentEvent>()

        val outcome = AgentLoop(providerWith(runtime), registry, onEvent = { events.add(it) })
            .run("echo hello")

        assertTrue(outcome.ok)
        assertEquals("The tool said hello.", outcome.answer)
        assertEquals(1, outcome.traces.size)
        assertEquals("Echo", outcome.traces[0].name)
        assertTrue(outcome.traces[0].ok)

        // The second model turn must carry the assistant call AND the tool
        // result, otherwise the final answer was not really informed by it.
        val second = runtime.requests[1].first
        val assistant = second.first { it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() }
        assertEquals("Echo", assistant.toolCalls.single().name)
        val toolTurn = second.first { it.role == Role.TOOL }
        assertEquals("call_1", toolTurn.toolCallId)
        assertEquals("Echo", toolTurn.toolName)
        assertTrue(toolTurn.content.contains("echo:hello"))

        assertTrue(events.any { it is AgentEvent.ToolStarted })
        assertTrue(events.any { it is AgentEvent.Completed })
    }

    @Test
    fun `loop makes a second model call after a tool call and offers the same tools`() = runTest {
        val runtime = StubLocalRuntime()
            .next(toolCalls = listOf(ToolCallSpec("call_1", "Echo", """{"text":"x"}""")))
            .next(text = "done")

        AgentLoop(providerWith(runtime), ToolRegistry(listOf(LocalEchoTool())), onEvent = {})
            .run("echo x")

        assertEquals(2, runtime.requests.size)
        assertEquals(1, runtime.requests[0].first.count { it.role == Role.USER })
        assertEquals(runtime.requests[0].second, runtime.requests[1].second)
        assertEquals(listOf("Echo"), runtime.requests[0].second.map { it.name })
    }

    @Test
    fun `a tool that fails still lets the model answer from the failure`() = runTest {
        val runtime = StubLocalRuntime()
            .next(toolCalls = listOf(ToolCallSpec("call_1", "Flaky", "{}")))
            .next(text = "The tool is unavailable.")

        val outcome = AgentLoop(
            providerWith(runtime), ToolRegistry(listOf(LocalFailingTool())), onEvent = {}
        ).run("use the flaky tool")

        assertTrue(outcome.ok)
        assertFalse(outcome.traces.single().ok)
        val toolTurn = runtime.requests[1].first.first { it.role == Role.TOOL }
        assertTrue(toolTurn.content.contains("backend unavailable"))
    }

    @Test
    fun `registry specs are what the loop hands the model`() = runTest {
        val registry = ToolRegistry(listOf(LocalEchoTool(), LocalFailingTool()))
        val runtime = StubLocalRuntime().next(
            toolCalls = listOf(ToolCallSpec("call_1", "Flaky", "{}"))
        ).next(text = "done")

        AgentLoop(providerWith(runtime), registry, onEvent = {}).run("use flaky")

        assertEquals(registry.specs(), runtime.requests[0].second)
    }
}
