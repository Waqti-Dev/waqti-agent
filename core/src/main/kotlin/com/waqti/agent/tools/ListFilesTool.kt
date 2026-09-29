package com.waqti.agent.tools

import com.waqti.agent.ToolSpec
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import org.json.JSONObject

/**
 * Lists files and directories inside the workspace with a hard result cap so a
 * huge tree can never flood the model or hang the UI.
 */
class ListFilesTool(private val workspace: Workspace) : Tool {

    override val spec = ToolSpec(
        name = "ListFiles",
        description = "List files and directories inside the workspace. " +
            "Returns sorted relative paths with entry kind (D=directory, F=file) and file sizes.",
        parameters = """
            {
              "type": "object",
              "properties": {
                "path": {"type": "string", "description": "Directory to list, relative to the workspace root. Defaults to the root."},
                "recursive": {"type": "boolean", "description": "When true, list the whole subtree instead of one level. Defaults to false."},
                "limit": {"type": "integer", "description": "Maximum number of entries to return, 1-500. Defaults to 200."}
              },
              "additionalProperties": false
            }
        """.trimIndent()
    )

    override fun execute(args: JSONObject): ToolResult {
        val path = stringArg(args, "path").ifBlank { "." }
        val recursive = booleanArg(args, "recursive", false)
        val limit = intArg(args, "limit", 200).coerceIn(1, 500)

        val dir = try {
            workspace.resolve(path)
        } catch (e: WorkspaceError) {
            return ToolResult.failure(e.message ?: "Invalid path")
        } catch (e: InvalidPathException) {
            return ToolResult.failure("Invalid path '$path'")
        }

        if (!exists(dir)) return ToolResult.failure("Path does not exist: $path")
        if (!Files.isDirectory(dir)) return ToolResult.failure("Not a directory: $path")
        if (!Files.isReadable(dir)) return ToolResult.failure("Path is not readable: $path")

        val entries = ArrayList<Path>()
        var hitScanCap = false
        try {
            if (recursive) {
                Files.walk(dir).use { stream ->
                    // skip(1) drops the listed directory itself; limit() bounds the work.
                    stream.skip(1).limit(SCAN_CAP.toLong()).forEach { entries.add(it) }
                }
                hitScanCap = entries.size >= SCAN_CAP
            } else {
                Files.list(dir).use { stream ->
                    stream.limit(SCAN_CAP.toLong()).forEach { entries.add(it) }
                }
                hitScanCap = entries.size >= SCAN_CAP
            }
        } catch (e: java.nio.file.AccessDeniedException) {
            return ToolResult.failure("Path is not accessible: $path")
        } catch (e: java.io.UncheckedIOException) {
            return ToolResult.failure("Path is not accessible: $path (${e.cause?.message ?: e.message})")
        } catch (e: SecurityException) {
            return ToolResult.failure("Path is not accessible: $path")
        } catch (e: java.io.IOException) {
            return ToolResult.failure("Could not list '$path': ${e.message}")
        }

        val relative: (Path) -> String = { entry -> dir.relativize(entry).toString().ifBlank { "." } }
        entries.sortBy { relative(it) }

        val shown = entries.take(limit)
        val lines = shown.map { entry ->
            val name = relative(entry)
            when {
                Files.isSymbolicLink(entry) -> "L $name"
                Files.isDirectory(entry) -> "D $name/"
                else -> "F $name (${fileSize(entry)} bytes)"
            }
        }

        val header = "entries: ${shown.size} of ${entries.size} (limit $limit) in ${displayPath(path)}" +
            if (recursive) " recursive" else ""
        val body = if (lines.isEmpty()) "(empty directory)" else lines.joinToString("\n")
        val footer = when {
            hitScanCap && entries.size > limit -> "truncated at scan cap $SCAN_CAP"
            entries.size > limit -> "truncated: more entries exist"
            else -> null
        }
        return ToolResult.success(listOfNotNull(header, body, footer).joinToString("\n"))
    }

    private fun displayPath(path: String): String =
        if (path == "." || path.isBlank()) "/" else path

    private fun fileSize(entry: Path): Long = try {
        Files.size(entry)
    } catch (e: Exception) {
        -1
    }

    private fun exists(path: Path): Boolean = try {
        Files.exists(path)
    } catch (e: Exception) {
        false
    }

    companion object {
        /** Upper bound on entries scanned in one call. */
        const val SCAN_CAP = 5000
    }
}

// Shared argument helpers for filesystem tools.
internal fun stringArg(args: JSONObject, name: String): String =
    if (args.has(name) && !args.isNull(name)) args.optString(name).trim() else ""

internal fun booleanArg(args: JSONObject, name: String, fallback: Boolean): Boolean =
    if (args.has(name) && !args.isNull(name)) args.optBoolean(name, fallback) else fallback

internal fun intArg(args: JSONObject, name: String, fallback: Int): Int =
    if (args.has(name) && !args.isNull(name)) args.optInt(name, fallback) else fallback
