package com.nebi.promediadownloader

import android.content.Context

// Kalite ve kodek değerleri sabit kimliklerdir (dil değişse de kaydedilen ayarlar bozulmaz).
val VIDEO_QUALITIES = listOf("best", "2160", "1080", "720", "480")
val AUDIO_QUALITIES = listOf("320", "192", "128")
const val DEFAULT_VIDEO_QUALITY = "best"
const val DEFAULT_AUDIO_QUALITY = "320"

val VIDEO_HEIGHTS = mapOf("2160" to 2160, "1080" to 1080, "720" to 720, "480" to 480)

/** Video kodeği. sortKey, yt-dlp'nin -S sıralamasındaki kodek adıdır. */
data class CodecOption(val id: String, val labelRes: Int, val sortKey: String, val hintRes: Int)

const val DEFAULT_CODEC = "h264"

val VIDEO_CODECS = listOf(
    CodecOption("h264", R.string.codec_h264, "h264", R.string.hint_h264),
    CodecOption("hevc", R.string.codec_hevc, "h265", R.string.hint_hevc),
    CodecOption("vp9", R.string.codec_vp9, "vp9", R.string.hint_vp9),
    CodecOption("av1", R.string.codec_av1, "av01", R.string.hint_av1),
)

/** Kalite kimliğini ekranda gösterilecek metne çevirir. */
fun qualityLabel(ctx: Context, id: String, audio: Boolean): String = when {
    audio -> "$id kbps"
    id == "best" -> ctx.getString(R.string.quality_best)
    id == "2160" -> "4K (2160p)"
    else -> "${id}p"
}
