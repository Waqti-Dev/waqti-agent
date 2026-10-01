package com.waqti.agent.runtime

import com.waqti.agent.ToolSpec
import com.waqti.agent.model.ChatMessage

/**
 * Pure Kotlin interface for local on-device inference.
 * No JNI types, no native pointers — only plain data types.
 * Implemented in :app by [NativeLocalInferenceRuntime].
 */
interface LocalInferenceRuntime {

    /**
     * Short human-readable identity for observability.
     */
    val label: String

    /**
     * Loads a GGUF model from the app's private storage.
     * @param modelPath Path relative to app's filesDir (e.g. "model.gguf")
     * @throws InferenceException on failure
     */
    suspend fun loadModel(modelPath: String)

    /**
     * Creates a generation context with the loaded model.
     * @param nCtx Context window size (e.g. 2048, 4096, 8192)
     * @param nBatch Batch size for prompt processing (e.g. 512, 1024)
     * @throws InferenceException on failure
     */
    suspend fun createContext(nCtx: Int, nBatch: Int)

    /**
     * Generates text from a prompt.
     * @param prompt The input text
     * @param maxTokens Maximum tokens to generate
     * @param temperature Sampling temperature (0.0 = greedy)
     * @param topK Top-K sampling
     * @param topP Top-P (nucleus) sampling
     * @param seed Random seed (0 = random)
     * @return Generated text (without the prompt)
     * @throws InferenceException on failure
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int = 256,
        temperature: Float = 0.7f,
        topK: Int = 40,
        topP: Float = 0.9f,
        seed: Int = 0
    ): String

    /**
     * Releases the generation context (frees KV cache).
     */
    suspend fun releaseContext()

    /**
     * Unloads the model entirely.
     */
    suspend fun unloadModel()

    /**
     * Gets the chat template from the loaded model.
     * @return The chat template string, or empty string if not available
     */
    suspend fun getChatTemplate(): String

    /**
     * Runs one chat turn using the model's own chat template.
     *
     * The template decides how tools are described, how the assistant turn is
     * laid out, and how the reply is read back. The implementation must not
     * hardcode a model's tool syntax or search generated text for markers.
     *
     * @param messages The full transcript, oldest first. Assistant messages may
     *   carry [ChatMessage.toolCalls]; tool messages carry the result and the id
     *   of the call they answer.
     * @param tools Tool definitions the model may call. Empty means "no tools".
     * @param maxTokens Maximum tokens to generate (hard limit, not a target)
     * @return What the model produced: text, tool calls, or both
     * @throws InferenceException on failure
     */
    suspend fun generateChat(
        messages: List<ChatMessage>,
        tools: List<ToolSpec> = emptyList(),
        maxTokens: Int = 256,
        temperature: Float = 0.7f,
        topK: Int = 40,
        topP: Float = 0.9f,
        seed: Int = 0
    ): ChatGeneration
}

/**
 * A tool call the model asked for, as returned by the runtime.
 *
 * Deliberately separate from `ModelResponse.Calls` so the runtime contract does
 * not depend on the agent-loop seam it feeds.
 */
data class ToolCallSpec(
    val id: String,
    val name: String,
    val arguments: String
)

/**
 * What one inference turn produced.
 *
 * Both fields can be set: a model may emit prose and a tool call in the same
 * turn. A turn with neither is treated as an error by the caller rather than
 * silently treated as an empty answer.
 */
data class ChatGeneration(
    val text: String = "",
    val toolCalls: List<ToolCallSpec> = emptyList(),
    /** Why generation ended: eog, n_predict, stop_string, sampler_eof, decode_error. */
    val stopReason: String = "",
    val tokens: Int = 0
)

/**
 * Exception type for local inference failures.
 * Wraps the native error string for UI handling.
 */
class InferenceException(message: String, cause: Throwable? = null) : Exception(message, cause)