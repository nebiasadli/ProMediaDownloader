package com.nebi.promediadownloader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Sistem "Paylaş" menüsündeki hedef. YouTube vb. bir uygulamada Paylaş → Pro Media Downloader
 * seçilince ana uygulama açılmaz; arkadaki uygulamanın üstünde küçük bir pencere çıkar.
 * Video veya Ses düğmesi indirmeyi hemen başlatır (ilerleme bildirimde görünür) ve pencere kapanır.
 *
 * Kalite, kodek, klasörleme ve kayıt klasörü, ana ekranda en son seçilen değerlerdir.
 */
class ShareActivity : ComponentActivity() {

    private var url: String = ""
    private var destUri: String? = null
    private var pendingAudio = false

    /** Seçili dilde metinler (ana uygulamayla aynı dil). */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // İzin verilmese de indirme çalışır, sadece bildirim görünmez
            startNow(pendingAudio)
        }

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) askNotificationThenStart(pendingAudio)
            else toast(getString(R.string.share_storage_denied))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val text = if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        } else {
            ""
        }
        val found = Regex("https?://\\S+").find(text)?.value
        if (found == null) {
            toast(getString(R.string.share_no_link))
            finish()
            return
        }
        url = found

        // Kullanıcı seçim yaparken yt-dlp arkada hazırlansın
        Thread {
            try {
                DownloadEngine.prepare(applicationContext)
            } catch (_: Throwable) {
            }
        }.start()

        // Kayıt klasörü hâlâ erişilebilir mi?
        var destName: String? = null
        val saved = prefs().getString("dest_uri", null)
        if (saved != null) {
            destName = DownloadEngine.folderName(this, saved)
            if (destName != null) {
                destUri = saved
            } else {
                toast(getString(R.string.share_folder_unreachable))
            }
        }

        val videoLine = getString(
            R.string.share_line_video,
            getString(codec().labelRes),
            qualityLabel(this, videoQuality(), audio = false),
        )
        val audioLine = getString(R.string.share_line_audio, qualityLabel(this, audioQuality(), audio = true))
        val folderLine = getString(
            R.string.share_line_folder,
            destName ?: getString(R.string.default_folder_name),
        )

        setContent {
            ProMediaTheme {
                ShareSheet(
                    url = url,
                    settingsLines = listOf(videoLine, audioLine, folderLine),
                    onVideo = { requestStart(audio = false) },
                    onAudio = { requestStart(audio = true) },
                    onOpenApp = ::openInApp,
                    onClose = ::finish,
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Ayarlar (ana ekranda en son seçilenler)
    // ------------------------------------------------------------------
    private fun prefs() = getSharedPreferences(LocaleHelper.PREFS, MODE_PRIVATE)

    private fun videoQuality(): String =
        prefs().getString("video_quality", null)?.takeIf { it in VIDEO_QUALITIES } ?: DEFAULT_VIDEO_QUALITY

    private fun audioQuality(): String =
        prefs().getString("audio_quality", null)?.takeIf { it in AUDIO_QUALITIES } ?: DEFAULT_AUDIO_QUALITY

    private fun codec(): CodecOption {
        val id = prefs().getString("video_codec", DEFAULT_CODEC)
        return VIDEO_CODECS.firstOrNull { it.id == id } ?: VIDEO_CODECS.first()
    }

    // ------------------------------------------------------------------
    // İndirmeyi başlat
    // ------------------------------------------------------------------
    private fun requestStart(audio: Boolean) {
        pendingAudio = audio
        val needsStorage = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            destUri == null &&
            !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (needsStorage) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            askNotificationThenStart(audio)
        }
    }

    private fun askNotificationThenStart(audio: Boolean) {
        val needsNotification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(Manifest.permission.POST_NOTIFICATIONS)
        if (needsNotification) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startNow(audio)
        }
    }

    private fun startNow(audio: Boolean) {
        // Başka bir indirme sürüyorsa bu iş kuyruğa eklenir
        val wasBusy = DownloadEngine.state.value.busy
        val title = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").take(60)
        val job = DownloadJob(
            url = url,
            title = title,
            audioOnly = audio,
            quality = if (audio) audioQuality() else videoQuality(),
            playlist = false,
            subtitles = false,
            smartFolder = prefs().getBoolean("smart_folder", true),
            startSec = null,
            endSec = null,
            destinationUri = destUri,
            codec = codec().id,
        )

        if (!wasBusy) DownloadEngine.markQueued()
        try {
            ContextCompat.startForegroundService(this, job.toIntent(this))
            toast(
                getString(
                    when {
                        wasBusy -> R.string.share_queued
                        audio -> R.string.share_toast_audio
                        else -> R.string.share_toast_video
                    },
                ),
            )
        } catch (e: Exception) {
            val reason = getString(R.string.err_service_start, e.message ?: "")
            if (!wasBusy) DownloadEngine.abortQueued(reason)
            toast(getString(R.string.share_toast_failed, e.message ?: ""))
        }
        finish()
    }

    /** İsterse bağlantıyı tüm seçeneklerle görmek için ana uygulamayı açar. */
    private fun openInApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finish()
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

// ======================================================================
// Pencere
// ======================================================================
@Composable
private fun ShareSheet(
    url: String,
    settingsLines: List<String>,
    onVideo: () -> Unit,
    onAudio: () -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
) {
    val engine by DownloadEngine.state.collectAsStateWithLifecycle()
    val busy = engine.busy

    // Boşluğa dokununca pencere kapanır
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                // Pencerenin içine dokunmak scrim'e (kapatmaya) geçmesin
                .pointerInput(Unit) { detectTapGestures { } },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppLogo(44.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium.copy(brush = AccentBrush),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.share_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp),
                ) {
                    Text(
                        url,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    settingsLines.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (busy) {
                    Text(
                        stringResource(R.string.share_busy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GradientButton(
                        text = stringResource(R.string.share_btn_video),
                        onClick = onVideo,
                        enabled = true,
                        modifier = Modifier.weight(1f),
                        height = 52.dp,
                    )
                    GradientButton(
                        text = stringResource(R.string.share_btn_audio),
                        onClick = onAudio,
                        enabled = true,
                        modifier = Modifier.weight(1f),
                        height = 52.dp,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = onOpenApp) { Text(stringResource(R.string.share_open_app)) }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.share_close)) }
                }
            }
        }
    }
}
