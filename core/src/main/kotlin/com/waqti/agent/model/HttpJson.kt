package com.waqti.agent.model

import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Non-2xx response from the model endpoint. */
class HttpException(val code: Int, val body: String) :
    Exception("HTTP $code: ${body.take(300)}")

/**
 * Minimal blocking HTTP client exposed as a cancellable suspend function.
 *
 * The request runs on a daemon thread so a slow local model never blocks a caller
 * thread; cancelling the coroutine closes the socket, which aborts an in-flight read.
 */
internal object HttpJson {

    suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 180_000
    ): String = suspendCancellableCoroutine { continuation ->
        val connectionRef = AtomicReference<HttpURLConnection?>(null)

        val thread = Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    headers.forEach { (key, value) -> setRequestProperty(key, value) }
                }
                connectionRef.set(connection)

                connection.outputStream.use { out ->
                    out.write(body.toByteArray(Charsets.UTF_8))
                    out.flush()
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader -> reader.readText() }.orEmpty()

                if (code !in 200..299) throw HttpException(code, text)
                if (continuation.isActive) continuation.resume(text)
            } catch (t: Throwable) {
                if (t is IOException && !continuation.isActive) return@Thread
                if (continuation.isActive) continuation.resumeWithException(t)
            } finally {
                connection?.disconnect()
            }
        }
        thread.isDaemon = true
        thread.name = "waqti-http"

        continuation.invokeOnCancellation {
            // Closing the connection unblocks a read that is already in progress.
            runCatching { connectionRef.get()?.disconnect() }
        }
        thread.start()
    }
}
