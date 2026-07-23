package com.woolab.lumella

import com.woolab.lumella.util.MiniJson

/**
 * Adapts the legacy ELLA `RealtimeCredentialProvider` pattern (bearer credential
 * fetch + `sk-`/`sk_` guard) to the lumella token-service (plan P3): a local HTTP
 * service on `:8788` that mints short-lived Realtime client secrets, gated by an
 * `X-Lumella-Local-Token` shared-secret header.
 *
 * The HTTP transport is injected via [TokenHttpTransport] (not a concrete HTTP
 * client) so unit tests use fakes — no real network in tests.
 */
data class TokenHttpResponse(val code: Int, val body: String)

fun interface TokenHttpTransport {
    /** Issue a POST to [url] with [headers] and [bodyJson]; invoke [callback] with the result. */
    fun post(url: String, headers: Map<String, String>, bodyJson: String, callback: (Result<TokenHttpResponse>) -> Unit)
}

/** A Realtime client secret with its absolute expiry, as minted by the token-service. */
data class RealtimeToken(val value: String, val expiresAtEpochMillis: Long)

class MissingTokenServiceBaseUrlException : IllegalStateException(
    "Token-service base URL is not configured.",
)

class InvalidTokenServiceResponseException(message: String) : IllegalStateException(message)

/**
 * Fetches and caches Realtime client secrets from the local token-service.
 *
 * - POSTs to `<baseUrl>/v1/realtime/token` with the `X-Lumella-Local-Token` header.
 * - TTL-caches the token, refreshing [refreshBeforeExpiryMillis] before [RealtimeToken.expiresAtEpochMillis]
 *   so callers never hand out a token that is about to expire.
 * - Rejects standard OpenAI API keys (`sk-`/`sk_`) via [RealtimeCredentialGuard], matching
 *   the ELLA guard semantics: Android must only ever hold a short-lived Realtime client secret.
 */
class TokenServiceCredentialProvider(
    private val transport: TokenHttpTransport,
    baseUrl: String,
    private val localToken: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val refreshBeforeExpiryMillis: Long = 60_000L,
) {
    private val tokenUrl: String

    init {
        val normalized = baseUrl.trim()
        if (normalized.isEmpty()) throw MissingTokenServiceBaseUrlException()
        tokenUrl = "${normalized.trimEnd('/')}/v1/realtime/token"
    }

    @Volatile
    private var cached: RealtimeToken? = null

    /** Returns a cached token if still fresh (per [refreshBeforeExpiryMillis]), else fetches a new one. */
    fun fetchToken(callback: (Result<RealtimeToken>) -> Unit) {
        val current = cached
        if (current != null && clock() < current.expiresAtEpochMillis - refreshBeforeExpiryMillis) {
            callback(Result.success(current))
            return
        }

        transport.post(
            tokenUrl,
            mapOf("X-Lumella-Local-Token" to localToken),
            "{}",
        ) { result ->
            result.fold(
                onSuccess = { response -> handleResponse(response, callback) },
                onFailure = { callback(Result.failure(it)) },
            )
        }
    }

    /** Forces the next [fetchToken] call to refresh, ignoring any cached value. */
    fun invalidate() {
        cached = null
    }

    private fun handleResponse(response: TokenHttpResponse, callback: (Result<RealtimeToken>) -> Unit) {
        if (response.code !in 200..299) {
            callback(Result.failure(InvalidTokenServiceResponseException("Token-service returned HTTP ${response.code}")))
            return
        }

        val parsed = parseTokenResponse(response.body)
        if (parsed == null) {
            callback(Result.failure(InvalidTokenServiceResponseException("Token-service response missing token/expiresAt")))
            return
        }

        if (parsed.expiresAtEpochMillis <= clock()) {
            // Fail-closed: an already-expired token is never cached or returned.
            callback(Result.failure(InvalidTokenServiceResponseException("Token-service returned an already-expired token")))
            return
        }

        if (RealtimeCredentialGuard.isStandardOpenAiApiKey(parsed.value)) {
            callback(Result.failure(StandardOpenAiApiKeyRejectedException()))
            return
        }

        cached = parsed
        callback(Result.success(parsed))
    }

    /** Parses `{"token":"...","expiresAt":<epoch millis>}` (see token-service/server.mjs). */
    private fun parseTokenResponse(body: String): RealtimeToken? {
        val obj = MiniJson.asObject(MiniJson.parse(body)) ?: return null
        val token = obj["token"] as? String ?: return null
        val expiresAt = (obj["expiresAt"] as? Double)?.toLong() ?: return null
        if (token.isBlank()) return null
        return RealtimeToken(value = token, expiresAtEpochMillis = expiresAt)
    }
}

/**
 * Fail-closed bootstrap wrapper (plan G006 P3): [TokenServiceCredentialProvider]'s constructor
 * throws [MissingTokenServiceBaseUrlException] eagerly when `baseUrl` is blank (e.g. an
 * unconfigured/misconfigured `local.properties`). `MainActivity.onCreate` must never let that
 * escape as an activity-creation crash, so it calls this instead of the raw constructor and
 * degrades to the `TOKEN-FAIL` status (no realtime transport wiring) when it returns null.
 */
fun createTokenServiceCredentialProviderOrNull(
    transport: TokenHttpTransport,
    baseUrl: String,
    localToken: String,
): TokenServiceCredentialProvider? = try {
    TokenServiceCredentialProvider(transport, baseUrl, localToken)
} catch (e: Exception) {
    null
}
