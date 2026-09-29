package com.waqti.agent.tools

import com.waqti.agent.ToolSpec
import org.json.JSONException
import org.json.JSONObject

/**
 * Holds the tools the agent may execute. Invocation never throws: unknown tools,
 * malformed JSON, and tool crashes all come back as explicit failures the model
 * can read.
 */
class ToolRegistry(private val tools: List<Tool>) {

    private val byName: Map<String, Tool> = tools.associateBy { it.spec.name }

    fun specs(): List<ToolSpec> = tools.map { it.spec }

    fun names(): List<String> = tools.map { it.spec.name }

    fun isKnown(name: String): Boolean = byName.containsKey(name)

    /** Executes [name] with raw JSON [rawArguments]. Result is always explicit. */
    fun invoke(name: String, rawArguments: String): ToolResult {
        val tool = byName[name]
            ?: return ToolResult.failure(
                "Unknown tool '$name'. Available tools: ${names().joinToString(", ")}"
            )

        val args = try {
            if (rawArguments.isBlank()) JSONObject() else JSONObject(rawArguments)
        } catch (e: JSONException) {
            return ToolResult.failure("Invalid JSON arguments for $name: ${e.message}")
        }

        return try {
            val result = tool.execute(args)
            if (!result.ok && result.error.isNullOrBlank()) {
                ToolResult.failure("Tool $name failed without an error message")
            } else {
                result
            }
        } catch (e: WorkspaceError) {
            ToolResult.failure(e.message ?: "Path rejected by workspace policy")
        } catch (e: Exception) {
            ToolResult.failure("Tool $name crashed: ${e::class.simpleName}: ${e.message}")
        }
    }
}
