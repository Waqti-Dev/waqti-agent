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
    val isNotice: Boolean = false
)

/** Editable settings draft; kept in the ViewModel so rotation does not lose input. */
data class SettingsDraft(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val workspacePath: String,
    val error: String? = null
)

data class ChatUiState(
    val messages: List<ChatMessageUi> = emptyList(),
    val input: String = "",
    val phase: TaskPhase = TaskPhase.IDLE,
    /** Human-readable label of what is happening right now, null when not working. */
    val activity: String? = null,
    /** Tool trace accumulated by the run in progress. */
    val liveTrace: List<UiTrace> = emptyList(),
    val settingsVisible: Boolean = false,
    val settingsDraft: SettingsDraft? = null,
    val allFilesAccessGranted: Boolean = true
)
