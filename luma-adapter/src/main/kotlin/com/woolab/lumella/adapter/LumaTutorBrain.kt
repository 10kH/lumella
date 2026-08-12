package com.woolab.lumella.adapter

import com.woolab.lumella.contract.BrainCapabilities
import com.woolab.lumella.contract.BrainConnection
import com.woolab.lumella.contract.BrainConnectionState
import com.woolab.lumella.contract.BrainCredentialsProvider
import com.woolab.lumella.contract.BrainSession
import com.woolab.lumella.contract.ImageContext
import com.woolab.lumella.contract.CoachIndicator
import com.woolab.lumella.contract.ResumableSession
import com.woolab.lumella.contract.SessionPolicy
import com.woolab.lumella.contract.SteeringCorrection
import com.woolab.lumella.contract.SteeringEvidence
import com.woolab.lumella.contract.SteeringResult
import com.woolab.lumella.contract.SteeringVisual
import com.woolab.lumella.contract.TurnEvidence
import com.woolab.lumella.contract.TutorBrain
import com.woolab.lumella.contract.UnavailableReason
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Tunables for [LumaTutorBrain]. All defaults are conservative and safe for
 * production; tests override [heartbeatIntervalMs] to keep the daemon thread
 * fast without sleeping the whole suite.
 *
 * @param resumeWindowMinutes D-7: an active session younger than this is
 *   eligible for [SessionPolicy.RESUME_ACTIVE]; older sessions start fresh.
 */
data class LumaAdapterConfig(
    val heartbeatIntervalMs: Long = 60_000,
    val resumeWindowMinutes: Long = 30,
)

/**
 * [TutorBrain] implementation against the luma REST backend
 * (`luma-api/src/luma_api/api/routes/{auth,devices,users,sessions,orchestrator,images}.py`).
 * All luma-specific knowledge (routes, request/response shapes) is scoped to
 * this module; `:tutor-contract` and `:app` never see luma types directly.
 *
 * Session-creation note: per `LumaBackendClient.orchestratorTurn`, a turn POST
 * accepts a nullable `orchestratorSessionId` and the backend creates one
 * implicitly on the first turn when absent. [startSession] with
 * [SessionPolicy.FRESH] therefore cannot obtain a real backend session id
 * up front — it hands back a locally-generated pending id
 * (`"pending-<n>"`); [submitTurnEvidence] rebinds it to the backend-assigned
 * id once the first turn response comes back.
 */
