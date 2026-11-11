package com.nebi.promediadownloader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Bekleyen ve çalışan indirmelerin takibi. Servis, aynı anda en fazla
 * kullanıcının seçtiği sayıda işi buradan alır.
 */
object DownloadQueue {

    data class Entry(val id: String, val job: DownloadJob)

    private val pending = ArrayList<Entry>()
    private val _items = MutableStateFlow<List<Entry>>(emptyList())
    val items: StateFlow<List<Entry>> = _items.asStateFlow()

    /** Şu an çalışan iş sayısı. */
    private var running = 0

    /** Birden fazla işi tek seferde ekler (oynatma listesi seçimi için). */
    @Synchronized
    fun addAll(jobs: List<DownloadJob>) {
        jobs.forEach { pending.add(Entry(UUID.randomUUID().toString(), it)) }
        publish()
    }

    @Synchronized
    fun add(job: DownloadJob) {
        pending.add(Entry(UUID.randomUUID().toString(), job))
        publish()
    }

    /** Boş bir yuva ve bekleyen iş varsa işi alır; yoksa null döner. */
    @Synchronized
    fun claim(maxParallel: Int): Entry? {
        if (running >= maxParallel || pending.isEmpty()) return null
        running++
        val entry = pending.removeAt(0)
        publish()
        return entry
    }

    /** Bir işin yuvasını bırakır. */
    @Synchronized
    fun release() {
        if (running > 0) running--
    }

    /** Hiç iş çalışmıyor ve bekleyen yok mu? */
    @Synchronized
    fun isIdle(): Boolean = running == 0 && pending.isEmpty()

    @Synchronized
    fun remove(id: String) {
        pending.removeAll { it.id == id }
        publish()
    }

    @Synchronized
    fun clear() {
        pending.clear()
        publish()
    }

    /** Bekleyen (henüz başlamamış) iş sayısı. */
    @Synchronized
    fun size(): Int = pending.size

    @Synchronized
    fun reset() {
        running = 0
    }

    private fun publish() {
        _items.value = pending.toList()
    }
}
