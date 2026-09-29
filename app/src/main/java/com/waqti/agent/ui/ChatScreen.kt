package com.waqti.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.text.selection.SelectionContainer

private const val EXAMPLE_TASK = "Find all Kotlin files in the workspace containing TODO"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.phase) {
        val last = state.messages.lastIndex
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Waqti", fontWeight = FontWeight.SemiBold) },
                actions = {
                    TextButton(onClick = viewModel::openSettings) { Text("Model") }
                }
            )
        },
        bottomBar = {
            InputBar(
                value = state.input,
                working = state.phase == TaskPhase.WORKING,
                onChange = viewModel::onInputChanged,
                onSend = viewModel::send,
                onStop = viewModel::stop
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.phase == TaskPhase.WORKING) {
                WorkingPanel(activity = state.activity, trace = state.liveTrace)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.messages.isEmpty()) {
                    item(key = "empty") {
                        EmptyHint(onUseExample = { viewModel.onInputChanged(EXAMPLE_TASK) })
                    }
                }
                items(state.messages, key = { it.id }) { message ->
                    MessageRow(message)
                }
            }
        }
    }

    val draft = state.settingsDraft
    if (state.settingsVisible && draft != null) {
        SettingsDialog(
            draft = draft,
            allFilesAccessGranted = state.allFilesAccessGranted,
            onDismiss = viewModel::closeSettings,
            onFieldChange = viewModel::updateSettingsDraft,
            onSave = viewModel::saveSettings,
            onGrantAccess = viewModel::openAllFilesAccessSettings
        )
    }
}

@Composable
private fun EmptyHint(onUseExample: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Give Waqti a task.",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            "It decides whether it can answer directly or needs to run a tool first.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onUseExample) {
            Text("Try: $EXAMPLE_TASK", textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun WorkingPanel(activity: String?, trace: List<UiTrace>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            text = activity ?: "Working…",
            style = MaterialTheme.typography.labelLarge
        )
        trace.forEach { TraceLine(it) }
    }
}

@Composable
private fun TraceLine(trace: UiTrace) {
    val mark = if (trace.ok) "✓" else "✗"
    val color = if (trace.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(verticalAlignment = Alignment.Top) {
        Text("$mark ", color = color, style = MaterialTheme.typography.labelMedium)
        Text(
            text = "${trace.name} · ${trace.durationMs} ms · ${trace.summary}",
            color = color,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun MessageRow(message: ChatMessageUi) {
    if (message.isNotice) {
        Text(
            text = message.text,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start
    ) {
        val shape = RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = if (message.fromUser) 16.dp else 4.dp,
            bottomEnd = if (message.fromUser) 4.dp else 16.dp
        )
        val (container, content) = when {
            message.isError ->
                MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            message.fromUser ->
                MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
            else ->
                MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Surface(
            color = container,
            contentColor = content,
            shape = shape,
            modifier = Modifier.fillMaxWidth(if (message.fromUser) 0.88f else 0.95f)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (message.trace.isNotEmpty()) {
                    message.trace.forEach { TraceLine(it) }
                    Spacer(modifier = Modifier.height(6.dp))
                }
                SelectionContainer {
                    Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    working: Boolean,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Describe a task…") },
                    minLines = 1,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Send,
                        capitalization = KeyboardCapitalization.Sentences
                    ),
                    keyboardActions = KeyboardActions(onSend = { if (!working) onSend() })
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (working) {
                    FilledTonalButton(
                        onClick = onStop,
                        modifier = Modifier.height(52.dp)
                    ) { Text("Stop") }
                } else {
                    Button(
                        onClick = onSend,
                        enabled = value.isNotBlank(),
                        modifier = Modifier.height(52.dp)
                    ) { Text("Send") }
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    draft: SettingsDraft,
    allFilesAccessGranted: Boolean,
    onDismiss: () -> Unit,
    onFieldChange: ((SettingsDraft) -> SettingsDraft) -> Unit,
    onSave: () -> Unit,
    onGrantAccess: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Model & workspace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.baseUrl,
                    onValueChange = { value -> onFieldChange { draft.copy(baseUrl = value) } },
                    label = { Text("Endpoint base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.model,
                    onValueChange = { value -> onFieldChange { draft.copy(model = value) } },
                    label = { Text("Model name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.apiKey,
                    onValueChange = { value -> onFieldChange { draft.copy(apiKey = value) } },
                    label = { Text("API key (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.workspacePath,
                    onValueChange = { value -> onFieldChange { draft.copy(workspacePath = value) } },
                    label = { Text("Workspace path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!allFilesAccessGranted) {
                    Text(
                        "This workspace needs the \"All files access\" permission.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = onGrantAccess) { Text("Grant file access") }
                }
                draft.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