class LumaTutorBrain(
    private val transport: LumaHttpTransport = LumaHttpUrlConnectionTransport(),
    private val config: LumaAdapterConfig = LumaAdapterConfig(),
    private val clock: () -> Instant = Instant::now,
) : TutorBrain {

    private var baseUrl: String = ""
    private var accessToken: String = ""
    private var deviceId: String = ""
    private var currentSessionId: String? = null
    private var lastResumableSession: ResumableSession? = null

    @Volatile private var capabilities: BrainCapabilities = BrainCapabilities(coach = false, capabilitiesRoute = false)

    private var lastSubmittedTurnId: Int? = null
    @Volatile private var lastEvidence: SteeringEvidence? = null
    @Volatile private var steeringUnavailable: UnavailableReason? = null
    @Volatile private var coachIndicatorField: CoachIndicator? = null

    private val pendingSessionCounter = AtomicLong(0)
    private var heartbeatThread: Thread? = null
    private val heartbeatStop = AtomicBoolean(false)

    override fun connect(provider: BrainCredentialsProvider): BrainConnection {
        val credentials = provider.credentials()
        baseUrl = credentials.baseUrl.trim().removeSuffix("/")

        val loginPayload = jsonObj(
            "provider" to jsonStr("email"),
            "email" to jsonStr(credentials.email),
            "password" to jsonStr(credentials.password),
            "client" to jsonStr("glasses"),
        )
        val loginResponse = try {
            postJson("/v1/auth/session", loginPayload, authorized = false)
        } catch (_: Exception) {
            return BrainConnection(BrainConnectionState.AUTH_REQUIRED, BrainCapabilities(coach = false, capabilitiesRoute = false))
        }
        if (loginResponse.code == 401) {
            return BrainConnection(BrainConnectionState.AUTH_REQUIRED, BrainCapabilities(coach = false, capabilitiesRoute = false))
        }
        val loginJson = LumaJsonParser.parseOrNull(loginResponse.body) as? LumaJson.Obj
        val token = if (loginResponse.code in 200..299) loginJson?.str("accessToken") else null
        if (token.isNullOrBlank()) {
            return BrainConnection(BrainConnectionState.AUTH_REQUIRED, BrainCapabilities(coach = false, capabilitiesRoute = false))
        }
        accessToken = token

        val caps = fetchCapabilities()
        capabilities = caps

        registerDevice(credentials.deviceName)
        startHeartbeat(credentials.deviceName)

        val resumable = fetchActiveOrchestratorSession()
        lastResumableSession = resumable

        val state = if (caps.capabilitiesRoute) BrainConnectionState.READY else BrainConnectionState.DEGRADED
        return BrainConnection(state = state, capabilities = caps, resumableSession = resumable)
    }

    override fun startSession(policy: SessionPolicy): BrainSession {
        val resumable = lastResumableSession
        if (policy == SessionPolicy.RESUME_ACTIVE && resumable != null && resumable.ageMinutes < config.resumeWindowMinutes) {
            currentSessionId = resumable.sessionId
            lastSubmittedTurnId = null
            lastEvidence = null
            steeringUnavailable = null
            coachIndicatorField = null
            return BrainSession(sessionId = resumable.sessionId, resumed = true)
        }

        val pendingId = "pending-${pendingSessionCounter.incrementAndGet()}"
        currentSessionId = pendingId
        lastSubmittedTurnId = null
        lastEvidence = null
        steeringUnavailable = null
        coachIndicatorField = null
        return BrainSession(sessionId = pendingId, resumed = false)
    }

    override fun submitTurnEvidence(evidence: TurnEvidence) {
        if (evidence.turnId == lastSubmittedTurnId) return
        lastSubmittedTurnId = evidence.turnId

        val fields = linkedMapOf<String, LumaJson>(
            "orchestratorSessionId" to jsonStr(resolvedSessionIdOrNull()),
            "surface" to jsonStr("glasses"),
            "deviceId" to jsonStr(deviceId),
            "content" to jsonStr(evidence.learnerTranscript),
            "responseMode" to jsonStr("coach"),
            "attachments" to LumaJson.Arr(emptyList()),
            "metadata" to jsonObj(),
        )
        if (evidence.imageId != null) {
            fields["imageId"] = jsonStr(evidence.imageId)
        }

        try {
            val response = postJson("/v1/orchestrator/turn", LumaJson.Obj(fields))
            if (response.code !in 200..299) {
                steeringUnavailable = UnavailableReason.SLOW_PATH_UNAVAILABLE
                coachIndicatorField = null
                return
            }
            val json = LumaJsonParser.parseOrNull(response.body) as? LumaJson.Obj
            if (json == null) {
                steeringUnavailable = UnavailableReason.SLOW_PATH_UNAVAILABLE
                coachIndicatorField = null
                return
            }

            json.obj("session")?.str("id")?.let { sessionId -> currentSessionId = sessionId }

            val coach = json.obj("coachEvidence")
            if (coach != null) {
                lastEvidence = distillCoachEvidence(coach, evidence.turnId)
            } else {
                // Parseable 2xx without coachEvidence: not steering, but the slow path is
                // reachable again — clear any stale unavailability flag (NOT_READY surfaces
                // via the null lastEvidence check in fetchSteering).
                lastEvidence = null
            }

            // 08/05 requirement 3: honest per-turn coach indicator. Store only when the wire
            // carries BOTH fields (see luma-api schemas/orchestrator.py OrchestratorTurnResponse
            // — selectedRoute/selectedProvider are camelCase, non-optional on that model, but
            // this adapter stays tolerant of a response that omits them). Clear when there is
            // neither coachEvidence nor a route — stale routing must never outlive the turn it
            // described.
            val route = json.str("selectedRoute")
            val provider = json.str("selectedProvider")
            if (route != null && provider != null) {
                coachIndicatorField = CoachIndicator(route = route, provider = provider)
            } else if (coach == null && route == null) {
                coachIndicatorField = null
            }

            steeringUnavailable = null
        } catch (_: Exception) {
            steeringUnavailable = UnavailableReason.SLOW_PATH_UNAVAILABLE
            coachIndicatorField = null
        }
    }

    override fun coachIndicator(): CoachIndicator? = coachIndicatorField

    override fun fetchSteering(sessionId: String): SteeringResult {
        if (!capabilities.coach) return SteeringResult.Unavailable(UnavailableReason.COACH_UNSUPPORTED)
        steeringUnavailable?.let { return SteeringResult.Unavailable(it) }
        val evidence = lastEvidence ?: return SteeringResult.Unavailable(UnavailableReason.NOT_READY)
        return SteeringResult.Available(evidence)
    }

    override fun analyzeImage(bytes: ByteArray, mime: String): ImageContext {
        val boundary = "----lumaAdapter${System.nanoTime()}"
        val body = buildMultipartBody(boundary, fieldName = "file", fileName = "image", mime = mime, bytes = bytes)
        val headers = authHeaders() + mapOf("Content-Type" to "multipart/form-data; boundary=$boundary")
        val response = try {
            transport.request("POST", "$baseUrl/v1/images/analyze", headers, body)
        } catch (_: Exception) {
            return ImageContext(imageId = "")
        }
        val json = LumaJsonParser.parseOrNull(response.body) as? LumaJson.Obj
        return ImageContext(
            imageId = json?.str("imageId").orEmpty(),
            caption = json?.str("caption"),
            imageKind = json?.str("imageKind"),
            visibleText = json?.arr("visibleTextBlocks")?.let { LumaJson.Arr(it).strings() } ?: emptyList(),
        )
    }

    override fun endSession(sessionId: String) {
        stopHeartbeat()
        val resolvedId = if (sessionId.startsWith("pending-")) resolvedSessionIdOrNull() else sessionId
        if (resolvedId.isNullOrBlank()) return
        try {
            transport.request("POST", "$baseUrl/v1/orchestrator/sessions/$resolvedId/end", authHeaders(), null)
        } catch (_: Exception) {
            // best-effort; nothing more to do client-side.
        }
    }

    /** Stops the heartbeat daemon thread, joining it so callers observe a clean shutdown. Safe to call repeatedly. */
    fun stopHeartbeat() {
        heartbeatStop.set(true)
        heartbeatThread?.join(5_000)
        heartbeatThread = null
    }

    fun isHeartbeatRunning(): Boolean = heartbeatThread?.isAlive == true

    private fun resolvedSessionIdOrNull(): String? =
        currentSessionId?.takeUnless { it.startsWith("pending-") }

    private fun distillCoachEvidence(coach: LumaJson.Obj, turnRef: Int): SteeringEvidence {
        val corrections = coach.arr("corrections").orEmpty().mapNotNull { item ->
            val obj = item as? LumaJson.Obj ?: return@mapNotNull null
            val original = obj.str("original") ?: return@mapNotNull null
            val corrected = obj.str("corrected") ?: return@mapNotNull null
            val sourceTurnRef = obj.int("sourceTurnRef") ?: return@mapNotNull null
            SteeringCorrection(
                original = original,
                corrected = corrected,
                errorType = obj.str("errorType"),
                sourceTurnId = sourceTurnRef,
            )
        }
        val hints = coach.arr("hints")?.let { LumaJson.Arr(it).strings() } ?: emptyList()
        return SteeringEvidence(
            corrections = corrections,
            hints = hints,
            focusHint = coach.str("focusHint"),
            confidence = coach.num("confidence") ?: 0.0,
            sourceTurnId = turnRef,
            visual = distillVisual(coach.obj("visual")),
        )
    }

    /**
     * Tolerant [SteeringVisual] mapping: only produces a non-null value when the
     * `visual` object is present AND carries a non-empty `caption` — a real image
     * analysis always has a caption, so a missing/blank one signals no real
     * evidence rather than a caption to fabricate. Unknown fields are ignored.
     */
    private fun distillVisual(visual: LumaJson.Obj?): SteeringVisual? {
        if (visual == null) return null
        val imageId = visual.str("imageId") ?: return null
        val caption = visual.str("caption")?.takeIf { it.isNotEmpty() } ?: return null
        val salientElements = visual.arr("salientElements")?.let { LumaJson.Arr(it).strings() } ?: emptyList()
        val visibleTextBlocks = visual.arr("visibleTextBlocks")?.let { LumaJson.Arr(it).strings() } ?: emptyList()
        return SteeringVisual(
            imageId = imageId,
            caption = caption,
            salientElements = salientElements,
            visibleTextBlocks = visibleTextBlocks,
        )
    }

    private fun fetchCapabilities(): BrainCapabilities {
        val response = try {
            getJson("/v1/capabilities")
        } catch (_: Exception) {
            return BrainCapabilities(coach = false, capabilitiesRoute = false)
        }
        if (response.code == 404) return BrainCapabilities(coach = false, capabilitiesRoute = false)
        if (response.code !in 200..299) return BrainCapabilities(coach = false, capabilitiesRoute = false)

        val json = LumaJsonParser.parseOrNull(response.body) as? LumaJson.Obj
            ?: return BrainCapabilities(coach = false, capabilitiesRoute = false)

        val coach = json.bool("coach") ?: false
        val schemaRev = json.int("schemaRev")
        val routes = json.arr("routes")?.let { LumaJson.Arr(it).strings() } ?: emptyList()
        val raw = buildMap {
            if (schemaRev != null) put("schemaRev", schemaRev.toString())
            if (routes.isNotEmpty()) put("routes", routes.joinToString(","))
        }
        return BrainCapabilities(coach = coach, capabilitiesRoute = true, raw = raw)
    }

    private fun registerDevice(label: String) {
        val payload = jsonObj(
            "label" to jsonStr(label),
            "platform" to jsonStr("android"),
            "capabilities" to LumaJson.Arr(emptyList()),
        )
        val response = try {
            postJson("/v1/devices/register", payload)
        } catch (_: Exception) {
            return
        }
        if (response.code in 200..299) {
            val json = LumaJsonParser.parseOrNull(response.body) as? LumaJson.Obj
            deviceId = json?.str("deviceId").orEmpty()
        }
    }

    private fun startHeartbeat(label: String) {
        if (deviceId.isBlank()) return
        heartbeatStop.set(false)
        val thread = Thread({
            while (!heartbeatStop.get()) {
                try {
                    val payload = jsonObj("label" to jsonStr(label))
                    postJson("/v1/devices/$deviceId/heartbeat", payload)
                } catch (_: Exception) {
                    // Heartbeat is best-effort; keep looping until stopped.
                }
                var remaining = config.heartbeatIntervalMs
                while (remaining > 0 && !heartbeatStop.get()) {
                    val step = minOf(remaining, 100L)
                    Thread.sleep(step)
                    remaining -= step
                }
            }
        }, "luma-heartbeat")
        thread.isDaemon = true
        thread.start()
        heartbeatThread = thread
    }

    private fun fetchActiveOrchestratorSession(): ResumableSession? {
        val response = try {
            getJson("/v1/users/me/active-orchestrator-session")
        } catch (_: Exception) {
            return null
        }
        if (response.code !in 200..299) return null
        val json = LumaJsonParser.parseOrNull(response.body) as? LumaJson.Obj ?: return null
        val session = json.obj("session") ?: return null
        val sessionId = session.str("id") ?: return null
        val timestamp = session.str("lastMessageAt") ?: session.str("startedAt")
        val ageMinutes = timestamp?.let(::parseAgeMinutes) ?: 0L
        return ResumableSession(sessionId = sessionId, ageMinutes = ageMinutes)
    }

    private fun parseAgeMinutes(iso: String): Long = try {
        Duration.between(Instant.parse(iso), clock()).toMinutes().coerceAtLeast(0)
    } catch (_: Exception) {
        0L
    }

    private fun buildMultipartBody(boundary: String, fieldName: String, fileName: String, mime: String, bytes: ByteArray): ByteArray {
        val header = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\n")
            append("Content-Type: $mime\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val footer = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        return header + bytes + footer
    }

    private fun getJson(path: String): LumaHttpResponse =
        transport.request("GET", "$baseUrl$path", authHeaders(), null)

    private fun postJson(path: String, payload: LumaJson, authorized: Boolean = true): LumaHttpResponse {
        val body = LumaJsonWriter.write(payload).toByteArray(Charsets.UTF_8)
        val headers = if (authorized) {
            authHeaders() + mapOf("Content-Type" to "application/json")
        } else {
            mapOf("Content-Type" to "application/json", "X-Luma-Client" to "glasses")
        }
        return transport.request("POST", "$baseUrl$path", headers, body)
    }

    private fun authHeaders(): Map<String, String> =
        mapOf("Authorization" to "Bearer $accessToken", "X-Luma-Client" to "glasses")
}
