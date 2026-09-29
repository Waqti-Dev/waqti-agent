package com.waqti.agent.loop

/** Execution limits and permissions for one agent run. */
data class AgentPolicy(
    /** Maximum model rounds before the run is failed explicitly. */
    val maxSteps: Int = 6,
    /** Wall-clock budget for a single tool execution. */
    val toolTimeoutMs: Long = 15_000,
    /** Explicit allowlist; null means "every registered tool is permitted". */
    val allowedTools: Set<String>? = null
)

/** Observable steps of one agent run. Emitted synchronously from the loop. */
sealed interface AgentEvent {
    data class Started(val task: String, val modelLabel: String) : AgentEvent
    data class ModelRoundStarted(val step: Int) : AgentEvent
    data class ToolStarted(val step: Int, val name: String, val arguments: String) : AgentEvent
    data class ToolFinished(
        val step: Int,
        val name: String,
        val ok: Boolean,
        val summary: String,
        val durationMs: Long
    ) : AgentEvent

    data class Completed(val answer: String, val steps: Int, val durationMs: Long) : AgentEvent
    data class Failed(val message: String) : AgentEvent
}

/** One executed tool call, kept for the final answer trace. */
data class ToolTrace(
    val name: String,
    val ok: Boolean,
    val summary: String,
    val durationMs: Long
)

/** Terminal result of an agent run. */
data class AgentOutcome(
    val ok: Boolean,
    val answer: String?,
    val error: String?,
    val steps: Int,
    val traces: List<ToolTrace>
)
