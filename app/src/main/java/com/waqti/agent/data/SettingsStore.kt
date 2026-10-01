package com.waqti.agent.data

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Persisted runtime configuration: which model endpoint to call and which
 * directory the filesystem tools are allowed to touch.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("waqti_settings", Context.MODE_PRIVATE)

    private val defaultWorkspace: String =
        (context.getExternalFilesDir(null) ?: context.filesDir).absolutePath + "/workspace"

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trim()).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var workspacePath: String
        get() = prefs.getString(KEY_WORKSPACE, defaultWorkspace) ?: defaultWorkspace
        set(value) = prefs.edit().putString(KEY_WORKSPACE, value.trim()).apply()

    fun defaultWorkspacePath(): String = defaultWorkspace

    companion object {
        // "local" enables the on-device llama.cpp runtime.
        // llama-server started by ./start-ai exposes an OpenAI-compatible API on loopback.
        const val DEFAULT_BASE_URL = "local"
        const val DEFAULT_MODEL = "qwen2.5-3b-OFFICIAL-Q4_K_M.gguf"

        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_WORKSPACE = "workspace"
    }
}

/** True when [path] needs "All files access" (MANAGE_EXTERNAL_STORAGE) to be readable. */
fun needsAllFilesAccess(path: String): Boolean {
    val normalized = path.trim()
    return normalized.startsWith("/storage/") ||
        normalized.startsWith("/sdcard/") ||
        normalized == "/sdcard" ||
        normalized.startsWith("/mnt/")
}
