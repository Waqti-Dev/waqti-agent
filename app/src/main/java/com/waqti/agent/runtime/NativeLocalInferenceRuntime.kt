package com.waqti.agent.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementation of [LocalInferenceRuntime] that delegates to the JNI
 * [NativeRuntime] object. Runs all blocking native calls on the IO dispatcher.
 */
class NativeLocalInferenceRuntime : LocalInferenceRuntime {

    override val label = "local (llama.cpp)"

    override suspend fun loadModel(modelPath: String) = withContext(Dispatchers.IO) {
        val result = NativeRuntime.loadModel(modelPath)
        checkResult("loadModel", result)
    }

    override suspend fun createContext(nCtx: Int, nBatch: Int) = withContext(Dispatchers.IO) {
        val result = NativeRuntime.createContext(nCtx, nBatch)
        checkResult("createContext", result)
    }

    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        seed: Int
    ): String = withContext(Dispatchers.IO) {
        val result = NativeRuntime.generate(prompt, maxTokens, temperature, topK, topP, seed)
        return@withContext extractTextOrThrow("generate", result)
    }

    override suspend fun releaseContext() = withContext(Dispatchers.IO) {
        val result = NativeRuntime.releaseContext()
        checkResult("releaseContext", result)
    }

    override suspend fun unloadModel() = withContext(Dispatchers.IO) {
        val result = NativeRuntime.unloadModel()
        checkResult("unloadModel", result)
    }

    private fun checkResult(op: String, result: String) {
        if (!result.startsWith("ok|")) {
            throw InferenceException("$op failed: $result")
        }
    }

    private fun extractTextOrThrow(op: String, result: String): String {
        if (!result.startsWith("ok|")) {
            throw InferenceException("$op failed: $result")
        }
        // ok|<ms>|tokens=<n>|text=<text>
        val parts = result.split('|')
        val textPart = parts.find { it.startsWith("text=") }
        return textPart?.substringAfter("text=") ?: ""
    }
}