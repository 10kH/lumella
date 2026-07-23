package com.woolab.lumella.brain

import com.woolab.lumella.contract.BrainCapabilities
import com.woolab.lumella.contract.BrainConnection
import com.woolab.lumella.contract.BrainConnectionState
import com.woolab.lumella.contract.BrainCredentialsProvider
import com.woolab.lumella.contract.BrainSession
import com.woolab.lumella.contract.ImageContext
import com.woolab.lumella.contract.SessionPolicy
import com.woolab.lumella.contract.SteeringResult
import com.woolab.lumella.contract.TurnEvidence
import com.woolab.lumella.contract.TutorBrain
import com.woolab.lumella.contract.UnavailableReason

/**
 * [TutorBrain] fallback used when no engine adapter is bound at runtime (plan G006's DI seam
 * — see [BrainFactory]). Fails closed: every readiness/steering query reports
 * [UnavailableReason.SLOW_PATH_UNAVAILABLE] rather than throwing, so
 * [com.woolab.lumella.voice.VoiceFastPath] degrades to voice-only exactly as it would for an
 * unreachable luma backend (W-1 posture).
 */
class NoOpBrain : TutorBrain {
    override fun connect(provider: BrainCredentialsProvider): BrainConnection =
        BrainConnection(BrainConnectionState.DEGRADED, BrainCapabilities(coach = false, capabilitiesRoute = false))

    override fun startSession(policy: SessionPolicy): BrainSession =
        BrainSession(sessionId = "noop", resumed = false)

    override fun submitTurnEvidence(evidence: TurnEvidence) = Unit

    override fun fetchSteering(sessionId: String): SteeringResult =
        SteeringResult.Unavailable(UnavailableReason.SLOW_PATH_UNAVAILABLE)

    override fun analyzeImage(bytes: ByteArray, mime: String): ImageContext = ImageContext(imageId = "")

    override fun endSession(sessionId: String) = Unit
}

/**
 * Runtime DI seam for the engine adapter (plan G006, continuing the P3 dependency rule):
 * `:app` declares no compile-time dependency on `:luma-adapter` (see
 * `contract-tests` `DependencyRuleGuardTest` / `README.md` "Dependency rule"), so the
 * concrete [TutorBrain] implementation is looked up reflectively by fully-qualified class
 * name — default `com.woolab.lumella.adapter.LumaTutorBrain`, overridable via
 * `local.properties`' `lumella.brainClassName` / `BuildConfig.BRAIN_CLASS_NAME`.
 * `:luma-adapter` is bound at RUNTIME only, via `runtimeOnly(project(":luma-adapter"))` in
 * `app/build.gradle.kts` — never `implementation`/`api`, so `debugCompileClasspath` stays
 * free of it.
 *
 * Fail-closed: if the class is absent from the runtime classpath, has no accessible no-arg
 * constructor, or does not implement [TutorBrain], [create] never throws — it returns
 * [NoOpBrain] and reports the reason via [onFallback] (for logging only; never crashes the
 * caller).
 */
object BrainFactory {
    const val DEFAULT_BRAIN_CLASS_NAME = "com.woolab.lumella.adapter.LumaTutorBrain"

    fun create(className: String, onFallback: (String) -> Unit = {}): TutorBrain {
        val resolvedClassName = className.ifBlank { DEFAULT_BRAIN_CLASS_NAME }
        return try {
            val clazz = Class.forName(resolvedClassName)
            when (val instance = clazz.getDeclaredConstructor().newInstance()) {
                is TutorBrain -> instance
                else -> {
                    onFallback("$resolvedClassName does not implement TutorBrain")
                    NoOpBrain()
                }
            }
        } catch (e: ClassNotFoundException) {
            onFallback("brain class not found on runtime classpath: $resolvedClassName")
            NoOpBrain()
        } catch (e: ReflectiveOperationException) {
            onFallback("failed to instantiate brain class $resolvedClassName: ${e.message}")
            NoOpBrain()
        } catch (e: LinkageError) {
            // ExceptionInInitializerError (static init threw) / NoClassDefFoundError (a
            // transitive class the brain class depends on is missing from the runtime
            // classpath) etc. are JVM Errors, not Exceptions — Class.forName/newInstance can
            // throw them for a brain class whose own static init or supertype chain is broken.
            // Fail-closed like every other branch here: NoOpBrain, never let it crash the caller.
            onFallback("brain class failed to link/initialize: $resolvedClassName: ${e.message}")
            NoOpBrain()
        } catch (e: Exception) {
            onFallback("unexpected brain factory failure for $resolvedClassName: ${e.message}")
            NoOpBrain()
        }
    }
}
