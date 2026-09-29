package com.waqti.agent.tools

import com.waqti.agent.ToolSpec
import org.json.JSONObject

/** Result of one tool execution. */
data class ToolResult(
    val ok: Boolean,
    val output: String = "",
    val error: String? = null
) {
    /** What the model sees as the tool message content. */
    fun forModel(): String = if (ok) output else "ERROR: ${error ?: "unknown error"}"

    /** One short line for the UI activity trace. */
    fun summary(maxChars: Int = 140): String {
        val source = if (ok) output else "failed: ${error ?: "unknown error"}"
        return source.lineSequence().firstOrNull().orEmpty().take(maxChars)
    }

    companion object {
        fun success(output: String): ToolResult = ToolResult(ok = true, output = output)
        fun failure(error: String): ToolResult = ToolResult(ok = false, error = error)
    }
}

/**
 * A real capability the agent can execute. Tools are synchronous, deterministic
 * where practical, and never throw for expected failures.
 */
interface Tool {
    val spec: ToolSpec

    /** Executes with parsed JSON arguments. Unexpected exceptions are contained by [ToolRegistry]. */
    fun execute(args: JSONObject): ToolResult
}
