package com.waqti.agent.model

import com.waqti.agent.runtime.InferenceException
import com.waqti.agent.runtime.LocalInferenceRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local [ModelProvider] that uses [LocalInferenceRuntime] for on-device inference.
 * Uses the model's embedded chat template for proper conversation formatting.
 *
 * Conversation state lives entirely in [ModelRequest.messages]: the whole
 * transcript is formatted and prefilled on every call and the KV cache is reset
 * before each prefill, so what the model conditions on is exactly the transcript
 * it was handed. A shared conversation passes the earlier turns; a fresh
 * conversation passes only its own first message — there is no second, hidden
 * carry-over.
 *
 * The agent loop already handles [ModelResponse.Text] and [ModelResponse.Calls];
 * this provider produces only Text responses.
 */
class LocalModelProvider(
    private val runtime: LocalInferenceRuntime,
    private val modelPath: String,
    private val nCtx: Int = 4096,
    private val nBatch: Int = 512,
    private val maxTokens: Int = 256,
    private val temperature: Float = 0.7f,
    private val topK: Int = 40,
    private val topP: Float = 0.9f,
    private val seed: Int = 0
) : ModelProvider {

    override val label = runtime.label

    private var initialized = false

    override suspend fun respond(request: ModelRequest): ModelResponse {
        // Ensure model and context are initialized
        ensureInitialized()

        // The full transcript AND the tool definitions go to the model's own
        // chat template; nPredict is a hard upper bound on the reply, never a
        // target. Tool definitions come straight from the request, so
        // ToolRegistry stays the single source of truth.
        val generated = try {
            runtime.generateChat(
                messages = request.messages,
                tools = request.tools,
                maxTokens = maxTokens,
                temperature = temperature,
                topK = topK,
                topP = topP,
                seed = seed
            )
        } catch (e: InferenceException) {
            return ModelResponse.Text("Error: ${e.message}")
        } catch (e: Exception) {
            return ModelResponse.Text("Error: ${e.message}")
        }

        // Tool calls win over prose: the loop must execute them before it can
        // produce a final answer. Text alongside a call is kept as the call's
        // preamble rather than silently dropped.
        if (generated.toolCalls.isNotEmpty()) {
            // Some chat formats (Qwen2.5 among them) have no tool-call id at all.
            // An empty id would make several calls in one turn indistinguishable
            // once their results come back, so a positional id is used when the
            // model supplied none. The model's own id is never overwritten.
            val calls = generated.toolCalls.mapIndexed { index, call ->
                ToolCall(
                    id = call.id.ifBlank { "call_${index + 1}" },
                    name = call.name,
                    arguments = call.arguments
                )
            }
            val preamble = if (generated.text.isBlank()) "" else generated.text
            return ModelResponse.Calls(
                calls = calls,
                assistantContent = preamble
            )
        }

        return ModelResponse.Text(generated.text)
    }

    private suspend fun ensureInitialized() {
        if (initialized) return

        // Load model if not already loaded
        runtime.loadModel(modelPath)

        // Create context
        runtime.createContext(nCtx, nBatch)

        initialized = true
    }

    /**
     * Explicit release for lifecycle management.
     */
    suspend fun release() {
        runtime.releaseContext()
        runtime.unloadModel()
        initialized = false
    }
}