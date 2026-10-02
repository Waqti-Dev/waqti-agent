package com.waqti.agent.ui

/** State of the current (or last) task. Explicit, never hidden. */
enum class TaskPhase { IDLE, WORKING, SUCCESS, ERROR }

/** One executed tool call, shown while working and kept under the final answer. */
data class UiTrace(
    val name: String,
    val ok: Boolean,
    val summary: String,
    val durationMs: Long
)

data class ChatMessageUi(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val trace: List<UiTrace> = emptyList(),
    val isError: Boolean = false,
    val isNotice: Boolean = false,
    /**
     * The untranslated failure, kept for diagnostics. The interface shows
     * [text]; this is only revealed behind an explicit "Details" disclosure, so
     * an internal message never becomes the primary user-facing error.
     */
    val detail: String? = null
)

/** Editable settings draft; kept in the ViewModel so rotation does not lose input. */
data class SettingsDraft(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val workspacePath: String,
    val error: String? = null
)

/**
 * The on-device model, as far as the interface is allowed to claim anything.
 *
 * Every value is read from real application state: [Absent] means no GGUF exists
 * at the resolved path, [Importing] means a copy is genuinely running, [Ready]
 * means the file is really there with the size really reported, and [Invalid]
 * means a file is present but is not a readable GGUF model. Nothing here is
 * inferred from a successful request or a previous run.
 */
sealed interface ModelUiState {

    data object Absent : ModelUiState

    data class Importing(
        val fileName: String,
        val copiedBytes: Long,
        val totalBytes: Long
    ) : ModelUiState

    data class Ready(val fileName: String, val sizeBytes: Long) : ModelUiState

    /**
     * A file exists at the resolved path but does not begin with the GGUF magic
     * bytes, so the runtime cannot load it. Reported instead of [Ready] so the
     * interface never presents an unusable file as a working model.
     */
    data class Invalid(val fileName: String, val sizeBytes: Long) : ModelUiState
}

/**
 * Where a run currently is. Driven by the loop's own events, so every label the
 * interface shows corresponds to work that is actually happening — in
 * particular [LOADING_MODEL] is the real window in which the provider loads the
 * GGUF, between the run starting and the first model round.
 */
enum class RunStage { IDLE, LOADING_MODEL, GENERATING, RUNNING_TOOL, FINISHING }

data class ChatUiState(
    val messages: List<ChatMessageUi> = emptyList(),
    val input: String = "",
    val phase: TaskPhase = TaskPhase.IDLE,
    /** Tool trace accumulated by the run in progress. */
    val liveTrace: List<UiTrace> = emptyList(),
    val settingsVisible: Boolean = false,
    val settingsDraft: SettingsDraft? = null,
    val allFilesAccessGranted: Boolean = true,

    /** Real model state; drives the header badge, the composer and the empty state. */
    val model: ModelUiState = ModelUiState.Absent,
    val stage: RunStage = RunStage.IDLE,
    /** Folder the filesystem tools are allowed to read. */
    val workspacePath: String = "",
    /** A failed import, shown inside the model sheet and never as a crash. */
    val importError: String? = null
) {
    /** True while a GGUF is being copied in, so the interface cannot offer a task yet. */
    val isImporting: Boolean get() = model is ModelUiState.Importing

    /** A task can only be given to a model that is really present and not mid-import. */
    val canRunTask: Boolean get() = model is ModelUiState.Ready && !isImporting
}