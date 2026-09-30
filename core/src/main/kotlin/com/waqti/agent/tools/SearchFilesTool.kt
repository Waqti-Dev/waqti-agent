package com.waqti.agent.tools

import java.nio.charset.Charset
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.FileSystems
import org.json.JSONObject

/**
 * Greps file contents inside the workspace.
 *
 * Work is bounded three ways: files scanned, per-file bytes read, and matches
 * reported. Binary and oversized files are skipped and counted.
 */
class SearchFilesTool(private val workspace: Workspace) : Tool {

    override val spec = com.waqti.agent.ToolSpec(
        name = "SearchFiles",
        description = "Search file contents inside the workspace and return matching lines " +
            "as 'path:line: text'. Use it to find which files contain a phrase, token or symbol.",
        parameters = """
            {
              "type": "object",
              "properties": {
                "query": {"type": "string", "description": "Text to look for. Must not be blank."},
                "path": {"type": "string", "description": "Directory or file to search, relative to the workspace root. Defaults to the root."},
                "recursive": {"type": "boolean", "description": "Descend into subdirectories. Defaults to true."},
                "include": {"type": "string", "description": "Optional file-name glob filter, for example '*.kt' or '*.md'."},
                "ignoreCase": {"type": "boolean", "description": "Case-insensitive matching. Defaults to true."},
                "limit": {"type": "integer", "description": "Maximum matching lines to return, 1-500. Defaults to 100."}
              },
              "required": ["query"],
              "additionalProperties": false
            }
        """.trimIndent()
    )

    override fun execute(args: JSONObject): ToolResult {
        val query = stringArg(args, "query")
        if (query.isEmpty()) {
            return ToolResult.failure("Argument 'query' must not be blank")
        }
        val path = stringArg(args, "path").ifBlank { "." }
        val recursive = booleanArg(args, "recursive", true)
        val ignoreCase = booleanArg(args, "ignoreCase", true)
        val limit = intArg(args, "limit", 100).coerceIn(1, 500)
        val include = stringArg(args, "include").ifBlank { null }

        val target = try {
            workspace.resolve(path)
        } catch (e: WorkspaceError) {
            return ToolResult.failure(e.message ?: "Invalid path")
        } catch (e: InvalidPathException) {
            return ToolResult.failure("Invalid path '$path'")
        }

        if (!exists(target)) {
            if (isGlobPath(path)) return ToolResult.failure(globInPathHelp("SearchFiles", path))
            return ToolResult.failure("Path does not exist: $path")
        }
        if (!Files.isReadable(target)) return ToolResult.failure("Path is not readable: $path")

        val includeMatcher = include?.let { pattern ->
            try {
                FileSystems.getDefault().getPathMatcher("glob:$pattern")
            } catch (e: Exception) {
                return ToolResult.failure("Invalid include pattern '$pattern'")
            }
        }

        val files = ArrayList<Path>()
        var binarySkipped = 0
        var oversizedSkipped = 0
        try {
            if (Files.isDirectory(target)) {
                collectFiles(target, recursive, includeMatcher, files)
            } else {
                if (includeMatcher == null || includeMatcher.matches(target.fileName)) files.add(target)
            }
        } catch (e: AccessDeniedException) {
            return ToolResult.failure("Path is not accessible: $path")
        } catch (e: java.io.UncheckedIOException) {
            return ToolResult.failure("Path is not accessible: $path (${e.cause?.message ?: e.message})")
        } catch (e: SecurityException) {
            return ToolResult.failure("Path is not accessible: $path")
        } catch (e: java.io.IOException) {
            return ToolResult.failure("Could not scan '$path': ${e.message}")
        }
        // Deterministic scan order regardless of filesystem ordering.
        files.sortBy { it.toString() }

        val matches = ArrayList<String>()
        var totalMatches = 0
        var matchedFiles = 0
        var filesScanned = 0

        for (file in files) {
            if (filesScanned >= MAX_FILES_SCANNED) break
            val size = try {
                Files.size(file)
            } catch (e: Exception) {
                continue
            }
            if (size > MAX_FILE_BYTES) {
                oversizedSkipped++
                continue
            }
            val bytes = try {
                Files.readAllBytes(file)
            } catch (e: AccessDeniedException) {
                continue
            } catch (e: Exception) {
                continue
            }
            if (looksBinary(bytes)) {
                binarySkipped++
                continue
            }
            filesScanned++
            val text = decode(bytes)
            var fileHadMatch = false
            var lineNumber = 0
            for (line in text.lineSequence()) {
                lineNumber++
                if (line.contains(query, ignoreCase)) {
                    totalMatches++
                    fileHadMatch = true
                    if (matches.size < limit) {
                        matches.add("${displayRelative(target, file)}:$lineNumber: ${line.trim()}")
                    }
                }
            }
            if (fileHadMatch) matchedFiles++
        }

        val header = "matches: $totalMatches in $matchedFiles file(s) (showing ${matches.size}, limit $limit)"
        val body = if (matches.isEmpty()) "(no matches)" else matches.joinToString("\n")
        val notes = buildList {
            if (binarySkipped > 0) add("$binarySkipped binary skipped")
            if (oversizedSkipped > 0) add("$oversizedSkipped over ${MAX_FILE_BYTES / 1024}KB skipped")
            if (files.size >= MAX_FILES_SCANNED) add("stopped at scan cap $MAX_FILES_SCANNED")
        }
        val footer = "scanned $filesScanned file(s) under ${displayPath(path)}" +
            if (notes.isEmpty()) "" else notes.joinToString(prefix = " (", postfix = ")", separator = ", ")

        return ToolResult.success("$header\n$body\n$footer")
    }

    private fun collectFiles(
        root: Path,
        recursive: Boolean,
        includeMatcher: java.nio.file.PathMatcher?,
        out: ArrayList<Path>
    ) {
        if (!recursive) {
            Files.newDirectoryStream(root).use { stream ->
                for (entry in stream) {
                    if (out.size >= MAX_FILES_SCANNED) return
                    if (!Files.isRegularFile(entry)) continue
                    if (includeMatcher != null && !includeMatcher.matches(entry.fileName)) continue
                    out.add(entry)
                }
            }
            return
        }
        Files.walk(root).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                if (out.size >= MAX_FILES_SCANNED) return
                val entry = iterator.next()
                if (!Files.isRegularFile(entry)) continue
                if (includeMatcher != null && !includeMatcher.matches(entry.fileName)) continue
                out.add(entry)
            }
        }
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val probe = minOf(bytes.size, 8192)
        for (i in 0 until probe) {
            if (bytes[i].toInt() == 0) return true
        }
        return false
    }

    private fun decode(bytes: ByteArray): String =
        runCatching { String(bytes, Charsets.UTF_8) }
            .getOrDefault(String(bytes, Charset.forName("ISO-8859-1")))

    private fun displayRelative(target: Path, file: Path): String = try {
        if (Files.isDirectory(target)) target.relativize(file).toString() else file.fileName.toString()
    } catch (e: Exception) {
        file.toString()
    }

    private fun displayPath(path: String): String =
        if (path == "." || path.isBlank()) "/" else path

    private fun exists(path: Path): Boolean = try {
        Files.exists(path)
    } catch (e: Exception) {
        false
    }

    companion object {
        const val MAX_FILES_SCANNED = 5000
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
    }
}
