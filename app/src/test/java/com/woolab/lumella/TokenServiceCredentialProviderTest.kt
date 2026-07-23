package com.woolab.lumella

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TokenServiceCredentialProvider: TTL cache + refresh-before-expiry + auth-header
 * presence, all exercised via a fake transport (no real network in unit tests).
 */
class TokenServiceCredentialProviderTest {

    private class FakeTransport(private val respond: () -> TokenHttpResponse) : TokenHttpTransport {
        var calls = 0
        var lastUrl: String? = null
        var lastHeaders: Map<String, String>? = null
        override fun post(url: String, headers: Map<String, String>, bodyJson: String, callback: (Result<TokenHttpResponse>) -> Unit) {
            calls++
            lastUrl = url
            lastHeaders = headers
            callback(Result.success(respond()))
        }
    }

    private fun tokenBody(value: String, expiresAt: Long) = """{"token":"$value","expiresAt":$expiresAt}"""

    @Test
    fun postsToTokenEndpointWithLocalTokenHeader() {
        val transport = FakeTransport { TokenHttpResponse(200, tokenBody("ek_abc123", 1_000_000L)) }
        val provider = TokenServiceCredentialProvider(transport, "http://127.0.0.1:8788", "secret-local-token", clock = { 0L })

        var result: Result<RealtimeToken>? = null
        provider.fetchToken { result = it }

        assertTrue(result!!.isSuccess)
        assertEquals("http://127.0.0.1:8788/v1/realtime/token", transport.lastUrl)
        assertEquals("secret-local-token", transport.lastHeaders?.get("X-Lumella-Local-Token"))
        assertEquals("ek_abc123", result!!.getOrThrow().value)
    }

    @Test
    fun cachesTokenUntilWithinRefreshWindow() {
        val transport = FakeTransport { TokenHttpResponse(200, tokenBody("ek_first", 100_000L)) }
        var now = 0L
        val provider = TokenServiceCredentialProvider(
            transport, "http://127.0.0.1:8788", "tok", clock = { now }, refreshBeforeExpiryMillis = 60_000L,
        )

        provider.fetchToken {}
        assertEquals(1, transport.calls)

        // Well before the refresh window (100_000 - 60_000 = 40_000): cache hit, no new call.
        now = 10_000L
        var second: Result<RealtimeToken>? = null
        provider.fetchToken { second = it }
        assertEquals(1, transport.calls)
        assertEquals("ek_first", second!!.getOrThrow().value)
    }

    @Test
    fun refreshesBeforeExpiryWindow() {
        var now = 0L
        var served = "ek_first"
        val transport = FakeTransport { TokenHttpResponse(200, tokenBody(served, 100_000L)) }
        val provider = TokenServiceCredentialProvider(
            transport, "http://127.0.0.1:8788", "tok", clock = { now }, refreshBeforeExpiryMillis = 60_000L,
        )

        provider.fetchToken {}
        assertEquals(1, transport.calls)

        // Within the refresh window (>= 100_000 - 60_000 = 40_000): must refresh.
        now = 41_000L
        served = "ek_second"
        var refreshed: Result<RealtimeToken>? = null
        provider.fetchToken { refreshed = it }
        assertEquals(2, transport.calls)
        assertEquals("ek_second", refreshed!!.getOrThrow().value)
    }

    @Test
    fun expiredTokenFromTransportIsRejectedAsFailureNotCachedNotReturned() {
        // Fail-closed: an already-expired expiresAt (<= clock()) must be rejected,
        // never cached, never handed back as a usable token.
        val transport = FakeTransport { TokenHttpResponse(200, tokenBody("ek_expired", 1_000L)) }
        val provider = TokenServiceCredentialProvider(transport, "http://127.0.0.1:8788", "tok", clock = { 1_000L })

        var result: Result<RealtimeToken>? = null
        provider.fetchToken { result = it }

        assertTrue(result!!.isFailure)
        assertTrue(result!!.exceptionOrNull() is InvalidTokenServiceResponseException)

        // Not cached: a subsequent fetch must hit the transport again, not return
        // the rejected (expired) token from a cache.
        var second: Result<RealtimeToken>? = null
        provider.fetchToken { second = it }
        assertEquals(2, transport.calls)
        assertTrue(second!!.isFailure)
    }

    @Test
    fun rejectsStandardOpenAiApiKeyFromTokenService() {
        val transport = FakeTransport { TokenHttpResponse(200, tokenBody("sk-should-not-be-issued", 100_000L)) }
        val provider = TokenServiceCredentialProvider(transport, "http://127.0.0.1:8788", "tok", clock = { 0L })

        var result: Result<RealtimeToken>? = null
        provider.fetchToken { result = it }

        assertTrue(result!!.isFailure)
        assertTrue(result!!.exceptionOrNull() is StandardOpenAiApiKeyRejectedException)
    }

    @Test
    fun nonSuccessHttpCodeYieldsFailure() {
        val transport = FakeTransport { TokenHttpResponse(500, "") }
        val provider = TokenServiceCredentialProvider(transport, "http://127.0.0.1:8788", "tok", clock = { 0L })

        var result: Result<RealtimeToken>? = null
        provider.fetchToken { result = it }

        assertTrue(result!!.isFailure)
        assertTrue(result!!.exceptionOrNull() is InvalidTokenServiceResponseException)
    }

    @Test
    fun missingBaseUrlThrowsAtConstruction() {
        val transport = FakeTransport { TokenHttpResponse(200, tokenBody("ek_x", 1L)) }
        try {
            TokenServiceCredentialProvider(transport, "  ", "tok")
            throw AssertionError("expected MissingTokenServiceBaseUrlException")
        } catch (e: MissingTokenServiceBaseUrlException) {
            // expected
        }
    }

    @Test
    fun blankBaseUrlBootstrapWrapperFailsClosedInsteadOfThrowing() {
        // Plan G006 P3: MainActivity.onCreate calls createTokenServiceCredentialProviderOrNull
        // instead of the raw constructor. A blank tokenServiceBaseUrl must yield null (so the
        // caller can degrade to TOKEN-FAIL) — the MissingTokenServiceBaseUrlException must never
        // escape this call and crash activity creation.
        val transport = FakeTransport { TokenHttpResponse(200, tokenBody("ek_x", 1L)) }

        val provider = createTokenServiceCredentialProviderOrNull(transport, "   ", "tok")

        assertEquals(null, provider)
    }
}
