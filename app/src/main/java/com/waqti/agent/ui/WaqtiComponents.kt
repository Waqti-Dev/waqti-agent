package com.waqti.agent.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * The Waqti mark: a rounded tile carrying a drawn "W".
 *
 * Drawn rather than imported so it stays crisp at any size, needs no density
 * buckets, and is unmistakably Waqti's own rather than a stock icon. The stroke
 * is round-capped so the letterform keeps its shape when the tile shrinks to the
 * 18dp attribution mark next to an answer.
 */
@Composable
fun WaqtiMark(
    size: Dp,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primary,
    glyph: Color = MaterialTheme.colorScheme.onPrimary
) {
    Canvas(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.small)
            .background(container)
    ) {
        val inset = this.size.width * 0.24f
        val top = this.size.height * 0.30f
        val bottom = this.size.height * 0.70f
        val w = this.size.width - inset * 2f
        val path = Path().apply {
            moveTo(inset, top)
            lineTo(inset + w * 0.22f, bottom)
            lineTo(inset + w * 0.5f, top + (bottom - top) * 0.55f)
            lineTo(inset + w * 0.78f, bottom)
            lineTo(inset + w, top)
        }
        drawPath(
            path = path,
            color = glyph,
            style = Stroke(
                width = this.size.width * 0.11f,
                cap = StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        )
    }
}

/**
 * The single indicator of what the model is doing.
 *
 * It is drawn from [ModelUiState] and [RunStage] only, so it cannot claim a state
 * the application is not in: no dot without a file behind it, and an "importing"
 * arc that only moves when the copy really has moved.
 */
