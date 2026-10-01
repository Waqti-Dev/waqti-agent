package com.waqti.agent.runtime

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
}

/**
 * Exception type for local inference failures.
 * Wraps the native error string for UI handling.
 */
class InferenceException(message: String, cause: Throwable? = null) : Exception(message, cause)