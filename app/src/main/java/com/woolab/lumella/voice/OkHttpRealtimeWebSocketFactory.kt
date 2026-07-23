package com.woolab.lumella.voice

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Production [RealtimeWebSocketFactory] backed by okhttp3 (ELLA's proven WS client for the
 * OpenAI Realtime API — see `TUTOR/LEGACY/ELLA` MainActivity's `realtimeWebSocketClient`). A
 * single client instance is reused across connections; [shutdown] releases its dispatcher
 * executor on final app teardown.
 *
 * Not exercised by unit tests (real okhttp3 sockets) — [OpenAiRealtimeTransport]'s tests
 * inject a fake [RealtimeWebSocketFactory] instead. On-device behavior is verified via the P5
 * smoke pass (see docs/smoke-checklist.md).
 */
class OkHttpRealtimeWebSocketFactory : RealtimeWebSocketFactory {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // WebSocket: unbounded read, matches LEGACY.
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun connect(url: String, headers: Map<String, String>, listener: RealtimeWebSocketListener): RealtimeWebSocket {
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        val socket = client.newWebSocket(
            requestBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
                override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = listener.onClosing(code, reason)
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(code, reason)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onFailure(t)
            },
        )
        return object : RealtimeWebSocket {
            override fun send(text: String): Boolean = socket.send(text)
            override fun close(code: Int, reason: String) {
                socket.close(code, reason)
            }
        }
    }

    /** Releases the shared client's dispatcher executor. Call once on final app teardown. */
    fun shutdown() {
        client.dispatcher.executorService.shutdown()
    }
}
