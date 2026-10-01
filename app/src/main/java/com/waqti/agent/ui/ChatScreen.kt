package com.waqti.agent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Examples that a local instruction model can genuinely answer without running a
 * tool, so nothing on screen implies a capability the app may not have.
 */
private val EXAMPLE_TASKS = listOf(
    "Explain what a Kotlin data class is, in three sentences.",
    "What is the difference between a value class and a data class?"
)

@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importModel(uri)
    }
    val requestImport = remember { { picker.launch(arrayOf("*/*")) } }

    val listState = rememberLazyListState()
    val working = state.phase == TaskPhase.WORKING
    // Follow the conversation as it grows: new messages, and the run card while a
    // turn is in flight.
    LaunchedEffect(state.messages.size, state.stage) {
        val target = listState.layoutInfo.totalItemsCount - 1
        if (target >= 0) listState.animateScrollToItem(target)
    }

    // The gutter is resolved once, outside the Scaffold, so the header, the
    // conversation and the composer share one margin on every screen size instead
    // of each region picking its own.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val gutter = WaqtiSpace.contentGutter(maxWidth)

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                WaqtiHeader(
                    gutter = gutter,
                    model = state.model,
                    stage = state.stage,
                    onOpenModel = viewModel::openSettings
                )
            },
            bottomBar = {
                WaqtiComposer(
                    gutter = gutter,
                    value = state.input,
                    onChange = viewModel::onInputChanged,
                    onSend = viewModel::send,
                    onStop = viewModel::stop,
                    working = working,
                    enabled = state.canRunTask,
                    hint = composerHint(state)
                )
            }
        ) { padding ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = gutter, vertical = WaqtiSpace.md),
                verticalArrangement = Arrangement.spacedBy(WaqtiSpace.messageGap)
            ) {
                item(key = "lead") {
                    MeasureBox {
                        if (state.messages.isEmpty()) {
                            WaqtiEmptyState(
                                canRun = state.canRunTask,
                                importing = state.isImporting,
                                onImport = requestImport,
                                onExample = viewModel::onInputChanged
                            )
                        }
                    }
                }
                items(state.messages, key = { it.id }) { message ->
                    MeasureBox { MessageRow(message) }
                }
                if (working) {
                    item(key = "run") {
                        MeasureBox { RunCard(stage = state.stage, trace = state.liveTrace) }
                    }
                }
            }
        }
    }

    val draft = state.settingsDraft
    if (state.settingsVisible && draft != null) {
        ModelSheet(
            state = state,
            draft = draft,
            onDismiss = viewModel::closeSettings,
            onFieldChange = viewModel::updateSettingsDraft,
            onSave = viewModel::saveSettings,
            onImport = requestImport,
            onGrantAccess = viewModel::openAllFilesAccessSettings
        )
    }
}

/**
 * Keeps a conversation item to a comfortable reading measure and centred, so a
 * long answer on a wide screen is not one 900dp line and a phone is not stretched.
 */
@Composable
private fun MeasureBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = WaqtiSpace.measure)) { content() }
    }
}

private fun composerHint(state: ChatUiState): String? = when {
    state.isImporting -> {
        val model = state.model
        if (model is ModelUiState.Importing && model.fileName.isNotBlank()) {
            "Importing ${model.fileName}…"
        } else {
            "Importing a model…"
        }
    }
    !state.canRunTask -> "Import a model to give Waqti a task."
    else -> null
}

// --- header ------------------------------------------------------------------

@Composable
private fun WaqtiHeader(gutter: Dp, model: ModelUiState, stage: RunStage, onOpenModel: () -> Unit) {
    // The window is edge-to-edge, so the header has to inset itself. Without
    // this the status bar overlays the top of the header: the model name was
    // drawn under the clock and the status pill sat inside the status bar's
    // touch region, so taps on it were consumed by the system bar and never
    // reached onOpenModel.
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = gutter),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WaqtiMark(size = 30.dp)
                Box(modifier = Modifier.width(WaqtiSpace.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Waqti",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        text = when (model) {
                            is ModelUiState.Ready -> model.fileName
                            is ModelUiState.Importing ->
                                if (model.fileName.isBlank()) "Importing a model" else model.fileName
                            ModelUiState.Absent -> "No model on this device"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(modifier = Modifier.width(WaqtiSpace.sm))
                StatusPill(model = model, stage = stage, onClick = onOpenModel)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun StatusPill(model: ModelUiState, stage: RunStage, onClick: () -> Unit) {
    val spoken = statusDescription(model, stage)
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(percent = 50),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .height(34.dp)
            // The pill reads as a 34.dp chip; the minimum interactive size keeps
            // it reachable at 48.dp without changing how it looks.
            .minimumInteractiveComponentSize()
            .semantics { stateDescription = "Model status: " + spoken }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = WaqtiSpace.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WaqtiStatusDot(model = model, stage = stage, diameter = 8.dp)
            Box(modifier = Modifier.width(WaqtiSpace.sm))
            Text(text = statusWord(model, stage), style = MaterialTheme.typography.labelLarge)
        }
    }
}

