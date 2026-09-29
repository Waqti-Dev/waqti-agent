package com.waqti.agent.model

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * OpenAI-compatible chat completions client (works with llama-server, OpenAI,
 * and other compatible endpoints).
 *
 * This is the only place in the agent that knows the wire format of a model
 * endpoint; everything else speaks [ModelProvider].
 */
class OpenAICompatProvider(
    baseUrl: String,
    private val model: String,
    private val apiKey: String? = null,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 180_000
) : ModelProvider {

    private val endpoint = baseUrl.trim().removeSuffix("/") + "/chat/completions"

    override val label: String = "$model @ ${baseUrl.trim().removeSuffix("/").ifBlank { "?" }}"

    override suspend fun respond(request: ModelRequest): ModelResponse {
        val headers = buildMap {
            if (!apiKey.isNullOrBlank()) put("Authorization", "Bearer $apiKey")
        }
        val raw = try {
            HttpJson.post(
                url = endpoint,
                body = buildRequestBody(request),
                headers = headers,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs
            )
        } catch (e: HttpException) {
            throw ModelException("Model endpoint $endpoint returned HTTP ${e.code}: ${e.body.take(300)}", e)
        } catch (e: Exception) {
            throw ModelException("Could not reach model endpoint $endpoint: ${e.message}", e)
        }
        return parseResponse(raw)
    }

    private fun buildRequestBody(request: ModelRequest): String {
        val root = JSONObject()
        root.put("model", model)
        root.put("stream", false)
        root.put("temperature", 0.2)

        val messages = JSONArray()
        for (message in request.messages) {
            val entry = JSONObject()
            entry.put("role", message.role.wireName())
            entry.put("content", message.content)
            if (message.role == Role.TOOL) {
                message.toolCallId?.let { entry.put("tool_call_id", it) }
                message.toolName?.let { entry.put("name", it) }
            }
            if (message.toolCalls.isNotEmpty()) {
                val calls = JSONArray()
                for (call in message.toolCalls) {
                    calls.put(
                        JSONObject()
                            .put("id", call.id)
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", call.name)
                                    .put("arguments", call.arguments)
                            )
                    )
                }
                entry.put("tool_calls", calls)
            }
            messages.put(entry)
        }
        root.put("messages", messages)

        if (request.tools.isNotEmpty()) {
            val tools = JSONArray()
            for (spec in request.tools) {
                val parameters = try {
                    JSONObject(spec.parameters)
                } catch (e: JSONException) {
                    throw IllegalArgumentException("Tool ${spec.name} has an invalid parameter schema", e)
                }
                tools.put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", spec.name)
                                .put("description", spec.description)
                                .put("parameters", parameters)
                        )
                )
            }
            root.put("tools", tools)
        }
        return root.toString()
    }

    private fun parseResponse(raw: String): ModelResponse {
        val root = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            throw ModelException("Model returned invalid JSON: ${raw.take(200)}", e)
        }

        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw ModelException("Model response contains no choices: ${raw.take(200)}")
        val message = choice.optJSONObject("message")
            ?: throw ModelException("Model response contains no message: ${raw.take(200)}")

        val callsArray = message.optJSONArray("tool_calls")
        if (callsArray != null && callsArray.length() > 0) {
            val calls = ArrayList<ToolCall>(callsArray.length())
            for (i in 0 until callsArray.length()) {
                val call = callsArray.optJSONObject(i) ?: continue
                val function = call.optJSONObject("function") ?: continue
                val name = function.optString("name").trim()
                if (name.isEmpty()) continue
                calls.add(
                    ToolCall(
                        id = call.optString("id").ifBlank { "call_$i" },
                        name = name,
                        arguments = function.optString("arguments").ifBlank { "{}" }
                    )
                )
            }
            if (calls.isNotEmpty()) return ModelResponse.Calls(calls)
        }

        val content: String = when (val rawContent = message.opt("content")) {
            null, JSONObject.NULL -> ""
            is String -> rawContent
            else -> rawContent.toString()
        }
        if (content.isBlank()) {
            throw ModelException("Model returned neither text nor tool calls")
        }
        return ModelResponse.Text(content)
    }
}
