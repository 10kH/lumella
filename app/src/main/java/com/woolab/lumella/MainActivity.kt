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
import com.woolab.lumella.camera.ImageEncoder
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
 * `TUTOR/ELLA` MainActivity recipe, and renders a status text view mirroring LEGACY's
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
        /** Contact shorter than this is capacitive noise, not a finger (observed bounce: 6ms). */
        private const val MIN_TAP_DURATION_MS = 40L
        private const val PERMISSION_REQUEST_CODE = 1001
        /** Debug-only broadcast that triggers the photo path without a touchpad tap. */
        private const val DEBUG_CAPTURE_ACTION = "com.woolab.lumella.DEBUG_CAPTURE_PHOTO"
        /** Debug-only broadcast that toggles a speech turn without a touchpad tap. */
        private const val DEBUG_SPEECH_ACTION = "com.woolab.lumella.DEBUG_TOGGLE_SPEECH"
        /** Debug-only broadcast that plays a 1s tone to exercise the speaker path. */
        private const val DEBUG_PLAYBACK_ACTION = "com.woolab.lumella.DEBUG_PLAYBACK"
        /** Fills the subtitles with sample text so the layout can be checked with no wearer. */
        private const val DEBUG_SUBTITLE_ACTION = "com.woolab.lumella.DEBUG_SUBTITLE"
        /** Drives one real turn with no wearer and no microphone. See ops/screen-dump.sh. */
        private const val DEBUG_SAY_ACTION = "com.woolab.lumella.DEBUG_SAY"
        /** Shows the model a picture from a file path, bypassing the camera. */
        private const val DEBUG_SEE_ACTION = "com.woolab.lumella.DEBUG_SEE"
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
    /** Accumulates AUDIO_TRANSCRIPT_DELTA chunks for the current tutor turn (UI-thread only). */
    private val subtitleAccumulator = StringBuilder()

    /**
     * Set when a right-tap arrives while the transport is idle-closed (see
     * [OpenAiRealtimeTransport.isClosed]): the tap triggers [OpenAiRealtimeTransport.connect]
     * immediately and recording starts automatically once READY arrives, so a single tap both
     * wakes and starts listening.
     */

    private var lastTouchDownTimeMs = 0L
    private var lastTouchDeviceName = ""
    private var lastRightTapTimeMs = 0L

    @Volatile
    private var speaking = false
    /** When the current turn was closed, so time-to-first-audio can be measured. */
    @Volatile private var turnEndedAtMs = 0L

    /**
     * Whether server VAD has reported speech since the last turn closed.
     *
     * The obvious test — did we upload any audio — is useless once the microphone never
     * stops: it accumulates chunks of silence too, so it is never zero and the guard it
     * backs never fires. Measured on-device: a tap during silence still committed 244
     * chunks, the server answered "no speech detected", and the tutor replied to nothing.
     * Only VAD can tell speech from an open mic in a quiet room.
     */
    private val heardSpeechThisTurn = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // BaseMirrorActivity inflates ActivityMainBinding per-eye into mBindingPair — no
        // setContentView() here (see LEGACY TUTOR/ELLA MainActivity, same base class).
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
                    if (status == RealtimeConnectionStatus.READY) {
                        // Hands-free: the microphone stays open for the whole session, so a
                        // wearer can simply talk. Recording is no longer something a tap
                        // turns on, which also means a reconnect resumes listening without
                        // the wearer having to notice it happened.
                        if (!audioCapture.isRecording) {
                            audioCapture.start()
                            Log.i(TAG, "연속 청취 시작 (핸즈프리): recording=${audioCapture.isRecording}")
                        }
                        runOnUiThread {
                            updateStatus(listeningLabel(), "#FF5722")
                            clearSubtitle()
                        }
                    }
                }

                override fun onAudioDelta(base64Pcm16: String) {
                    if (!speaking) {
                        speaking = true
                        // Time to first audio: what the wearer actually experiences as the
                        // pause after they stop talking. Also marks where the tutor's own
                        // voice starts, which is the window an echo loop would show up in.
                        val since = if (turnEndedAtMs > 0) System.currentTimeMillis() - turnEndedAtMs else -1
                        Log.i(TAG, "튜터 발화 시작 (TTFA ${since}ms)")
                        runOnUiThread { updateStatus("Speaking...", "#4CAF50") }
                    }
                    audioPlayback.playDelta(base64Pcm16)
                }

                override fun onAudioDone() {
                    // Marks the end of the tutor's own voice. Any VAD trigger between this
                    // and the start of speaking is the microphone hearing the tutor, which
                    // is the failure hands-free lives or dies on.
                    Log.i(TAG, "튜터 발화 끝")
                    speaking = false
                    runOnUiThread { updateStatus("Ready") }
                }

                override fun onToolCall(name: String, callId: String) {
                    if (name != "capture_photo") {
                        Log.w(TAG, "알 수 없는 도구 호출: $name")
                        return
                    }
                    Log.i(TAG, "음성 명령: 사진 촬영 (call_id=$callId)")
                    runOnUiThread { capturePhoto(toolCallId = callId) }
                }

                override fun onSpeechStarted() {
                    Log.i(TAG, "음성 감지됨 (VAD)")
                    heardSpeechThisTurn.set(true)
                    runOnUiThread {
                        updateStatus(listeningLabel(), "#FF5722")
                        clearSubtitle()
                    }
                }

                override fun onSpeechStopped() {
                    Log.i(TAG, "음성 종료됨 (VAD) - 턴 종료")
                    beginTurn(vadDriven = true)
                }

                override fun onInputTranscript(text: String) {
                    currentTurnUserTranscript = text
                    runOnUiThread { updateUserEcho(text) }
                    submitCurrentTurnEvidence()
                }

                override fun onTranscriptDelta(text: String) {
                    runOnUiThread {
                        subtitleAccumulator.append(text)
                        updateSubtitle(subtitleAccumulator.toString())
                    }
                }

                override fun onError(message: String) {
                    // Server VAD found no speech in the committed buffer. Not a fault: the
                    // wear-gated microphone returns near-silence when the glasses are off, and
                    // a raw transport error told the wearer nothing actionable.
                    if (message.contains("input_audio_buffer_commit_empty")) {
                        Log.i(TAG, "no speech detected in this turn")
                        runOnUiThread { updateStatus("Didn't catch that", "#FFC107") }
                        return
                    }
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
            registerReceiver(
                it,
                IntentFilter().apply {
                    addAction(DEBUG_CAPTURE_ACTION)
                    addAction(DEBUG_SPEECH_ACTION)
                    addAction(DEBUG_PLAYBACK_ACTION)
                    addAction(DEBUG_SUBTITLE_ACTION)
                    addAction(DEBUG_SAY_ACTION)
                    addAction(DEBUG_SEE_ACTION)
                },
                Context.RECEIVER_EXPORTED,
            )
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
                    Log.i(TAG, "touch UP device='${lastTouchDeviceName}' duration=${duration}ms")
                    when {
                        lastTouchDeviceName == RIGHT_TOUCHPAD_DEVICE && duration < TAP_MAX_DURATION_MS -> {
                            val now = System.currentTimeMillis()
                            val sinceLastTap = now - lastRightTapTimeMs
                            // Any tap counts as user activity for the idle-timeout window (Change
                            // B), independent of which branch below runs.
                            if (::transport.isInitialized) transport.noteActivity()
                            when {
                                // Contact bounce, not intent. The real discriminator is CONTACT
                                // DURATION, not the gap between taps: the bounce that killed the
                                // app mid-turn was 6ms of contact, while every deliberate tap in
                                // the same session measured 87-222ms. Gating on the gap instead
                                // made the deliberate double-tap unreachable, because the first
                                // tap always starts a turn.
                                duration < MIN_TAP_DURATION_MS -> {
                                    Log.d(TAG, "Ignoring contact bounce (${duration}ms contact)")
                                    return super.dispatchTouchEvent(ev)
                                }
                                sinceLastTap < DOUBLE_TAP_INTERVAL_MS -> {
                                    // Recorded with the measured gap: a wearer reporting the
                                    // app "just quit" is otherwise indistinguishable from a
                                    // fault, and this is the only path that closes it.
                                    Log.i(TAG, "우측 더블탭 (간격 ${sinceLastTap}ms) - 종료")
                                    endSessionAndExit()
                                }
                                else -> toggleSpeechTurn()
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

    /**
     * Right tap. Hands-free took turn-taking away from this gesture — VAD ends a turn now —
     * so the tap means "I have finished speaking", for the wearer who does not want to wait
     * out the silence window. It never means "say something": tapping without having spoken
     * used to make the tutor start talking to itself, so a tap with no captured audio does
     * nothing at all.
     *
     * Returns immediately — never blocks the touch/UI thread.
     */
    private fun toggleSpeechTurn() {
        if (voiceTransportUnavailable) {
            Log.w(TAG, "Ignoring right-tap: realtime transport unavailable (TOKEN-FAIL)")
            return
        }
        if (audioCapture.isRecording) {
            beginTurn(vadDriven = false)
        } else if (!transport.sessionReady) {
            if (transport.isClosed) {
                // Idle-timeout, a client close, or a fatal account error left the session
                // closed: wake it with this tap and start recording once READY arrives (see
                // the Listener.onStatus READY branch above), so one tap wakes + listens.
                //
                // MUST run off the UI thread: connect() -> fetchToken() -> HttpURLConnection
                // is SYNCHRONOUS despite its callback shape, so calling it here threw
                // NetworkOnMainThreadException and killed the app on every tap once the
                // session was closed (which ACCOUNT_BLOCKED makes the normal case).
                Log.i(TAG, "Right-tap on closed session: reconnecting; READY resumes listening")
                updateStatus("Connecting...", "#9C27B0")
                slowPathExecutor.execute { transport.connect() }
            } else {
                Log.w(TAG, "Ignoring right-tap: realtime session not ready")
            }
        } else {
            transport.resetAppendedChunkCounter()
            audioCapture.start()
            Log.i(TAG, "turn start: recording=${audioCapture.isRecording}")
            updateStatus(listeningLabel(), "#FF5722")
            clearSubtitle()
        }
    }

    /** "Listening...", plus a note when a photo is waiting to go out with this turn. */
    private fun listeningLabel(): String =
        if (turnEvidenceAssembler.peekPendingImageId() != null) "Listening... (+ Photo)" else "Listening..."

    /**
     * Ends the current turn and asks for a reply. Reached two ways: server VAD hearing the
     * learner stop ([vadDriven] = true), or a tap from someone who does not want to wait out
     * the silence window.
     *
     * The microphone is deliberately left running either way — hands-free means the next
     * utterance is caught without anyone doing anything.
     */
    private fun beginTurn(vadDriven: Boolean) {
        val chunks = transport.appendedChunksSinceCommit
        if (!vadDriven && !heardSpeechThisTurn.get()) {
            // A tap before saying anything used to ask for a response anyway, and the tutor
            // would start talking to itself. A tap means "I am done", not "your turn".
            //
            // Silence is its own trap though: a tap that does nothing visible reads as a tap
            // that did not register, so the wearer taps again — and a second right tap
            // inside DOUBLE_TAP_INTERVAL_MS quits the app. Say why nothing happened.
            Log.i(TAG, "tap without any detected speech; not asking for a response")
            runOnUiThread { updateStatus("아직 들은 말이 없어요", "#FFC107") }
            return
        }
        heardSpeechThisTurn.set(false)
        if (vadDriven) {
            // The server commits the buffer itself when VAD closes a turn. Committing again
            // asks it to commit an empty buffer, which it rejects — and the wearer sees an
            // error for a turn that was actually fine.
            transport.resetAppendedChunkCounter()
            turnEndedAtMs = System.currentTimeMillis()
            Log.i(TAG, "turn end (VAD): audioChunks=$chunks")
        } else {
            val committed = transport.commitAudio()
            turnEndedAtMs = System.currentTimeMillis()
            Log.i(TAG, "turn end (tap): audioChunks=$chunks committed=$committed")
        }
        val turnId = turnTracker.next()
        // voiceFastPath.onTurnStart does model/network work; keep it off the caller's thread
        // (a touch handler, or the websocket reader) and post the status back to the UI.
        slowPathExecutor.execute {
            voiceFastPath.onTurnStart(turnId)
            runOnUiThread { updateStatus("Thinking...", "#2196F3") }
        }
    }

    /**
     * Keeps the exact frame handed to the model, so a description can be checked against what
     * was really in front of the camera. Without it there is no way to separate an accurate
     * answer from a confident invention — the failure this path exists to prevent, and one
     * this app actually shipped (2026-08-05: a desk scene described from a pitch-dark room).
     * The downscaled frame is saved rather than the raw capture because that is what the
     * model is shown. Debug builds only.
     */
    private fun saveFrameTheModelSaw(base64Jpeg: String) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            java.io.File(getExternalFilesDir(null), "model-saw.jpg")
                .also { it.writeBytes(android.util.Base64.decode(base64Jpeg, android.util.Base64.NO_WRAP)) }
                .also { Log.i(TAG, "debug: 모델이 본 프레임 저장 ${it.absolutePath}") }
        }
    }

    /** Left tap: photo capture. Analysis is async (45s ceiling tolerated by TutorBrain callers); the fast path keeps talking. */
    private fun capturePhoto(toolCallId: String? = null) {
        updateStatus("Capturing...", "#9C27B0")
        // A tool call leaves the model waiting: its response already ended when it emitted
        // the call, so nothing resumes until the app answers and asks for a continuation.
        // Every exit from here has to do that, including the failures.
        fun answerToolCall(result: String) {
            val callId = toolCallId ?: return
            slowPathExecutor.execute {
                transport.sendFunctionCallOutput(callId, result)
                transport.requestResponseContinuation()
            }
        }
        camera.captureImage(
            onCaptured = { bytes ->
                // 1) Let the realtime model SEE it. Without this the tutor answers about
                //    something else entirely — the luma caption is only steering text on a
                //    later turn, which is not the same as showing the model the photo.
                slowPathExecutor.execute {
                    val base64 = ImageEncoder.toDownscaledBase64Jpeg(bytes)
                    if (base64 == null) {
                        Log.w(TAG, "image encode failed; realtime model will not see this photo")
                        answerToolCall("""{"status":"error","reason":"encode_failed"}""")
                    } else {
                        saveFrameTheModelSaw(base64)
                        val ok = transport.sendUserImage(base64)
                        Log.i(TAG, "photo -> realtime model: chars=${base64.length} sent=$ok")
                        answerToolCall("""{"status":"ok"}""")
                    }
                }
                // 2) And send it to luma for the coach's structured visual evidence.
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
                answerToolCall("""{"status":"error","reason":"camera_failed"}""")
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
        // The only way this app closes. Logged because a wearer reporting "it just quit"
        // otherwise leaves no way to tell a deliberate double-tap from a real fault.
        Log.i(TAG, "우측 더블탭으로 종료")
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

    /** Dual-eye tutor-subtitle update (tvSubtitle): live AUDIO_TRANSCRIPT_DELTA accumulation for the current turn. */
    private fun updateSubtitle(text: String) {
        val shown = tailForDisplay(text, SubtitleFit.SUBTITLE_MAX_LINES)
        mBindingPair.left.tvSubtitle.text = shown
        mBindingPair.right.tvSubtitle.text = shown
        warnIfClipped(mBindingPair.left.tvSubtitle, "tvSubtitle")
    }

    /**
     * The per-line character budget is an estimate, and Hangul is roughly twice as wide as
     * Latin at the same size, so a shaped string can overflow the view's maxLines and lose
     * its closing words with nothing to show for it. These glasses cannot be screencapped —
     * the AR overlay never reaches the framebuffer — so this makes the loss audible in the
     * log instead of invisible. Debug builds only, after layout, off the audio path.
     */
    private fun warnIfClipped(view: android.widget.TextView, name: String) {
        if (!BuildConfig.DEBUG) return
        view.post {
            val lines = view.layout?.lineCount ?: return@post
            if (lines > view.maxLines) {
                Log.w(TAG, "$name 잘림: ${lines}줄로 감겼으나 ${view.maxLines}줄만 보임 - 뒷부분 유실")
            }
        }
    }

    /** @see SubtitleFit — budgeted by display width so Hangul cannot overflow the view. */
    private fun tailForDisplay(text: String, maxLines: Int): String = SubtitleFit.tail(text, maxLines)

    /** Dual-eye learner-echo update (tvUserEcho): the learner's own completed transcript for the current turn. */
    private fun updateUserEcho(text: String) {
        val shown = tailForDisplay(text, SubtitleFit.USER_ECHO_MAX_LINES)
        mBindingPair.left.tvUserEcho.text = shown
        mBindingPair.right.tvUserEcho.text = shown
        warnIfClipped(mBindingPair.left.tvUserEcho, "tvUserEcho")
    }

    /**
     * Clears the tutor subtitle accumulator and view. Call when a new turn starts (right tap
     * begins listening) so the previous turn's subtitle doesn't linger and read as part of the
     * new one — must run on the UI thread.
     */
    private fun clearSubtitle() {
        subtitleAccumulator.setLength(0)
        updateSubtitle("")
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
                    when (intent?.action) {
                        DEBUG_SAY_ACTION -> {
                            val said = intent.getStringExtra("text") ?: "이 방에 대해 이야기해 줘."
                            Log.i(TAG, "debug: 학습자 발화 주입 = $said")
                            // Without this the TTFA log would measure from some unrelated
                            // earlier turn and print a number that means nothing.
                            turnEndedAtMs = System.currentTimeMillis()
                            slowPathExecutor.execute {
                                transport.sendUserText(said)
                                voiceFastPath.onTurnStart(turnTracker.next())
                            }
                            runOnUiThread { updateUserEcho(said) }
                        }
                        DEBUG_SEE_ACTION -> {
                            // Shows the model a known picture from a file, so "does it
                            // describe what it was actually shown" can be checked without
                            // depending on what happens to be in front of the glasses.
                            val path = intent.getStringExtra("path") ?: return
                            slowPathExecutor.execute {
                                val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull()
                                if (bytes == null) {
                                    Log.w(TAG, "debug: 이미지 파일 없음 $path")
                                    return@execute
                                }
                                val base64 = ImageEncoder.toDownscaledBase64Jpeg(bytes)
                                if (base64 == null) {
                                    Log.w(TAG, "debug: 이미지 인코딩 실패")
                                    return@execute
                                }
                                saveFrameTheModelSaw(base64)
                                val ok = transport.sendUserImage(base64)
                                Log.i(TAG, "debug: 이미지 주입 ${bytes.size}B -> ${base64.length} chars sent=$ok")
                                // Must forbid a fresh capture explicitly: asked to "look",
                                // the persona correctly calls capture_photo and answers about
                                // the live scene instead of the picture just handed over.
                                transport.sendUserText(
                                    intent.getStringExtra("ask")
                                        ?: "방금 보낸 이미지 안에 있는 도형과 색을 그대로 말해줘. 새로 사진 찍지 말고 그 이미지만 보고 답해.",
                                )
                                turnEndedAtMs = System.currentTimeMillis()
                                voiceFastPath.onTurnStart(turnTracker.next())
                            }
                        }
                        DEBUG_SUBTITLE_ACTION -> {
                            val tutor = intent.getStringExtra("tutor")
                                ?: "네, 벽에 붙어 있는 건 에어컨 실내기예요. 아래로 전선이 늘어져 있는 걸 보니 아직 연결이 덜 된 것 같네요. 설치하는 중이신가요?"
                            val user = intent.getStringExtra("user") ?: "저 벽에 있는 거 뭐야?"
                            runOnUiThread {
                                updateSubtitle(tutor)
                                updateUserEcho(user)
                            }
                        }
                        DEBUG_CAPTURE_ACTION -> {
                            Log.i(TAG, "debug: capturePhoto triggered via broadcast")
                            capturePhoto()
                        }
                        DEBUG_SPEECH_ACTION -> {
                            Log.i(TAG, "debug: toggleSpeechTurn triggered via broadcast")
                            toggleSpeechTurn()
                        }
                        DEBUG_PLAYBACK_ACTION -> {
                            // Exercises the speaker path without a live turn, so the media-stack
                            // side effects of playback can be observed on demand.
                            Log.i(TAG, "debug: playback tone triggered via broadcast")
                            val samples = ShortArray(24_000) { i ->
                                (Math.sin(2.0 * Math.PI * 440.0 * i / 24_000.0) * 6000).toInt().toShort()
                            }
                            val pcm = ByteArray(samples.size * 2)
                            for (i in samples.indices) {
                                pcm[i * 2] = (samples[i].toInt() and 0xFF).toByte()
                                pcm[i * 2 + 1] = ((samples[i].toInt() shr 8) and 0xFF).toByte()
                            }
                            audioPlayback.playDelta(android.util.Base64.encodeToString(pcm, android.util.Base64.NO_WRAP))
                        }
                    }
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
        // brain.endSession is a blocking HTTP call and teardown() runs on the UI thread
        // (onDestroy / right-double-tap exit). Calling it inline raised
        // NetworkOnMainThreadException, which runCatching silently swallowed — so the luma
        // session was NEVER actually closed and every exit leaked one. Submit it to the
        // executor first: shutdown() lets already-queued tasks finish.
        val endingSessionId = sessionIdRef.get().takeIf { it.isNotBlank() }
        if (endingSessionId != null) {
            runCatching { slowPathExecutor.execute { runCatching { brain.endSession(endingSessionId) } } }
        }
        slowPathExecutor.shutdown()
    }
}
