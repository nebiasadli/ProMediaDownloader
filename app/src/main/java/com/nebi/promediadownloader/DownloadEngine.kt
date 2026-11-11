package com.nebi.promediadownloader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class Tone { IDLE, WORKING, SUCCESS, ERROR }

fun formatSeconds(total: Long): String {
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** Servise gönderilen, indirmeyi tarif eden tüm bilgiler. */
data class DownloadJob(
    val url: String,
    val title: String,
    val audioOnly: Boolean,
    val quality: String,
    val playlist: Boolean,
    val subtitles: Boolean,
    val smartFolder: Boolean,
    val startSec: Long?,
    val endSec: Long?,
    val destinationUri: String?,
    val codec: String = DEFAULT_CODEC,
) {
    fun toIntent(context: Context): Intent =
        Intent(context, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra("url", url)
            putExtra("title", title)
            putExtra("audio", audioOnly)
            putExtra("quality", quality)
            putExtra("playlist", playlist)
            putExtra("subtitles", subtitles)
            putExtra("smart", smartFolder)
            putExtra("start", startSec ?: -1L)
            putExtra("end", endSec ?: -1L)
            putExtra("dest", destinationUri)
            putExtra("codec", codec)
        }

    companion object {
        fun from(intent: Intent): DownloadJob? {
            val url = intent.getStringExtra("url") ?: return null
            return DownloadJob(
                url = url,
                title = intent.getStringExtra("title") ?: url,
                audioOnly = intent.getBooleanExtra("audio", false),
                quality = intent.getStringExtra("quality") ?: DEFAULT_VIDEO_QUALITY,
                playlist = intent.getBooleanExtra("playlist", false),
                subtitles = intent.getBooleanExtra("subtitles", false),
                smartFolder = intent.getBooleanExtra("smart", true),
                startSec = intent.getLongExtra("start", -1L).takeIf { it >= 0 },
                endSec = intent.getLongExtra("end", -1L).takeIf { it >= 0 },
                destinationUri = intent.getStringExtra("dest"),
                codec = intent.getStringExtra("codec") ?: DEFAULT_CODEC,
            )
        }
    }
}

/** Ekrana giden toplu durum. Birden fazla iş sürerken ilerleme ortalamasıdır. */
data class EngineState(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val progress: Float = 0f,
    val eta: String = "--:--",
    val status: String = "",
    val tone: Tone = Tone.WORKING,
    val message: String? = null,
    val logs: List<String> = emptyList(),
    val activeTitles: List<String> = emptyList(),
)

/** Kaydedilmiş bir medya dosyası: geçmişe ve bildirim tıklamasına bağlanır. */
data class Output(
    val uri: String,
    val mime: String,
    val title: String,
    val audio: Boolean,
)

sealed class DownloadResult {
    data class Success(val count: Int, val where: String, val outputs: List<Output>) : DownloadResult()
    data class Failed(val reason: String) : DownloadResult()
    object Cancelled : DownloadResult()
}

/**
 * İndirmelerin yapıldığı yer. Birden fazla iş aynı anda çalışabilir; her işin
 * kendi ilerlemesi, iptal bayrağı ve geçici klasörü vardır.
 */
object DownloadEngine {

    private const val PROCESS_PREFIX = "pro_media_"
    private const val ROOT_FOLDER = "ProMediaDownloader"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Çalışan her iş için durum. Anahtar: kuyruk kimliği. */
    private val running = ConcurrentHashMap<String, Run>()
    private val counter = AtomicInteger(0)

    private class Run(val id: String, val no: Int, val title: String) {
        @Volatile var cancelled = false
        @Volatile var progress = 0f
        @Volatile var eta = "--:--"
        @Volatile var status = ""
    }

    private var initialized = false
    private var prepared = false

    /** Yerelleştirilmiş metinler için; her çağrıda seçili dil okunur. */
    @Volatile
    private var appCtx: Context? = null

    private fun str(id: Int, vararg args: Any): String {
        val c = appCtx ?: return ""
        return LocaleHelper.wrap(c).getString(id, *args)
    }

    // ------------------------------------------------------------------
    // Günlük / durum
    // ------------------------------------------------------------------
    fun log(message: String) {
        val stamp = synchronized(clock) { clock.format(Date()) }
        val line = "$stamp  $message"
        _state.update { it.copy(logs = (it.logs + line).takeLast(200)) }
    }

    fun clearLog() = _state.update { it.copy(logs = emptyList()) }

    fun setStatus(status: String, tone: Tone) {
        _state.update { if (it.busy) it else it.copy(status = status, tone = tone) }
    }

    fun resetIdle() {
        _state.update {
            if (it.busy) it else it.copy(progress = 0f, eta = "--:--", status = str(R.string.status_ready), tone = Tone.IDLE, message = null)
        }
    }

    /** Çalışan işlerden toplu durumu yeniden hesaplar. */
    private fun refresh(extra: (EngineState) -> EngineState = { it }) {
        val runs = running.values.toList()
        _state.update { prev ->
            val e = extra(prev)
            if (runs.isEmpty()) {
                e.copy(busy = false, eta = "--:--", activeTitles = emptyList())
            } else {
                e.copy(
                    busy = true,
                    tone = Tone.WORKING,
                    message = null,
                    progress = runs.map { it.progress }.average().toFloat(),
                    eta = if (runs.size == 1) runs[0].eta else "--:--",
                    status = if (runs.size == 1) runs[0].status else str(R.string.status_parallel, runs.size),
                    activeTitles = runs.map { it.title },
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Motor hazırlığı
    // ------------------------------------------------------------------
    @Synchronized
    private fun ensureInit(ctx: Context) {
        if (initialized) return
        YoutubeDL.getInstance().init(ctx)
        FFmpeg.getInstance().init(ctx)
        initialized = true
    }

    /** Arka planda çağrılmalı: yt-dlp'yi başlatır ve güncellemeyi dener. */
    @Synchronized
    fun prepare(ctx: Context) {
        if (prepared) return
        val app = ctx.applicationContext
        appCtx = app
        _state.update { it.copy(status = str(R.string.status_preparing)) }
        try {
            ensureInit(app)
        } catch (e: Throwable) {
            log(str(R.string.log_engine_failed, e.message ?: ""))
            _state.update { it.copy(status = str(R.string.status_engine_error), tone = Tone.ERROR) }
            return
        }
        try {
            log(str(R.string.log_ytdlp_check))
            val result = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
            log(str(R.string.log_ytdlp_result, result.toString()))
        } catch (e: Throwable) {
            log(str(R.string.log_ytdlp_failed, e.message ?: ""))
        }
        prepared = true
        _state.update { it.copy(ready = true, status = str(R.string.status_ready), tone = Tone.IDLE) }
        log(str(R.string.log_engine_ready))
    }

    // ------------------------------------------------------------------
    // İndirme yaşam döngüsü
    // ------------------------------------------------------------------
    /** Servis başlamadan önce arayüzün hemen tepki vermesi için. */
    fun markQueued() {
        _state.update {
            if (it.busy) it else it.copy(
                progress = 0f, eta = "--:--", status = str(R.string.status_starting),
                tone = Tone.WORKING, message = null,
            )
        }
    }

    fun abortQueued(reason: String) {
        log(str(R.string.log_error_line, reason))
        _state.update { it.copy(status = str(R.string.status_not_started), tone = Tone.ERROR, message = reason) }
    }

    /** Çalışan tüm indirmeleri iptal eder. Kuyruktaki bekleyen işler etkilenmez. */
    fun cancel() {
        if (running.isEmpty()) return
        log(str(R.string.log_cancel_sent))
        running.values.forEach { run ->
            run.cancelled = true
            scope.launch {
                try {
                    YoutubeDL.getInstance().destroyProcessById(PROCESS_PREFIX + run.id)
                } catch (e: Throwable) {
                    log(str(R.string.log_cancel_failed, e.message ?: ""))
                }
            }
        }
    }

    /**
     * Bloklayıcıdır; servisin arka plan iş parçacıklarından çağrılır. Aynı anda
     * birden fazla çağrı yapılabilir: her biri kendi kimliğiyle ilerler.
     */
    fun download(ctx: Context, job: DownloadJob, jobId: String): DownloadResult {
        val app = ctx.applicationContext
        appCtx = app

        val run = Run(jobId, counter.incrementAndGet(), job.title)
        run.status = str(R.string.status_starting)
        running[jobId] = run
        refresh()
        log(str(R.string.log_download_start, job.url))

        val base = app.getExternalFilesDir(null) ?: app.filesDir
        val workDir = File(base, "work/$jobId").apply { mkdirs() }

        var outcome: DownloadResult = DownloadResult.Cancelled
        try {
            ensureInit(app)
            if (run.cancelled) throw InterruptedException("cancelled")

            val request = buildRequest(job, workDir)
            YoutubeDL.getInstance().execute(request, PROCESS_PREFIX + jobId) { progress, eta, line ->
                onProgress(run, progress, eta, line)
            }
            if (run.cancelled) throw InterruptedException("cancelled")

            run.status = str(R.string.status_saving)
            refresh()
            val export = exportFiles(app, workDir, job)
            if (export.count == 0) throw IllegalStateException(str(R.string.err_no_output))

            run.progress = 1f
            run.eta = "00:00"
            log(str(R.string.log_saved, export.count, export.where))
            outcome = DownloadResult.Success(export.count, export.where, export.outputs)
        } catch (e: Throwable) {
            outcome = if (run.cancelled) {
                log(str(R.string.log_cancelled))
                DownloadResult.Cancelled
            } else {
                val reason = e.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: e.javaClass.simpleName
                log(str(R.string.log_failed, reason))
                DownloadResult.Failed(reason)
            }
        } finally {
            running.remove(jobId)
            workDir.deleteRecursively()
        }

        val result = outcome
        refresh { prev -> applyOutcome(prev, result) }
        return result
    }

    private fun applyOutcome(prev: EngineState, o: DownloadResult): EngineState = when (o) {
        is DownloadResult.Success -> prev.copy(
            status = str(R.string.status_done), tone = Tone.SUCCESS, progress = 1f,
            message = str(R.string.files_saved, o.count, o.where),
        )
        is DownloadResult.Failed -> prev.copy(
            status = str(R.string.status_error), tone = Tone.ERROR, progress = 0f, message = o.reason,
        )
        DownloadResult.Cancelled -> prev.copy(
            status = str(R.string.status_cancelled), tone = Tone.IDLE, progress = 0f, message = null,
        )
    }

    private fun onProgress(run: Run, progress: Float, etaSeconds: Long, line: String) {
        if (progress >= 0f) {
            run.progress = (progress / 100f).coerceIn(0f, 1f)
            run.eta = if (etaSeconds in 0..359999) formatSeconds(etaSeconds) else "--:--"
            run.status = str(R.string.status_downloading)
            refresh()
        }
        val text = line.trim()
        if (text.isEmpty()) return
        // Sürekli tekrarlanan yüzde satırlarını günlüğe yazma
        if (text.startsWith("[download]") && text.contains("%")) return
        if (text.startsWith("[Merger]") || text.startsWith("[ExtractAudio]") ||
            text.startsWith("[EmbedThumbnail]") || text.startsWith("[VideoConvertor]")
        ) {
            run.status = str(R.string.status_processing)
            refresh()
        }
        log("[#${run.no}] ${text.take(160)}")
    }

    // ------------------------------------------------------------------
    // yt-dlp isteği
    // ------------------------------------------------------------------
    private fun buildRequest(job: DownloadJob, workDir: File): YoutubeDLRequest {
        val request = YoutubeDLRequest(job.url)

        val template = if (job.playlist) {
            "%(playlist_title|Playlist)s/%(playlist_index&{} - |)s%(title)s.%(ext)s"
        } else {
            "%(title)s.%(ext)s"
        }
        request.addOption("-o", "${workDir.absolutePath}/$template")
        request.addOption(if (job.playlist) "--yes-playlist" else "--no-playlist")
        request.addOption("--trim-filenames", "120")
        request.addOption("--no-mtime")
        request.addOption("-N", "4")

        if (job.audioOnly) {
            val bitrate = when (job.quality) {
                "320" -> "320"
                "128" -> "128"
                else -> "192"
            }
            request.addOption("-f", "bestaudio/best")
            request.addOption("-x")
            request.addOption("--audio-format", "mp3")
            request.addOption("--audio-quality", "${bitrate}K")
            request.addOption("--add-metadata")
            request.addOption("--embed-thumbnail")
            request.addOption("--convert-thumbnails", "jpg")
        } else {
            val height = VIDEO_HEIGHTS[job.quality]
            // Çözünürlük sınırı -f ile, kodek tercihi -S (sıralama) ile verilir.
            // Seçilen kodek sitede yoksa indirme başarısız olmaz, en yakın biçim alınır.
            val format = if (height != null) {
                "bv*[height<=$height]+ba/b[height<=$height]/bv*+ba/b"
            } else {
                "bv*+ba/b"
            }
            val codec = VIDEO_CODECS.firstOrNull { it.id == job.codec } ?: VIDEO_CODECS.first()
            request.addOption("-f", format)
            request.addOption("-S", "vcodec:${codec.sortKey},res,fps,acodec:m4a")
            request.addOption("--merge-output-format", "mp4")
            log(str(R.string.log_codec, str(codec.labelRes)))
        }

        if (job.subtitles) {
            request.addOption("--write-subs")
            request.addOption("--write-auto-subs")
            request.addOption("--sub-langs", "tr,en")
            request.addOption("--convert-subs", "srt")
            if (!job.audioOnly) request.addOption("--embed-subs")
        }

        if (job.startSec != null || job.endSec != null) {
            val from = formatHms(job.startSec ?: 0L)
            val to = if (job.endSec != null) formatHms(job.endSec) else "inf"
            request.addOption("--download-sections", "*$from-$to")
            request.addOption("--force-keyframes-at-cuts")
            log(str(R.string.log_range, from, to))
        }
        return request
    }

    private fun formatHms(total: Long): String =
        "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)

    // ------------------------------------------------------------------
    // Kaydetme: seçilen klasör (SAF) veya varsayılan Downloads
    // ------------------------------------------------------------------
    private class Export(val count: Int, val where: String, val outputs: List<Output>)

    private fun exportFiles(ctx: Context, workDir: File, job: DownloadJob): Export {
        val skip = setOf("part", "ytdl", "temp", "tmp")
        val files = workDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) !in skip }
            .toList()
        val sub = if (job.smartFolder) (if (job.audioOnly) "Audio" else "Video") else ""
        val outputs = mutableListOf<Output>()

        if (job.destinationUri != null) {
            val root = DocumentFile.fromTreeUri(ctx, Uri.parse(job.destinationUri))
                ?: throw IllegalStateException(str(R.string.err_folder_open))
            if (!root.exists() || !root.canWrite()) {
                throw IllegalStateException(str(R.string.err_folder_write))
            }
            val base = if (sub.isNotEmpty()) childDir(root, sub) else root
            for (file in files) {
                var dir = base
                val rel = file.relativeTo(workDir).parent
                if (!rel.isNullOrEmpty()) {
                    for (part in rel.split(File.separatorChar)) {
                        if (part.isNotEmpty()) dir = childDir(dir, part)
                    }
                }
                val uri = copyToDocument(ctx, file, dir)
                outputs.addIfMedia(file, uri)
            }
            val rootName = root.name ?: str(R.string.folder_selected)
            val where = if (sub.isNotEmpty()) "$rootName/$sub" else rootName
            return Export(files.size, where, outputs)
        }

        val baseRel = listOf(ROOT_FOLDER, sub).filter { it.isNotEmpty() }.joinToString("/")
        for (file in files) {
            val relParent = file.relativeTo(workDir).parent
            val targetRel = if (relParent.isNullOrEmpty()) baseRel else "$baseRel/$relParent"
            val uri = saveToDownloads(ctx, file, targetRel)
            outputs.addIfMedia(file, uri)
        }
        val defaultName = str(R.string.default_folder_name)
        val where = if (sub.isNotEmpty()) "$defaultName/$sub" else defaultName
        return Export(files.size, where, outputs)
    }

    private fun MutableList<Output>.addIfMedia(file: File, uri: String) {
        val mime = mimeOf(file)
        if (mime.startsWith("video/") || mime.startsWith("audio/")) {
            add(Output(uri, mime, file.nameWithoutExtension, mime.startsWith("audio/")))
        }
    }

    /** Seçili klasör erişilebiliyorsa adını, değilse null döndürür. */
    fun folderName(ctx: Context, uriString: String): String? = try {
        val doc = DocumentFile.fromTreeUri(ctx, Uri.parse(uriString))
        if (doc != null && doc.exists() && doc.canWrite()) doc.name else null
    } catch (e: Exception) {
        null
    }

    private fun mimeOf(file: File): String {
        val ext = file.extension.lowercase(Locale.ROOT)
        return when (ext) {
            "srt" -> "application/x-subrip"
            "vtt" -> "text/vtt"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    private fun childDir(parent: DocumentFile, name: String): DocumentFile {
        val existing = parent.findFile(name)
        if (existing != null && existing.isDirectory) return existing
        return parent.createDirectory(name)
            ?: throw IllegalStateException(str(R.string.err_mkdir, name))
    }

    private fun copyToDocument(ctx: Context, file: File, dir: DocumentFile): String {
        val target = dir.createFile(mimeOf(file), file.name)
            ?: throw IllegalStateException(str(R.string.err_create_file, file.name))
        ctx.contentResolver.openOutputStream(target.uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: throw IllegalStateException(str(R.string.err_write_file, file.name))
        return target.uri.toString()
    }

    /** Dosyayı Downloads'a kaydeder ve oynatıcının açabileceği bir içerik adresi döndürür. */
    @Suppress("DEPRECATION")
    private fun saveToDownloads(ctx: Context, file: File, relativeDir: String): String {
        val mime = mimeOf(file)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$relativeDir")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException(str(R.string.err_create_file, file.name))
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException(str(R.string.err_write_file, file.name))
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return uri.toString()
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), relativeDir)
            dir.mkdirs()
            val target = File(dir, file.name)
            file.copyTo(target, overwrite = true)
            MediaScannerConnection.scanFile(ctx, arrayOf(target.absolutePath), arrayOf(mime), null)
            // Android 9 ve altı: dosyayı FileProvider üzerinden paylaşılabilir hale getir
            return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", target).toString()
        }
    }
}
