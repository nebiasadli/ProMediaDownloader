package com.nebi.promediadownloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    companion object {
        /** Bildirimden "geçmiş" sekmesini açmak için ek. */
        const val EXTRA_OPEN_HISTORY = "open_history"
    }

    private val viewModel: DownloadViewModel by viewModels()

    /** Seçili dili uygula: uygulama her zaman ayarlanan dilde açılır. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    // Android 13+: durum çubuğunda bildirim göstermek için izin
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // İzin verilmese de indirme çalışır, sadece bildirim görünmez
            continueAfterStoragePermission()
        }

    // Android 9 ve altında Downloads klasörüne yazmak için izin
    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) requestNotificationThenStart() else viewModel.cancelDownloadPermission()
        }

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) viewModel.onDestinationPicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleShare(intent)
            handleOpenHistory(intent)
        }

        setContent {
            ProMediaTheme {
                MainScreen(
                    viewModel = viewModel,
                    onStart = ::requestStart,
                    onPickFolder = { folderPicker.launch(null) },
                    onLanguageChange = ::changeLanguage,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
        handleOpenHistory(intent)
    }

    override fun onResume() {
        super.onResume()
        checkClipboard()
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { viewModel.setUrlFromShare(it) }
        }
    }

    private fun handleOpenHistory(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_HISTORY, false) == true) {
            viewModel.setTab(1)
        }
    }

    /** Uygulama açılınca panodaki bağlantıyı önerir (otomatik doldurmaz). */
    private fun checkClipboard() {
        try {
            val cm = getSystemService(ClipboardManager::class.java) ?: return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(this)?.toString()
            viewModel.onClipboard(text)
        } catch (_: Exception) {
            // Pano okunamazsa uygulama normal çalışmaya devam eder
        }
    }

    private fun changeLanguage(tag: String) {
        if (tag == LocaleHelper.savedTag(this)) return
        LocaleHelper.setTag(this, tag)
        recreate()
    }

    private fun requestStart() {
        val needsStorage = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            viewModel.needsLegacyStoragePermission() &&
            granted(Manifest.permission.WRITE_EXTERNAL_STORAGE).not()
        if (needsStorage) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            requestNotificationThenStart()
        }
    }

    private fun requestNotificationThenStart() {
        val needsNotification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            granted(Manifest.permission.POST_NOTIFICATIONS).not()
        if (needsNotification) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            continueAfterStoragePermission()
        }
    }

    private fun continueAfterStoragePermission() = viewModel.startDownload()

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
