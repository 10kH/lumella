package com.woolab.lumella

import java.net.HttpURLConnection
import java.net.URL

/**
 * Default [TokenHttpTransport] backed by [java.net.HttpURLConnection] — no external HTTP
 * client dependency (mirrors `LumaHttpUrlConnectionTransport`'s pattern in `:luma-adapter`).
 * Intended to be called off the UI thread (e.g. [MainActivity]'s bootstrap thread); the
 * callback runs synchronously on the calling thread.
 */
class HttpUrlConnectionTokenHttpTransport(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 8_000,
) : TokenHttpTransport {
    override fun post(url: String, headers: Map<String, String>, bodyJson: String, callback: (Result<TokenHttpResponse>) -> Unit) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                connection.outputStream.use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                callback(Result.success(TokenHttpResponse(code, text)))
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }
}
