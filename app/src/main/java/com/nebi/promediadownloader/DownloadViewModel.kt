package com.nebi.promediadownloader

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.Locale
import org.json.JSONObject

/** Oynatma listesindeki bir öğe. selected: indirilecek mi. */
data class PlaylistEntry(
    val title: String,
    val url: String,
    val duration: String,
    val selected: Boolean = true,
)

/** Oynatma listesi seçici penceresinin durumu. */
data class PickerState(
    val loading: Boolean = false,
    val entries: List<PlaylistEntry> = emptyList(),
)

data class Preview(
    val title: String,
    val uploader: String,
    val duration: String,
    val source: String,
    val thumbnail: String,
)

/** Arayüz formu (sadece ekrana ait değerler). */
private data class FormState(
    val url: String = "",
    val audioOnly: Boolean = false,
    val quality: String = DEFAULT_VIDEO_QUALITY,
    val playlist: Boolean = false,
    val subtitles: Boolean = false,
    val smartFolder: Boolean = true,
    val start: String = "",
    val end: String = "",
    val preview: Preview? = null,
    val fetching: Boolean = false,
    val destinationUri: String? = null,
    val destinationName: String? = null,
    val codec: String = DEFAULT_CODEC,
    val tab: Int = 0,
    val clipOffer: String? = null,
    val concurrency: Int = 10,
    val picker: PickerState? = null,
)

/** Ekranın okuduğu birleşik durum: form + indirme motoru + geçmiş. */
data class UiState(
    val url: String = "",
    val audioOnly: Boolean = false,
    val quality: String = DEFAULT_VIDEO_QUALITY,
    val playlist: Boolean = false,
    val subtitles: Boolean = false,
    val smartFolder: Boolean = true,
    val start: String = "",
    val end: String = "",
    val preview: Preview? = null,
    val fetching: Boolean = false,
    val destinationName: String? = null,
    val codec: String = DEFAULT_CODEC,
    val tab: Int = 0,
    val clipOffer: String? = null,
    val history: List<HistoryItem> = emptyList(),
    val queue: List<DownloadQueue.Entry> = emptyList(),
    val concurrency: Int = 10,
    val picker: PickerState? = null,
    val engineReady: Boolean = false,
    val busy: Boolean = false,
    val progress: Float = 0f,
    val eta: String = "--:--",
    val status: String = "",
    val tone: Tone = Tone.WORKING,
    val message: String? = null,
    val logs: List<String> = emptyList(),
)

class DownloadViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx: Application = app
    private val prefs = app.getSharedPreferences(LocaleHelper.PREFS, 0)
    private val form = MutableStateFlow(FormState())
    private var fetchJob: Job? = null

    /** Kullanıcının kapattığı kopyalanmış bağlantı; aynısı bir daha önerilmez. */
    private var dismissedClip: String? = null

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts = _toasts.asSharedFlow()

    val state: StateFlow<UiState> = combine(form, DownloadEngine.state, HistoryStore.items, DownloadQueue.items) { f, e, h, q ->
        UiState(
            url = f.url,
            audioOnly = f.audioOnly,
            quality = f.quality,
            playlist = f.playlist,
            subtitles = f.subtitles,
            smartFolder = f.smartFolder,
            start = f.start,
            end = f.end,
            preview = f.preview,
            fetching = f.fetching,
            destinationName = f.destinationName,
            codec = f.codec,
            tab = f.tab,
            clipOffer = f.clipOffer,
            history = h,
            queue = q,
            concurrency = f.concurrency,
            picker = f.picker,
            engineReady = e.ready,
            busy = e.busy,
            progress = e.progress,
            eta = e.eta,
            status = e.status,
            tone = e.tone,
            message = e.message,
            logs = e.logs,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        restorePrefs()
        restoreDestination()
        viewModelScope.launch(Dispatchers.IO) {
            HistoryStore.load(appCtx)
            DownloadEngine.prepare(appCtx)
        }
    }

    /** Seçili dile göre metin. */
    private fun str(id: Int, vararg args: Any): String =
        LocaleHelper.wrap(appCtx).getString(id, *args)

    private fun log(message: String) = DownloadEngine.log(message)

    private fun isUrl(text: String) = Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(text)

    private fun sourceName(url: String): String {
        val host = try {
            URI(url).host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: ""
        } catch (e: Exception) {
            ""
        }
        val map = mapOf(
            "youtube.com" to "YouTube", "youtu.be" to "YouTube", "tiktok.com" to "TikTok",
            "instagram.com" to "Instagram", "x.com" to "X", "twitter.com" to "X",
            "vimeo.com" to "Vimeo", "twitch.tv" to "Twitch", "facebook.com" to "Facebook",
            "reddit.com" to "Reddit", "dailymotion.com" to "Dailymotion",
            "soundcloud.com" to "SoundCloud",
        )
        for ((suffix, label) in map) {
            if (host.endsWith(suffix)) return label
        }
        return host.ifEmpty { "-" }
    }

    /** "90", "01:30", "01:02:03" → saniye. Boşsa null, hatalıysa istisna. */
    private fun parseTime(text: String, labelRes: Int): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val invalid = { IllegalArgumentException(str(R.string.err_time_invalid, str(labelRes))) }
        val parts = t.split(":")
        if (parts.size > 3) throw invalid()
        var total = 0L
        for (p in parts) {
            val n = p.trim().toDoubleOrNull()?.toLong()
            if (n == null || n < 0) throw invalid()
            total = total * 60 + n
        }
        return total
    }

    // ------------------------------------------------------------------
    // Form değişiklikleri
    // ------------------------------------------------------------------
    fun setUrl(value: String) = form.update { it.copy(url = value, preview = if (value == it.url) it.preview else null) }

    fun setUrlFromShare(text: String) {
        val found = Regex("https?://\\S+").find(text)?.value ?: return
        form.update { it.copy(url = found, preview = null) }
        fetchPreview()
    }

    fun pasteUrl(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        form.update { it.copy(url = clean, preview = null) }
        fetchPreview()
    }

    fun clearUrl() = form.update { it.copy(url = "", preview = null) }

    fun setAudioOnly(audio: Boolean) = form.update {
        val list = if (audio) AUDIO_QUALITIES else VIDEO_QUALITIES
        val saved = prefs.getString(if (audio) "audio_quality" else "video_quality", null)
        val keep = when {
            it.quality in list -> it.quality
            saved != null && saved in list -> saved
            audio -> DEFAULT_AUDIO_QUALITY
            else -> DEFAULT_VIDEO_QUALITY
        }
        it.copy(audioOnly = audio, quality = keep)
    }

    // Kalite, kodek ve klasörleme seçimleri hatırlanır; "Paylaş" penceresi de bunları kullanır.
    fun setQuality(value: String) {
        val key = if (form.value.audioOnly) "audio_quality" else "video_quality"
        prefs.edit().putString(key, value).apply()
        form.update { it.copy(quality = value) }
    }

    fun setCodec(id: String) {
        prefs.edit().putString("video_codec", id).apply()
        form.update { it.copy(codec = id) }
    }

    /** Aynı anda kaç indirme yapılacağı (1–10). */
    fun setConcurrency(n: Int) {
        val value = n.coerceAtLeast(1)
        prefs.edit().putInt("concurrency", value).apply()
        form.update { it.copy(concurrency = value) }
    }

    fun setPlaylist(value: Boolean) = form.update { it.copy(playlist = value) }
    fun setSubtitles(value: Boolean) = form.update { it.copy(subtitles = value) }

    fun setSmartFolder(value: Boolean) {
        prefs.edit().putBoolean("smart_folder", value).apply()
        form.update { it.copy(smartFolder = value) }
    }

    fun setStart(value: String) = form.update { it.copy(start = value) }
    fun setEnd(value: String) = form.update { it.copy(end = value) }

    /** 0 = İndir, 1 = Geçmiş. */
    fun setTab(index: Int) = form.update { it.copy(tab = index) }

    fun clearLog() = DownloadEngine.clearLog()

    fun resetUi() {
        form.update {
            it.copy(url = "", start = "", end = "", preview = null)
        }
        DownloadEngine.resetIdle()
    }

    // ------------------------------------------------------------------
    // Pano: kopyalanan bağlantıyı önerme
    // ------------------------------------------------------------------
    fun onClipboard(text: String?) {
        val clean = text?.trim().orEmpty()
        if (!isUrl(clean)) return
        val f = form.value
        if (clean == f.url.trim() || clean == f.clipOffer || clean == dismissedClip) return
        form.update { it.copy(clipOffer = clean) }
    }

    fun acceptClipboard() {
        val offer = form.value.clipOffer ?: return
        form.update { it.copy(url = offer, preview = null, clipOffer = null) }
    }

    fun dismissClipboard() {
        dismissedClip = form.value.clipOffer
        form.update { it.copy(clipOffer = null) }
    }

    // ------------------------------------------------------------------
    // Geçmiş
    // ------------------------------------------------------------------
    fun playItem(item: HistoryItem) {
        viewModelScope.launch {
            val exists = withContext(Dispatchers.IO) { MediaLinks.exists(appCtx, item.uri) }
            if (!exists) {
                _toasts.tryEmit(str(R.string.history_missing))
                return@launch
            }
            val opened = MediaLinks.open(appCtx, item.uri, item.mime)
            if (!opened) _toasts.tryEmit(str(R.string.history_no_player))
        }
    }

    fun clearHistory() = HistoryStore.clear(appCtx)

    // ------------------------------------------------------------------
    // Kuyruk: bekleyen indirmeleri yönetme (çalışan indirme etkilenmez)
    // ------------------------------------------------------------------
    fun removeQueued(id: String) = DownloadQueue.remove(id)

    fun clearQueue() = DownloadQueue.clear()

    // ------------------------------------------------------------------
    // Oynatma listesi seçici: öğeleri seç, sonra sırayla indirilmek üzere kuyruğa ekle
    // ------------------------------------------------------------------
    private fun updatePicker(transform: (PickerState) -> PickerState) {
        form.update { f -> f.picker?.let { f.copy(picker = transform(it)) } ?: f }
    }

    private fun openPlaylistPicker(url: String) {
        form.update { it.copy(picker = PickerState(loading = true)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entries = fetchPlaylistEntries(url)
                if (entries.isEmpty()) throw IllegalStateException(str(R.string.picker_empty))
                form.update { it.copy(picker = PickerState(loading = false, entries = entries)) }
            } catch (e: Throwable) {
                form.update { it.copy(picker = null) }
                val reason = e.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: e.javaClass.simpleName
                _toasts.tryEmit(str(R.string.picker_fetch_failed, reason))
                log(str(R.string.vm_info_failed, reason))
            }
        }
    }

    /** Listeyi yt-dlp ile okur; her öğe için JSON satırı döner. Öğe sayısında sınır yoktur. */
    private fun fetchPlaylistEntries(url: String): List<PlaylistEntry> {
        val request = YoutubeDLRequest(url)
        request.addOption("--flat-playlist")
        request.addOption("--dump-json")
        request.addOption("--yes-playlist")
        request.addOption("--ignore-errors")
        val response = YoutubeDL.getInstance().execute(request)

        val result = mutableListOf<PlaylistEntry>()
        response.out.lineSequence().forEach { line ->
            val text = line.trim()
            if (!text.startsWith("{")) return@forEach
            try {
                val o = JSONObject(text)
                val rawUrl = o.optString("url")
                val webUrl = o.optString("webpage_url")
                val link = when {
                    rawUrl.startsWith("http") -> rawUrl
                    webUrl.startsWith("http") -> webUrl
                    (url.contains("youtube") || url.contains("youtu.be")) && o.optString("id").isNotEmpty() ->
                        "https://www.youtube.com/watch?v=" + o.optString("id")
                    else -> return@forEach
                }
                val seconds = o.optDouble("duration", 0.0).toLong()
                result.add(
                    PlaylistEntry(
                        title = o.optString("title").ifBlank { link },
                        url = link,
                        duration = if (seconds > 0) formatSeconds(seconds) else "--:--",
                    ),
                )
            } catch (_: Exception) {
                // Bozuk satırı atla
            }
        }
        return result
    }

    fun togglePlaylistEntry(index: Int) = updatePicker { p ->
        p.copy(entries = p.entries.mapIndexed { i, e -> if (i == index) e.copy(selected = !e.selected) else e })
    }

    fun setAllPlaylistEntries(value: Boolean) = updatePicker { p ->
        p.copy(entries = p.entries.map { it.copy(selected = value) })
    }

    fun closePlaylistPicker() = form.update { it.copy(picker = null) }

    /** Seçili öğeleri ayrı ayrı indirme olarak kuyruğa ekler ve servisi başlatır. */
    fun confirmPlaylist() {
        val f = form.value
        val picker = f.picker ?: return
        val chosen = picker.entries.filter { it.selected }
        if (chosen.isEmpty()) {
            _toasts.tryEmit(str(R.string.picker_none))
            return
        }
        val jobs = chosen.map { e ->
            DownloadJob(
                url = e.url,
                title = e.title,
                audioOnly = f.audioOnly,
                quality = f.quality,
                playlist = false,
                subtitles = f.subtitles,
                smartFolder = f.smartFolder,
                startSec = null,
                endSec = null,
                destinationUri = f.destinationUri,
                codec = f.codec,
            )
        }
        form.update { it.copy(picker = null) }
        DownloadQueue.addAll(jobs)
        try {
            ContextCompat.startForegroundService(
                appCtx,
                Intent(appCtx, DownloadService::class.java).apply { action = DownloadService.ACTION_KICK },
            )
            _toasts.tryEmit(str(R.string.picker_started, jobs.size))
        } catch (e: Exception) {
            log(str(R.string.err_service_start, e.message ?: ""))
        }
    }

    // ------------------------------------------------------------------
    // Hedef klasör (kullanıcı seçer, kalıcı olarak hatırlanır)
    // ------------------------------------------------------------------
    private fun restorePrefs() {
        val quality = prefs.getString("video_quality", null)?.takeIf { it in VIDEO_QUALITIES }
        val codec = prefs.getString("video_codec", null)?.takeIf { id -> VIDEO_CODECS.any { it.id == id } }
        form.update {
            it.copy(
                quality = quality ?: it.quality,
                codec = codec ?: it.codec,
                smartFolder = prefs.getBoolean("smart_folder", true),
                concurrency = prefs.getInt("concurrency", 10).coerceAtLeast(1),
            )
        }
    }

    private fun restoreDestination() {
        val saved = prefs.getString("dest_uri", null) ?: return
        val name = resolveName(saved)
        if (name == null) {
            prefs.edit().remove("dest_uri").apply()
            return
        }
        form.update { it.copy(destinationUri = saved, destinationName = name) }
    }

    private fun resolveName(uriString: String): String? = try {
        val doc = DocumentFile.fromTreeUri(appCtx, Uri.parse(uriString))
        if (doc != null && doc.exists() && doc.canWrite()) doc.name ?: "" else null
    } catch (e: Exception) {
        null
    }

    fun onDestinationPicked(uri: Uri) {
        try {
            appCtx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: Exception) {
            log(str(R.string.vm_perm_persist, e.message ?: ""))
        }
        val name = resolveName(uri.toString())
        if (name == null) {
            log(str(R.string.vm_folder_unwritable))
            return
        }
        prefs.edit().putString("dest_uri", uri.toString()).apply()
        form.update { it.copy(destinationUri = uri.toString(), destinationName = name) }
        log(str(R.string.vm_folder_set, name))
    }

    fun useDefaultDestination() {
        prefs.edit().remove("dest_uri").apply()
        form.update { it.copy(destinationUri = null, destinationName = null) }
        log(str(R.string.vm_folder_default))
    }

    /** Eski telefonlarda (Android 9-) klasör seçilmediyse depolama izni gerekir. */
    fun needsLegacyStoragePermission(): Boolean = form.value.destinationUri == null

    // ------------------------------------------------------------------
    // Bağlantı önizlemesi
    // ------------------------------------------------------------------
    fun fetchPreview() {
        val s = state.value
        val url = s.url.trim()
        if (!isUrl(url)) {
            log(str(R.string.vm_need_link))
            DownloadEngine.setStatus(str(R.string.status_invalid_link), Tone.ERROR)
            return
        }
        if (!s.engineReady) {
            log(str(R.string.vm_engine_wait))
            return
        }
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch(Dispatchers.IO) {
            form.update { it.copy(fetching = true) }
            DownloadEngine.setStatus(str(R.string.status_fetching), Tone.WORKING)
            log(str(R.string.vm_inspecting))
            try {
                val request = YoutubeDLRequest(url)
                request.addOption("--no-playlist")
                val info = YoutubeDL.getInstance().getInfo(request)

                val seconds = info.duration?.toLong() ?: 0L
                val preview = Preview(
                    title = info.title ?: str(R.string.preview_unknown_title),
                    uploader = info.uploader ?: "--",
                    duration = if (seconds > 0) formatSeconds(seconds) else "--:--",
                    source = sourceName(url),
                    thumbnail = info.thumbnail ?: "",
                )
                form.update { it.copy(preview = preview, fetching = false) }
                DownloadEngine.setStatus(str(R.string.status_ready), Tone.IDLE)
                log(str(R.string.vm_info_ready, preview.title.take(70)))
            } catch (e: Throwable) {
                form.update { it.copy(fetching = false) }
                val reason = e.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: e.javaClass.simpleName
                DownloadEngine.setStatus(str(R.string.status_fetch_failed), Tone.ERROR)
                log(str(R.string.vm_info_failed, reason))
            }
        }
    }

    // ------------------------------------------------------------------
    // İndirme: iş ön plan servisine devredilir
    // ------------------------------------------------------------------
    fun startDownload() {
        val s = state.value
        val f = form.value
        val url = f.url.trim()
        if (!isUrl(url)) {
            DownloadEngine.setStatus(str(R.string.status_invalid_link), Tone.ERROR)
            log(str(R.string.vm_need_link))
            return
        }
        if (!s.engineReady) {
            log(str(R.string.vm_engine_wait))
            return
        }

        // Tüm liste seçiliyse önce hangi öğelerin indirileceğini kullanıcıya sor
        if (f.playlist) {
            openPlaylistPicker(url)
            return
        }

        val start: Long?
        val end: Long?
        try {
            start = parseTime(f.start, R.string.time_start)
            end = parseTime(f.end, R.string.time_end)
            if (start != null && end != null && end <= start) {
                throw IllegalArgumentException(str(R.string.err_time_order))
            }
        } catch (e: IllegalArgumentException) {
            DownloadEngine.setStatus(str(R.string.status_bad_time), Tone.ERROR)
            log(e.message.orEmpty())
            return
        }

        val job = DownloadJob(
            url = url,
            title = f.preview?.title ?: url,
            audioOnly = f.audioOnly,
            quality = f.quality,
            playlist = f.playlist,
            subtitles = f.subtitles,
            smartFolder = f.smartFolder,
            startSec = start,
            endSec = end,
            destinationUri = f.destinationUri,
            codec = f.codec,
        )

        if (s.busy) {
            _toasts.tryEmit(str(R.string.queue_added, s.queue.size + 1))
        } else {
            DownloadEngine.markQueued()
        }
        try {
            ContextCompat.startForegroundService(appCtx, job.toIntent(appCtx))
        } catch (e: Exception) {
            val reason = str(R.string.err_service_start, e.message ?: "")
            if (s.busy) log(reason) else DownloadEngine.abortQueued(reason)
        }
    }

    fun cancelDownload() = DownloadEngine.cancel()

    /** Eski telefonlarda depolama izni reddedilirse MainActivity çağırır. */
    fun cancelDownloadPermission() {
        log(str(R.string.vm_permission))
        DownloadEngine.setStatus(str(R.string.status_permission), Tone.ERROR)
    }
}
