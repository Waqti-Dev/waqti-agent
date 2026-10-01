package com.waqti.agent.runtime

import android.util.Base64
import com.waqti.agent.ToolSpec
import com.waqti.agent.model.ChatMessage
import com.waqti.agent.model.wireName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Implementation of [LocalInferenceRuntime] that delegates to the JNI
 * [NativeRuntime] object. Runs all blocking native calls on the IO dispatcher.
 */
class NativeLocalInferenceRuntime : LocalInferenceRuntime {

    override val label = "local (llama.cpp)"

    override suspend fun loadModel(modelPath: String) = withContext(Dispatchers.IO) {
        val result = NativeRuntime.loadModel(modelPath)
        checkResult("loadModel", result)
    }

    override suspend fun createContext(nCtx: Int, nBatch: Int) = withContext(Dispatchers.IO) {
        val result = NativeRuntime.createContext(nCtx, nBatch)
        checkResult("createContext", result)
    }

    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        seed: Int
    ): String = withContext(Dispatchers.IO) {
        val result = NativeRuntime.generate(prompt, maxTokens, temperature, topK, topP, seed)
        return@withContext extractTextOrThrow("generate", result)
    }

    override suspend fun generateChat(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        seed: Int
    ): ChatGeneration = withContext(Dispatchers.IO) {
        val chatJson = chatEnvelopeToJson(messages, tools)
        val result = NativeRuntime.generateChat(chatJson, maxTokens, temperature, topK, topP, seed)
        return@withContext parseChatGeneration("generateChat", result)
    }

    override suspend fun releaseContext() = withContext(Dispatchers.IO) {
        val result = NativeRuntime.releaseContext()
        checkResult("releaseContext", result)
    }

    override suspend fun unloadModel() = withContext(Dispatchers.IO) {
        val result = NativeRuntime.unloadModel()
        checkResult("unloadModel", result)
    }

    override suspend fun getChatTemplate(): String = withContext(Dispatchers.IO) {
        val result = NativeRuntime.getChatTemplate()
        if (!result.startsWith("ok|")) {
            ""
        } else {
            // ok|<template>
            result.substringAfter("ok|")
        }
    }

    /**
     * Builds the single JSON object the native side reads:
     * `{"messages":[...],"tools":[...]}`.
     *
     * Assistant turns carry their `tool_calls` and tool turns carry the id they
     * answer, so the transcript the model sees is the real one — including what
     * a previous turn's tool actually returned. Tool definitions are passed
     * through verbatim; the native side knows nothing about Waqti's tools.
     */
    private fun chatEnvelopeToJson(messages: List<ChatMessage>, tools: List<ToolSpec>): String {
        val msgs = JSONArray()
        for (msg in messages) {
            val obj = JSONObject()
            obj.put("role", msg.role.wireName())
            obj.put("content", msg.content)
            if (msg.toolCalls.isNotEmpty()) {
                val calls = JSONArray()
                for (call in msg.toolCalls) {
                    calls.put(
                        JSONObject()
                            .put("id", call.id)
                            .put("name", call.name)
                            .put("arguments", call.arguments)
                    )
                }
                obj.put("tool_calls", calls)
            }
            msg.toolCallId?.let { obj.put("tool_call_id", it) }
            msg.toolName?.let { obj.put("tool_name", it) }
            msgs.put(obj)
        }

        val toolArray = JSONArray()
        for (spec in tools) {
            toolArray.put(
                JSONObject()
                    .put("name", spec.name)
                    .put("description", spec.description)
                    .put("parameters", spec.parameters)
            )
        }

        return JSONObject().put("messages", msgs).put("tools", toolArray).toString()
    }

    /**
     * Parses `ok|<ms>|tokens=<n>|stop=<reason>|tool_calls_b64=<b64>|text=<text>`.
     *
     * The tool-call array is base64 because it is JSON that can contain '|', the
     * same reason `text` is read with `substringAfter` rather than by splitting.
     * A payload that will not decode is a controlled failure, never a crash and
     * never a silently empty result.
     */
    private fun parseChatGeneration(op: String, result: String): ChatGeneration {
        if (!result.startsWith("ok|")) {
            throw InferenceException("$op failed: $result")
        }
        val text = extractTextOrThrow(op, result)
        val tokens = extractIntField(result, "tokens=")
        val stop = extractField(result, "stop=")
        val encoded = extractField(result, "tool_calls_b64=")

        val calls = if (encoded.isEmpty()) {
            emptyList()
        } else {
            try {
                val decoded = String(Base64.decode(encoded, Base64.DEFAULT))
                val array = JSONArray(decoded)
                (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    ToolCallSpec(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        arguments = o.optString("arguments")
                    )
                }
            } catch (e: Exception) {
                throw InferenceException("$op returned unreadable tool calls: ${e.message}")
            }
        }

        return ChatGeneration(
            text = text,
            toolCalls = calls,
            stopReason = stop,
            tokens = tokens
        )
    }

    /** Reads `|<name><value>` up to the next '|' — only for fields that cannot contain one. */
    private fun extractField(result: String, name: String): String {
        val marker = "|$name"
        val at = result.indexOf(marker)
        if (at < 0) return ""
        val from = at + marker.length
        val end = result.indexOf('|', from)
        return if (end < 0) result.substring(from) else result.substring(from, end)
    }

    private fun extractIntField(result: String, name: String): Int =
        extractField(result, name).toIntOrNull() ?: 0

    private fun checkResult(op: String, result: String) {
        if (!result.startsWith("ok|")) {
            throw InferenceException("$op failed: $result")
        }
    }

    private fun extractTextOrThrow(op: String, result: String): String {
        if (!result.startsWith("ok|")) {
            throw InferenceException("$op failed: $result")
        }
        // ok|<ms>|tokens=<n>|stop=<reason>|tool_calls_b64=<b64>|text=<text>
        // text is the LAST field and generated text may legitimately contain '|',
        // so take everything after the first "|text=" marker instead of splitting
        // on '|' — splitting silently truncated any answer containing a pipe.
        val marker = "|text="
        val at = result.indexOf(marker)
        if (at < 0) {
            throw InferenceException("$op returned no text field: $result")
        }
        return result.substring(at + marker.length)
    }
}