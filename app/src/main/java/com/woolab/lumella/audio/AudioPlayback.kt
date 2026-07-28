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
