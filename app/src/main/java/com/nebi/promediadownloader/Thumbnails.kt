package com.nebi.promediadownloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Geçmiş listesi için kapak görüntüsü üretir. Videoda dosyanın ortasından bir kare,
 * seste MP3'e gömülü kapak resmi alınır. Görüntüler filesDir/thumbs altında saklanır.
 */
object Thumbnails {

    private const val DIR = "thumbs"
    private const val MAX_SIDE = 480

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    /** Kapağı kaydeder ve dosya yolunu döndürür. Kapak alınamazsa boş döner. */
    fun create(ctx: Context, uri: String, mime: String, id: String): String {
        val out = File(dir(ctx), "$id.jpg")
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(ctx, Uri.parse(uri))
            val source: Bitmap? = if (mime.startsWith("audio/")) {
                retriever.embeddedPicture?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            } else {
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                // Videonun üçte biri civarındaki bir kare genelde anlamlı bir önizleme olur
                val atMicros = durationMs * 1000L / 3L
                retriever.getFrameAtTime(atMicros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            if (source == null) return ""

            val bitmap = scaleDown(source)
            FileOutputStream(out).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            }
            out.absolutePath
        } catch (e: Exception) {
            out.delete()
            ""
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= MAX_SIDE) return bitmap
        val factor = MAX_SIDE.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * factor).toInt().coerceAtLeast(1),
            (bitmap.height * factor).toInt().coerceAtLeast(1),
            true,
        )
    }
}
