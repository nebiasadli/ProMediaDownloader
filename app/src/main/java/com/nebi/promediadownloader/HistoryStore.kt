package com.nebi.promediadownloader

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryItem(
    val id: String,
    val title: String,
    val uri: String,
    val mime: String,
    val audio: Boolean,
    val date: Long,
    /** Kapak görüntüsünün dosya yolu; yoksa boş. */
    val thumb: String = "",
)

/** İndirilen medya listesi. filesDir/history.json içinde saklanır, en yeni en üstte. */
object HistoryStore {
    private const val FILE = "history.json"
    private const val MAX_ITEMS = 300

    private val _items = MutableStateFlow<List<HistoryItem>>(emptyList())
    val items: StateFlow<List<HistoryItem>> = _items.asStateFlow()

    private var loaded = false

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        _items.value = readFile(ctx)
    }

    @Synchronized
    fun add(ctx: Context, newItems: List<HistoryItem>) {
        if (newItems.isEmpty()) return
        load(ctx)
        val merged = (newItems.reversed() + _items.value).distinctBy { it.uri }.take(MAX_ITEMS)
        _items.value = merged
        writeFile(ctx, merged)
    }

    @Synchronized
    fun clear(ctx: Context) {
        _items.value = emptyList()
        File(ctx.filesDir, FILE).delete()
        Thumbnails.dir(ctx).deleteRecursively()
    }

    private fun readFile(ctx: Context): List<HistoryItem> = try {
        val file = File(ctx.filesDir, FILE)
        if (!file.exists()) {
            emptyList()
        } else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryItem(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    uri = o.getString("uri"),
                    mime = o.getString("mime"),
                    audio = o.getBoolean("audio"),
                    date = o.getLong("date"),
                    thumb = o.optString("thumb", ""),
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun writeFile(ctx: Context, list: List<HistoryItem>) {
        try {
            val arr = JSONArray()
            list.forEach { item ->
                arr.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("title", item.title)
                        .put("uri", item.uri)
                        .put("mime", item.mime)
                        .put("audio", item.audio)
                        .put("date", item.date)
                        .put("thumb", item.thumb),
                )
            }
            File(ctx.filesDir, FILE).writeText(arr.toString())
        } catch (_: Exception) {
            // Geçmiş yazılamasa bile indirme başarılı sayılır
        }
    }
}
