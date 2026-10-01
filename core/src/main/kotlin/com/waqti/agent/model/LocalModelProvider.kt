package com.waqti.agent.model

import com.waqti.agent.runtime.InferenceException
import com.waqti.agent.runtime.LocalInferenceRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local [ModelProvider] that uses [LocalInferenceRuntime] for on-device inference.
 * Maps raw generated text to [ModelResponse.Text].
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

        // Convert chat messages to a simple prompt string
        // For now, use a basic concatenation. Chat template support can be added later.
        val prompt = buildPrompt(request.messages)

        // Generate
        val generated = try {
            runtime.generate(
                prompt = prompt,
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

        return ModelResponse.Text(generated)
    }

    private suspend fun ensureInitialized() {
        if (initialized) return

        // Load model if not already loaded
        runtime.loadModel(modelPath)

        // Create context
        runtime.createContext(nCtx, nBatch)

        initialized = true
    }

    private fun buildPrompt(messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        for (msg in messages) {
            when (msg.role) {
                Role.SYSTEM -> sb.append("System: ${msg.content}\n\n")
                Role.USER -> sb.append("User: ${msg.content}\n\n")
                Role.ASSISTANT -> sb.append("Assistant: ${msg.content}\n\n")
                Role.TOOL -> sb.append("Tool result (${msg.toolName}): ${msg.content}\n\n")
            }
        }
        sb.append("Assistant: ")
        return sb.toString()
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