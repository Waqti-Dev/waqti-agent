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
}
