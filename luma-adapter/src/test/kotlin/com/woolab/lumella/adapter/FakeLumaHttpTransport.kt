package com.woolab.lumella.adapter

/**
 * In-memory [LumaHttpTransport] fake. Routes requests by matching [path]
 * against the tail of the request URL (so tests don't need to know the base
 * URL). No test in this module performs real network I/O.
 */
class FakeLumaHttpTransport : LumaHttpTransport {

    data class Recorded(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

    private val routes = mutableListOf<Triple<String, String, (Recorded) -> LumaHttpResponse>>()
    val recorded = mutableListOf<Recorded>()

    /** Registers a canned response for the first request whose [method]/URL-suffix [pathSuffix] matches. */
    fun on(method: String, pathSuffix: String, respond: (Recorded) -> LumaHttpResponse) {
        routes.add(Triple(method, pathSuffix, respond))
    }

    fun on(method: String, pathSuffix: String, response: LumaHttpResponse) {
        on(method, pathSuffix) { response }
    }

    fun countOf(method: String, pathSuffix: String): Int =
        recorded.count { it.method == method && it.url.endsWith(pathSuffix) }

    override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): LumaHttpResponse {
        val bodyText = body?.toString(Charsets.UTF_8)
        val record = Recorded(method, url, headers, bodyText)
        recorded.add(record)
        val match = routes.firstOrNull { (m, suffix, _) -> m == method && url.endsWith(suffix) }
            ?: throw AssertionError("no fake route registered for $method $url")
        return match.third(record)
    }
}
