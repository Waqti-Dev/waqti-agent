package com.waqti.agent.runtime

/**
 * The only JNI surface of the app: a thin handle on the in-process llama.cpp
 * runtime. Nothing outside this package may call it — the UI and the agent
 * speak [com.waqti.agent.model.ModelProvider] only.
 *
 * This object deliberately exposes no generation yet; that arrives with
 * [com.waqti.agent.model.LocalModelProvider] once loading and inference are
 * proven on device.
 */
object NativeRuntime {

    init {
        System.loadLibrary("waqti_local_runtime")
    }

    /** llama.cpp build version and detected CPU features of this device. */
    external fun versionInfo(): String

    /**
     * Loads a GGUF from this app's own storage into this process (in-process
     * load — no server, no socket, no other process).
     *
     * Returns a `|`-separated status string and never throws:
     * `ok|<load time>|<architecture>|name=…|bytes=…` on success,
     * `error|<reason>` on failure (missing file, rejected GGUF, OOM, ...).
     *
     * Blocks for the whole load — must be called off the main thread.
     */
    external fun loadModel(path: String): String

    /**
     * Creates a generation context with the loaded model.
     * Returns `ok|<init ms>|ctx=<ctx size>|n_batch=<batch>` on success,
     * `error|<reason>` on failure.
     */
    external fun createContext(nCtx: Int, nBatch: Int): String

    /**
     * Generates text from a prompt using the loaded model and context.
     * Returns `ok|<gen ms>|tokens=<n>|text=<generated text>` on success,
     * `error|<reason>` on failure.
     */
    external fun generate(
        prompt: String,
        nPredict: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        seed: Int
    ): String

    /**
     * Releases the generation context (frees KV cache etc.)
     */
    external fun releaseContext(): String

    /**
     * Unloads the model entirely.
     */
    external fun unloadModel(): String

    /**
     * Gets the chat template from the loaded model.
     * Returns `ok|<template>` on success, `error|<reason>` on failure.
     */
    external fun getChatTemplate(): String

    /**
     * Generates text from a chat conversation using the model's chat template.
     * Returns `ok|<gen ms>|tokens=<n>|text=<generated text>` on success,
     * `error|<reason>` on failure.
     *
     * @param chatJson JSON array of messages, each with "role" and "content"
     */
    external fun generateChat(
        chatJson: String,
        nPredict: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        seed: Int
    ): String
}