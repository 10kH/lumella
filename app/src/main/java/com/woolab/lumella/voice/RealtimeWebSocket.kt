package com.woolab.lumella.voice

/**
 * Thin abstraction over a realtime WebSocket connection, injectable so
 * [OpenAiRealtimeTransport] can be exercised in JVM unit tests with a fake implementation —
 * no real network in tests (plan G006).
 */
interface RealtimeWebSocket {
    /** Enqueue [text] for sending; returns false if the connection cannot accept it (e.g. closed). */
    fun send(text: String): Boolean

    /** Initiate a graceful close. */
    fun close(code: Int, reason: String)
}

/** Callbacks for realtime WebSocket lifecycle/events, mirroring okhttp3.WebSocketListener's shape. */
interface RealtimeWebSocketListener {
    fun onOpen()
    fun onMessage(text: String)
    fun onClosing(code: Int, reason: String)
    fun onClosed(code: Int, reason: String)
    fun onFailure(t: Throwable)
}

/** Opens a [RealtimeWebSocket] to [url] with [headers], dispatching events to [listener]. */
fun interface RealtimeWebSocketFactory {
    fun connect(url: String, headers: Map<String, String>, listener: RealtimeWebSocketListener): RealtimeWebSocket
}
