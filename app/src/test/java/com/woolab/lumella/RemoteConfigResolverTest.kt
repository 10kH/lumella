package com.woolab.lumella

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RemoteConfigResolver / AppConfig.withResolvedLumaBaseUrl: remote-first with a silent
 * BuildConfig fallback on any failure, exercised via a fake transport (no real network, plan:
 * break the "quick-tunnel URL baked into the APK" coupling).
 */
class RemoteConfigResolverTest {

    private class FakeTransport(private val respond: () -> Result<TokenHttpResponse>) : ConfigHttpTransport {
        var calls = 0
        var lastUrl: String? = null
        var lastHeaders: Map<String, String>? = null
        override fun get(url: String, headers: Map<String, String>, callback: (Result<TokenHttpResponse>) -> Unit) {
            calls++
            lastUrl = url
            lastHeaders = headers
            callback(respond())
        }
    }

    /**
     * Answers per-URL so a test can make the config endpoint healthy while the host it advertises
     * is dead — the shape of the real 2026-08-31 outage, which a single-response fake cannot
     * express.
     */
    private class RoutingTransport(
        private val configResponse: Result<TokenHttpResponse>,
        private val probeResponse: Result<TokenHttpResponse>,
    ) : ConfigHttpTransport {
        val urls = mutableListOf<String>()
        override fun get(url: String, headers: Map<String, String>, callback: (Result<TokenHttpResponse>) -> Unit) {
            urls += url
            callback(if (url.endsWith("/v1/config")) configResponse else probeResponse)
        }
    }

    private fun okConfig(url: String) =
        Result.success(TokenHttpResponse(200, """{"lumaBaseUrl":"$url","schemaRev":1}"""))

    private fun baseConfig() = AppConfig(
        tokenServiceBaseUrl = "https://lumella-token.vercel.app",
        lumaBaseUrl = "http://10.0.2.2:8010",
        localToken = "local-secret",
        brainClassName = "com.example.Brain",
        brainEmail = "learner@example.com",
        brainPassword = "pw",
    )

    @Test
    fun remoteUrlIsUsedWhenPresentAndReachable() {
        val transport = RoutingTransport(
            configResponse = okConfig("https://random-words.trycloudflare.com"),
            probeResponse = Result.success(TokenHttpResponse(200, """{"coach":true}""")),
        )

        val resolved = AppConfig.withResolvedLumaBaseUrl(baseConfig(), transport)

        assertEquals("https://random-words.trycloudflare.com", resolved.lumaBaseUrl)
        assertEquals("https://lumella-token.vercel.app/v1/config", transport.urls.first())
        assertEquals(
            "https://random-words.trycloudflare.com/v1/capabilities",
            transport.urls.last(),
        )
    }

    /**
     * The regression this probe exists for: /v1/config answers 200 with a well-formed URL whose
     * host is gone (a tunnel that died after publishing). Before the probe the app adopted that
     * host and lost the coach even on the home LAN, where the fallback was answering all along.
     */
    @Test
    fun staleButWellFormedRemoteUrlFallsBackToBuildConfig() {
        val transport = RoutingTransport(
            configResponse = okConfig("https://conventions-acres-copper-hayes.trycloudflare.com"),
            probeResponse = Result.failure(java.io.IOException("unknown host")),
        )

        val resolved = AppConfig.withResolvedLumaBaseUrl(baseConfig(), transport)

        assertEquals("http://10.0.2.2:8010", resolved.lumaBaseUrl)
    }

    @Test
    fun remoteHostAnsweringNonSuccessOnProbeFallsBackToBuildConfig() {
        val transport = RoutingTransport(
            configResponse = okConfig("https://tunnel-up-but-luma-down.example"),
            probeResponse = Result.success(TokenHttpResponse(502, "bad gateway")),
        )

        val resolved = AppConfig.withResolvedLumaBaseUrl(baseConfig(), transport)

        assertEquals("http://10.0.2.2:8010", resolved.lumaBaseUrl)
    }

    @Test
    fun nonSuccessHttpCodeFallsBackToBuildConfig() {
        val transport = FakeTransport { Result.success(TokenHttpResponse(401, """{"error":"unauthorized"}""")) }

        val resolved = AppConfig.withResolvedLumaBaseUrl(baseConfig(), transport)

        assertEquals("http://10.0.2.2:8010", resolved.lumaBaseUrl)
    }

    @Test
    fun transportFailureFallsBackToBuildConfig() {
        val transport = FakeTransport { Result.failure(java.io.IOException("timeout")) }

        val resolved = AppConfig.withResolvedLumaBaseUrl(baseConfig(), transport)

        assertEquals("http://10.0.2.2:8010", resolved.lumaBaseUrl)
    }

    @Test
    fun nullLumaBaseUrlFallsBackToBuildConfig() {
        val transport = FakeTransport { Result.success(TokenHttpResponse(200, """{"lumaBaseUrl":null,"schemaRev":1}""")) }

        val resolved = AppConfig.withResolvedLumaBaseUrl(baseConfig(), transport)

        assertEquals("http://10.0.2.2:8010", resolved.lumaBaseUrl)
    }

    @Test
    fun blankLumaBaseUrlFallsBackToBuildConfig() {
        val transport = FakeTransport { Result.success(TokenHttpResponse(200, """{"lumaBaseUrl":"   ","schemaRev":1}""")) }

        val resolved = AppConfig.withResolvedLumaBaseUrl(baseConfig(), transport)

        assertEquals("http://10.0.2.2:8010", resolved.lumaBaseUrl)
    }

    @Test
    fun malformedJsonFallsBackWithoutCrashing() {
        val transport = FakeTransport { Result.success(TokenHttpResponse(200, "not json at all {{{")) }

        val resolved = AppConfig.withResolvedLumaBaseUrl(baseConfig(), transport)

        assertEquals("http://10.0.2.2:8010", resolved.lumaBaseUrl)
    }

    @Test
    fun blankTokenServiceBaseUrlSkipsRemoteCallAndFallsBack() {
        val transport = FakeTransport { Result.success(TokenHttpResponse(200, """{"lumaBaseUrl":"https://x.trycloudflare.com"}""")) }
        val config = baseConfig().copy(tokenServiceBaseUrl = "   ")

        val resolved = AppConfig.withResolvedLumaBaseUrl(config, transport)

        assertEquals("http://10.0.2.2:8010", resolved.lumaBaseUrl)
        assertEquals(0, transport.calls)
    }
}
