package com.woolab.lumella.contracttests

import com.woolab.lumella.adapter.LumaHttpResponse
import com.woolab.lumella.adapter.LumaHttpTransport
import com.woolab.lumella.contract.BrainCredentials
import com.woolab.lumella.contract.BrainCredentialsProvider

/**
 * Minimal in-memory [LumaHttpTransport] fake local to `:contract-tests`.
 * `:luma-adapter`'s own `FakeLumaHttpTransport` lives in that module's test
 * sourceSet and isn't visible from here, so this is a small test-local
 * duplicate of the same "match by method + URL-suffix" routing shape. It
 * carries NO luma-api parsing/business logic of its own — every canned
 * response body wired through it in the fixture tests is real
 * fixture-recorded JSON text read straight off disk, and every response is
 * fed through the ADAPTER's own [com.woolab.lumella.adapter.LumaTutorBrain]
 * / `LumaJson*` parse path, never re-parsed by the test itself.
 */
class FixtureFakeTransport : LumaHttpTransport {
    private val routes = mutableMapOf<String, LumaHttpResponse>()

    fun on(method: String, pathSuffix: String, response: LumaHttpResponse) {
        routes["$method $pathSuffix"] = response
    }

    override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): LumaHttpResponse {
        val match = routes.entries.firstOrNull { (key, _) ->
            val separator = key.indexOf(' ')
            val routeMethod = key.substring(0, separator)
            val pathSuffix = key.substring(separator + 1)
            routeMethod == method && url.endsWith(pathSuffix)
        } ?: throw AssertionError("no fake route registered for $method $url")
        return match.value
    }
}

/** Shared demo credentials provider for the fixture-driven adapter tests below. */
class FixtureCredentialsProvider : BrainCredentialsProvider {
    override fun credentials(): BrainCredentials = BrainCredentials(
        baseUrl = "http://luma.fixture",
        email = "learner@luma.app",
        password = "luma1234",
        deviceName = "contract-tests",
    )
}