@Composable
fun WaqtiStatusDot(
    model: ModelUiState,
    stage: RunStage,
    modifier: Modifier = Modifier,
    diameter: Dp = 9.dp
) {
    val importing = model as? ModelUiState.Importing
    val live = stage != RunStage.IDLE
    val target = when {
        importing != null -> MaterialTheme.colorScheme.tertiary
        model is ModelUiState.Ready && live -> MaterialTheme.colorScheme.tertiary
        model is ModelUiState.Ready -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val color by animateColorAsState(
        targetValue = target,
        animationSpec = tween(WaqtiMotion.MEDIUM, easing = FastOutSlowInEasing),
        label = "waqti-status-dot"
    )
    val progress = if (importing != null && importing.totalBytes > 0) {
        (importing.copiedBytes.toFloat() / importing.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    WaqtiDotBody(
        target = target,
        color = color,
        progress = progress,
        ringed = importing != null,
        modifier = modifier,
        diameter = diameter
    )
}

/**
 * The indicator inside the run card. It knows only that a run is in flight, so it
 * never has to borrow a model state to decide how to look.
 */
@Composable
fun WaqtiLiveDot(modifier: Modifier = Modifier, diameter: Dp = 8.dp) {
    val color by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.tertiary,
        animationSpec = tween(WaqtiMotion.MEDIUM, easing = FastOutSlowInEasing),
        label = "waqti-live-dot"
    )
    WaqtiDotBody(
        target = MaterialTheme.colorScheme.tertiary,
        color = color,
        progress = 1f,
        ringed = false,
        modifier = modifier,
        diameter = diameter
    )
}

@Composable
private fun WaqtiDotBody(
    target: Color,
    color: Color,
    progress: Float,
    ringed: Boolean,
    modifier: Modifier,
    diameter: Dp
) {
    Canvas(modifier = modifier.size(diameter)) {
        val stroke = this.size.width * 0.26f
        val inset = stroke / 2f
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        val topLeft = Offset(inset, inset)
        if (ringed) {
            drawCircle(color = target.copy(alpha = 0.28f), radius = this.size.minDimension / 2f)
            if (progress > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        } else {
            drawCircle(color = color)
        }
    }
}

/**
 * Short, honest status word for the header. Deliberately no model name and no
 * internal terminology: the full detail lives in the model sheet.
 */
fun statusWord(model: ModelUiState, stage: RunStage): String = when {
    model is ModelUiState.Importing -> "Importing"
    stage == RunStage.LOADING_MODEL -> "Loading"
    stage == RunStage.RUNNING_TOOL -> "Using a tool"
    stage == RunStage.GENERATING || stage == RunStage.FINISHING -> "Working"
    model is ModelUiState.Ready -> "Ready"
    model is ModelUiState.Invalid -> "Not a model"
    else -> "No model"
}

fun statusDescription(model: ModelUiState, stage: RunStage): String = when (model) {
    is ModelUiState.Absent -> "No model on this device"
    is ModelUiState.Importing -> buildString {
        append("Importing ")
        if (model.fileName.isNotBlank()) append(model.fileName)
        if (model.totalBytes > 0) {
            append(", ").append(formatBytes(model.copiedBytes))
            append(" of ").append(formatBytes(model.totalBytes))
        }
    }
    is ModelUiState.Ready ->
        if (stage == RunStage.IDLE) "${model.fileName}, ready" else "${model.fileName}, ${statusWord(model, stage)}"
    is ModelUiState.Invalid -> "${model.fileName} is not a GGUF model"
}

/** Small uppercase label used to title a region or a group inside the interface. */
@Composable
fun WaqtiSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { heading() }
    )
}

/** The one filled action style in the system. Used at most once per screen. */
@Composable
fun WaqtiPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null
) {
    val container by animateColorAsState(
        targetValue = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(WaqtiMotion.FAST),
        label = "waqti-primary-bg"
    )
    val content by animateColorAsState(
        targetValue = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(WaqtiMotion.FAST),
        label = "waqti-primary-fg"
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.defaultMinSize(minHeight = 48.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = WaqtiSpace.xl, vertical = WaqtiSpace.md),
            horizontalArrangement = Arrangement.spacedBy(WaqtiSpace.sm, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon?.invoke()
            Text(text = text, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** A low-emphasis action: bordered, no fill, same touch target as the primary. */
@Composable
fun WaqtiQuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    maxLines: Int = 1
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        contentColor = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (enabled) {
                MaterialTheme.colorScheme.outlineVariant
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            }
        ),
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            )
    ) {
        Box(modifier = Modifier.padding(horizontal = WaqtiSpace.lg, vertical = WaqtiSpace.md)) {
            Text(text = text, style = MaterialTheme.typography.titleSmall, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The composer's single round action. It is send while idle, stop while the run
 * is in flight, and inert while there is no model — the state is never implied by
 * a separate label that could disagree with the glyph.
 */
@Composable
fun WaqtiCircleAction(
    working: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val active = working || enabled
    val container by animateColorAsState(
        targetValue = when {
            working -> MaterialTheme.colorScheme.tertiary
            enabled -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(WaqtiMotion.MEDIUM, easing = FastOutSlowInEasing),
        label = "waqti-action-bg"
    )
    val content by animateColorAsState(
        targetValue = when {
            working -> MaterialTheme.colorScheme.onTertiary
            enabled -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        },
        animationSpec = tween(WaqtiMotion.MEDIUM, easing = FastOutSlowInEasing),
        label = "waqti-action-fg"
    )
    val label = if (working) "Stop" else "Send"

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = active, onClick = if (working) onStop else onSend)
            .semantics {
                contentDescription = label
                stateDescription = if (working) "Working" else if (enabled) "Ready to send" else "Unavailable"
            },
        contentAlignment = Alignment.Center
    ) {
        if (working) {
            StopGlyph(color = content)
        } else {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

/** A filled square, drawn so the stop action needs no extra icon dependency. */
@Composable
private fun StopGlyph(color: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val side = this.size.width * 0.46f
        val origin = Offset((this.size.width - side) / 2f, (this.size.height - side) / 2f)
        drawRect(
            color = color,
            topLeft = origin,
            size = Size(side, side)
        )
    }
}

/**
 * What Waqti actually did, kept next to the answer it produced.
 *
 * Every row corresponds to a completed [com.waqti.agent.loop.ToolTrace], so this
 * can only ever show tools that really ran, with the duration that was really
 * measured.
 */
@Composable
fun WaqtiTraceCard(trace: List<UiTrace>, modifier: Modifier = Modifier) {
    if (trace.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
    ) {
        Column(modifier = Modifier.padding(WaqtiSpace.md)) {
            WaqtiSectionLabel("Work")
            trace.forEachIndexed { index, item ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = WaqtiSpace.xs)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }
                WaqtiTraceRow(item)
            }
        }
    }
}

/** One completed tool call: what it was, whether it worked, how long it took. */
@Composable
fun WaqtiTraceRow(item: UiTrace) {
    val accent = if (item.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = if (item.ok) Icons.Filled.Check else Icons.Filled.Warning,
            contentDescription = if (item.ok) "Succeeded" else "Failed",
            tint = accent,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(15.dp)
        )
        Spacer(Modifier.width(WaqtiSpace.sm))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
                Text(
                    text = formatDuration(item.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (item.summary.isNotBlank()) {
                Text(
                    text = item.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun WaqtiErrorCard(
    message: String,
    detail: String?,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(WaqtiSpace.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = "Error",
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(WaqtiSpace.sm))
                Text(
                    text = "Something went wrong",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f)
                )
            }
            Spacer(Modifier.height(WaqtiSpace.sm))
            Text(text = message, style = MaterialTheme.typography.bodyLarge)
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(WaqtiSpace.md))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable { expanded = !expanded }
                        .defaultMinSize(minHeight = 32.dp)
                        .semantics {
                            stateDescription = if (expanded) "Showing technical details" else "Technical details hidden"
                        }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(WaqtiSpace.xs))
                    Text(
                        text = if (expanded) "Hide details" else "Details",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.width(WaqtiSpace.xs))
                    ChevronGlyph(
                        color = LocalContentColor.current,
                        expanded = expanded,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
            if (expanded && !detail.isNullOrBlank()) {
                Spacer(Modifier.height(WaqtiSpace.sm))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}
/** Disclosure chevron: points down when collapsed, up when the detail is open. */
@Composable
private fun ChevronGlyph(color: Color, expanded: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = this.size.width
        val h = this.size.height
        val direction = if (expanded) -1f else 1f
        val path = Path().apply {
            moveTo(0f, h * 0.3f * direction)
            lineTo(w / 2f, h * 0.72f * direction)
            lineTo(w, h * 0.3f * direction)
        }
        drawPath(path, color, style = Stroke(width = w * 0.16f, cap = StrokeCap.Round))
    }
}
