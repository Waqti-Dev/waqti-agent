package com.waqti.agent.tools

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** A path request that violated the workspace boundary. */
class WorkspaceError(message: String) : Exception(message)

/**
 * The trust boundary for filesystem tools.
 *
 * Every path a tool touches must resolve inside [root]. Absolute paths outside the
 * root and ".." traversal are rejected; symlinked paths are checked after resolving
 * to their real location so a link cannot be used to escape.
 */
class Workspace(root: Path) {

    /** Canonical absolute root, resolved through symlinks. */
    val root: Path = run {
        val absolute = root.toAbsolutePath().normalize()
        if (!Files.isDirectory(absolute)) {
            throw WorkspaceError("Workspace does not exist or is not a directory: $absolute")
        }
        try {
            absolute.toRealPath()
        } catch (e: IOException) {
            throw WorkspaceError("Workspace cannot be resolved: ${e.message}")
        }
    }

    /** Human-readable root for display. */
    fun displayRoot(): String = root.toString()

    /**
     * Resolves [rawPath] (relative to the root, or an absolute path already inside it)
     * to a safe absolute path. Blank input means the root itself.
     * Non-existent targets are returned unresolved but still boundary-checked.
     */
    fun resolve(rawPath: String?): Path {
        val raw = rawPath?.trim().orEmpty()
        val candidate = if (raw.isEmpty() || raw == ".") {
            root
        } else if (raw.startsWith("/")) {
            Path.of(raw).normalize()
        } else {
            root.resolve(raw).normalize()
        }

        if (!candidate.startsWith(root)) {
            throw WorkspaceError("Path '$raw' is outside the workspace")
        }
        if (candidate == root) return root

        // Walk down to the deepest existing ancestor: that is what a symlink check
        // can actually verify for a possibly non-existent target.
        var existing: Path? = candidate
        while (existing != null && existing != root && !existsNoFollow(existing)) {
            existing = existing.parent
        }
        val anchor = existing ?: root
        val realAnchor = try {
            if (anchor == root) root else anchor.toRealPath()
        } catch (e: NoSuchFileException) {
            throw WorkspaceError("Path '$raw' does not exist")
        } catch (e: IOException) {
            throw WorkspaceError("Path '$raw' cannot be accessed: ${e.message}")
        }
        if (!realAnchor.startsWith(root)) {
            throw WorkspaceError("Path '$raw' escapes the workspace through a link")
        }
        val remainder = anchor.relativize(candidate)
        return realAnchor.resolve(remainder).normalize()
    }

    private fun existsNoFollow(path: Path): Boolean = try {
        Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
    } catch (e: SecurityException) {
        false
    }
}
