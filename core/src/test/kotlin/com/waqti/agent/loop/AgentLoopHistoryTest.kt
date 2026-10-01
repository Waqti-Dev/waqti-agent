package com.waqti.agent.loop

import com.waqti.agent.ToolSpec
import com.waqti.agent.model.ChatMessage
import com.waqti.agent.model.ModelResponse
import com.waqti.agent.model.Role
import com.waqti.agent.model.ToolCall
import com.waqti.agent.tools.Tool
import com.waqti.agent.tools.ToolRegistry
import com.waqti.agent.tools.ToolResult
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class HistoryEchoTool : Tool {
    override val spec = ToolSpec(
        name = "Echo",
        description = "Echoes the arguments back.",
        parameters = """{"type":"object","properties":{"text":{"type":"string"}}}"""
    )

    override fun execute(args: JSONObject): ToolResult =
        ToolResult.success("echo:${args.optString("text")}")
}

/**
 * Conversation continuity for [AgentLoop.run].
 *
 * A chat UI keeps showing earlier turns; without re-sending them the model can
 * only ever see SYSTEM + the newest message, so "what is my favourite colour?"
 * after "my favourite colour is teal" is unanswerable. These tests pin the
 * transcript the loop actually hands to the provider.
 */
class AgentLoopHistoryTest {

    private fun loopWith(model: FakeModelProvider) =
        AgentLoop(model, ToolRegistry(emptyList()))

    @Test
    fun `no history keeps the previous single turn transcript`() = runTest {
        val model = FakeModelProvider(listOf(ModelResponse.Text("ok")))

        loopWith(model).run("say hi")

        val sent = model.requests.single().messages
        assertEquals(2, sent.size)
        assertEquals(Role.SYSTEM, sent[0].role)
        assertEquals(Role.USER, sent[1].role)
        assertEquals("say hi", sent[1].content)
    }

    @Test
    fun `prior turns are sent between the system prompt and the new task`() = runTest {
        val model = FakeModelProvider(listOf(ModelResponse.Text("ok")))
        val history = listOf(
            ChatMessage(Role.USER, "my favourite colour is teal"),
            ChatMessage(Role.ASSISTANT, "noted, teal it is")
        )

        loopWith(model).run("what is my favourite colour?", history)

        val sent = model.requests.single().messages
        assertEquals(4, sent.size)
        assertEquals(Role.SYSTEM, sent[0].role)
        assertEquals(Role.USER, sent[1].role)
        assertEquals("my favourite colour is teal", sent[1].content)
        assertEquals(Role.ASSISTANT, sent[2].role)
        assertEquals("noted, teal it is", sent[2].content)
        assertEquals(Role.USER, sent[3].role)
        assertEquals("what is my favourite colour?", sent[3].content)
    }

    @Test
    fun `system and tool turns in the supplied history are not echoed back`() = runTest {
        val model = FakeModelProvider(listOf(ModelResponse.Text("ok")))
        val history = listOf(
            ChatMessage(Role.SYSTEM, "a stale system prompt"),
            ChatMessage(Role.USER, "first"),
            ChatMessage(Role.ASSISTANT, ""),
            ChatMessage(Role.TOOL, "tool output", toolName = "Echo"),
            ChatMessage(Role.ASSISTANT, "second")
        )

        loopWith(model).run("next", history)

        val sent = model.requests.single().messages
        assertEquals(listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.USER), sent.map { it.role })
        assertEquals(DEFAULT_SYSTEM_PROMPT, sent[0].content)
        assertEquals("first", sent[1].content)
        assertEquals("second", sent[2].content)
        assertEquals("next", sent[3].content)
    }

    @Test
    fun `long transcripts are trimmed to the newest turns`() = runTest {
        val model = FakeModelProvider(listOf(ModelResponse.Text("ok")))
        val history = (1..MAX_HISTORY_MESSAGES + 20).map {
            ChatMessage(Role.USER, "turn $it")
        }

        loopWith(model).run("latest", history)

        val sent = model.requests.single().messages
        // SYSTEM + capped history + the new task
        assertEquals(MAX_HISTORY_MESSAGES + 2, sent.size)
        // the oldest turns are the ones dropped
        assertEquals("turn ${history.size - MAX_HISTORY_MESSAGES + 1}", sent[1].content)
        assertEquals("turn ${history.size}", sent[MAX_HISTORY_MESSAGES].content)
        assertEquals("latest", sent.last().content)
    }

    @Test
    fun `history survives a tool round trip`() = runTest {
        val model = FakeModelProvider(
            listOf(
                ModelResponse.Calls(listOf(ToolCall("c1", "Echo", """{"text":"hi"}"""))),
                ModelResponse.Text("done")
            )
        )

        AgentLoop(model, ToolRegistry(listOf(HistoryEchoTool())), onEvent = {})
            .run("echo something", listOf(ChatMessage(Role.USER, "earlier")))

        assertEquals(2, model.requests.size)
        val second = model.requests[1].messages
        assertEquals("earlier", second[1].content)
        assertTrue(second.any { it.role == Role.TOOL })
        assertEquals("echo something", second.first { it.role == Role.USER && it.content == "echo something" }.content)
    }
}