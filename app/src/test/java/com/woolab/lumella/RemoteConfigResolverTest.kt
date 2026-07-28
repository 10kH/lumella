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

    private fun baseConfig() = AppConfig(
        tokenServiceBaseUrl = "https://lumella-token.vercel.app",
        lumaBaseUrl = "http://10.0.2.2:8010",
        localToken = "local-secret",
        brainClassName = "com.example.Brain",
        brainEmail = "learner@example.com",
        brainPassword = "pw",
    )

    @Test
    fun remoteUrlIsUsedWhenPresent() {
        val transport = FakeTransport {
            Result.success(TokenHttpResponse(200, """{"lumaBaseUrl":"https://random-words.trycloudflare.com","schemaRev":1}"""))
        }

        val resolved = AppConfig.withResolvedLumaBaseUrl(baseConfig(), transport)

        assertEquals("https://random-words.trycloudflare.com", resolved.lumaBaseUrl)
        assertEquals("https://lumella-token.vercel.app/v1/config", transport.lastUrl)
        assertEquals("local-secret", transport.lastHeaders?.get("X-Lumella-Local-Token"))
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
