package com.woolab.lumella.adapter

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Raw HTTP response: status code plus the response body decoded as UTF-8 text. */
data class LumaHttpResponse(val code: Int, val body: String)

/**
 * Minimal HTTP transport seam so [LumaTutorBrain] never touches the network
 * directly. Production wiring uses [LumaHttpUrlConnectionTransport]; unit
 * tests inject fakes — no real network call happens in this module's tests.
 */
fun interface LumaHttpTransport {
    /**
     * Issue a single request against [url] (already the full absolute URL —
     * base URL + route). [body] is the raw request payload, or `null` for
     * bodyless requests (e.g. GET).
     */
    fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): LumaHttpResponse
}

/** Default [LumaHttpTransport] backed by [java.net.HttpURLConnection] — no external HTTP client dependency. */
class LumaHttpUrlConnectionTransport(
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 20_000,
) : LumaHttpTransport {
    override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): LumaHttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return LumaHttpResponse(code, text)
        } catch (e: IOException) {
            throw LumaTransportException("luma transport request failed: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }
}

/** Thrown by [LumaHttpTransport] implementations on transport-level (non-HTTP-status) failure. */
class LumaTransportException(message: String, cause: Throwable? = null) : IOException(message, cause)
