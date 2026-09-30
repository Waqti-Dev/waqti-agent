package com.waqti.agent.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Debug-only, headless trigger for the in-process runtime, so on-device
 * validation (Tasks 4/5) needs no UI: it belongs to `src/debug` and is not
 * compiled into release builds at all.
 *
 * ```
 * adb shell am broadcast -n com.waqti.agent/com.waqti.agent.runtime.ModelLoadDebugReceiver \
 *     --es path files/<model>.gguf
 * ```
 *
 * The path is relative to the app's private files directory (absolute paths
 * are accepted only when they stay inside it). Results are reported on logcat
 * under the [TAG].
 */
class ModelLoadDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOAD_MODEL) return

        val rawPath = intent.getStringExtra(EXTRA_PATH)
        if (rawPath.isNullOrBlank()) {
            Log.w(TAG, "ignored: missing string extra '$EXTRA_PATH'")
            return
        }

        val filesDir = context.filesDir.canonicalFile
        val target = (if (rawPath.startsWith("/")) File(rawPath) else File(filesDir, rawPath))
            .canonicalFile
        if (!target.path.startsWith(filesDir.path + File.separator)) {
            Log.w(TAG, "refused: path outside app-private storage: ${target.path}")
            return
        }
        if (!target.isFile) {
            Log.w(TAG, "refused: not a file: ${target.path}")
            return
        }

        Log.i(
            TAG,
            "load requested: ${target.path} (${target.length()} bytes); " +
                "continuing on a background thread, receiver returns now"
        )

        // Loading blocks for seconds — it must never run on the main thread.
        Thread(
            {
                val result = try {
                    NativeRuntime.loadModel(target.absolutePath)
                } catch (t: Throwable) {
                    "error|${t.javaClass.name}: ${t.message}"
                }
                Log.i(TAG, "loadModel result: $result")
            },
            "waqti-model-load"
        ).start()
    }

    companion object {
        const val TAG = "waqti-task4"
        const val ACTION_LOAD_MODEL = "com.waqti.agent.runtime.action.LOAD_MODEL"
        const val EXTRA_PATH = "path"
    }
}
