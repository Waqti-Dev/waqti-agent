package com.waqti.agent.tools

import com.waqti.agent.ToolSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    private class EchoTool : Tool {
        override val spec = ToolSpec("Echo", "Echoes text.", """{"type":"object"}""")

        override fun execute(args: JSONObject): ToolResult =
            ToolResult.success("echo:${args.optString("text")}")
    }

    private class BrokenTool : Tool {
        override val spec = ToolSpec("Broken", "Throws.", """{"type":"object"}""")

        override fun execute(args: JSONObject): ToolResult = throw IllegalStateException("kaboom")
    }

    private val registry = ToolRegistry(listOf(EchoTool(), BrokenTool()))

    @Test
    fun `specs and names are exposed`() {
        assertEquals(listOf("Echo", "Broken"), registry.names())
        assertEquals(2, registry.specs().size)
        assertTrue(registry.isKnown("Echo"))
        assertFalse(registry.isKnown("Nope"))
    }

    @Test
    fun `valid arguments execute the tool`() {
        val result = registry.invoke("Echo", """{"text":"hi"}""")
        assertTrue(result.ok)
        assertEquals("echo:hi", result.output)
    }

    @Test
    fun `blank arguments are treated as an empty object`() {
        val result = registry.invoke("Echo", "")
        assertTrue(result.ok)
        assertEquals("echo:", result.output)
    }

    @Test
    fun `unknown tool returns explicit failure listing available tools`() {
        val result = registry.invoke("Delete", "{}")
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("Unknown tool"))
        assertTrue(result.error!!.contains("Echo"))
    }

    @Test
    fun `malformed json is rejected as invalid arguments`() {
        val result = registry.invoke("Echo", "not json at all")
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("Invalid JSON arguments"))
    }

    @Test
    fun `json array instead of object is rejected`() {
        val result = registry.invoke("Echo", """["a","b"]""")
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("Invalid JSON arguments"))
    }

    @Test
    fun `tool crash is contained as failure`() {
        val result = registry.invoke("Broken", "{}")
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("crashed"))
        assertTrue(result.error!!.contains("kaboom"))
    }

    @Test
    fun `failure result without message gets a generic error`() {
        val anonymous = ToolRegistry(
            listOf(object : Tool {
                override val spec = ToolSpec("Silent", "Fails silently.", """{"type":"object"}""")
                override fun execute(args: JSONObject): ToolResult = ToolResult(ok = false, output = "")
            })
        )
        val result = anonymous.invoke("Silent", "{}")
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("without an error message"))
    }

    @Test
    fun `forModel renders success and failure for the model`() {
        assertEquals("line one\nline two", ToolResult.success("line one\nline two").forModel())
        assertEquals("ERROR: broken", ToolResult.failure("broken").forModel())
    }

    @Test
    fun `summary keeps the first line within bounds`() {
        val multi = ToolResult.success("first line\nsecond line")
        assertEquals("first line", multi.summary())
        assertEquals("ab", ToolResult.success("abcdefghij").summary(2))
        assertTrue(ToolResult.failure("bad thing happened").summary().startsWith("failed: "))
    }
}
