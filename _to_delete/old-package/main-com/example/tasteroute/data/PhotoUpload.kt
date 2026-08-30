package com.example.tasteroute.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Prepares a picked image for upload.
 *
 * Phone cameras produce 4–12MB files; a restaurant photo in a feed needs a fraction of that.
 * Downscaling on the device saves the user's data, the server's disk, and — because the same
 * bytes come back down — every future viewer's data too. Decoding happens in two passes so a
 * 50-megapixel source is never fully allocated, which is what would OOM a low-end phone.
 */
object PhotoUpload {

    private const val MAX_EDGE = 1440
    private const val QUALITY = 82

    data class Prepared(val bytes: ByteArray, val mime: String) {
        // Kotlin's generated equals on a ByteArray compares references; nothing here compares
        // Prepared values, so the honest move is to say so rather than ship a wrong equals.
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    suspend fun prepare(context: Context, uri: Uri): Prepared = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw HttpException(0, "Couldn't read that image")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw HttpException(0, "That file isn't an image")

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw HttpException(0, "Couldn't decode that image")

        val scaled = scale(decoded)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()

        Prepared(out.toByteArray(), "image/jpeg")
    }

    /** Power-of-two prescale, so the full-size bitmap is never allocated. */
    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= MAX_EDGE && h / 2 >= MAX_EDGE) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private fun scale(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_EDGE) return source
        val ratio = MAX_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }
}
