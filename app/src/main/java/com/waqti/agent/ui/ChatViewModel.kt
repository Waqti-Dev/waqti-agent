package com.waqti.agent.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.waqti.agent.data.SettingsStore
import com.waqti.agent.data.needsAllFilesAccess
import com.waqti.agent.loop.AgentEvent
import com.waqti.agent.loop.AgentLoop
import com.waqti.agent.loop.AgentOutcome
import com.waqti.agent.loop.AgentPolicy
import com.waqti.agent.loop.ToolTrace
import com.waqti.agent.model.LocalModelProvider
import com.waqti.agent.model.OpenAICompatProvider
import com.waqti.agent.runtime.NativeLocalInferenceRuntime
import com.waqti.agent.tools.ListFilesTool
import com.waqti.agent.tools.SearchFilesTool
import com.waqti.agent.tools.ToolRegistry
import com.waqti.agent.tools.Workspace
import com.waqti.agent.tools.WorkspaceError
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the conversation state and the single running agent job.
 * Every state change happens on the main thread; model/tool work runs in :core.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsStore(application)

    private val _state = MutableStateFlow(ChatUiState(allFilesAccessGranted = accessGrantedFor(settings.workspacePath)))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var runningJob: Job? = null
    private var nextMessageId = 0L

    // --- conversation -------------------------------------------------------

    fun onInputChanged(value: String) = updateState { it.copy(input = value) }

    fun send() {
        val snapshot = state.value
        val task = snapshot.input.trim()
        if (task.isEmpty() || snapshot.phase == TaskPhase.WORKING) return

        val workspace = try {
            createWorkspace(settings.workspacePath)
        } catch (e: WorkspaceError) {
            val message = newMessage(fromUser = false, text = "Workspace error: ${e.message}", isError = true)
            updateState { it.copy(messages = it.messages + message, phase = TaskPhase.ERROR) }
            return
        }

        val userMessage = newMessage(fromUser = true, text = task)
        updateState {
            it.copy(
                input = "",
                messages = it.messages + userMessage,
                phase = TaskPhase.WORKING,
                activity = "Starting…",
                liveTrace = emptyList()
            )
        }

        val trace = ArrayList<UiTrace>()
        runningJob = viewModelScope.launch {
            val provider = if (isLocalModel()) {
                val runtime = NativeLocalInferenceRuntime()
                LocalModelProvider(
                    runtime = runtime,
                    modelPath = settings.model,
                    nCtx = 4096,
                    nBatch = 512,
                    maxTokens = 256,
                    temperature = 0.7f,
                    topK = 40,
                    topP = 0.9f,
                    seed = 0
                )
            } else {
                OpenAICompatProvider(
                    baseUrl = settings.baseUrl,
                    model = settings.model,
                    apiKey = settings.apiKey.ifBlank { null }
                )
            }
            val registry = ToolRegistry(
                listOf(ListFilesTool(workspace), SearchFilesTool(workspace))
            )
            val loop = AgentLoop(
                model = provider,
                tools = registry,
                policy = AgentPolicy(),
                onEvent = { event -> onAgentEvent(event, trace) }
            )

            val outcome = try {
                loop.run(task)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AgentOutcome(
                    ok = false,
                    answer = null,
                    error = "Unexpected error: ${e::class.simpleName}: ${e.message}",
                    steps = 0,
                    traces = trace.map { t -> ToolTrace(t.name, t.ok, t.summary, t.durationMs) }
                )
            }
            finish(outcome, trace)
            runningJob = null
        }
    }

    fun stop() {
        val job = runningJob ?: return
        runningJob = null
        job.cancel()
        val notice = newMessage(fromUser = false, text = "Stopped.", isNotice = true)
        updateState {
            it.copy(
                messages = it.messages + notice,
                phase = TaskPhase.IDLE,
                activity = null,
                liveTrace = emptyList()
            )
        }
    }

    private fun onAgentEvent(event: AgentEvent, trace: ArrayList<UiTrace>) {
        when (event) {
            is AgentEvent.Started ->
                updateState { it.copy(activity = "Model: ${event.modelLabel}") }
            is AgentEvent.ModelRoundStarted ->
                updateState { it.copy(activity = "Thinking (round ${event.step})…") }
            is AgentEvent.ToolStarted ->
                updateState { it.copy(activity = "Running ${event.name}…") }
            is AgentEvent.ToolFinished -> {
                trace.add(UiTrace(event.name, event.ok, event.summary, event.durationMs))
                val snapshotTrace = trace.toList()
                updateState {
                    it.copy(
                        activity = "${event.name} finished in ${event.durationMs} ms",
                        liveTrace = snapshotTrace
                    )
                }
            }
            is AgentEvent.Completed, is AgentEvent.Failed ->
                updateState { it.copy(activity = "Finishing…") }
        }
    }

    private fun finish(outcome: AgentOutcome, trace: List<UiTrace>) {
        val message = if (outcome.ok) {
            newMessage(fromUser = false, text = outcome.answer.orEmpty(), trace = trace)
        } else {
            newMessage(
                fromUser = false,
                text = outcome.error ?: "The run failed without an error message",
                trace = trace,
                isError = true
            )
        }
        updateState {
            it.copy(
                messages = it.messages + message,
                phase = if (outcome.ok) TaskPhase.SUCCESS else TaskPhase.ERROR,
                activity = null,
                liveTrace = emptyList()
            )
        }
    }

    // --- settings -----------------------------------------------------------

    fun openSettings() {
        val draft = SettingsDraft(
            baseUrl = settings.baseUrl,
            model = settings.model,
            apiKey = settings.apiKey,
            workspacePath = settings.workspacePath
        )
        updateState { it.copy(settingsVisible = true, settingsDraft = draft) }
        refreshAccessFlag()
    }

    fun closeSettings() = updateState { it.copy(settingsVisible = false, settingsDraft = null) }

    fun updateSettingsDraft(transform: (SettingsDraft) -> SettingsDraft) = updateState { state ->
        state.settingsDraft?.let { state.copy(settingsDraft = transform(it)) } ?: state
    }

    fun saveSettings() {
        val draft = state.value.settingsDraft ?: return
        try {
            createWorkspace(draft.workspacePath)
        } catch (e: WorkspaceError) {
            updateSettingsDraft { it.copy(error = e.message ?: "Invalid workspace") }
            return
        }
        settings.baseUrl = draft.baseUrl.ifBlank { SettingsStore.DEFAULT_BASE_URL }
        settings.model = draft.model.ifBlank { SettingsStore.DEFAULT_MODEL }
        settings.apiKey = draft.apiKey
        settings.workspacePath = draft.workspacePath.trim()
        closeSettings()
        refreshAccessFlag()
    }

    fun refreshAccessFlag() = updateState { it.copy(allFilesAccessGranted = accessGrantedFor(settings.workspacePath)) }

    /** Opens the system screen for the "All files access" special permission. */
    fun openAllFilesAccessSettings() {
        val app = getApplication<Application>()
        val specific = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${app.packageName}")
        )
        runCatching { app.startActivity(specific) }.onFailure {
            runCatching { app.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    private fun isLocalModel(): Boolean {
        return settings.baseUrl == "local"
    }

    // --- helpers ------------------------------------------------------------

    private fun createWorkspace(path: String): Workspace {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) throw WorkspaceError("Workspace path is empty")
        val dir = File(trimmed)
        if (!dir.exists() && !dir.mkdirs()) {
            throw WorkspaceError("Cannot create workspace directory: $trimmed")
        }
        if (!dir.isDirectory) throw WorkspaceError("Workspace path is not a directory: $trimmed")
        return Workspace(dir.toPath())
    }

    private fun accessGrantedFor(path: String): Boolean {
        if (!needsAllFilesAccess(path)) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
    }

    private fun newMessage(
        fromUser: Boolean,
        text: String,
        trace: List<UiTrace> = emptyList(),
        isError: Boolean = false,
        isNotice: Boolean = false
    ): ChatMessageUi = ChatMessageUi(
        id = nextMessageId++,
        fromUser = fromUser,
        text = text,
        trace = trace,
        isError = isError,
        isNotice = isNotice
    )

    /** Single-writer state updates; everything runs on the main dispatcher. */
    private fun updateState(transform: (ChatUiState) -> ChatUiState) {
        _state.value = transform(_state.value)
    }
}
