package com.woolab.lumella

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
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
import com.woolab.lumella.contract.TutorBrain
import com.woolab.lumella.databinding.ActivityMainBinding
import com.woolab.lumella.orchestration.StalenessGuard
import com.woolab.lumella.orchestration.StateGraphOrchestrator
import com.woolab.lumella.slowpath.SlowPathQueue
import com.woolab.lumella.slowpath.SlowPathTask
import com.woolab.lumella.slowpath.TurnEvidenceAssembler
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
 * status-text approach (CONNECTING/READY/DEGRADED/TOKEN-FAIL/IDLE).
 *
 * Native RayNeo glasses app (not a "virtual machine"/touchpad-relay app): extends
 * [BaseMirrorActivity] (Mercury SDK; verified hierarchy via javap: BaseMirrorActivity ->
 * BaseEventActivity -> BaseTouchActivity -> BaseActivity -> AppCompatActivity, so this
 * activity IS a LifecycleOwner and CameraX's `bindToLifecycle` keeps working unchanged),
 * with dual-eye rendering via [mBindingPair] (`activity_main.xml` inflated per-eye).
 *
 * Fail-closed: a missing/unreachable token-service or brain never crashes the activity — the
 * status view reports the degrade state and the touch/mic loop stays alive (voice-only when
 * the brain is unavailable; visibly TOKEN-FAIL when no realtime credential can be minted).
 */
class MainActivity : BaseMirrorActivity<ActivityMainBinding>() {

    companion object {
        private const val TAG = "lumella"
        private const val RIGHT_TOUCHPAD_DEVICE = "cyttsp5_mt"
        private const val LEFT_TOUCHPAD_DEVICE = "cyttsp6_mt"
        private const val TAP_MAX_DURATION_MS = 500L
        private const val DOUBLE_TAP_INTERVAL_MS = 400L
        private const val PERMISSION_REQUEST_CODE = 1001
        /** Debug-only broadcast that triggers the photo path without a touchpad tap. */
        private const val DEBUG_CAPTURE_ACTION = "com.woolab.lumella.DEBUG_CAPTURE_PHOTO"
        /** Short timeout for the boot-time remote config fetch — must never stall app boot. */
        private const val REMOTE_CONFIG_TIMEOUT_MS = 3_000
    }

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

    private val turnEvidenceAssembler = TurnEvidenceAssembler()
    @Volatile private var currentTurnUserTranscript: String = ""
    @Volatile private var voiceTransportUnavailable = false

    /**
     * Set when a right-tap arrives while the transport is idle-closed (see
     * [OpenAiRealtimeTransport.isClosed]): the tap triggers [OpenAiRealtimeTransport.connect]
     * immediately and recording starts automatically once READY arrives, so a single tap both
     * wakes and starts listening.
     */
    @Volatile private var recordWhenReady = false

    private var lastTouchDownTimeMs = 0L
    private var lastTouchDeviceName = ""
    private var lastRightTapTimeMs = 0L

    @Volatile
    private var speaking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // BaseMirrorActivity inflates ActivityMainBinding per-eye into mBindingPair — no
        // setContentView() here (see LEGACY TUTOR/LEGACY/ELLA MainActivity, same base class).
        updateStatus("Connecting...", "#9C27B0")

        config = AppConfig.fromBuildConfig()
        camera = GlassesCamera(this, this)
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
            updateStatus("Token error", "#FF0000")
            ensurePermissions()
            return
        }

        socketFactory = OkHttpRealtimeWebSocketFactory()
        transport = OpenAiRealtimeTransport(
            credentialProvider = tokenProvider,
            socketFactory = socketFactory,
            listener = object : OpenAiRealtimeTransport.Listener {
                override fun onStatus(status: RealtimeConnectionStatus) {
                    Log.i(TAG, "status=${statusLabel(status)}")
                    runOnUiThread { applyStatus(status) }
                    if (status == RealtimeConnectionStatus.READY && recordWhenReady) {
                        recordWhenReady = false
                        audioCapture.start()
                        runOnUiThread {
                            updateStatus(if (turnEvidenceAssembler.peekPendingImageId() != null) "Listening... (+ Photo)" else "Listening...", "#FF5722")
                        }
                    }
                }

                override fun onAudioDelta(base64Pcm16: String) {
                    if (!speaking) {
                        speaking = true
                        runOnUiThread { updateStatus("Speaking...", "#4CAF50") }
                    }
                    audioPlayback.playDelta(base64Pcm16)
                }

                override fun onAudioDone() {
                    speaking = false
                    runOnUiThread { updateStatus("Ready") }
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
                runOnUiThread { updateStatus("Mic error", "#FF0000") }
            },
        )

        ensurePermissions()

        debugCaptureReceiver?.let {
            registerReceiver(it, IntentFilter(DEBUG_CAPTURE_ACTION), Context.RECEIVER_EXPORTED)
        }

        Thread({ bootstrapBrainAndTransport(credentialsProvider) }, "lumella-bootstrap").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Runs off the UI thread: resolves the remote luma config (short-timeout, silently
     * falls back to BuildConfig on any failure — see [RemoteConfigResolver]) so the tunnel
     * URL can change without an APK rebuild, then brain.connect/startSession (best-effort),
     * then realtime transport connect.
     */
    private fun bootstrapBrainAndTransport(credentialsProvider: BrainCredentialsProvider) {
        val buildConfigLumaBaseUrl = config.lumaBaseUrl
        config = AppConfig.withResolvedLumaBaseUrl(
            config,
            HttpUrlConnectionTokenHttpTransport(connectTimeoutMs = REMOTE_CONFIG_TIMEOUT_MS, readTimeoutMs = REMOTE_CONFIG_TIMEOUT_MS),
        )
        if (config.lumaBaseUrl != buildConfigLumaBaseUrl) {
            Log.i(TAG, "Resolved lumaBaseUrl from remote config (was BuildConfig fallback $buildConfigLumaBaseUrl)")
        } else {
            Log.i(TAG, "Using BuildConfig lumaBaseUrl (remote config unavailable or unchanged)")
        }
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
                            // Any tap counts as user activity for the idle-timeout window (Change
                            // B), independent of which branch below runs.
                            if (::transport.isInitialized) transport.noteActivity()
                            if (now - lastRightTapTimeMs < DOUBLE_TAP_INTERVAL_MS) {
                                endSessionAndExit()
                            } else {
                                toggleSpeechTurn()
                            }
                            lastRightTapTimeMs = now
                        }
                        lastTouchDeviceName == LEFT_TOUCHPAD_DEVICE && duration < TAP_MAX_DURATION_MS -> {
                            if (::transport.isInitialized) transport.noteActivity()
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
                runOnUiThread { updateStatus("Thinking...", "#2196F3") }
            }
        } else if (!transport.sessionReady) {
            if (transport.isClosed) {
                // Idle-timeout (or a prior client close) left the session closed: wake it with
                // this tap and start recording automatically once READY arrives (see the
                // Listener.onStatus READY branch above), so one tap wakes + starts listening.
                Log.i(TAG, "Right-tap on closed session: reconnecting and recording on READY")
                recordWhenReady = true
                updateStatus("Connecting...", "#9C27B0")
                transport.connect()
            } else {
                Log.w(TAG, "Ignoring right-tap: realtime session not ready")
            }
        } else {
            audioCapture.start()
            updateStatus(if (turnEvidenceAssembler.peekPendingImageId() != null) "Listening... (+ Photo)" else "Listening...", "#FF5722")
        }
    }

    /** Left tap: photo capture. Analysis is async (45s ceiling tolerated by TutorBrain callers); the fast path keeps talking. */
    private fun capturePhoto() {
        updateStatus("Capturing...", "#9C27B0")
        camera.captureImage(
            onCaptured = { bytes ->
                slowPathExecutor.execute {
                    try {
                        val imageContext = brain.analyzeImage(bytes, "image/jpeg")
                        turnEvidenceAssembler.setPendingImageId(imageContext.imageId)
                        runOnUiThread { updateStatus("Photo ready! Tap to speak", "#9C27B0") }
                    } catch (e: Exception) {
                        Log.w(TAG, "analyzeImage failed: ${e.message}")
                        runOnUiThread { updateStatus("Capture failed", "#FF0000") }
                    }
                }
            },
            onError = { message ->
                Log.w(TAG, "Camera capture failed: $message")
                runOnUiThread { updateStatus("Capture error", "#FF0000") }
            },
        )
    }

    /** On input-transcript completion: submits this turn's evidence (single submitter, fire-and-forget) and drains the slow path. */
    private fun submitCurrentTurnEvidence() {
        val turnId = turnTracker.current().takeIf { it > 0 } ?: return
        val evidence = turnEvidenceAssembler.assemble(turnId = turnId, transcript = currentTurnUserTranscript)
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
        RealtimeConnectionStatus.ACCOUNT_BLOCKED -> "ACCOUNT-BLOCKED"
        RealtimeConnectionStatus.IDLE -> "IDLE"
    }

    /** LEGACY-ELLA status/color mapping (user feedback 2026-07-23: minimal, no prefixes). */
    private fun applyStatus(status: RealtimeConnectionStatus) = when (status) {
        RealtimeConnectionStatus.CONNECTING -> updateStatus("Connecting...", "#9C27B0")
        RealtimeConnectionStatus.READY -> updateStatus("Ready")
        RealtimeConnectionStatus.DEGRADED -> updateStatus("Voice-only", "#FFC107")
        RealtimeConnectionStatus.TOKEN_FAIL -> updateStatus("Token error", "#FF0000")
        RealtimeConnectionStatus.CLOSED -> updateStatus("Reconnecting...", "#9C27B0")
        RealtimeConnectionStatus.ACCOUNT_BLOCKED -> updateStatus("No API credit", "#FF0000")
        // Cost-safety idle timeout (Change B): unattended session closed itself; a tap re-wakes it.
        RealtimeConnectionStatus.IDLE -> updateStatus("Idle - tap to wake", "#888888")
    }

    /** Dual-eye status update (Mercury SDK mirror rendering): both [mBindingPair] panes in lockstep. */
    private fun updateStatus(text: String, colorHex: String = "#FFFFFF") {
        val color = android.graphics.Color.parseColor(colorHex)
        mBindingPair.left.tvStatus.text = text
        mBindingPair.left.tvStatus.setTextColor(color)
        mBindingPair.right.tvStatus.text = text
        mBindingPair.right.tvStatus.setTextColor(color)
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

    /**
     * Debug-only test hook for the photo path.
     *
     * The left-touchpad tap cannot be simulated: taps are discriminated by input
     * device name (`cyttsp6_mt`) and SELinux denies `sendevent` on the /dev/input event nodes
     * even though the shell user is in the `input` group (verified 2026-07-28,
     * matching the LEGACY finding). Without this hook the capture -> analyzeImage
     * -> imageId path could only ever be exercised by a human wearing the glasses,
     * which is a poor thing to have on the critical path of a release check.
     *
     * Registered ONLY in debug builds:
     *   adb shell am broadcast -a com.woolab.lumella.DEBUG_CAPTURE_PHOTO
     */
    private val debugCaptureReceiver: BroadcastReceiver? =
        if (BuildConfig.DEBUG) {
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    Log.i(TAG, "debug: capturePhoto triggered via broadcast")
                    capturePhoto()
                }
            }
        } else {
            null
        }

    override fun onDestroy() {
        super.onDestroy()
        teardown()
    }

    /** Graceful teardown: stop capture/playback/camera, close transport + WS client, end the brain session, stop heartbeat. */
    private fun teardown() {
        debugCaptureReceiver?.let { r -> runCatching { unregisterReceiver(r) } }
        runCatching { audioCapture.stop() }
        runCatching { audioPlayback.stop() }
        runCatching { camera.shutdown() }
        runCatching { transport.close() }
        runCatching { socketFactory.shutdown() }
        runCatching { sessionIdRef.get().takeIf { it.isNotBlank() }?.let { brain.endSession(it) } }
        slowPathExecutor.shutdown()
    }
}
