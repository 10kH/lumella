package com.woolab.lumella.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Prepares a captured JPEG for the OpenAI Realtime conversation.
 *
 * The glasses capture at 4032x3024, which is far larger than the model needs and would make
 * the base64 payload enormous. LEGACY ELLA downscaled to 1024px before sending; this mirrors
 * that so the realtime item stays a reasonable size.
 */
object ImageEncoder {

    /** OpenAI's recommended max edge for vision input, matching LEGACY ELLA's IMAGE_MAX_SIZE. */
    const val MAX_EDGE_PX = 1024

    /** JPEG quality for the re-encode; 85 keeps scene detail at a fraction of the size. */
    const val JPEG_QUALITY = 85

    /**
     * Decodes [jpegBytes], downscales the long edge to [MAX_EDGE_PX], and returns base64 JPEG.
     * Returns null when the bytes cannot be decoded rather than throwing — a bad frame must
     * never take down the capture path.
     */
    fun toDownscaledBase64Jpeg(jpegBytes: ByteArray): String? {
        val decoded = runCatching { BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) }
            .getOrNull() ?: return null
        return try {
            val scaled = downscale(decoded, MAX_EDGE_PX)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (scaled !== decoded) scaled.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        } finally {
            decoded.recycle()
        }
    }

    /** Scales so the longer edge is at most [maxEdge], preserving aspect ratio. */
    internal fun downscale(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxEdge && h <= maxEdge) return bitmap
        val (newW, newH) = if (w >= h) {
            maxEdge to (h.toLong() * maxEdge / w).toInt().coerceAtLeast(1)
        } else {
            (w.toLong() * maxEdge / h).toInt().coerceAtLeast(1) to maxEdge
        }
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
