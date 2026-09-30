package com.waqti.agent.tools

import java.nio.file.Files
import java.nio.file.Path
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ListFilesToolTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: Path
    private lateinit var tool: ListFilesTool

    private fun setupTree() {
        root = temp.newFolder("workspace").toPath()
        Files.createDirectories(root.resolve("src"))
        Files.createDirectories(root.resolve("src/deep"))
        Files.createDirectories(root.resolve("docs"))
        Files.writeString(root.resolve("src/Main.kt"), "fun main() {}")
        Files.writeString(root.resolve("src/deep/Helper.kt"), "fun helper() {}")
        Files.writeString(root.resolve("docs/readme.md"), "# readme")
        Files.writeString(root.resolve("root.txt"), "root file")
        tool = ListFilesTool(Workspace(root))
    }

    private fun list(args: String = "{}"): ToolResult = tool.execute(JSONObject(args))

    @Test
    fun `glob in path is rejected with corrective guidance`() {
        setupTree()
        val result = list("""{"path":"*"}""")
        assertFalse(result.ok)
        assertTrue(result.error!!, result.error!!.contains("glob pattern"))
        assertTrue(result.error!!, result.error!!.contains("says nothing"))
    }

    @Test
    fun `non recursive listing shows only one level`() {
        setupTree()
        val result = list()
        assertTrue(result.ok, result.error ?: "")
        val output = result.output
        assertTrue(output.contains("D src/"))
        assertTrue(output.contains("D docs/"))
        assertTrue(output.contains("F root.txt"))
        assertFalse("must not descend one level", output.contains("Main.kt"))
        assertFalse(output.contains("Helper.kt"))
    }

    @Test
    fun `recursive listing shows the subtree`() {
        setupTree()
        val result = list("""{"recursive":true}""")
        assertTrue(result.ok, result.error ?: "")
        val output = result.output
        assertTrue(output.contains("src/Main.kt"))
        assertTrue(output.contains("src/deep/Helper.kt"))
        assertTrue(output.contains("docs/readme.md"))
        assertFalse("root directory itself is not an entry", output.contains("workspace/"))
    }

    @Test
    fun `entries are sorted and prefixed by kind`() {
        setupTree()
        val output = list().output
        val entryLines = output.lines().filter { it.startsWith("D ") || it.startsWith("F ") || it.startsWith("L ") }
        assertEquals(entryLines, entryLines.sortedBy { it.substringAfter(' ') })
        assertTrue(entryLines.any { it.startsWith("D ") })
        assertTrue(entryLines.any { it.startsWith("F ") && it.contains("bytes") })
    }

    @Test
    fun `missing path fails explicitly`() {
        setupTree()
        val result = list("""{"path":"nope"}""")
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("does not exist"))
    }

    @Test
    fun `file path fails as not a directory`() {
        setupTree()
        val result = list("""{"path":"root.txt"}""")
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("Not a directory"))
    }

    @Test
    fun `path traversal is rejected`() {
        setupTree()
        val result = list("""{"path":".."}""")
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("outside the workspace"))
    }

    @Test
    fun `limit caps returned entries`() {
        setupTree()
        val result = list("""{"limit":2}""")
        assertTrue(result.ok, result.error ?: "")
        val entryLines = result.output.lines().count { it.startsWith("D ") || it.startsWith("F ") }
        assertTrue("expected at most 2 entry lines, got $entryLines", entryLines <= 2)
        assertTrue(result.output.contains("limit 2"))
    }

    @Test
    fun `limit is clamped to a sane range`() {
        setupTree()
        val tooBig = list("""{"limit":99999}""")
        assertTrue(tooBig.ok, tooBig.error ?: "")
        assertTrue(tooBig.output.contains("limit 500"))

        val negative = list("""{"limit":-5}""")
        assertTrue(negative.ok, negative.error ?: "")
        assertTrue(negative.output.contains("limit 1"))
    }

    @Test
    fun `empty directory reports empty`() {
        val empty = temp.newFolder("empty").toPath()
        val result = ListFilesTool(Workspace(empty)).execute(JSONObject("{}"))
        assertTrue(result.ok, result.error ?: "")
        assertTrue(result.output.contains("(empty directory)"))
    }

    @Test
    fun `inaccessible directory fails explicitly`() {
        setupTree()
        val locked = Files.createDirectories(root.resolve("locked"))
        Files.setPosixFilePermissions(locked, java.util.EnumSet.noneOf(java.nio.file.attribute.PosixFilePermission::class.java))
        try {
            assumeTrue("environment ignores directory permissions", !Files.isReadable(locked))
            val result = list("""{"path":"locked"}""")
            assertFalse(result.ok)
            assertTrue(result.error!!.contains("not readable") || result.error!!.contains("not accessible"))
        } finally {
            Files.setPosixFilePermissions(
                locked,
                java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
                )
            )
        }
    }

    @Test
    fun `large result set is capped by scan cap and limit`() {
        setupTree()
        val many = Files.createDirectories(root.resolve("many"))
        for (i in 1..600) {
            Files.writeString(many.resolve("file-%04d.txt".format(i)), "x")
        }
        val result = list("""{"path":"many","recursive":true,"limit":500}""")
        assertTrue(result.ok, result.error ?: "")
        val entryLines = result.output.lines().count { it.startsWith("F ") }
        assertTrue("expected at most 500 lines, got $entryLines", entryLines <= 500)
        assertTrue(result.output.contains("truncated"))
    }

    private fun assertEquals(expected: List<String>, actual: List<String>) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
