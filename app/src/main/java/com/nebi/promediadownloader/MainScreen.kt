package com.nebi.promediadownloader

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.ui.window.Dialog
import java.text.DateFormat
import java.util.Date
import java.io.File

internal val AccentBrush = Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)))
private val Good = Color(0xFF34D399)
private val Info = Color(0xFF60A5FA)
private val CardShape = RoundedCornerShape(20.dp)

// Yerleşim ölçüleri: boşluklar tek yerden ayarlanır
private val ScreenGap = 16.dp      // kartlar arası
private val CardPadding = 18.dp    // kart iç boşluğu
private val GroupGap = 18.dp       // kart içindeki gruplar arası
private val LabelGap = 8.dp        // etiket ile kontrol arası

// ======================================================================
// Ana ekran
// ======================================================================
@Composable
fun MainScreen(
    viewModel: DownloadViewModel,
    onStart: () -> Unit,
    onPickFolder: () -> Unit,
    onLanguageChange: (String) -> Unit,
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val language = LocaleHelper.savedTag(context)

    // Kısa bilgi mesajları (ör. dosya bulunamadı) ekrana toast olarak çıkar
    LaunchedEffect(viewModel) {
        viewModel.toasts.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    // Surface, içerideki yazılara varsayılan rengi (onBackground) verir; başlık artık siyah kalmaz
    Surface(
        modifier = Modifier.fillMaxSize().imePadding(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        // Oynatma listesi seçici penceresi (yalnızca açıkken görünür)
        s.picker?.let { PlaylistPicker(it, viewModel) }

        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = { ActionBar(s, onStart, viewModel::cancelDownload) },
        ) { inner ->
            // Ekran genişliğine göre düzen: dar = tek sütun, geniş (tablet/yatay) = iki sütun
            BoxWithConstraints(Modifier.fillMaxSize().padding(inner)) {
                val wide = maxWidth >= 840.dp
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = if (wide) 1080.dp else 640.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(ScreenGap),
                    ) {
                        Header(s)

                        Segmented(
                            options = listOf(stringResource(R.string.tab_download), stringResource(R.string.tab_history)),
                            selected = s.tab,
                            enabled = true,
                            onSelect = viewModel::setTab,
                        )

                        if (s.tab == 1) {
                            HistoryCard(s, viewModel)
                        } else if (wide) {
                            Row(horizontalArrangement = Arrangement.spacedBy(ScreenGap)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ScreenGap)) {
                                    LinkCard(s, viewModel)
                                    PreviewCard(s)
                                    FolderCard(s, onPickFolder, viewModel::useDefaultDestination)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ScreenGap)) {
                                    OptionsCard(s, viewModel)
                                    if (s.queue.isNotEmpty()) QueueCard(s, viewModel)
                                    LogCard(s, viewModel::clearLog)
                                }
                            }
                            LanguageCard(language, !s.busy, onLanguageChange)
                            AboutCard()
                        } else {
                            LinkCard(s, viewModel)
                            PreviewCard(s)
                            OptionsCard(s, viewModel)
                            FolderCard(s, onPickFolder, viewModel::useDefaultDestination)
                            if (s.queue.isNotEmpty()) QueueCard(s, viewModel)
                            LogCard(s, viewModel::clearLog)
                            LanguageCard(language, !s.busy, onLanguageChange)
                            AboutCard()
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

// ======================================================================
// Başlık
// ======================================================================
@Composable
private fun Header(s: UiState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AppLogo(52.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // Başlık, uygulama ikonuyla aynı indigo → mor geçişle boyanır
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge.copy(brush = AccentBrush),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(if (s.engineReady) R.string.tagline_ready else R.string.tagline_preparing),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = if (s.engineReady) MaterialTheme.colorScheme.onSurfaceVariant else Info,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ======================================================================
// Kartlar
// ======================================================================
@Composable
private fun LinkCard(s: UiState, vm: DownloadViewModel) {
    val clipboard = LocalClipboardManager.current
    // Kuyruk sayesinde bağlantı ve ayarlar indirme sürerken de değiştirilebilir
    val locked = false

    AppCard(
        title = stringResource(R.string.link_title),
        subtitle = stringResource(R.string.link_subtitle),
        trailing = { TextAction(stringResource(R.string.btn_clear), enabled = !locked) { vm.resetUi() } },
    ) {
        val offer = s.clipOffer
        if (offer != null && !locked) {
            ClipboardBanner(
                link = offer,
                onAccept = vm::acceptClipboard,
                onDismiss = vm::dismissClipboard,
            )
        }

        OutlinedTextField(
            value = s.url,
            onValueChange = { vm.setUrl(it) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !locked,
            placeholder = {
                Text(stringResource(R.string.link_placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            trailingIcon = {
                if (s.url.isNotEmpty() && !locked) {
                    IconButtonLike(onClick = { vm.clearUrl() })
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { vm.fetchPreview() }),
            shape = RoundedCornerShape(14.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { vm.pasteUrl(clipboard.getText()?.text.orEmpty()) },
                enabled = !locked,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text(stringResource(R.string.btn_paste)) }
            GradientButton(
                text = stringResource(if (s.fetching) R.string.btn_fetching else R.string.btn_fetch),
                onClick = { vm.fetchPreview() },
                enabled = !locked && !s.fetching && s.engineReady && s.url.isNotBlank(),
                modifier = Modifier.weight(1f),
                height = 48.dp,
            )
        }
    }
}

/** Pano balonu: kopyalanan bağlantıyı gösterir; kullanıcı isterse alana yapıştırır. */
@Composable
private fun ClipboardBanner(link: String, onAccept: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.clipboard_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                link,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextAction(stringResource(R.string.btn_paste), onClick = onAccept)
        TextAction(stringResource(R.string.clipboard_dismiss), onClick = onDismiss)
    }
}

@Composable
private fun IconButtonLike(onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_close),
            contentDescription = stringResource(R.string.clear_desc),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun PreviewCard(s: UiState) {
    AppCard(title = stringResource(R.string.preview_title)) {
        val p = s.preview
        if (p == null) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = 96.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (s.fetching) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.preview_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.preview_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            if (p.thumbnail.isNotEmpty()) {
                AsyncImage(
                    model = p.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(14.dp)),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    p.uploader,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(stringResource(R.string.preview_duration, p.duration))
                Pill(p.source)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionsCard(s: UiState, vm: DownloadViewModel) {
    val locked = false
    val context = LocalContext.current

    AppCard(
        title = stringResource(R.string.options_title),
        subtitle = stringResource(R.string.options_subtitle),
    ) {
        Field(stringResource(R.string.label_format)) {
            Segmented(
                options = listOf(stringResource(R.string.format_video), stringResource(R.string.format_audio)),
                selected = if (s.audioOnly) 1 else 0,
                enabled = !locked,
                onSelect = { vm.setAudioOnly(it == 1) },
            )
        }

        Field(stringResource(R.string.label_quality)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val options = if (s.audioOnly) AUDIO_QUALITIES else VIDEO_QUALITIES
                options.forEach { q ->
                    FilterChip(
                        selected = s.quality == q,
                        onClick = { vm.setQuality(q) },
                        enabled = !locked,
                        label = { Text(qualityLabel(context, q, s.audioOnly)) },
                    )
                }
            }
        }

        if (!s.audioOnly) {
            Field(stringResource(R.string.label_codec)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    VIDEO_CODECS.forEach { c ->
                        FilterChip(
                            selected = s.codec == c.id,
                            onClick = { vm.setCodec(c.id) },
                            enabled = !locked,
                            label = { Text(stringResource(c.labelRes)) },
                        )
                    }
                }
                val codec = VIDEO_CODECS.firstOrNull { it.id == s.codec } ?: VIDEO_CODECS.first()
                val hintRes = if (s.quality == "2160" && s.codec == "h264") R.string.hint_h264_4k else codec.hintRes
                Text(
                    stringResource(hintRes),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Field(stringResource(R.string.label_time_range)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = s.start,
                    onValueChange = { vm.setStart(it) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !locked,
                    label = { Text(stringResource(R.string.time_start)) },
                    placeholder = { Text("00:00") },
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = s.end,
                    onValueChange = { vm.setEnd(it) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !locked,
                    label = { Text(stringResource(R.string.time_end)) },
                    placeholder = { Text("10:30") },
                    shape = RoundedCornerShape(14.dp),
                )
            }
        }

        Field(stringResource(R.string.label_concurrency)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 30, 50, 100).forEach { n ->
                    FilterChip(
                        selected = s.concurrency == n,
                        onClick = { vm.setConcurrency(n) },
                        enabled = !locked,
                        label = { Text(n.toString()) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.concurrency_hint),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column {
            SwitchRow(stringResource(R.string.switch_playlist), s.playlist, !locked) { vm.setPlaylist(it) }
            SwitchRow(stringResource(R.string.switch_subtitles), s.subtitles, !locked) { vm.setSubtitles(it) }
            SwitchRow(stringResource(R.string.switch_smart), s.smartFolder, !locked) { vm.setSmartFolder(it) }
        }
    }
}

@Composable
private fun FolderCard(s: UiState, onPick: () -> Unit, onDefault: () -> Unit) {
    val locked = false
    val custom = s.destinationName != null

    AppCard(
        title = stringResource(R.string.folder_title),
        subtitle = stringResource(R.string.folder_subtitle),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(AccentBrush),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    s.destinationName ?: stringResource(R.string.default_folder_name),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(if (custom) R.string.folder_custom else R.string.folder_default),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GradientButton(
                text = stringResource(if (custom) R.string.folder_change else R.string.folder_choose),
                onClick = onPick,
                enabled = !locked,
                modifier = Modifier.weight(1f),
                height = 48.dp,
            )
            if (custom) {
                OutlinedButton(
                    onClick = onDefault,
                    enabled = !locked,
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(stringResource(R.string.folder_reset)) }
            }
        }
    }
}

@Composable
private fun LogCard(s: UiState, onClear: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val empty = stringResource(R.string.log_empty)

    AppCard(
        title = stringResource(R.string.log_title),
        trailing = {
            Text(
                stringResource(if (open) R.string.log_hide else R.string.log_show),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        onHeaderClick = { open = !open },
    ) {
        if (!open) {
            Text(
                s.logs.lastOrNull() ?: empty,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState(), reverseScrolling = true),
            ) {
                Text(
                    text = if (s.logs.isEmpty()) empty else s.logs.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextAction(stringResource(R.string.log_clear_btn), onClick = onClear)
        }
    }
}

// ======================================================================
// Geçmiş sekmesi
// ======================================================================
/** Oynatma listesindeki öğeleri seçtiren pencere. */
@Composable
private fun PlaylistPicker(p: PickerState, vm: DownloadViewModel) {
    Dialog(onDismissRequest = { if (!p.loading) vm.closePlaylistPicker() }) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(16.dp),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.picker_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (p.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.picker_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    val chosen = p.entries.count { it.selected }
                    Text(
                        stringResource(R.string.picker_count, chosen, p.entries.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextAction(stringResource(R.string.picker_select_all)) { vm.setAllPlaylistEntries(true) }
                        TextAction(stringResource(R.string.picker_clear)) { vm.setAllPlaylistEntries(false) }
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        itemsIndexed(p.entries) { index, entry ->
                            PickerRow(entry) { vm.togglePlaylistEntry(index) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = vm::closePlaylistPicker,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text(stringResource(R.string.picker_cancel)) }
                        GradientButton(
                            text = stringResource(R.string.picker_save, chosen),
                            onClick = vm::confirmPlaylist,
                            enabled = chosen > 0,
                            modifier = Modifier.weight(1f),
                            height = 48.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(entry: PlaylistEntry, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = entry.selected, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.duration,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QueueCard(s: UiState, vm: DownloadViewModel) {
    AppCard(
        title = stringResource(R.string.queue_title),
        subtitle = stringResource(R.string.queue_subtitle),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            s.queue.forEachIndexed { index, entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "${index + 1}.",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            entry.job.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(if (entry.job.audioOnly) R.string.format_audio else R.string.format_video),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextAction(stringResource(R.string.queue_remove)) { vm.removeQueued(entry.id) }
                }
            }
        }
        TextAction(stringResource(R.string.queue_clear), onClick = vm::clearQueue)
    }
}

@Composable
private fun HistoryCard(s: UiState, vm: DownloadViewModel) {
    AppCard(
        title = stringResource(R.string.history_title),
        subtitle = stringResource(R.string.history_subtitle),
    ) {
        if (s.history.isEmpty()) {
            Text(
                stringResource(R.string.history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                s.history.forEach { item ->
                    HistoryRow(item) { vm.playItem(item) }
                }
            }
            TextAction(stringResource(R.string.history_clear), onClick = vm::clearHistory)
        }
    }
}

/** Kapak görüntüsü (yoksa gradyan kutu) ve üzerinde oynatma simgesi. */
@Composable
private fun HistoryThumb(item: HistoryItem) {
    Box(
        Modifier
            .size(width = 96.dp, height = 54.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(AccentBrush),
        contentAlignment = Alignment.Center,
    ) {
        if (item.thumb.isNotEmpty()) {
            AsyncImage(
                model = File(item.thumb),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (item.audio) "♪" else "▶",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, onClick: () -> Unit) {
    val date = remember(item.date) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.date))
    }
    val typeLabel = stringResource(if (item.audio) R.string.type_audio else R.string.type_video)

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HistoryThumb(item)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$typeLabel · $date",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ======================================================================
// Dil kartı
// ======================================================================
@Composable
private fun LanguageCard(language: String, enabled: Boolean, onChange: (String) -> Unit) {
    AppCard(
        title = stringResource(R.string.language_title),
        subtitle = stringResource(R.string.language_subtitle),
    ) {
        Segmented(
            options = listOf(stringResource(R.string.language_en), stringResource(R.string.language_tr)),
            selected = if (language == LocaleHelper.TR) 1 else 0,
            enabled = enabled,
            onSelect = { onChange(if (it == 1) LocaleHelper.TR else LocaleHelper.EN) },
        )
    }
}

// ======================================================================
// Alt eylem çubuğu
// ======================================================================
@Composable
private fun ActionBar(s: UiState, onStart: () -> Unit, onCancel: () -> Unit) {
    val toneColor = when (s.tone) {
        Tone.SUCCESS -> Good
        Tone.ERROR -> MaterialTheme.colorScheme.error
        Tone.WORKING -> Info
        Tone.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val percent = (s.progress * 100).toInt().coerceIn(0, 100)
    val message = s.message

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(toneColor))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            s.status,
                            style = MaterialTheme.typography.labelLarge,
                            color = toneColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (s.busy) {
                            Text(
                                stringResource(R.string.action_remaining, s.eta),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (!message.isNullOrBlank()) {
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("%$percent", style = MaterialTheme.typography.titleLarge)
                }

                LinearProgressIndicator(
                    progress = { s.progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline,
                )

                if (s.busy) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    ) {
                        Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        stringResource(R.string.action_bg_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // İndirme sürerken de bağlantı eklenebilir; yeni iş kuyruğa girer
                GradientButton(
                    text = stringResource(if (s.busy) R.string.action_enqueue else R.string.action_start),
                    onClick = onStart,
                    enabled = s.engineReady,
                    modifier = Modifier.fillMaxWidth(),
                    height = 52.dp,
                )
            }
        }
    }
}

// ======================================================================
// Ortak parçalar
// ======================================================================

/**
 * Kart: üstte başlık satırı (başlık solda, eylem sağda), altında tam genişlikte alt yazı,
 * sonra içerik. Alt yazı artık eylem düğmesiyle yan yana sıkışmaz.
 */
@Composable
private fun AppCard(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onHeaderClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(GroupGap)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onHeaderClick != null) Modifier.clickable(onClick = onHeaderClick) else Modifier),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 24.sp),
                        modifier = Modifier.weight(1f),
                    )
                    if (trailing != null) trailing()
                }
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun AboutCard() {
    val context = LocalContext.current
    val version = remember {
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    AppCard(title = stringResource(R.string.about_title)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppLogo(48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleSmall)
                if (version.isNotEmpty()) {
                    Text(
                        stringResource(R.string.about_version, version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.about_developer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(110.dp),
                )
                Text("Nebi Asadli", style = MaterialTheme.typography.titleMedium.copy(brush = AccentBrush))
            }
            Text(
                stringResource(R.string.about_copyright),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.about_disclaimer),
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Uygulama logosu (launcher ikonuyla aynı tasarım). Başlıkta, Hakkında'da ve Paylaş penceresinde kullanılır. */
@Composable
internal fun AppLogo(size: Dp) {
    Image(
        painter = painterResource(R.drawable.ic_app_logo),
        contentDescription = null,
        modifier = Modifier.size(size),
    )
}

/** Etiket + kontrol bir grup: etiket kendi kontrolüne yakın, diğer gruplardan belirgin uzak. */
@Composable
private fun Field(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LabelGap)) {
        FieldLabel(label)
        content()
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Küçük metin eylemi; Material TextButton'ın 48dp'lik boşluğu satırı şişirdiği için özel. */
@Composable
private fun TextAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f),
        )
    }
}

@Composable
private fun Pill(text: String) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val on = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .then(if (on) Modifier.background(AccentBrush) else Modifier)
                    .clickable(enabled = enabled) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 48.dp,
) {
    val background: Brush = if (enabled) AccentBrush else SolidColor(MaterialTheme.colorScheme.surfaceVariant)
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(if (height >= 50.dp) 16.dp else 14.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}
