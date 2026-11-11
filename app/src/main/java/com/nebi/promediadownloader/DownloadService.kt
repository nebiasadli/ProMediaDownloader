package com.nebi.promediadownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * İndirmeleri ön plan servisi olarak çalıştırır. İşler kuyruğa girer ve
 * kullanıcının seçtiği sayı kadar paralel indirilir. Uygulama kapansa bile sürer.
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_START = "com.nebi.promediadownloader.START"
        const val ACTION_CANCEL = "com.nebi.promediadownloader.CANCEL"
        /** Kuyruğa zaten eklenmiş işleri başlatmak için (oynatma listesi seçiminde). */
        const val ACTION_KICK = "com.nebi.promediadownloader.KICK"
        private const val CHANNEL_PROGRESS = "downloads_progress"
        private const val CHANNEL_DONE = "downloads_done"
        private const val NOTIF_PROGRESS = 1001
        private const val NOTIF_DONE_BASE = 2000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Kuyruk ve işçi sayısı kararlarını tek seferde vermek için kilit. */
    private val lock = Any()

    private var wakeLock: PowerManager.WakeLock? = null
    private var watcher: Job? = null
    private var lastStartId = 0
    private val doneCount = AtomicInteger(0)

    /** Seçili dile göre metin. */
    private fun str(id: Int, vararg args: Any): String = LocaleHelper.wrap(this).getString(id, *args)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            // Çalışan indirmeleri iptal eder; kuyruktaki bekleyen işler sürer
            ACTION_CANCEL -> DownloadEngine.cancel()

            ACTION_START, ACTION_KICK -> handleStart(intent)

            else -> if (DownloadQueue.isIdle()) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        createChannels()
        val job = DownloadJob.from(intent)

        // startForegroundService() çağrıldıktan sonra 5 sn içinde bu çağrı şarttır
        try {
            ServiceCompat.startForeground(
                this,
                NOTIF_PROGRESS,
                progressNotification(
                    job?.title ?: getString(R.string.app_name),
                    str(R.string.notif_starting),
                    0,
                    true,
                    null,
                ),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: Exception) {
            DownloadEngine.abortQueued(str(R.string.err_service_start, e.message ?: ""))
            finishIfIdle()
            return
        }

        if (job != null) {
            DownloadQueue.add(job)
        } else if (intent.action == ACTION_START) {
            DownloadEngine.abortQueued(str(R.string.err_invalid_request))
            finishIfIdle()
            return
        }
        pump()
    }

    /** Boş yuva varken bekleyen işleri başlatır. */
    private fun pump() {
        synchronized(lock) {
            while (true) {
                val entry = DownloadQueue.claim(maxParallel()) ?: break
                acquireWakeLock()
                if (watcher == null) watcher = startWatcher()
                launchWorker(entry)
            }
        }
    }

    /** Kullanıcının seçtiği paralel indirme sayısı (1–4). */
    private fun maxParallel(): Int =
        getSharedPreferences(LocaleHelper.PREFS, MODE_PRIVATE).getInt("concurrency", 10).coerceAtLeast(1)

    private fun launchWorker(entry: DownloadQueue.Entry) {
        scope.launch {
            val result = try {
                DownloadEngine.download(applicationContext, entry.job, entry.id)
            } catch (t: Throwable) {
                DownloadResult.Failed(t.message ?: "")
            }

            if (result is DownloadResult.Success) {
                val now = System.currentTimeMillis()
                HistoryStore.add(
                    applicationContext,
                    result.outputs.map {
                        val id = UUID.randomUUID().toString()
                        HistoryItem(
                            id, it.title, it.uri, it.mime, it.audio, now,
                            Thumbnails.create(applicationContext, it.uri, it.mime, id),
                        )
                    },
                )
            }
            postResult(entry.job, result)

            DownloadQueue.release()
            pump()
            finishIfIdle()
        }
    }

    /** Ekranda ve bildirimde ilerlemeyi gösteren tek izleyici. */
    private fun startWatcher(): Job = scope.launch {
        var lastNotify = 0L
        DownloadEngine.state.collect { st ->
            if (!st.busy) return@collect
            val now = SystemClock.elapsedRealtime()
            if (now - lastNotify < 800) return@collect
            lastNotify = now

            val pct = (st.progress * 100).toInt().coerceIn(0, 100)
            val title = when {
                st.activeTitles.size > 1 -> str(R.string.notif_parallel, st.activeTitles.size)
                st.activeTitles.isNotEmpty() -> st.activeTitles.first()
                else -> getString(R.string.app_name)
            }
            val text = if (st.progress > 0f) "${st.status} · %$pct" else st.status
            val parts = mutableListOf<String>()
            if (st.eta != "--:--") parts.add(str(R.string.notif_remaining, st.eta))
            val queued = DownloadQueue.size()
            if (queued > 0) parts.add(str(R.string.notif_queue_left, queued))

            notificationManager().notify(
                NOTIF_PROGRESS,
                progressNotification(title, text, pct, st.progress <= 0f, parts.joinToString(" · ").ifEmpty { null }),
            )
        }
    }

    /** Kuyrukta iş kalmadıysa bildirimi kaldırıp servisi kapatır. */
    private fun finishIfIdle() {
        synchronized(lock) {
            if (!DownloadQueue.isIdle()) return
            releaseWakeLock()
            watcher?.cancel()
            watcher = null
            notificationManager().cancel(NOTIF_PROGRESS)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            // Bu sırada yeni bir istek geldiyse servis açık kalır
            stopSelfResult(lastStartId)
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        watcher?.cancel()
        DownloadQueue.reset()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Bildirimler
    // ------------------------------------------------------------------
    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = notificationManager()
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                str(R.string.notif_channel_progress),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = str(R.string.notif_channel_progress_desc)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DONE,
                str(R.string.notif_channel_done),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = str(R.string.notif_channel_done_desc)
            },
        )
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Geçmiş sekmesini açar (birden fazla dosya indiğinde). */
    private fun historyIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_HISTORY, true)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Bildirime dokununca: tek ses dosyası → müzik uygulaması, tek video → video oynatıcı.
     * Birden fazla dosya ya da açacak uygulama yoksa geçmiş ekranı açılır.
     */
    private fun resultIntent(result: DownloadResult.Success): PendingIntent {
        val single = result.outputs.singleOrNull()
        if (single != null) {
            val view = MediaLinks.viewIntent(single.uri, single.mime)
            if (MediaLinks.canOpen(this, view)) {
                return PendingIntent.getActivity(
                    this,
                    2,
                    view,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
        }
        return if (result.outputs.isNotEmpty()) historyIntent() else openAppIntent()
    }

    private fun progressNotification(
        title: String,
        text: String,
        percent: Int,
        indeterminate: Boolean,
        sub: String?,
    ): Notification {
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(sub)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, str(R.string.notif_cancel), cancel)
            .build()
    }

    private fun postResult(job: DownloadJob, result: DownloadResult) {
        val notification = when (result) {
            is DownloadResult.Success -> NotificationCompat.Builder(this, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(str(R.string.notif_done_title))
                .setContentText(str(R.string.files_saved, result.count, result.where))
                .setSubText(job.title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(str(R.string.files_saved, result.count, result.where)))
                .setContentIntent(resultIntent(result))
                .setAutoCancel(true)
                .build()

            is DownloadResult.Failed -> NotificationCompat.Builder(this, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(str(R.string.notif_failed_title))
                .setContentText(result.reason)
                .setSubText(job.title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(result.reason))
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build()

            DownloadResult.Cancelled -> return
        }
        // Her biten indirme kendi bildirimini bırakır
        notificationManager().notify(NOTIF_DONE_BASE + doneCount.getAndIncrement(), notification)
    }

    // ------------------------------------------------------------------
    // Ekran kapanınca CPU uyumasın
    // ------------------------------------------------------------------
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProMedia:download").apply {
                acquire(3 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            DownloadEngine.log(str(R.string.log_wakelock_failed, e.message ?: ""))
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }
}
