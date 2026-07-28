package com.woolab.lumella

import com.woolab.lumella.brain.BrainFactory

/**
 * Device-side runtime configuration (plan G006): base URLs, local pairing token, and the
 * runtime-DI brain implementation class name. Sourced from `local.properties` via generated
 * `BuildConfig` fields (see `app/build.gradle.kts`) — the Android app itself holds zero API
 * credentials (see `docs/dev-loop.md` "Credential placement"); `localToken` is the shared
 * local pairing secret, not an OpenAI key.
 */
data class AppConfig(
    val tokenServiceBaseUrl: String,
    val lumaBaseUrl: String,
    val localToken: String,
    val brainClassName: String,
    val brainEmail: String,
    val brainPassword: String,
) {
    companion object {
        /** Reads config from the generated `BuildConfig` (production wiring). */
        fun fromBuildConfig(): AppConfig = AppConfig(
            tokenServiceBaseUrl = BuildConfig.TOKEN_SERVICE_BASE_URL,
            lumaBaseUrl = BuildConfig.LUMA_BASE_URL,
            localToken = BuildConfig.LUMELLA_LOCAL_TOKEN,
            brainClassName = BuildConfig.BRAIN_CLASS_NAME,
            brainEmail = BuildConfig.BRAIN_EMAIL,
            brainPassword = BuildConfig.BRAIN_PASSWORD,
        )

        /**
         * Parses config from a raw key/value map mirroring `local.properties` keys —
         * unit-testable without Android's `BuildConfig`.
         */
        fun fromProperties(props: Map<String, String>): AppConfig = AppConfig(
            tokenServiceBaseUrl = props["lumella.tokenServiceBaseUrl"].orEmpty(),
            lumaBaseUrl = props["lumella.lumaBaseUrl"].orEmpty(),
            localToken = props["lumella.localToken"].orEmpty(),
            brainClassName = props["lumella.brainClassName"]
                ?.takeIf { it.isNotBlank() }
                ?: BrainFactory.DEFAULT_BRAIN_CLASS_NAME,
            brainEmail = props["lumella.brainEmail"].orEmpty(),
            brainPassword = props["lumella.brainPassword"].orEmpty(),
        )

        /**
         * Resolves the effective `lumaBaseUrl` for [config]: remote config
         * (`<tokenServiceBaseUrl>/v1/config`) first, BuildConfig/properties-sourced
         * `config.lumaBaseUrl` as the fallback on any remote failure — see
         * [RemoteConfigResolver] for the exact fallback conditions. This breaks the
         * "quick-tunnel URL baked into the APK" coupling: the tunnel URL can change on every
         * `ops/luma-tunnel.sh` restart without requiring a rebuild.
         *
         * MUST be called off the UI thread (performs blocking I/O via [transport]); never
         * throws, so a failed/unreachable remote config never blocks or crashes app boot.
         */
        fun withResolvedLumaBaseUrl(config: AppConfig, transport: ConfigHttpTransport): AppConfig {
            val resolved = RemoteConfigResolver.resolveLumaBaseUrl(
                transport = transport,
                tokenServiceBaseUrl = config.tokenServiceBaseUrl,
                localToken = config.localToken,
                buildConfigFallback = config.lumaBaseUrl,
            )
            return config.copy(lumaBaseUrl = resolved)
        }
    }
}
