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
 *     --es path <model>.gguf
 * ```
 *
 * The path is relative to the app's private files directory (absolute paths
 * are accepted only when they stay inside it). Results are reported on logcat
 * under the [TAG].
 */
class ModelLoadDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        when (action) {
            ACTION_LOAD_MODEL -> handleLoadModel(context, intent)
            ACTION_CREATE_CONTEXT -> handleCreateContext(intent)
            ACTION_GENERATE -> handleGenerate(intent)
            ACTION_RELEASE_CONTEXT -> handleReleaseContext()
            ACTION_UNLOAD_MODEL -> handleUnloadModel()
        }
    }

    private fun handleLoadModel(context: Context, intent: Intent) {
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

    private fun handleCreateContext(intent: Intent) {
        val nCtx = intent.getIntExtra(EXTRA_N_CTX, 4096)
        val nBatch = intent.getIntExtra(EXTRA_N_BATCH, 512)

        Thread(
            {
                val result = try {
                    NativeRuntime.createContext(nCtx, nBatch)
                } catch (t: Throwable) {
                    "error|${t.javaClass.name}: ${t.message}"
                }
                Log.i(TAG, "createContext result: $result")
            },
            "waqti-create-context"
        ).start()
    }

    private fun handleGenerate(intent: Intent) {
        val prompt = intent.getStringExtra(EXTRA_PROMPT) ?: ""
        val nPredict = intent.getIntExtra(EXTRA_N_PREDICT, 128)
        val temperature = intent.getFloatExtra(EXTRA_TEMPERATURE, 0.7f)
        val topK = intent.getIntExtra(EXTRA_TOP_K, 40)
        val topP = intent.getFloatExtra(EXTRA_TOP_P, 0.9f)
        val seed = intent.getIntExtra(EXTRA_SEED, 0)

        Thread(
            {
                val result = try {
                    NativeRuntime.generate(prompt, nPredict, temperature, topK, topP, seed)
                } catch (t: Throwable) {
                    "error|${t.javaClass.name}: ${t.message}"
                }
                Log.i(TAG, "generate result: $result")
            },
            "waqti-generate"
        ).start()
    }

    private fun handleReleaseContext() {
        Thread(
            {
                val result = try {
                    NativeRuntime.releaseContext()
                } catch (t: Throwable) {
                    "error|${t.javaClass.name}: ${t.message}"
                }
                Log.i(TAG, "releaseContext result: $result")
            },
            "waqti-release-context"
        ).start()
    }

    private fun handleUnloadModel() {
        Thread(
            {
                val result = try {
                    NativeRuntime.unloadModel()
                } catch (t: Throwable) {
                    "error|${t.javaClass.name}: ${t.message}"
                }
                Log.i(TAG, "unloadModel result: $result")
            },
            "waqti-unload-model"
        ).start()
    }

    companion object {
        const val TAG = "waqti-task4"
        const val ACTION_LOAD_MODEL = "com.waqti.agent.runtime.action.LOAD_MODEL"
        const val ACTION_CREATE_CONTEXT = "com.waqti.agent.runtime.action.CREATE_CONTEXT"
        const val ACTION_GENERATE = "com.waqti.agent.runtime.action.GENERATE"
        const val ACTION_RELEASE_CONTEXT = "com.waqti.agent.runtime.action.RELEASE_CONTEXT"
        const val ACTION_UNLOAD_MODEL = "com.waqti.agent.runtime.action.UNLOAD_MODEL"
        const val EXTRA_PATH = "path"
        const val EXTRA_N_CTX = "n_ctx"
        const val EXTRA_N_BATCH = "n_batch"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_N_PREDICT = "n_predict"
        const val EXTRA_TEMPERATURE = "temperature"
        const val EXTRA_TOP_K = "top_k"
        const val EXTRA_TOP_P = "top_p"
        const val EXTRA_SEED = "seed"
    }
}