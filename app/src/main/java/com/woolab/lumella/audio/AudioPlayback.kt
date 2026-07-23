package com.woolab.lumella.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Base64

/**
 * RayNeo speaker playback of realtime `response.audio.delta` chunks: 24kHz PCM16 mono,
 * streamed via [AudioTrack.MODE_STREAM] (ported from `TUTOR/LEGACY/ELLA` MainActivity's
 * `initAudioTrack`).
 *
 * Not exercised by JVM unit tests (real `android.media.AudioTrack`); verified on-device via
 * the P5 smoke pass.
 */
class AudioPlayback(private val sampleRateHz: Int = 24_000) {
    private var audioTrack: AudioTrack? = null

    /** Allocates and starts the streaming AudioTrack. Safe to call repeatedly (no-op if already started). */
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
                    .setUsage(AudioAttributes.USAGE_MEDIA)
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
        track.play()
        audioTrack = track
    }

    /** Decodes and enqueues a base64 PCM16 delta chunk for streaming playback. Tolerant of malformed input. */
    fun playDelta(base64Pcm16: String) {
        val bytes = try {
            Base64.getDecoder().decode(base64Pcm16)
        } catch (_: IllegalArgumentException) {
            return
        }
        audioTrack?.write(bytes, 0, bytes.size)
    }

    fun stop() {
        try {
            audioTrack?.stop()
        } catch (_: Exception) {
            // best-effort
        }
        audioTrack?.release()
        audioTrack = null
    }
}
