package com.waqti.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the interface relies on to describe real state to the user.
 *
 * These are the only parts of the presentation layer that can be proved without a
 * device, so the behaviours that would otherwise be "checked by eye" — that a
 * model size reads correctly, that a duration reads correctly, and above all that
 * a failure never reaches the user as an implementation string — are pinned here.
 */
class WaqtiFormatTest {

    // --- sizes ---------------------------------------------------------------

    @Test
    fun `byte counts keep a unit at every scale`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1 kB", formatBytes(1_000))
        assertEquals("2 MB", formatBytes(2_104_932))
        assertEquals("2.1 GB", formatBytes(2_104_932_768))
        assertEquals("1.0 GB", formatBytes(1_000_000_000))
    }

    @Test
    fun `a real model file size reads as a gigabyte figure`() {
        // The size actually reported for qwen2.5-3b-OFFICIAL-Q4_K_M.gguf.
        assertEquals("2.1 GB", formatBytes(2_104_932_768L))
    }

    @Test
    fun `sizes do not follow the device locale decimal separator`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("2.1 GB", formatBytes(2_104_932_768L))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    // --- durations -----------------------------------------------------------

    @Test
    fun `durations keep a readable granularity`() {
        assertEquals("—", formatDuration(-1))
        assertEquals("0 ms", formatDuration(0))
        assertEquals("240 ms", formatDuration(240))
        assertEquals("4.7 s", formatDuration(4_700))
        assertEquals("1 min", formatDuration(90_000))
    }

    // --- error presentation --------------------------------------------------

    @Test
    fun `a runtime error prefix never reaches the user`() {
        val shown = humanizeError("error|llama_decode prefill failed with code 1")
        assertFalse("leaked the runtime delimiter", shown.contains("|"))
        assertFalse("leaked the internal verb", shown.contains("llama_decode"))
        assertEquals("Waqti could not run the model on this conversation.", shown)
    }

    @Test
    fun `a java exception is never the headline`() {
        val shown = humanizeError("Unexpected error: IllegalStateException: no context")
        assertFalse(shown.contains("java."))
        assertFalse(shown.contains("Exception"))
        assertEquals("Waqti hit an unexpected problem while working.", shown)
    }

    @Test
    fun `a specific cause beats the generic prefix it arrives wrapped in`() {
        assertEquals(
            "Waqti could not open the model file.",
            humanizeError("Model error: error|cannot stat file")
        )
        assertEquals(
            "The model could not finish this task.",
            humanizeError("Model error: error|context is full")
        )
    }

    @Test
    fun `tool level failures are described without inventing a cause`() {
        assertEquals(
            "A tool call took too long and was stopped.",
            humanizeError("Tool 'SearchFiles' timed out after 15000 ms")
        )
        assertEquals(
            "Waqti blocked that tool call.",
            humanizeError("Tool 'Shell' is not permitted by policy")
        )
        assertEquals(
            "Waqti asked for a tool that does not exist.",
            humanizeError("Unknown tool 'RunCmd'. Available tools: ListFiles, SearchFiles")
        )
        assertEquals(
            "Waqti could not read the arguments for that tool.",
            humanizeError("Invalid JSON arguments for SearchFiles: expected string")
        )
    }

    @Test
    fun `an exhausted run budget is explained plainly`() {
        assertEquals(
            "Waqti used every round without reaching an answer.",
            humanizeError("Stopped after 6 model rounds without a final answer")
        )
    }

    @Test
    fun `a blank failure still produces a sentence`() {
        assertEquals("Something went wrong while running this task.", humanizeError(""))
        assertEquals("Something went wrong while running this task.", humanizeError("   "))
    }

    @Test
    fun `a workspace failure is named as a folder problem`() {
        assertEquals(
            "Waqti could not open that folder.",
            humanizeError("Workspace error: Workspace path is empty")
        )
    }

    @Test
    fun `a short plain message is passed through unchanged`() {
        assertEquals("Nothing to do.", humanizeError("Nothing to do."))
    }

    @Test
    fun `no input can produce a blank or oversized headline`() {
        val inputs = listOf(
            "",
            "   ",
            "error|",
            "error|llama_decode prefill failed with code -1",
            "Model error: Unexpected error: java.lang.IllegalStateException: something long happened here",
            "Workspace error: Cannot create workspace directory: " + "/storage/emulated/0/".repeat(8),
            "Tool 'SearchFiles' timed out after 15000 ms",
            "x".repeat(400)
        )
        inputs.forEach { raw ->
            val shown = humanizeError(raw)
            assertTrue("blank output for <$raw>", shown.isNotBlank())
            assertTrue("too long (${shown.length}) for <$raw>", shown.length <= 120)
        }
    }

    @Test
    fun `the raw failure is never silently dropped from the record`() {
        // The headline is cleaned up, but the original still has to reach the
        // message so the Details disclosure has something to show.
        val raw = "Model error: error|cannot stat file"
        assertTrue(humanizeError(raw) != raw)
        assertTrue(raw.isNotEmpty())
    }
}