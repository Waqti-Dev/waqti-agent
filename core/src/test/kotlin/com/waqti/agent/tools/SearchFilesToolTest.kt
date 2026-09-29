package com.waqti.agent.tools

import java.nio.file.Files
import java.nio.file.Path
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SearchFilesToolTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: Path
    private lateinit var tool: SearchFilesTool

    private fun setupTree() {
        root = temp.newFolder("workspace").toPath()
        Files.createDirectories(root.resolve("src"))
        Files.createDirectories(root.resolve("docs"))
        Files.writeString(root.resolve("src/Main.kt"), "fun main() {\n    // TODO: finish\n}\n")
        Files.writeString(root.resolve("src/Util.kt"), "val ready = true\n")
        Files.writeString(root.resolve("docs/notes.md"), "nothing to see\nno TODO here\n")
        Files.writeString(root.resolve("root.txt"), "TODO at top level\n")
        tool = SearchFilesTool(Workspace(root))
    }

    private fun search(args: String): ToolResult = tool.execute(JSONObject(args))

    @Test
    fun `finds matching lines with file and line numbers`() {
        setupTree()
        val result = search("""{"query":"TODO"}""")
        assertTrue(result.ok, result.error ?: "")
        assertTrue(result.output.contains("matches: 3 in 3 file(s)"))
        assertTrue(result.output.contains("src/Main.kt:2:"))
        assertTrue(result.output.contains("docs/notes.md:2:"))
        assertTrue(result.output.contains("root.txt:1:"))
    }

    @Test
    fun `no match is a successful empty result`() {
        setupTree()
        val result = search("""{"query":"definitely-not-present"}""")
        assertTrue(result.ok, result.error ?: "")
        assertTrue(result.output.contains("matches: 0"))
        assertTrue(result.output.contains("(no matches)"))
    }

    @Test
    fun `empty query is rejected`() {
        setupTree()
        val result = search("""{"query":""}""")
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("must not be blank"))

        val missing = search("""{}""")
        assertFalse(missing.ok)
        assertTrue(missing.error!!.contains("must not be blank"))
    }

    @Test
    fun `missing path is rejected`() {
        setupTree()
        val result = search("""{"query":"TODO","path":"missing-dir"}""")
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("does not exist"))
    }

    @Test
    fun `path traversal is rejected`() {
        setupTree()
        val result = search("""{"query":"root","path":".."}""")
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("outside the workspace"))
    }

    @Test
    fun `matching is case insensitive by default and can be disabled`() {
        setupTree()
        val insensitive = search("""{"query":"todo"}""")
        assertTrue(insensitive.ok, insensitive.error ?: "")
        assertTrue(insensitive.output.contains("matches: 3"))

        val sensitive = search("""{"query":"todo","ignoreCase":false}""")
        assertTrue(sensitive.ok, sensitive.error ?: "")
        assertTrue(sensitive.output.contains("matches: 0"))
    }

    @Test
    fun `include glob narrows the search to file names`() {
        setupTree()
        val result = search("""{"query":"TODO","include":"*.kt"}""")
        assertTrue(result.ok, result.error ?: "")
        assertTrue(result.output.contains("matches: 1 in 1 file(s)"))
        assertTrue(result.output.contains("src/Main.kt:2:"))
        assertFalse(result.output.contains("root.txt"))
        assertFalse(result.output.contains("notes.md"))
    }

    @Test
    fun `non recursive search only inspects the directory level`() {
        setupTree()
        val result = search("""{"query":"TODO","recursive":false}""")
        assertTrue(result.ok, result.error ?: "")
        // Only root.txt is at the top level; nested files must not be scanned.
        assertTrue(result.output.contains("matches: 1 in 1 file(s)"))
        assertTrue(result.output.contains("root.txt:1:"))
        assertFalse(result.output.contains("src/Main.kt"))
    }

    @Test
    fun `binary files are skipped and counted`() {
        setupTree()
        Files.write(root.resolve("blob.bin"), byteArrayOf(0x50, 0x4B, 0x00, 0x01, 0x02))

        val result = search("""{"query":"TODO"}""")
        assertTrue(result.ok, result.error ?: "")
        assertTrue(result.output.contains("matches: 3"))
        assertTrue("binary skip must be reported", result.output.contains("binary skipped"))
        assertFalse(result.output.contains("blob.bin"))
    }

    @Test
    fun `large result sets respect the limit while counting everything`() {
        setupTree()
        val big = Files.createDirectories(root.resolve("big"))
        val content = (1..80).joinToString("\n") { "line $it TODO" }
        Files.writeString(big.resolve("many.txt"), content)

        val result = search("""{"query":"TODO","path":"big","limit":10}""")
        assertTrue(result.ok, result.error ?: "")
        assertTrue(result.output.contains("matches: 80 in 1 file(s) (showing 10, limit 10)"))
        val shown = result.output.lines().count { it.contains("many.txt:") }
        assertTrue("expected 10 shown matches, got $shown", shown == 10)
    }

    @Test
    fun `limit is clamped to a sane range`() {
        setupTree()
        val big = search("""{"query":"TODO","limit":99999}""")
        assertTrue(big.ok, big.error ?: "")
        assertTrue(big.output.contains("limit 500"))

        val small = search("""{"query":"TODO","limit":0}""")
        assertTrue(small.ok, small.error ?: "")
        assertTrue(small.output.contains("limit 1"))
    }

    @Test
    fun `searching a single file works`() {
        setupTree()
        val result = search("""{"query":"TODO","path":"root.txt"}""")
        assertTrue(result.ok, result.error ?: "")
        assertTrue(result.output.contains("matches: 1"))
        assertTrue(result.output.contains("root.txt:1:"))
    }

    @Test
    fun `oversized files are skipped and counted`() {
        setupTree()
        val huge = StringBuilder()
        while (huge.length <= SearchFilesTool.MAX_FILE_BYTES) {
            huge.append("0123456789abcdef\n")
        }
        Files.writeString(root.resolve("huge.txt"), huge.toString())

        val result = search("""{"query":"TODO"}""")
        assertTrue(result.ok, result.error ?: "")
        assertTrue(result.output.contains("skipped"))
        assertTrue(result.output.contains("matches: 3"))
    }
}
