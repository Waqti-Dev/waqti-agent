package com.waqti.agent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.waqti.agent.data.SettingsStore
import kotlin.math.roundToInt

/**
 * Everything the user can actually configure, in one place.
 *
 * The order is the order the product actually works in: a model, the folder the
 * tools may read, and only then the connection settings that almost nobody on a
 * local install ever needs. Nothing on this sheet can change how inference works
 * — it selects what already exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    state: ChatUiState,
    draft: SettingsDraft,
    onDismiss: () -> Unit,
    onFieldChange: ((SettingsDraft) -> SettingsDraft) -> Unit,
    onSave: () -> Unit,
    onImport: () -> Unit,
    onGrantAccess: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var advancedOpen by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = WaqtiSpace.xl)
                .padding(bottom = WaqtiSpace.xl)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Model and workspace",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                )
                Surface(
                    onClick = onDismiss,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = androidx.compose.foundation.shape.CircleShape,
                    // The glyph is 18.dp inside a 36.dp disc; the extra insets are
                    // what lift the touch target to 48.dp without moving the icon.
                    modifier = Modifier
                        .size(36.dp)
                        .minimumInteractiveComponentSize()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Box(modifier = Modifier.height(WaqtiSpace.xl))

            WaqtiSectionLabel("Model")
            Box(modifier = Modifier.height(WaqtiSpace.sm))
            ModelStatusBlock(
                model = state.model,
                importError = state.importError,
                onImport = onImport
            )

            Box(modifier = Modifier.height(WaqtiSpace.xl))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(modifier = Modifier.height(WaqtiSpace.xl))

            WaqtiSectionLabel("Workspace")
            Box(modifier = Modifier.height(WaqtiSpace.xs))
            Text(
                text = "Waqti may read files in this folder and nowhere else.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(modifier = Modifier.height(WaqtiSpace.md))
            OutlinedTextField(
                value = draft.workspacePath,
                onValueChange = { value -> onFieldChange { draft.copy(workspacePath = value) } },
                label = { Text("Folder") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions.Default,
                isError = draft.error != null,
                modifier = Modifier.fillMaxWidth()
            )
            if (!state.allFilesAccessGranted) {
                Box(modifier = Modifier.height(WaqtiSpace.md))
                AccessWarning(onGrantAccess = onGrantAccess)
            }

            Box(modifier = Modifier.height(WaqtiSpace.xl))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(modifier = Modifier.height(WaqtiSpace.md))

            // Collapsed by default: on a local install these fields are noise, and
            // a remote endpoint is not part of what Waqti is for.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Advanced connection",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            heading()
                        }
                )
                WaqtiQuietButton(
                    text = if (advancedOpen) "Hide" else "Show",
                    onClick = { advancedOpen = !advancedOpen },
                    contentDescription = if (advancedOpen) {
                        "Hide advanced connection settings"
                    } else {
                        "Show advanced connection settings"
                    }
                )
            }
            if (advancedOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(WaqtiSpace.md)) {
                    if (draft.baseUrl.trim() != SettingsStore.DEFAULT_BASE_URL) {
                        Text(
                            text = "Waqti is calling a remote endpoint instead of the model on this device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    OutlinedTextField(
                        value = draft.baseUrl,
                        onValueChange = { value -> onFieldChange { draft.copy(baseUrl = value) } },
                        label = { Text("Endpoint") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = draft.model,
                        onValueChange = { value -> onFieldChange { draft.copy(model = value) } },
                        label = { Text("Model") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = draft.apiKey,
                        onValueChange = { value -> onFieldChange { draft.copy(apiKey = value) } },
                        label = { Text("API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            draft.error?.let { message ->
                Box(modifier = Modifier.height(WaqtiSpace.md))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Box(modifier = Modifier.height(WaqtiSpace.xl))
            Row(horizontalArrangement = Arrangement.spacedBy(WaqtiSpace.md)) {
                WaqtiQuietButton(
                    text = "Cancel",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                WaqtiPrimaryButton(
                    text = "Save",
                    onClick = onSave,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ModelStatusBlock(
    model: ModelUiState,
    importError: String?,
    onImport: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(WaqtiSpace.md)) {
        when (model) {
            is ModelUiState.Absent -> {
                Text(
                    text = "Waqti has no model yet. Choose a GGUF file and Waqti copies it " +
                        "into its own private folder on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                WaqtiPrimaryButton(text = "Choose a GGUF file", onClick = onImport)
            }

            is ModelUiState.Importing -> {
                val total = model.totalBytes
                val fraction = if (total > 0) {
                    (model.copiedBytes.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
                } else {
                    0.0
                }
                Text(
                    text = if (model.fileName.isBlank()) {
                        "Importing a model…"
                    } else {
                        "Importing ${model.fileName}…"
                    },
                    style = MaterialTheme.typography.titleSmall
                )
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { fraction.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        gapSize = 0.dp
                    )
                    Text(
                        text = "${formatBytes(model.copiedBytes)} of ${formatBytes(total)} " +
                            "(${(fraction * 100).roundToInt()}%)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        gapSize = 0.dp
                    )
                }
            }

            is ModelUiState.Ready -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WaqtiStatusDot(model = model, stage = RunStage.IDLE, diameter = 9.dp)
                    Box(modifier = Modifier.width(WaqtiSpace.sm))
                    Text(
                        text = "Ready",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                DetailRow("File", model.fileName)
                DetailRow("Size", formatBytes(model.sizeBytes))
                Text(
                    text = "Stored in Waqti's private folder on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                WaqtiQuietButton(
                    text = "Replace model",
                    onClick = onImport,
                    enabled = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            is ModelUiState.Invalid -> {
                Text(
                    text = "${model.fileName} is not a valid GGUF model, so Waqti cannot " +
                        "load it. Choose a different file.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                WaqtiPrimaryButton(text = "Choose a GGUF file", onClick = onImport)
            }
        }

        if (!importError.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = importError,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(WaqtiSpace.md)
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AccessWarning(onGrantAccess: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(WaqtiSpace.md)) {
            Text(
                text = "Waqti needs “All files access” to read that folder.",
                style = MaterialTheme.typography.bodySmall
            )
            Box(modifier = Modifier.height(WaqtiSpace.sm))
            WaqtiQuietButton(text = "Open permission settings", onClick = onGrantAccess)
        }
    }
}