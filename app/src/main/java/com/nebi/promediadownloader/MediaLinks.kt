package com.nebi.promediadownloader

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/** İndirilen dosyayı uygun oynatıcıda açmak için yardımcılar (ses → müzik, video → video oynatıcı). */
object MediaLinks {

    fun viewIntent(uri: String, mime: String): Intent {
        val parsed = Uri.parse(uri)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(parsed, mime)
            // ClipData, bildirimden açılınca okuma izninin gerçekten verilmesini sağlar
            clipData = ClipData.newRawUri("", parsed)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun canOpen(ctx: Context, intent: Intent): Boolean =
        intent.resolveActivity(ctx.packageManager) != null

    /** Dosya hâlâ erişilebilir mi? (taşınmış ya da silinmiş olabilir) */
    fun exists(ctx: Context, uri: String): Boolean = try {
        ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    fun open(ctx: Context, uri: String, mime: String): Boolean {
        val intent = viewIntent(uri, mime)
        if (!canOpen(ctx, intent)) return false
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
