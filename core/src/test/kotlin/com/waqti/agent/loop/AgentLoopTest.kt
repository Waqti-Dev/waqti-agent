package com.waqti.agent.loop

import com.waqti.agent.ToolSpec
import com.waqti.agent.model.ModelResponse
import com.waqti.agent.model.Role
import com.waqti.agent.model.ToolCall
import com.waqti.agent.tools.Tool
import com.waqti.agent.tools.ToolRegistry
import com.waqti.agent.tools.ToolResult
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behaviour protection for the agent loop. */

private class EchoTool : Tool {
    override val spec = ToolSpec(
        name = "Echo",
        description = "Echoes the arguments back.",
        parameters = """{"type":"object","properties":{"text":{"type":"string"}}}"""
    )

    override fun execute(args: JSONObject): ToolResult =
        ToolResult.success("echo:${args.optString("text")}")
}

private class FailingTool : Tool {
    override val spec = ToolSpec(
        name = "Failing",
        description = "Always fails.",
        parameters = """{"type":"object"}"""
    )

    override fun execute(args: JSONObject): ToolResult =
        ToolResult.failure("backend unavailable")
}

private class CrashingTool : Tool {
    override val spec = ToolSpec(
        name = "Crashing",
        description = "Throws unexpectedly.",
        parameters = """{"type":"object"}"""
    )

    override fun execute(args: JSONObject): ToolResult =
        throw IllegalStateException("boom")
}

private class SlowTool(private val millis: Long) : Tool {
    override val spec = ToolSpec(
        name = "Slow",
        description = "Sleeps.",
        parameters = """{"type":"object"}"""
    )

    override fun execute(args: JSONObject): ToolResult {
        Thread.sleep(millis)
        return ToolResult.success("woke up")
    }
}

private fun call(id: String, name: String, arguments: String = "{}") =
    ToolCall(id = id, name = name, arguments = arguments)

class AgentLoopTest {

    // Case 1: model returns a final answer immediately.
    @Test
    fun `model returns final answer immediately`() = runTest {
        val model = FakeModelProvider(listOf(ModelResponse.Text("hello from the agent")))
        val events = mutableListOf<AgentEvent>()

        val outcome = AgentLoop(model, ToolRegistry(emptyList()), onEvent = { events.add(it) }).run("say hi")

        assertTrue(outcome.ok)
        assertEquals("hello from the agent", outcome.answer)
        assertEquals(1, outcome.steps)
        assertTrue(outcome.traces.isEmpty())
        assertEquals(1, model.requests.size)
        assertTrue(events.any { it is AgentEvent.Completed })
        assertFalse(events.any { it is AgentEvent.ToolStarted })
    }

    // Case 2: model requests a tool.
    @Test
    fun `model requests a tool`() = runTest {
        val model = FakeModelProvider(
            listOf(
                ModelResponse.Calls(listOf(call("c1", "Echo", """{"text":"hi"}"""))),
                ModelResponse.Text("done")
            )
        )
        val events = mutableListOf<AgentEvent>()

        AgentLoop(model, ToolRegistry(listOf(EchoTool())), onEvent = { events.add(it) })
            .run("echo something")

        val toolStarted = events.filterIsInstance<AgentEvent.ToolStarted>().single()
        assertEquals("Echo", toolStarted.name)
        assertTrue(toolStarted.arguments.contains("hi"))
    }

    // Case 3 + 4: tool executes and its real result flows back to the model.
    @Test
    fun `tool result is returned to the model as a tool message`() = runTest {
        val model = FakeModelProvider(
            listOf(
                ModelResponse.Calls(listOf(call("c1", "Echo", """{"text":"real-execution"}"""))),
                ModelResponse.Text("answer")
            )
        )

        AgentLoop(model, ToolRegistry(listOf(EchoTool()))).run("task")

        assertEquals(2, model.requests.size)
        val followUp = model.requests[1]
        // Assistant turn must carry the tool call it made.
        val assistant = followUp.messages.first { it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() }
        assertEquals("Echo", assistant.toolCalls.single().name)
        // Tool turn must carry the actual execution output.
        val toolMessage = followUp.messages.first { it.role == Role.TOOL }
        assertEquals("c1", toolMessage.toolCallId)
        assertEquals("echo:real-execution", toolMessage.content)
    }

    // Case 5: agent produces the final answer from tool output.
    @Test
    fun `final answer follows tool execution`() = runTest {
        val model = FakeModelProvider(
            listOf(
                ModelResponse.Calls(listOf(call("c1", "Echo", """{"text":"payload"}"""))),
                ModelResponse.Text("The tool reported echo:payload")
            )
        )

        val events = mutableListOf<AgentEvent>()
        val outcome = AgentLoop(model, ToolRegistry(listOf(EchoTool())), onEvent = { events.add(it) })
            .run("find it")

        assertTrue(outcome.ok)
        assertEquals("The tool reported echo:payload", outcome.answer)
        assertEquals(2, outcome.steps)
        val trace = outcome.traces.single()
        assertEquals("Echo", trace.name)
        assertTrue(trace.ok)
        assertTrue(trace.summary.contains("payload"))
        assertTrue(events.filterIsInstance<AgentEvent.ToolFinished>().single().ok)
    }

    // Case 6: tool execution fails and the failure reaches the model.
    @Test
    fun `tool failure is reported to the model and run still completes`() = runTest {
        val model = FakeModelProvider(
            listOf(
                ModelResponse.Calls(listOf(call("c1", "Failing"))),
                ModelResponse.Text("the tool failed, so I cannot answer")
            )
        )

        val outcome = AgentLoop(model, ToolRegistry(listOf(FailingTool()))).run("try it")

        assertTrue(outcome.ok)
        val toolMessage = model.requests[1].messages.first { it.role == Role.TOOL }
        assertTrue(toolMessage.content.startsWith("ERROR:"))
        assertTrue(toolMessage.content.contains("backend unavailable"))
        assertFalse(outcome.traces.single().ok)
    }

