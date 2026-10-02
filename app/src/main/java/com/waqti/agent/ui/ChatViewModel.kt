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
import com.waqti.agent.model.ChatMessage
import com.waqti.agent.model.LocalModelProvider
import com.waqti.agent.model.OpenAICompatProvider
import com.waqti.agent.model.Role
import com.waqti.agent.runtime.NativeLocalInferenceRuntime
import com.waqti.agent.tools.ListFilesTool
import com.waqti.agent.tools.SearchFilesTool
import com.waqti.agent.tools.ToolRegistry
import com.waqti.agent.tools.Workspace
import com.waqti.agent.tools.WorkspaceError
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the conversation state and the single running agent job.
 * Every state change happens on the main thread; model/tool work runs in :core.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsStore(application)

    private val _state = MutableStateFlow(
        ChatUiState(
            allFilesAccessGranted = accessGrantedFor(settings.workspacePath),
            workspacePath = settings.workspacePath,
            model = currentModelState()
        )
    )
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var runningJob: Job? = null
    private var importJob: Job? = null
    private var importProgress: ImportProgress? = null
    private var nextMessageId = 0L

    // --- conversation -------------------------------------------------------

    fun onInputChanged(value: String) = updateState { it.copy(input = value) }

    fun send() {
        val snapshot = state.value
        val task = snapshot.input.trim()
        if (task.isEmpty() || snapshot.phase == TaskPhase.WORKING) return

        // Refuse in the interface rather than letting the runtime fail: without a
        // GGUF there is nothing to run, and saying so plainly beats a load error.
        if (!snapshot.canRunTask) {
            val model = snapshot.model
            val notice = if (model is ModelUiState.Importing) {
                "Still importing ${model.fileName}. Give Waqti a task once it finishes."
            } else {
                "Import a model for this device before giving Waqti a task."
            }
            val message = newMessage(fromUser = false, text = notice, isNotice = true)
            updateState { it.copy(messages = it.messages + message) }
            return
        }

        val workspace = try {
            createWorkspace(settings.workspacePath)
        } catch (e: WorkspaceError) {
            val message = newMessage(
                fromUser = false,
                text = "Waqti could not open that folder.",
                detail = "Workspace error: ${e.message}",
                isError = true
            )
            updateState { it.copy(messages = it.messages + message, phase = TaskPhase.ERROR, stage = RunStage.IDLE) }
            return
        }

        val userMessage = newMessage(fromUser = true, text = task)
        updateState {
            it.copy(
                input = "",
                messages = it.messages + userMessage,
                phase = TaskPhase.WORKING,
                stage = RunStage.LOADING_MODEL,
                liveTrace = emptyList()
            )
        }

        val trace = ArrayList<UiTrace>()
        runningJob = viewModelScope.launch {
            val provider = if (isLocalModel()) {
                val runtime = NativeLocalInferenceRuntime()
                LocalModelProvider(
                    runtime = runtime,
                    modelPath = resolveLocalModelPath(),
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
                loop.run(task, priorTurns(snapshot.messages))
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
                stage = RunStage.IDLE,
                liveTrace = emptyList()
            )
        }
    }

    private fun onAgentEvent(event: AgentEvent, trace: ArrayList<UiTrace>) {
        when (event) {
            is AgentEvent.Started -> updateState { it.copy(stage = RunStage.LOADING_MODEL) }
            is AgentEvent.ModelRoundStarted -> updateState { it.copy(stage = RunStage.GENERATING) }
            is AgentEvent.ToolStarted -> updateState { it.copy(stage = RunStage.RUNNING_TOOL) }
            is AgentEvent.ToolFinished -> {
                trace.add(UiTrace(event.name, event.ok, event.summary, event.durationMs))
                val snapshotTrace = trace.toList()
                updateState {
                    it.copy(stage = RunStage.GENERATING, liveTrace = snapshotTrace)
                }
            }
            is AgentEvent.Completed, is AgentEvent.Failed ->
                updateState { it.copy(stage = RunStage.FINISHING) }
        }
    }

    private fun finish(outcome: AgentOutcome, trace: List<UiTrace>) {
        val message = if (outcome.ok) {
            val answer = outcome.answer.orEmpty()
            if (answer.isBlank()) {
                // A blank answer is a real failure the loop already reported; a
                // blank row would read as a rendering bug, so name it instead.
                newMessage(
                    fromUser = false,
                    text = "Waqti received an empty answer.",
                    trace = trace,
                    isError = true,
                    detail = "Model returned an empty answer"
                )
            } else {
                newMessage(fromUser = false, text = answer, trace = trace)
            }
        } else {
            val raw = outcome.error ?: "The run failed without an error message"
            newMessage(
                fromUser = false,
                text = humanizeError(raw),
                trace = trace,
                isError = true,
                detail = raw
            )
        }
        updateState {
            it.copy(
                messages = it.messages + message,
                phase = if (outcome.ok) TaskPhase.SUCCESS else TaskPhase.ERROR,
                stage = RunStage.IDLE,
                liveTrace = emptyList()
            )
        }
    }

    // --- model import -------------------------------------------------------

    /**
     * Copies a GGUF picked by the system file picker into this app's private
     * files directory, which is where the runtime already loads models from.
     *
     * The copy is streamed, reports real progress, and lands on a temporary name
     * that is renamed only once it is complete — a cancelled or failed import
     * must never leave a truncated file behind that would then be reported to the
     * user as a ready model.
     */
    fun importModel(source: Uri) {
        if (importJob?.isActive == true) return

        val resolver = getApplication<Application>().contentResolver
        val filesDir = getApplication<Application>().filesDir
        importProgress = ImportProgress(fileName = "", copiedBytes = 0L, totalBytes = 0L)
        updateState { it.copy(importError = null, model = currentModelState()) }

        var staging: File? = null
        importJob = viewModelScope.launch {
            try {
                val displayName = withContext(Dispatchers.IO) {
                    resolver.query(source, null, null, null, null)?.use { cursor ->
                        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                    }
                }
                val name = (displayName ?: source.lastPathSegment ?: "model.gguf").trim()
                if (!name.endsWith(".gguf", ignoreCase = true)) {
                    failImport("That file is not a GGUF model. Choose a file ending in .gguf.")
                    return@launch
                }
                val target = File(filesDir, name)
                val partial = File(filesDir, "$name.part")
                staging = partial

                val total = withContext(Dispatchers.IO) {
                    resolver.openAssetFileDescriptor(source, "r")?.use { it.length } ?: -1L
                }
                var copied = 0L
                withContext(Dispatchers.IO) {
                    resolver.openInputStream(source)?.use { input ->
                        partial.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                publishImportProgress(name, copied, total)
                            }
                            output.flush()
                        }
                    } ?: throw IllegalStateException("The picked file could not be opened")
                }

                if (total > 0 && copied < total) {
                    partial.delete()
                    failImport("The model file was not copied completely. Try importing it again.")
                    return@launch
                }
                // Validate the staged copy before it is ever renamed into place or
                // registered as the active model, so an empty or renamed text file
                // cannot be reported as ready.
                if (!hasGgufMagic(partial)) {
                    partial.delete()
                    failImport("That file is not a valid GGUF model. Choose a real .gguf model file.")
                    return@launch
                }
                if (partial.renameTo(target).not()) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                staging = null
                settings.model = name
                refreshModelState()
            } catch (e: CancellationException) {
                staging?.delete()
                importProgress = null
                updateState { it.copy(model = currentModelState(), importError = null) }
                throw e
            } catch (e: Exception) {
                staging?.delete()
                // An import failure keeps its real reason: this is a file the user
                // just chose, so "no space left" or "permission denied" is exactly
                // the detail they need, and it is not an implementation string.
                val reason = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "unknown error"
                failImport("Waqti could not import that file: $reason")
            } finally {
                importJob = null
                importProgress = null
                updateState { it.copy(model = currentModelState()) }
            }
        }
    }

    private fun failImport(reason: String) {
        importProgress = null
        updateState { it.copy(model = currentModelState(), importError = reason) }
    }

    private fun publishImportProgress(name: String, copied: Long, total: Long) {
        importProgress = ImportProgress(fileName = name, copiedBytes = copied, totalBytes = total)
        updateState { it.copy(model = currentModelState()) }
    }

    /** Live copy progress; never held in the published state, only projected. */
    private data class ImportProgress(val fileName: String, val copiedBytes: Long, val totalBytes: Long)

    /**
     * Re-reads the model straight from the filesystem. Called whenever the
     * interface might otherwise be showing a model state that has changed, so the
     * status is a fact rather than a remembered guess.
     */
    fun refreshModelState() = updateState {
        it.copy(
            model = currentModelState(),
            workspacePath = settings.workspacePath,
            allFilesAccessGranted = accessGrantedFor(settings.workspacePath)
        )
    }

    /**
     * Projects the real state: a running import if there is one, otherwise what is
     * actually on disk.
     *
     * Import progress is held in a field rather than read back out of the published
     * state. Reading it back from the state would make a *failed* import report
     * itself as still importing, because the failure path needs the projection
     * precisely when the copy has stopped.
     */
    private fun currentModelState(): ModelUiState {
        importProgress?.let { progress ->
            return ModelUiState.Importing(progress.fileName, progress.copiedBytes, progress.totalBytes)
        }
        val file = File(resolveLocalModelPath())
        return when {
            !file.isFile -> ModelUiState.Absent
            hasGgufMagic(file) -> ModelUiState.Ready(file.name, file.length())
            // A file is there but is not a GGUF: say so rather than claim it is ready.
            else -> ModelUiState.Invalid(file.name, file.length())
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
        refreshModelState()
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

    /**
     * The turns already on screen, oldest first, mapped to model roles.
     *
     * Without this the loop only ever saw SYSTEM + the newest user message, so a
     * follow-up question ("what is my favourite colour?") was answered without
     * any of the earlier turns — the model could not refer back to them.
     * Error/notice bubbles are skipped: they are UI chrome, not conversation.
     */
    private fun priorTurns(visible: List<ChatMessageUi>): List<ChatMessage> =
        visible.asSequence()
            .filterNot { it.isError || it.isNotice }
            .map { msg ->
                ChatMessage(
                    role = if (msg.fromUser) Role.USER else Role.ASSISTANT,
                    content = msg.text
                )
            }
            .toList()

    private fun isLocalModel(): Boolean {
        return settings.baseUrl == "local"
    }

    /**
     * Resolves the configured model to an absolute path the JNI loader can stat().
     * Settings store a model *name* (e.g. "qwen2.5-3b-....gguf"); a bare name is
     * resolved inside this app's private files directory, which is where the
     * GGUF is pushed. Passing the bare name straight through made loadModel fail
     * with "cannot stat file", because stat() resolves it against the process
     * working directory.
     */
    private fun resolveLocalModelPath(): String {
        val configured = settings.model.trim()
        val filesDir = getApplication<Application>().filesDir
        return File(configured).let { if (it.isAbsolute) it.absolutePath else File(filesDir, configured).absolutePath }
    }

    /**
     * A GGUF model starts with the four ASCII bytes "GGUF". Reading only those
     * four bytes is the smallest honest test that rejects an empty file or a text
     * file renamed to .gguf, without duplicating llama.cpp's model parser. A file
     * shorter than four bytes cannot match.
     */
    private fun hasGgufMagic(file: File): Boolean {
        if (!file.isFile || file.length() < 4L) return false
        return runCatching {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                var read = 0
                while (read < 4) {
                    val n = input.read(magic, read, 4 - read)
                    if (n <= 0) break
                    read += n
                }
                read == 4 && String(magic, Charsets.US_ASCII) == "GGUF"
            }
        }.getOrDefault(false)
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
        isNotice: Boolean = false,
        detail: String? = null
    ): ChatMessageUi = ChatMessageUi(
        id = nextMessageId++,
        fromUser = fromUser,
        text = text,
        trace = trace,
        isError = isError,
        isNotice = isNotice,
        detail = detail
    )

    /** Single-writer state updates; everything runs on the main dispatcher. */
    private fun updateState(transform: (ChatUiState) -> ChatUiState) {
        _state.value = transform(_state.value)
    }
}