// --- empty state -------------------------------------------------------------

@Composable
private fun WaqtiEmptyState(
    canRun: Boolean,
    importing: Boolean,
    onImport: () -> Unit,
    onExample: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = WaqtiSpace.xxl + WaqtiSpace.xl, bottom = WaqtiSpace.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WaqtiSpace.lg)
    ) {
        WaqtiMark(size = 54.dp)
        Text(
            text = "Give Waqti something to do.",
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = "Waqti answers with the model stored on this phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (!canRun) {
            WaqtiPrimaryButton(
                text = if (importing) "Importing…" else "Import a model",
                onClick = onImport,
                enabled = !importing
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(WaqtiSpace.sm)
            ) {
                WaqtiSectionLabel("Try one")
                EXAMPLE_TASKS.forEach { example ->
                    WaqtiQuietButton(
                        text = example,
                        onClick = { onExample(example) },
                        modifier = Modifier.fillMaxWidth(),
                        // Example prompts are full sentences; clamping them to one
                        // line ellipsized them on narrower phones, hiding what the
                        // button actually does.
                        maxLines = 3
                    )
                }
            }
        }
    }
}

// --- messages ----------------------------------------------------------------

@Composable
private fun MessageRow(message: ChatMessageUi) {
    if (message.isNotice) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    if (message.fromUser) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 6.dp,
                    bottomStart = 20.dp,
                    bottomEnd = 20.dp
                ),
                modifier = Modifier.fillMaxWidth(0.9f)
            ) {
                SelectionContainer {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = WaqtiSpace.lg, vertical = WaqtiSpace.md)
                    )
                }
            }
        }
        return
    }

    // A Waqti answer is a reading surface, not a bubble: no fill behind the text,
    // so long answers keep an even measure and nothing nests card inside card.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(WaqtiSpace.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WaqtiMark(size = 18.dp)
            Box(modifier = Modifier.width(WaqtiSpace.sm))
            Text(
                text = "Waqti",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() }
            )
        }
        if (message.trace.isNotEmpty()) {
            WaqtiTraceCard(trace = message.trace)
        }
        if (message.isError) {
            WaqtiErrorCard(message = message.text, detail = message.detail)
        } else {
            SelectionContainer {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// --- live run ----------------------------------------------------------------

@Composable
private fun RunCard(stage: RunStage, trace: List<UiTrace>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(WaqtiSpace.lg)) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                gapSize = 0.dp
            )
            Box(modifier = Modifier.height(WaqtiSpace.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                WaqtiLiveDot()
                Box(modifier = Modifier.width(WaqtiSpace.sm))
                Text(text = stageLabel(stage), style = MaterialTheme.typography.titleSmall)
            }
            if (trace.isNotEmpty()) {
                Box(modifier = Modifier.height(WaqtiSpace.md))
                trace.forEach { WaqtiTraceRow(it) }
            }
        }
    }
}

/**
 * Every label here is tied to an event the loop actually emits, so the run card
 * cannot display a stage the run is not in.
 */
private fun stageLabel(stage: RunStage): String = when (stage) {
    RunStage.LOADING_MODEL -> "Loading the model"
    RunStage.GENERATING -> "Working on a reply"
    RunStage.RUNNING_TOOL -> "Running a tool"
    RunStage.FINISHING -> "Finishing up"
    RunStage.IDLE -> "Working"
}

// --- composer ----------------------------------------------------------------

@Composable
private fun WaqtiComposer(
    gutter: Dp,
    value: String,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    working: Boolean,
    enabled: Boolean,
    hint: String?
) {
    val interactive = enabled || working
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth().imePadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = gutter, vertical = WaqtiSpace.md)
            ) {
                if (hint != null) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(modifier = Modifier.height(WaqtiSpace.sm))
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.extraLarge,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (interactive) {
                            MaterialTheme.colorScheme.outline
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(
                            start = WaqtiSpace.lg,
                            end = WaqtiSpace.sm,
                            top = WaqtiSpace.xs,
                            bottom = WaqtiSpace.xs
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = value,
                            onValueChange = onChange,
                            enabled = interactive,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = WaqtiSpace.md),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(
                                // Once the task contains a line break the keyboard
                                // must be able to produce one, so Send becomes Enter.
                                imeAction = if (value.contains('\n')) ImeAction.Default else ImeAction.Send,
                                capitalization = KeyboardCapitalization.Sentences
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = { if (!working && enabled) onSend() }
                            ),
                            decorationBox = { field ->
                                if (value.isEmpty()) {
                                    Text(
                                        text = "Give Waqti a task…",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                field()
                            }
                        )
                        Box(modifier = Modifier.width(WaqtiSpace.sm))
                        WaqtiCircleAction(
                            working = working,
                            enabled = enabled && value.isNotBlank(),
                            onSend = onSend,
                            onStop = onStop
                        )
                    }
                }
            }
        }
    }
}