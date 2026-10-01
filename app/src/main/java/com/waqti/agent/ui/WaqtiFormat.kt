package com.waqti.agent.ui

import java.util.Locale

/**
 * Presentation helpers that carry no Compose dependency, so the rules they encode
 * — how a size reads, how long a duration reads, and what a user is told when a
 * run fails — are unit-testable on the JVM instead of only on a device.
 */

/** Human-readable size for a model file, with a locale-independent decimal point. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> String.format(Locale.US, "%.0f kB", bytes / 1_000.0)
    else -> "$bytes B"
}

/** Durations are shown at a readable granularity rather than raw milliseconds. */
fun formatDuration(ms: Long): String = when {
    ms < 0 -> "—"
    ms < 1_000 -> "$ms ms"
    ms < 60_000 -> String.format(Locale.US, "%.1f s", ms / 1000.0)
    else -> String.format(Locale.US, "%d min", ms / 60_000)
}

/**
 * Turns an internal failure into something a person can act on.
 *
 * The mapping is deliberately conservative: it never claims to know a cause it
 * cannot see, it only replaces an implementation string with a plain statement
 * about what happened. The original text is not discarded — it is carried
 * separately as [com.waqti.agent.ui.ChatMessageUi.detail] and shown behind an
 * explicit disclosure.
 *
 * Invariants this function is expected to keep, and which the unit tests pin:
 * the result is never blank, never leaks the `error|` prefix the runtime uses,
 * never leaks a Java exception class name, and never grows into a paragraph.
 */
fun humanizeError(raw: String): String {
    val text = raw.trim()
    return when {
        text.isEmpty() -> "Something went wrong while running this task."
        // Specific causes first: "Model error: error|cannot stat file" is really a
        // missing file, and matching the prefix first would hide that.
        text.contains("cannot stat file") -> "Waqti could not open the model file."
        text.contains("llama_decode") -> "Waqti could not run the model on this conversation."
        text.contains("empty answer") -> "The model returned an empty answer."
        text.contains("timed out") -> "A tool call took too long and was stopped."
        text.contains("not permitted by policy") -> "Waqti blocked that tool call."
        text.contains("Unknown tool") -> "Waqti asked for a tool that does not exist."
        text.contains("Invalid JSON arguments") -> "Waqti could not read the arguments for that tool."
        text.contains("model rounds without a final answer") -> "Waqti used every round without reaching an answer."
        text.startsWith("Workspace error:") -> "Waqti could not open that folder."
        text.startsWith("Model error:") -> "The model could not finish this task."
        text.startsWith("Unexpected error:") -> "Waqti hit an unexpected problem while working."
        text.startsWith("error|") -> "Waqti could not run the model on this task."
        // Already short and plain, and nothing matched above: show it as written.
        text.length <= 120 -> text
        else -> "Something went wrong while running this task."
    }
}