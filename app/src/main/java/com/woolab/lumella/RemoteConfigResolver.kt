package com.woolab.lumella

import com.woolab.lumella.util.MiniJson

/** GET transport used to fetch the remote runtime config; mirrors [TokenHttpTransport]'s DI shape
 * (a separate interface because the config endpoint is GET, not POST) so unit tests can fake it
 * without any real network I/O.
 */
fun interface ConfigHttpTransport {
    /** Issue a GET to [url] with [headers]; invoke [callback] with the result. */
    fun get(url: String, headers: Map<String, String>, callback: (Result<TokenHttpResponse>) -> Unit)
}

/**
 * Resolves the luma-api base URL at app boot without requiring an APK rebuild (plan: break the
 * "quick tunnel URL baked into BuildConfig" coupling).
 *
 * Resolution order:
 *   1. Remote: `GET <tokenServiceBaseUrl>/v1/config` (the stable `lumella-token.vercel.app`
 *      endpoint), which returns the current `lumaBaseUrl` (e.g. today's cloudflared quick-tunnel
 *      URL, republished by `ops/luma-tunnel.sh` on every tunnel restart).
 *   2. Fallback: [buildConfigFallback] (the `local.properties`-sourced `BuildConfig.LUMA_BASE_URL`)
 *      when the remote call fails for ANY reason — network error, non-2xx, malformed JSON, or a
 *      blank/missing `lumaBaseUrl` field.
 *
 * MUST be invoked off the UI thread: [transport] is expected to perform blocking I/O (see
 * [HttpUrlConnectionTokenHttpTransport]) and invoke its callback synchronously before returning,
 * so this function itself blocks the calling thread. It never throws — any transport exception
 * or malformed response is treated as a cache miss and silently falls back, so a home-network-only
 * boot with the tunnel down never crashes or hangs the app.
 *
 * NOTE: deliberately does not call `android.util.Log` here — this class is exercised by plain-JVM
 * unit tests (no Robolectric), where `android.util.Log` is an unmocked stub that throws. Callers
 * running on-device (e.g. [MainActivity]) can log the before/after `lumaBaseUrl` themselves.
 */
object RemoteConfigResolver {

    fun resolveLumaBaseUrl(
        transport: ConfigHttpTransport,
        tokenServiceBaseUrl: String,
        localToken: String,
        buildConfigFallback: String,
    ): String {
        val trimmedBase = tokenServiceBaseUrl.trim()
        if (trimmedBase.isEmpty()) return buildConfigFallback

        val url = "${trimmedBase.trimEnd('/')}/v1/config"
        var resolved = buildConfigFallback
        try {
            transport.get(url, mapOf("X-Lumella-Local-Token" to localToken)) { result ->
                result.onSuccess { response ->
                    if (response.code in 200..299) {
                        val remote = parseLumaBaseUrl(response.body)
                        if (!remote.isNullOrBlank()) {
                            resolved = remote
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Defense in depth: even a transport that throws instead of invoking the failure
            // callback must never crash the bootstrap thread — keep the BuildConfig fallback.
        }
        return resolved
    }

    /** Parses `{"lumaBaseUrl": "...", "schemaRev": 1}` (see api/config.js). Never throws. */
    private fun parseLumaBaseUrl(body: String): String? = try {
        val obj = MiniJson.asObject(MiniJson.parse(body))
        obj?.get("lumaBaseUrl") as? String
    } catch (e: Exception) {
        null
    }
}
