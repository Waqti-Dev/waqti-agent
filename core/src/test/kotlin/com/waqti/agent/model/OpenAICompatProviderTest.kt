package com.waqti.agent.model

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Wire-protocol contract tests: a real HTTP round trip against a local stub
 * endpoint, so both the outgoing request and the parsed response are verified
 * instead of inspecting private methods.
 */
class OpenAICompatProviderTest {

    private lateinit var server: HttpServer
    private val receivedBody = AtomicReference<String>("")

    @Before
    fun startStubEndpoint() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            receivedBody.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val payload = """{"choices":[{"message":{"content":"12"}}]}""".toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
    }

    @After
    fun stopStubEndpoint() = server.stop(0)

    private fun provider() = OpenAICompatProvider(
        baseUrl = "http://127.0.0.1:${server.address.port}/v1",
        model = "unit-model"
    )

    @Test
    fun `text response is parsed`() = runTest {
        val response = provider().respond(
            ModelRequest(listOf(ChatMessage(Role.USER, "hi")), emptyList())
        )
        assertEquals(ModelResponse.Text("12"), response)
    }

    @Test
    fun `request is bounded by a token cap`() = runTest {
        provider().respond(
            ModelRequest(listOf(ChatMessage(Role.USER, "What is 7 plus 5?")), emptyList())
        )

        val body = JSONObject(receivedBody.get())
        assertEquals(512, body.getInt("max_tokens"))
        assertEquals("unit-model", body.getString("model"))
        assertEquals(false, body.getBoolean("stream"))
        assertEquals(0.2, body.getDouble("temperature"), 0.0)

        val message = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", message.getString("role"))
        assertEquals("What is 7 plus 5?", message.getString("content"))
        // No tools are advertised when the agent has none registered.
        assertFalse(body.has("tools"))
    }

    @Test
    fun `tool calls in the response are parsed`() = runTest {
        // Same stub, but a response that asks for a tool instead of text.
        server.removeContext("/v1/chat/completions")
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.readBytes()
            val payload = """
                {"choices":[{"message":{"content":"","tool_calls":[
                  {"id":"call_7","type":"function",
                   "function":{"name":"SearchFiles","arguments":"{\"query\":\"TODO\"}"}}]}}]}
            """.trimIndent().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }

        val response = provider().respond(
            ModelRequest(listOf(ChatMessage(Role.USER, "find TODO")), emptyList())
        )

        val calls = (response as ModelResponse.Calls).calls
        assertEquals(1, calls.size)
        assertEquals("call_7", calls[0].id)
        assertEquals("SearchFiles", calls[0].name)
        assertEquals("""{"query":"TODO"}""", calls[0].arguments)
    }
}
