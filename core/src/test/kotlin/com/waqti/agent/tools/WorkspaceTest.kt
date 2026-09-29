package com.waqti.agent.tools

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun workspace(): Workspace {
        val root = temp.newFolder("workspace").toPath()
        return Workspace(root)
    }

    @Test
    fun `blank path resolves to workspace root`() {
        val ws = workspace()
        assertEquals(ws.root, ws.resolve(""))
        assertEquals(ws.root, ws.resolve(null))
        assertEquals(ws.root, ws.resolve("   "))
        assertEquals(ws.root, ws.resolve("."))
    }

    @Test
    fun `relative path resolves inside workspace`() {
        val ws = workspace()
        val target = ws.resolve("a/b.txt")
        assertTrue(target.startsWith(ws.root))
        assertTrue(target.toString().endsWith("a/b.txt"))
    }

    @Test
    fun `parent traversal is rejected`() {
        val ws = workspace()
        val error = assertThrows(WorkspaceError::class.java) { ws.resolve("../outside.txt") }
        assertTrue(error.message!!.contains("outside the workspace"))
    }

    @Test
    fun `nested parent traversal is rejected`() {
        val ws = workspace()
        assertThrows(WorkspaceError::class.java) { ws.resolve("a/../../escape") }
    }

    @Test
    fun `absolute path outside workspace is rejected`() {
        val ws = workspace()
        assertThrows(WorkspaceError::class.java) { ws.resolve("/etc/passwd") }
    }

    @Test
    fun `absolute path inside workspace is allowed`() {
        val ws = workspace()
        val absolute = ws.root.resolve("inside.txt").toString()
        assertEquals(ws.root.resolve("inside.txt"), ws.resolve(absolute))
    }

    @Test
    fun `non existent path inside workspace is still boundary checked`() {
        val ws = workspace()
        val resolved = ws.resolve("does/not/exist.txt")
        assertTrue(resolved.startsWith(ws.root))
    }

    @Test
    fun `symlink escape is rejected`() {
        val ws = workspace()
        val outside = temp.newFolder("outside").toPath()
        Files.createSymbolicLink(ws.root.resolve("link"), outside)

        val error = assertThrows(WorkspaceError::class.java) { ws.resolve("link") }
        assertTrue(error.message!!.contains("outside the workspace") || error.message!!.contains("escapes"))
    }

    @Test
    fun `symlinked child escape is rejected`() {
        val ws = workspace()
        val outside = temp.newFolder("outside2").toPath()
        Files.createSymbolicLink(ws.root.resolve("linkdir"), outside)
        Files.writeString(outside.resolve("secret.txt"), "secret")

        assertThrows(WorkspaceError::class.java) { ws.resolve("linkdir/secret.txt") }
    }

    @Test
    fun `missing workspace root fails at construction`() {
        val missing: Path = temp.root.toPath().resolve("nope")
        assertThrows(WorkspaceError::class.java) { Workspace(missing) }
    }

    @Test
    fun `file used as workspace root fails at construction`() {
        val file = temp.newFile("just-a-file").toPath()
        assertThrows(WorkspaceError::class.java) { Workspace(file) }
    }
}
