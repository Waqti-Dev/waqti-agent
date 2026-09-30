package com.waqti.agent.loop

import com.waqti.agent.model.OpenAICompatProvider
import com.waqti.agent.tools.ListFilesTool
import com.waqti.agent.tools.SearchFilesTool
import com.waqti.agent.tools.ToolRegistry
import com.waqti.agent.tools.Workspace
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live end-to-end check against a real OpenAI-compatible model endpoint.
 *
 * The test is skipped (not passed, not faked) when no endpoint is reachable.
 * Point it at a running llama-server with:
 *
 *   WAQTI_LIVE_MODEL_URL=http://127.0.0.1:8080/v1  (default)
 *   WAQTI_LIVE_MODEL_NAME=local                     (default)
 */
class AgentLiveE2ETest {

    private val baseUrl: String =
        System.getenv("WAQTI_LIVE_MODEL_URL") ?: "http://127.0.0.1:8080/v1"
    private val modelName: String =
        System.getenv("WAQTI_LIVE_MODEL_NAME") ?: "local"

    @Test(timeout = 600_000)
    fun `live model runs real tools and answers from their output`() {
        assumeTrue("no model endpoint at $baseUrl - skipped", endpointReachable())

        val workspaceDir = createTempDir(prefix = "waqti-live-")
        try {
            File(workspaceDir, "src").mkdirs()
            File(workspaceDir, "docs").mkdirs()
            File(workspaceDir, "src/alpha.kt").writeText("fun main() {\n    // TODO: wire the UI\n}\n")
            File(workspaceDir, "src/beta.kt").writeText("fun helper() = 42\n")
            File(workspaceDir, "docs/notes.md").writeText("No action items here.\n")

            val workspace = Workspace(workspaceDir.toPath())
            val registry = ToolRegistry(listOf(ListFilesTool(workspace), SearchFilesTool(workspace)))
            val provider = OpenAICompatProvider(baseUrl, modelName)
            val events = mutableListOf<AgentEvent>()

            val outcome = runBlocking {
                AgentLoop(
                    model = provider,
                    tools = registry,
                    policy = AgentPolicy(maxSteps = 5, toolTimeoutMs = 60_000),
                    onEvent = { events.add(it) }
                ).run("Find which file in the workspace contains TODO and tell me its exact path.")
            }

            assertTrue("run failed: ${outcome.error}", outcome.ok)
            assertTrue(
                "expected at least one executed tool, got ${outcome.traces}",
                outcome.traces.isNotEmpty()
            )
            assertTrue(
                "expected a successful SearchFiles execution, got ${outcome.traces}",
                outcome.traces.any { it.name == "SearchFiles" && it.ok }
            )
            val answer = outcome.answer.orEmpty()
            println("[live] model=${provider.label} steps=${outcome.steps} traces=${outcome.traces.size}")
            outcome.traces.forEach { println("[live] tool=${it.name} ok=${it.ok} ${it.durationMs}ms ${it.summary}") }
            println("[live] answer=$answer")
            assertTrue(
                "final answer must name the file that really contains TODO, got: $answer",
                answer.contains("alpha.kt")
            )
            assertTrue(
                "final answer must not name a file without TODO, got: $answer",
                !answer.contains("beta.kt") || answer.indexOf("alpha.kt") < answer.indexOf("beta.kt")
            )
        } finally {
            workspaceDir.deleteRecursively()
        }
    }

    private fun endpointReachable(): Boolean = try {
        val connection = URL(baseUrl.removeSuffix("/") + "/models").openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        val code = connection.responseCode
        connection.disconnect()
        code in 200..299
    } catch (e: Exception) {
        false
    }
}
