package com.waqti.agent.model

import com.waqti.agent.ToolSpec

/** Role of a message in the model conversation protocol. */
enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

fun Role.wireName(): String = when (this) {
    Role.SYSTEM -> "system"
    Role.USER -> "user"
    Role.ASSISTANT -> "assistant"
    Role.TOOL -> "tool"
}

/**
 * A tool invocation requested by the model. [arguments] is the raw JSON object
 * text produced by the model; parsing/validation belongs to the tool layer.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

data class ChatMessage(
    val role: Role,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null
)

sealed interface ModelResponse {
    /** The model produced a final answer. */
    data class Text(val text: String) : ModelResponse

    /**
     * The model wants tools executed before it can answer.
     *
     * [assistantContent] is any prose the model emitted in the same turn before
     * asking for the calls. It defaults to empty so existing callers and tests
     * that only care about the calls are unaffected.
     */
    data class Calls(
        val calls: List<ToolCall>,
        val assistantContent: String = ""
    ) : ModelResponse
}

data class ModelRequest(
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec>
)

/**
 * Narrow model boundary: the agent core knows only this interface, never a vendor,
 * transport, tokenizer or inference runtime.
 */
interface ModelProvider {
    /** Short human-readable identity for observability, e.g. "local @ 127.0.0.1:8080". */
    val label: String

    suspend fun respond(request: ModelRequest): ModelResponse
}

/** Explicit model failure with a message meant to be shown to the user. */
class ModelException(message: String, cause: Throwable? = null) : Exception(message, cause)
