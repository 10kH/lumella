package com.woolab.lumella

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.woolab.lumella.agents.SlowPathDispatcher
import com.woolab.lumella.agents.TutorBrainPedagogyClient
import com.woolab.lumella.audio.AudioCapture
import com.woolab.lumella.audio.AudioPlayback
import com.woolab.lumella.brain.BrainFactory
import com.woolab.lumella.camera.GlassesCamera
import com.woolab.lumella.config.AblationMode
import com.woolab.lumella.contract.BrainConnectionState
import com.woolab.lumella.contract.BrainCredentials
import com.woolab.lumella.contract.BrainCredentialsProvider
import com.woolab.lumella.contract.SessionPolicy
import com.woolab.lumella.contract.TurnEvidence
import com.woolab.lumella.contract.TutorBrain
import com.woolab.lumella.orchestration.StalenessGuard
import com.woolab.lumella.orchestration.StateGraphOrchestrator
import com.woolab.lumella.slowpath.SlowPathQueue
import com.woolab.lumella.slowpath.SlowPathTask
import com.woolab.lumella.slowpath.TurnTracker
import com.woolab.lumella.state.LearnerStateStore
import com.woolab.lumella.voice.OkHttpRealtimeWebSocketFactory
import com.woolab.lumella.voice.OpenAiRealtimeTransport
import com.woolab.lumella.voice.RealtimeConnectionStatus
import com.woolab.lumella.voice.VoiceFastPath
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Device bootstrap (plan G006): wires `local.properties`/`BuildConfig` config through
 * [TokenServiceCredentialProvider] -> [OpenAiRealtimeTransport] -> [VoiceFastPath], DI-binds
 * the [TutorBrain] via [BrainFactory] (runtime reflective lookup — no compile dependency on
 * `:luma-adapter`, see `BrainFactory`'s kdoc), wires RayNeo mic/speaker/touchpad/camera per the
 * `TUTOR/LEGACY/ELLA` MainActivity recipe, and renders a status text view mirroring LEGACY's
 * status-text approach (CONNECTING/READY/DEGRADED/TOKEN-FAIL).
 *
 * Fail-closed: a missing/unreachable token-service or brain never crashes the activity — the
 * status view reports the degrade state and the touch/mic loop stays alive (voice-only when
 * the brain is unavailable; visibly TOKEN-FAIL when no realtime credential can be minted).
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "lumella"
        private const val RIGHT_TOUCHPAD_DEVICE = "cyttsp5_mt"
        private const val LEFT_TOUCHPAD_DEVICE = "cyttsp6_mt"
        private const val TAP_MAX_DURATION_MS = 500L
        private const val DOUBLE_TAP_INTERVAL_MS = 400L
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private lateinit var statusView: TextView
    private lateinit var config: AppConfig
    private lateinit var brain: TutorBrain
    private lateinit var socketFactory: OkHttpRealtimeWebSocketFactory
    private lateinit var transport: OpenAiRealtimeTransport
    private lateinit var voiceFastPath: VoiceFastPath
    private lateinit var slowPathQueue: SlowPathQueue
    private lateinit var slowPathDispatcher: SlowPathDispatcher
    private lateinit var audioCapture: AudioCapture
    private lateinit var audioPlayback: AudioPlayback
    private lateinit var camera: GlassesCamera

    private val turnTracker = TurnTracker()
    private val sessionIdRef = AtomicReference("")
    private val slowPathExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lumella-slowpath").apply { isDaemon = true }
    }

    @Volatile private var pendingImageId: String? = null
    @Volatile private var currentTurnUserTranscript: String = ""
    @Volatile private var voiceTransportUnavailable = false

    private var lastTouchDownTimeMs = 0L
    private var lastTouchDeviceName = ""
    private var lastRightTapTimeMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusView = TextView(this).apply {
            textSize = 20f
            setPadding(32, 96, 32, 32)
        }
        setContentView(statusView)
        updateStatus("CONNECTING")

        config = AppConfig.fromBuildConfig()
        camera = GlassesCamera(this)
        audioPlayback = AudioPlayback().apply { start() }

        brain = BrainFactory.create(config.brainClassName) { reason ->
            Log.w(TAG, "BrainFactory fallback to NoOpBrain: $reason")
        }

        val credentialsProvider = object : BrainCredentialsProvider {
            override fun credentials(): BrainCredentials =
                BrainCredentials(baseUrl = config.lumaBaseUrl, email = config.brainEmail, password = config.brainPassword)
        }

        val orchestrator = StateGraphOrchestrator(LearnerStateStore(), StalenessGuard(3, 20), AblationMode.FULL)
        val pedagogyClient = TutorBrainPedagogyClient(brain, sessionId = { sessionIdRef.get() })
        slowPathQueue = SlowPathQueue()
        slowPathDispatcher = SlowPathDispatcher(pedagogyClient, orchestrator)

        val tokenProvider = createTokenServiceCredentialProviderOrNull(
            transport = HttpUrlConnectionTokenHttpTransport(),
            baseUrl = config.tokenServiceBaseUrl,
            localToken = config.localToken,
        )
        if (tokenProvider == null) {
            // Fail-closed (plan G006 P3): a blank/misconfigured tokenServiceBaseUrl must never
            // crash activity creation. Degrade to TOKEN-FAIL and skip realtime transport wiring;
            // toggleSpeechTurn() checks voiceTransportUnavailable before touching audioCapture/
            // transport, which are left uninitialized in this branch.
            Log.w(TAG, "TokenServiceCredentialProvider construction failed (blank/invalid base URL); degrading to TOKEN-FAIL")
            voiceTransportUnavailable = true
            updateStatus("TOKEN-FAIL")
            ensurePermissions()
            return
        }

        socketFactory = OkHttpRealtimeWebSocketFactory()
        transport = OpenAiRealtimeTransport(
            credentialProvider = tokenProvider,
            socketFactory = socketFactory,
            listener = object : OpenAiRealtimeTransport.Listener {
                override fun onStatus(status: RealtimeConnectionStatus) {
                    val label = statusLabel(status)
                    Log.i(TAG, "status=$label")
                    runOnUiThread { updateStatus(label) }
                }

                override fun onAudioDelta(base64Pcm16: String) {
                    audioPlayback.playDelta(base64Pcm16)
                }

                override fun onInputTranscript(text: String) {
                    currentTurnUserTranscript = text
                    submitCurrentTurnEvidence()
                }

                override fun onError(message: String) {
                    Log.w(TAG, "Realtime transport error: $message")
                }
            },
        )

        voiceFastPath = VoiceFastPath(
            orchestrator = orchestrator,
            brain = brain,
            transport = transport,
            sessionId = { sessionIdRef.get() },
            personaSummary = OpenAiRealtimeTransport.DEFAULT_SESSION_INSTRUCTIONS,
        )

        audioCapture = AudioCapture(
            onChunk = { chunk -> transport.appendAudio(chunk) },
            onError = { message ->
                Log.w(TAG, "Audio capture error: $message")
                runOnUiThread { updateStatus("MIC-ERROR") }
            },
        )

        ensurePermissions()

        Thread({ bootstrapBrainAndTransport(credentialsProvider) }, "lumella-bootstrap").apply {
            isDaemon = true
            start()
        }
    }

    /** Runs off the UI thread: brain.connect/startSession (best-effort) then realtime transport connect. */
    private fun bootstrapBrainAndTransport(credentialsProvider: BrainCredentialsProvider) {
        val connection = try {
            brain.connect(credentialsProvider)
        } catch (e: Exception) {
            Log.w(TAG, "brain.connect failed: ${e.message}")
            null
        }

        if (connection != null && connection.state != BrainConnectionState.AUTH_REQUIRED) {
            val session = try {
                brain.startSession(SessionPolicy.RESUME_ACTIVE)
            } catch (e: Exception) {
                Log.w(TAG, "brain.startSession failed: ${e.message}")
                null
            }
            session?.let { sessionIdRef.set(it.sessionId) }
        } else {
            Log.w(TAG, "Brain unavailable/auth-required at bootstrap; continuing voice-only per W-1 posture")
        }

        // Realtime voice transport connects independently of brain readiness — D-4/W-1:
        // a luma-unreachable brain degrades to voice-only, it never blocks the fast path.
        transport.connect()
    }

    // --- Touch mapping (RayNeo touchpad, ported from LEGACY MainActivity.dispatchTouchEvent) ---

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let { event ->
            val deviceName = event.device?.name.orEmpty()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchDownTimeMs = System.currentTimeMillis()
                    lastTouchDeviceName = deviceName
                }
                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - lastTouchDownTimeMs
                    when {
                        lastTouchDeviceName == RIGHT_TOUCHPAD_DEVICE && duration < TAP_MAX_DURATION_MS -> {
                            val now = System.currentTimeMillis()
                            if (now - lastRightTapTimeMs < DOUBLE_TAP_INTERVAL_MS) {
                                endSessionAndExit()
                            } else {
                                toggleSpeechTurn()
                            }
                            lastRightTapTimeMs = now
                        }
                        lastTouchDeviceName == LEFT_TOUCHPAD_DEVICE && duration < TAP_MAX_DURATION_MS -> {
                            capturePhoto()
                        }
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Right tap: start/stop the current speech turn. Returns immediately — never blocks the touch/UI thread. */
    private fun toggleSpeechTurn() {
        if (voiceTransportUnavailable) {
            Log.w(TAG, "Ignoring right-tap: realtime transport unavailable (TOKEN-FAIL)")
            return
        }
        if (audioCapture.isRecording) {
            audioCapture.stop()
            transport.commitAudio()
            val turnId = turnTracker.next()
            // voiceFastPath.onTurnStart does model/network work; offload it to the background
            // executor (mirrors submitCurrentTurnEvidence()) so the touch handler itself returns
            // immediately, then post the status update back to the UI thread.
            slowPathExecutor.execute {
                voiceFastPath.onTurnStart(turnId)
                runOnUiThread { updateStatus("THINKING") }
            }
        } else {
            if (!transport.sessionReady) {
                Log.w(TAG, "Ignoring right-tap: realtime session not ready")
                return
            }
            audioCapture.start()
            updateStatus("LISTENING")
        }
    }

    /** Left tap: photo capture. Analysis is async (45s ceiling tolerated by TutorBrain callers); the fast path keeps talking. */
    private fun capturePhoto() {
        camera.captureImage(
            onCaptured = { bytes ->
                slowPathExecutor.execute {
                    try {
                        val imageContext = brain.analyzeImage(bytes, "image/jpeg")
                        pendingImageId = imageContext.imageId.takeIf { it.isNotBlank() }
                        runOnUiThread { updateStatus("PHOTO-READY") }
                    } catch (e: Exception) {
                        Log.w(TAG, "analyzeImage failed: ${e.message}")
                    }
                }
            },
            onError = { message -> Log.w(TAG, "Camera capture failed: $message") },
        )
    }

    /** On input-transcript completion: submits this turn's evidence (single submitter, fire-and-forget) and drains the slow path. */
    private fun submitCurrentTurnEvidence() {
        val turnId = turnTracker.current().takeIf { it > 0 } ?: return
        val imageId = pendingImageId
        pendingImageId = null
        val evidence = TurnEvidence(turnId = turnId, learnerTranscript = currentTurnUserTranscript, imageId = imageId)
        slowPathExecutor.execute {
            voiceFastPath.submitTurnEvidence(evidence)
            slowPathQueue.enqueue(SlowPathTask(turnId = turnId, userTranscript = evidence.learnerTranscript))
            slowPathDispatcher.drain(slowPathQueue)
        }
    }

    /** Right double-tap: end the session and exit, mirroring LEGACY's exitApp(). */
    private fun endSessionAndExit() {
        teardown()
        finishAffinity()
    }

    private fun statusLabel(status: RealtimeConnectionStatus): String = when (status) {
        RealtimeConnectionStatus.CONNECTING -> "CONNECTING"
        RealtimeConnectionStatus.READY -> "READY"
        RealtimeConnectionStatus.DEGRADED -> "DEGRADED (voice-only)"
        RealtimeConnectionStatus.TOKEN_FAIL -> "TOKEN-FAIL"
        RealtimeConnectionStatus.CLOSED -> "CLOSED"
    }

    private fun updateStatus(text: String) {
        if (::statusView.isInitialized) statusView.text = "lumella: $text"
    }

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed += android.Manifest.permission.RECORD_AUDIO
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed += android.Manifest.permission.CAMERA
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        teardown()
    }

    /** Graceful teardown: stop capture/playback/camera, close transport + WS client, end the brain session, stop heartbeat. */
    private fun teardown() {
        runCatching { audioCapture.stop() }
        runCatching { audioPlayback.stop() }
        runCatching { camera.shutdown() }
        runCatching { transport.close() }
        runCatching { socketFactory.shutdown() }
        runCatching { sessionIdRef.get().takeIf { it.isNotBlank() }?.let { brain.endSession(it) } }
        slowPathExecutor.shutdown()
    }
}
