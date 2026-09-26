package com.woolab.tutor.slowpath

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * Calls the credential-safe slow-path endpoint (api/pedagogy-agent.js). AC7:
 * Android never holds OPENAI_API_KEY; the key lives only in Vercel env and is used
 * server-side. This client only sends the analysis request over HTTPS to the
 * trusted endpoint and returns the raw chat-completions response body for an agent
 * to parse into a StateDelta.
 */
class InvalidPedagogyEndpointException(message: String) : IllegalArgumentException(message)

class EndpointPedagogyAgentClient(
    private val language: TutorLanguage,
    private val okHttpClient: OkHttpClient,
    private val endpointUrl: String,
    /** Shared secret sent as `X-Ella-Token-Secret`; blank keeps the legacy anonymous call. */
    private val endpointSecret: String = "",
) : PedagogyAgentClient {

    override fun analyze(role: String, task: SlowPathTask, callback: (Result<String>) -> Unit) {
        val normalized = endpointUrl.trim()
        if (normalized.isEmpty()) {
            callback(Result.failure(InvalidPedagogyEndpointException("Pedagogy endpoint not configured")))
            return
        }

        val payload = buildRequestJson(language, role, task)

        val request = try {
            Request.Builder()
                .url(normalized)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .apply {
                    // The endpoint spends OpenAI credit and used to serve anyone who called
                    // it; it now requires this header (gated 2026-07-28).
                    if (endpointSecret.isNotBlank()) {
                        addHeader("X-Ella-Token-Secret", endpointSecret)
                    }
                }
                .build()
        } catch (e: IllegalArgumentException) {
            callback(Result.failure(InvalidPedagogyEndpointException("Pedagogy endpoint URL is invalid")))
            return
        }

        if (!request.url.isHttps) {
            callback(Result.failure(InvalidPedagogyEndpointException("Pedagogy endpoint must use HTTPS")))
            return
        }

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(IOException("Pedagogy endpoint request failed", e)))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        callback(Result.failure(IOException("Pedagogy endpoint returned HTTP ${it.code}")))
                        return
                    }
                    callback(Result.success(body))
                }
            }
        })
    }
}

/** Minimal JSON object encoder (avoids org.json so client guards are JVM-testable). */
internal fun buildRequestJson(language: TutorLanguage, role: String, task: SlowPathTask): String {
    val sb = StringBuilder()
    sb.append('{')
    sb.append("\"role\":").append(jsonString(role))
    // The function selects its prompt table by this; without it, it assumes English and
    // analyses a Korean sentence as if it were one (pedagogy-agent.js dcce79d).
    sb.append(",\"language\":").append(jsonString(language.code))
    sb.append(",\"userTranscript\":").append(jsonString(task.userTranscript))
    task.ellaTranscript?.let { sb.append(",\"ellaTranscript\":").append(jsonString(it)) }
    task.imageBase64?.let { sb.append(",\"imageBase64\":").append(jsonString(it)) }
    sb.append('}')
    return sb.toString()
}

private fun jsonString(value: String): String {
    val sb = StringBuilder(value.length + 2)
    sb.append('"')
    for (c in value) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}
