package com.waqti.agent.loop

import com.waqti.agent.model.ModelException
import com.waqti.agent.model.ModelProvider
import com.waqti.agent.model.ModelRequest
import com.waqti.agent.model.ModelResponse

/**
 * Scripted model for tests: hands back one prepared response per round and
 * records every request so tests can assert what the model actually saw.
 */
class FakeModelProvider(
    script: List<ModelResponse>,
    override val label: String = "fake"
) : ModelProvider {

    private val remaining = ArrayDeque(script)
    val requests = mutableListOf<ModelRequest>()

    override suspend fun respond(request: ModelRequest): ModelResponse {
        requests.add(request)
        return remaining.removeFirstOrNull()
            ?: throw ModelException("FakeModelProvider script exhausted after ${requests.size} request(s)")
    }
}
