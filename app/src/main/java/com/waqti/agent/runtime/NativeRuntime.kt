package com.waqti.agent.runtime

/**
 * The only JNI surface of the app: a thin handle on the in-process llama.cpp
 * runtime. Nothing outside this package may call it — the UI and the agent
 * speak [com.waqti.agent.model.ModelProvider] only.
 *
 * This object deliberately exposes no model loading or generation yet; it only
 * proves that the packaged native library loads and that llama.cpp is linked.
 */
object NativeRuntime {

    init {
        System.loadLibrary("waqti_local_runtime")
    }

    /** llama.cpp build version and detected CPU features of this device. */
    external fun versionInfo(): String
}
