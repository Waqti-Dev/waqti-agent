package com.waqti.agent.loop

import com.waqti.agent.model.ChatMessage
import com.waqti.agent.model.ModelProvider
import com.waqti.agent.model.ModelRequest
import com.waqti.agent.model.ModelResponse
import com.waqti.agent.model.Role
import com.waqti.agent.model.ToolCall
import com.waqti.agent.tools.ToolRegistry
import com.waqti.agent.tools.ToolResult
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

const val DEFAULT_SYSTEM_PROMPT: String =
    """You are Waqti, an agent running on a phone. You can use tools to inspect files inside a fixed workspace directory.

Rules:
- Call ListFiles to explore a directory and SearchFiles to find files whose content matches a phrase.
- Call a tool whenever your answer depends on facts you do not already know.
- Answer directly, without tools, when the request needs no external facts.
- After tool results arrive, give a concise final answer that states what was actually found, including exact paths when relevant.
- Never invent tool results. If a tool failed, say that it failed and why.
- Keep the final answer short and in the user's language."""

/**
 * The agent workflow: model decides, tools execute, results flow back, repeat
 * until a final answer or an explicit failure.
 *
 * Owns ModelProvider (what to think), ToolRegistry (what can be executed),
 * AgentPolicy (what is allowed), and event emission (what is observable).
 */
class AgentLoop(
    private val model: ModelProvider,
    private val tools: ToolRegistry,
    private val policy: AgentPolicy = AgentPolicy(),
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    private val onEvent: (AgentEvent) -> Unit = {}
) {

    suspend fun run(task: String): AgentOutcome {
        val trimmed = task.trim()
        if (trimmed.isEmpty()) {
            val message = "Empty task: nothing to do"
            emit(AgentEvent.Failed(message))
            return AgentOutcome(false, null, message, steps = 0, traces = emptyList())
        }

        val started = TimeSource.Monotonic.markNow()
        val traces = ArrayList<ToolTrace>()
        emit(AgentEvent.Started(trimmed, model.label))

        val messages = mutableListOf(
            ChatMessage(Role.SYSTEM, systemPrompt),
            ChatMessage(Role.USER, trimmed)
        )

        val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "waqti-tool").apply { isDaemon = true }
        }

        try {
            var step = 0
            while (step < policy.maxSteps) {
                currentCoroutineContext().ensureActive()
                step++
                emit(AgentEvent.ModelRoundStarted(step))

                val response = try {
                    model.respond(ModelRequest(messages.toList(), tools.specs()))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val detail = e.message ?: e::class.simpleName ?: "unknown error"
                    emit(AgentEvent.Failed(detail))
                    return AgentOutcome(
                        ok = false,
                        answer = null,
                        error = "Model error: $detail",
                        steps = step - 1,
                        traces = traces.toList()
                    )
                }

                when (response) {
                    is ModelResponse.Text -> {
                        val answer = response.text
                        if (answer.isBlank()) {
                            val detail = "Model returned an empty answer"
                            emit(AgentEvent.Failed(detail))
                            return AgentOutcome(false, null, detail, step, traces.toList())
                        }
                        val durationMs = started.elapsedNow().inWholeMilliseconds
                        emit(AgentEvent.Completed(answer, step, durationMs))
                        return AgentOutcome(true, answer, null, step, traces.toList())
                    }

                    is ModelResponse.Calls -> {
                        messages.add(
                            ChatMessage(Role.ASSISTANT, content = "", toolCalls = response.calls)
                        )
                        for (call in response.calls) {
                            currentCoroutineContext().ensureActive()
                            emit(AgentEvent.ToolStarted(step, call.name, call.arguments))

                            val callStarted = TimeSource.Monotonic.markNow()
                            val result = executeTool(executor, call)
                            val durationMs = callStarted.elapsedNow().inWholeMilliseconds
                            val trace = ToolTrace(call.name, result.ok, result.summary(), durationMs)
                            traces.add(trace)

                            emit(
                                AgentEvent.ToolFinished(
                                    step = step,
                                    name = call.name,
                                    ok = result.ok,
                                    summary = trace.summary,
                                    durationMs = durationMs
                                )
                            )
                            messages.add(
                                ChatMessage(
                                    role = Role.TOOL,
                                    content = result.forModel(),
                                    toolCallId = call.id,
                                    toolName = call.name
                                )
                            )
                        }
                    }
                }
            }

            val message = "Stopped after ${policy.maxSteps} model rounds without a final answer"
            emit(AgentEvent.Failed(message))
            return AgentOutcome(false, null, message, policy.maxSteps, traces.toList())
        } finally {
            executor.shutdownNow()
        }
    }

    /** Runs one tool call on a worker thread under the policy timeout. */
    private suspend fun executeTool(executor: ExecutorService, call: ToolCall): ToolResult {
        val deniedByPolicy = policy.allowedTools?.contains(call.name) == false
        if (deniedByPolicy) {
            return ToolResult.failure("Tool '${call.name}' is not permitted by policy")
        }

        return withContext(Dispatchers.IO) {
            val future = executor.submit(Callable { tools.invoke(call.name, call.arguments) })
            try {
                future.get(policy.toolTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                ToolResult.failure("Tool '${call.name}' timed out after ${policy.toolTimeoutMs} ms")
            } catch (e: ExecutionException) {
                ToolResult.failure("Tool '${call.name}' failed: ${e.cause?.message ?: e.message}")
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                ToolResult.failure("Tool '${call.name}' was interrupted")
            }
        }
    }

    private fun emit(event: AgentEvent) {
        try {
            onEvent(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Observability must never break the run itself.
        }
    }
}
