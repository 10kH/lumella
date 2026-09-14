package com.woolab.lumella.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Base64

/**
 * RayNeo speaker playback of realtime `response.audio.delta` chunks: 24kHz PCM16 mono,
 * streamed via [AudioTrack.MODE_STREAM] (ported from `TUTOR/ELLA` MainActivity's
 * `initAudioTrack`).
 *
 * Two deliberate choices keep the glasses' media stack out of this:
 *  - [AudioAttributes.USAGE_ASSISTANT], not `USAGE_MEDIA`. This is a tutor's voice, not
 *    music; declaring it as media made RayNeo's BLE music bridge treat the app as a player
 *    and pull up YouTube Music on launch (reported on-device 2026-07-28).
 *  - [AudioTrack.play] is deferred until the first audio chunk actually arrives. Entering
 *    PLAYING at startup looks exactly like "playback started" to that same bridge.
 *
 * Not exercised by JVM unit tests (real `android.media.AudioTrack`); verified on-device via
 * the P5 smoke pass.
 */
class AudioPlayback(private val sampleRateHz: Int = 24_000) {
    private var audioTrack: AudioTrack? = null

    /**
     * Optional WAV sink for the tutor's voice, used while filming.
     *
     * The POV recording captures the microphone, and the microphone is opened with
     * `VOICE_COMMUNICATION` so the platform echo canceller removes what the speaker is playing
     * — without it the tutor's own voice feeds back and server VAD reads it as the learner
     * talking (see `AudioCapture`). Correct for the product, but it means a take carries only
     * the learner's half: measured 2026-09-14 as -22 dB while the learner spoke against -39 dB
     * while the tutor did.
     *
     * The tutor's PCM is already in hand here, on its way to the track, so it is written out in
     * parallel rather than recovered acoustically. The edit gets two mono files — learner from
     * the video, tutor from this — and mixes them.
     */
    @Volatile private var tapStream: java.io.RandomAccessFile? = null
    @Volatile private var tapBytes: Int = 0

    /** Allocates the streaming AudioTrack WITHOUT starting playback. Safe to call repeatedly. */
    fun start() {
        if (audioTrack != null) return
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
    }

    /** Enters PLAYING lazily, on the first real chunk, so merely opening the app is silent. */
    private fun ensurePlaying(track: AudioTrack) {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            runCatching { track.play() }
        }
    }

    /** Decodes and enqueues a base64 PCM16 delta chunk for streaming playback. Tolerant of malformed input. */
    fun playDelta(base64Pcm16: String) {
        val bytes = try {
            Base64.getDecoder().decode(base64Pcm16)
        } catch (_: IllegalArgumentException) {
            return
        }
        val track = audioTrack ?: return
        ensurePlaying(track)
        track.write(bytes, 0, bytes.size)
        tapStream?.let { out ->
            // Best-effort: a failed tap must never interrupt playback the wearer is listening to.
            runCatching {
                out.write(bytes)
                tapBytes += bytes.size
            }
        }
    }

    /** Starts writing the tutor's voice to [file] as mono PCM16 WAV. Overwrites any existing file. */
    fun startVoiceTap(file: java.io.File) {
        stopVoiceTap()
        runCatching {
            val raf = java.io.RandomAccessFile(file, "rw")
            raf.setLength(0)
            raf.write(ByteArray(WAV_HEADER_BYTES)) // placeholder; sizes are patched on stop
            tapBytes = 0
            tapStream = raf
        }
    }

    /** Finalises the WAV header and closes the tap. Safe to call when not tapping. */
    fun stopVoiceTap() {
        val raf = tapStream ?: return
        tapStream = null
        runCatching {
            raf.seek(0)
            raf.write(wavHeader(tapBytes, sampleRateHz))
            raf.close()
        }
        tapBytes = 0
    }

    private fun wavHeader(dataBytes: Int, rate: Int): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(WAV_HEADER_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val byteRate = rate * 2 // mono, 16-bit
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + dataBytes)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)          // PCM chunk size
        bb.putShort(1)         // PCM
        bb.putShort(1)         // mono
        bb.putInt(rate)
        bb.putInt(byteRate)
        bb.putShort(2)         // block align
        bb.putShort(16)        // bits per sample
        bb.put("data".toByteArray())
        bb.putInt(dataBytes)
        return bb.array()
    }

    fun stop() {
        stopVoiceTap()
        try {
            audioTrack?.stop()
        } catch (_: Exception) {
            // best-effort
        }
        audioTrack?.release()
        audioTrack = null
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44
    }
}