    // Case 7: invalid tool arguments are rejected.
    @Test
    fun `invalid tool arguments are rejected as tool failure`() = runTest {
        val model = FakeModelProvider(
            listOf(
                ModelResponse.Calls(listOf(call("c1", "Echo", "this is not json"))),
                ModelResponse.Text("arguments were malformed")
            )
        )

        val outcome = AgentLoop(model, ToolRegistry(listOf(EchoTool()))).run("break it")

        assertTrue(outcome.ok)
        val toolMessage = model.requests[1].messages.first { it.role == Role.TOOL }
        assertTrue(toolMessage.content.contains("Invalid JSON arguments"))
        assertFalse(outcome.traces.single().ok)
    }

    @Test
    fun `unknown tool name is rejected with available tools`() = runTest {
        val model = FakeModelProvider(
            listOf(
                ModelResponse.Calls(listOf(call("c1", "DeleteEverything"))),
                ModelResponse.Text("ok")
            )
        )

        AgentLoop(model, ToolRegistry(listOf(EchoTool()))).run("task")

        val toolMessage = model.requests[1].messages.first { it.role == Role.TOOL }
        assertTrue(toolMessage.content.contains("Unknown tool"))
        assertTrue(toolMessage.content.contains("Echo"))
    }

    @Test
    fun `policy denies non allowed tools`() = runTest {
        val model = FakeModelProvider(
            listOf(
                ModelResponse.Calls(listOf(call("c1", "Echo"))),
                ModelResponse.Text("ok")
            )
        )

        val outcome = AgentLoop(
            model,
            ToolRegistry(listOf(EchoTool())),
            policy = AgentPolicy(allowedTools = emptySet())
        ).run("task")

        assertTrue(outcome.ok)
        val toolMessage = model.requests[1].messages.first { it.role == Role.TOOL }
        assertTrue(toolMessage.content.contains("not permitted"))
    }

    @Test
    fun `model failure becomes an explicit failed outcome`() = runTest {
        val model = FakeModelProvider(emptyList())
        val events = mutableListOf<AgentEvent>()

        val outcome = AgentLoop(model, ToolRegistry(emptyList()), onEvent = { events.add(it) }).run("task")

        assertFalse(outcome.ok)
        assertNull(outcome.answer)
        assertNotNull(outcome.error)
        assertTrue(outcome.error!!.contains("script exhausted"))
        assertTrue(events.last() is AgentEvent.Failed)
    }

    @Test
    fun `step limit ends in explicit failure`() = runTest {
        val model = FakeModelProvider(
            (1..5).map { ModelResponse.Calls(listOf(call("c$it", "Echo"))) }
        )

        val outcome = AgentLoop(
            model,
            ToolRegistry(listOf(EchoTool())),
            policy = AgentPolicy(maxSteps = 3)
        ).run("task")

        assertFalse(outcome.ok)
        assertTrue(outcome.error!!.contains("Stopped after 3 model rounds"))
        assertEquals(3, model.requests.size)
        assertEquals(3, outcome.traces.size)
    }

    @Test
    fun `tool timeout becomes a tool failure`() = runTest {
        val model = FakeModelProvider(
            listOf(
                ModelResponse.Calls(listOf(call("c1", "Slow"))),
                ModelResponse.Text("timed out but recovered")
            )
        )

        val outcome = AgentLoop(
            model,
            ToolRegistry(listOf(SlowTool(millis = 60_000))),
            policy = AgentPolicy(toolTimeoutMs = 150)
        ).run("task")

        assertTrue(outcome.ok)
        val toolMessage = model.requests[1].messages.first { it.role == Role.TOOL }
        assertTrue(toolMessage.content.contains("timed out"))
        assertFalse(outcome.traces.single().ok)
    }

    @Test
    fun `empty task fails without calling the model`() = runTest {
        val model = FakeModelProvider(listOf(ModelResponse.Text("unused")))

        val outcome = AgentLoop(model, ToolRegistry(emptyList())).run("   ")

        assertFalse(outcome.ok)
        assertTrue(outcome.error!!.contains("Empty task"))
        assertTrue(model.requests.isEmpty())
    }

    @Test
    fun `system prompt and user task are the first messages`() = runTest {
        val model = FakeModelProvider(listOf(ModelResponse.Text("ok")))

        AgentLoop(model, ToolRegistry(emptyList())).run("inspect the workspace")

        val messages = model.requests[0].messages
        assertEquals(Role.SYSTEM, messages[0].role)
        assertEquals(Role.USER, messages[1].role)
        assertEquals("inspect the workspace", messages[1].content)
        assertTrue(messages[0].content.contains("Waqti"))
    }

    @Test
    fun `system prompt forbids treating a failed tool call as evidence`() = runTest {
        val model = FakeModelProvider(listOf(ModelResponse.Text("ok")))

        AgentLoop(model, ToolRegistry(emptyList())).run("task")

        val system = model.requests[0].messages[0].content
        assertTrue(system.contains("failed tool call proves nothing"))
        assertTrue(system.contains("call again"))
    }

    @Test
    fun `tool specs are advertised to the model`() = runTest {
        val model = FakeModelProvider(listOf(ModelResponse.Text("ok")))

        AgentLoop(model, ToolRegistry(listOf(EchoTool()))).run("task")

        val tools = model.requests[0].tools
        assertEquals("Echo", tools.single().name)
        assertTrue(tools.single().parameters.contains("\"text\""))
        assertTrue(tools.single().description.isNotBlank())
    }
}
