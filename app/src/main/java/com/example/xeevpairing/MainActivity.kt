package com.example.xeevpairing

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.xeevpairing.data.EpgProgramme
import com.example.xeevpairing.data.M3uPlaylistParser
import com.example.xeevpairing.data.ParsedPlaylist
import com.example.xeevpairing.data.PlaylistCategory
import com.example.xeevpairing.data.TvChannel
import com.example.xeevpairing.data.XeevPlaylistParser
import com.example.xeevpairing.data.XeevRepository
import com.example.xeevpairing.data.XmlTvParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val PREFS_NAME = "xeev_tv_settings"
private const val FORMAT_XEEV = "xeev"
private const val FORMAT_M3U = "m3u"
private const val AUTO_POLL_MS = 20_000L
private const val AUTO_POLL_LIMIT = 30

private val AppBackground = Color(0xFF080D17)
private val PanelBackground = Color(0xFF111A29)
private val PanelRaised = Color(0xFF182438)
private val Accent = Color(0xFF72D5B0)
private val TextPrimary = Color(0xFFF2F6FC)
private val TextMuted = Color(0xFF9AAAC0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null)
            ?: UUID.randomUUID().toString().replace("-", "").uppercase().also {
                prefs.edit().putString("device_id", it).apply()
            }

        setContent {
            XeevTvApp(
                deviceId = deviceId,
                initialAppCode = prefs.getString("app_code", null),
                initialPlaylistId = prefs.getString("playlist_id", null),
                initialQrUrl = prefs.getString("qr_url", null),
                initialPlaylistFormat = prefs.getString("playlist_format", FORMAT_XEEV) ?: FORMAT_XEEV,
                repository = remember { XeevRepository() },
                saveAppCode = { code, qr ->
                    prefs.edit().putString("app_code", code).putString("qr_url", qr).apply()
                },
                savePlaylistId = { id -> prefs.edit().putString("playlist_id", id).apply() },
                savePlaylistFormat = { format -> prefs.edit().putString("playlist_format", format).apply() },
                clearPairing = {
                    prefs.edit().remove("app_code").remove("qr_url").remove("playlist_id").apply()
                }
            )
        }
    }
}

@Composable
private fun XeevTvApp(
    deviceId: String,
    initialAppCode: String?,
    initialPlaylistId: String?,
    initialQrUrl: String?,
    initialPlaylistFormat: String,
    repository: XeevRepository,
    saveAppCode: (String, String?) -> Unit,
    savePlaylistId: (String) -> Unit,
    savePlaylistFormat: (String) -> Unit,
    clearPairing: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var appCode by remember { mutableStateOf(initialAppCode.orEmpty()) }
    var qrUrl by remember { mutableStateOf(initialQrUrl.orEmpty()) }
    var playlistId by remember { mutableStateOf(initialPlaylistId.orEmpty()) }
    var playlistFormat by remember {
        mutableStateOf(if (initialPlaylistFormat.equals(FORMAT_M3U, true)) FORMAT_M3U else FORMAT_XEEV)
    }
    var categories by remember { mutableStateOf<List<PlaylistCategory>>(emptyList()) }
    var channels by remember { mutableStateOf<List<TvChannel>>(emptyList()) }
    var epgByChannelId by remember { mutableStateOf<Map<String, List<EpgProgramme>>>(emptyMap()) }
    var epgUrl by remember { mutableStateOf<String?>(null) }
    var selectedCategoryId by remember { mutableStateOf(ALL_CATEGORY_ID) }
    var selectedChannel by remember { mutableStateOf<TvChannel?>(null) }
    var searchText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Ready to connect to XEEV") }
    var isBusy by remember { mutableStateOf(false) }
    var autoPolling by remember { mutableStateOf(false) }
    var playlistLoaded by remember { mutableStateOf(false) }
    var showPairing by remember {
        mutableStateOf(initialAppCode.isNullOrBlank() || initialPlaylistId.isNullOrBlank())
    }
    var isFullScreen by remember { mutableStateOf(false) }
    BackHandler(enabled = isFullScreen) { isFullScreen = false }

    fun changeFormat(value: String) {
        playlistFormat = value
        savePlaylistFormat(value)
    }

    suspend fun loadPlaylist(id: String, format: String = playlistFormat) {
        val mode = if (format == FORMAT_M3U) FORMAT_M3U else FORMAT_XEEV
        status = if (mode == FORMAT_M3U) "Downloading M3U playlist…" else "Downloading XEEV playlist…"

        val parsed: ParsedPlaylist = withContext(Dispatchers.IO) {
            if (mode == FORMAT_M3U) {
                M3uPlaylistParser.parse(repository.downloadM3uPlaylist(id), repository.m3uPlaylistUrl(id))
            } else {
                XeevPlaylistParser.parse(repository.downloadPlaylist(id))
            }
        }

        categories = parsed.categories
        channels = parsed.channels
        selectedCategoryId = ALL_CATEGORY_ID
        selectedChannel = parsed.channels.firstOrNull()
        epgUrl = parsed.epgUrl
        epgByChannelId = emptyMap()
        playlistLoaded = true

        val playlistSummary = "${parsed.channels.size} TV channels · ${parsed.categories.size} categories"
        status = "${if (mode == FORMAT_M3U) "M3U" else "XEEV"} playlist loaded: $playlistSummary"
        if (parsed.channels.isEmpty()) status += ". No supported channel records were found."

        val guideUrl = parsed.epgUrl
        if (!guideUrl.isNullOrBlank()) {
            status = "Playlist loaded: $playlistSummary · Downloading EPG guide…"
            try {
                val guide = withContext(Dispatchers.IO) {
                    XmlTvParser.parse(repository.downloadEpg(guideUrl))
                }
                epgByChannelId = guide.groupBy { it.channelId.trim().uppercase(Locale.ROOT) }
                status = "Playlist loaded: $playlistSummary · EPG: ${guide.size} programmes"
                if (guide.isEmpty()) status += " (no programme entries found)"
            } catch (error: Exception) {
                status = "Playlist loaded: $playlistSummary · EPG unavailable: ${error.message ?: "download/parse error"}"
            }
        }
    }

    suspend fun checkPairingAndLoad(): Boolean {
        if (appCode.isBlank()) {
            status = "Create an app code first."
            return false
        }
        status = "Checking XEEV pairing status…"
        val foundId = withContext(Dispatchers.IO) { repository.checkPairing(appCode, deviceId) }
        if (foundId == null) {
            status = "Not paired yet. Connect this app code to a playlist in XEEV Playlist Manager."
            return false
        }
        playlistId = foundId
        savePlaylistId(foundId)
        loadPlaylist(foundId, playlistFormat)
        showPairing = false
        autoPolling = false
        return true
    }

    LaunchedEffect(initialPlaylistId) {
        val restoredId = initialPlaylistId.orEmpty()
        if (XeevRepository.isValidPlaylistId(restoredId)) {
            playlistId = restoredId
            isBusy = true
            runCatching { loadPlaylist(restoredId, playlistFormat) }
                .onFailure { status = "Saved pairing found, but playlist refresh failed: ${it.message}" }
            isBusy = false
            if (playlistLoaded) showPairing = false
        }
    }

    LaunchedEffect(autoPolling, appCode, playlistId, playlistFormat) {
        if (autoPolling && appCode.isNotBlank() && !XeevRepository.isValidPlaylistId(playlistId)) {
            repeat(AUTO_POLL_LIMIT) {
                delay(AUTO_POLL_MS)
                if (!autoPolling) return@LaunchedEffect
                isBusy = true
                try {
                    if (checkPairingAndLoad()) return@LaunchedEffect
                } catch (error: Exception) {
                    status = "Pairing check failed: ${error.message ?: "network error"}"
                } finally {
                    isBusy = false
                }
            }
            autoPolling = false
            if (!XeevRepository.isValidPlaylistId(playlistId)) {
                status = "Automatic checks finished. You can check again manually."
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color(0xFF062017),
            secondary = Color(0xFF89A8FF),
            background = AppBackground,
            surface = PanelBackground,
            surfaceVariant = PanelRaised,
            onBackground = TextPrimary,
            onSurface = TextPrimary
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = AppBackground) {
            if (!showPairing && playlistLoaded) {
                TvHomeScreen(
                    categories = categories,
                    channels = channels,
                    epgByChannelId = epgByChannelId,
                    epgAvailable = !epgUrl.isNullOrBlank(),
                    playlistFormat = playlistFormat,
                    selectedCategoryId = selectedCategoryId,
                    selectedChannel = selectedChannel,
                    searchText = searchText,
                    status = status,
                    onCategorySelected = { selectedCategoryId = it; selectedChannel = null },
                    onChannelSelected = { selectedChannel = it },
                    onSearchChanged = { searchText = it },
                    onOpenPairing = { showPairing = true },
                    onRefresh = {
                        scope.launch {
                            isBusy = true
                            runCatching { loadPlaylist(playlistId, playlistFormat) }
                                .onFailure { status = "Playlist refresh failed: ${it.message}" }
                            isBusy = false
                        }
                    },
                    isBusy = isBusy,
                    isFullScreen = isFullScreen,
                    onToggleFullScreen = { isFullScreen = !isFullScreen }
                )
            } else {
                PairingScreen(
                    appCode = appCode,
                    qrUrl = qrUrl,
                    playlistId = playlistId,
                    deviceId = deviceId,
                    playlistFormat = playlistFormat,
                    status = status,
                    isBusy = isBusy,
                    autoPolling = autoPolling,
                    paired = XeevRepository.isValidPlaylistId(playlistId),
                    playlistLoaded = playlistLoaded,
                    onFormatChanged = ::changeFormat,
                    onRegister = {
                        scope.launch {
                            isBusy = true
                            status = "Registering this device with XEEV…"
                            try {
                                val result = withContext(Dispatchers.IO) { repository.register(deviceId) }
                                appCode = result.appCode
                                qrUrl = result.qrImageUrl.orEmpty()
                                saveAppCode(result.appCode, result.qrImageUrl)
                                playlistId = ""
                                savePlaylistId("")
                                playlistLoaded = false
                                channels = emptyList()
                                categories = emptyList()
                                epgByChannelId = emptyMap()
                                status = "App code created. Pair it in XEEV Playlist Manager, then check pairing."
                            } catch (error: Exception) {
                                status = "Registration failed: ${error.message ?: "unknown error"}"
                            } finally {
                                isBusy = false
                            }
                        }
                    },
                    onCheckPairing = {
                        scope.launch {
                            isBusy = true
                            try {
                                checkPairingAndLoad()
                            } catch (error: Exception) {
                                status = "Pairing check failed: ${error.message ?: "network error"}"
                            } finally {
                                isBusy = false
                            }
                        }
                    },
                    onLoadSelectedFormat = {
                        scope.launch {
                            isBusy = true
                            try {
                                loadPlaylist(playlistId, playlistFormat)
                                showPairing = false
                            } catch (error: Exception) {
                                status = "Playlist load failed: ${error.message ?: "network error"}"
                            } finally {
                                isBusy = false
                            }
                        }
                    },
                    onAutoPollingChanged = { autoPolling = it },
                    onNewCode = {
                        appCode = ""
                        qrUrl = ""
                        playlistId = ""
                        channels = emptyList()
                        categories = emptyList()
                        epgByChannelId = emptyMap()
                        epgUrl = null
                        playlistLoaded = false
                        autoPolling = false
                        clearPairing()
                        status = "Ready to create a new app code."
                    },
                    onOpenTv = { showPairing = false }
                )
            }
        }
    }
}

@Composable
private fun PairingScreen(
    appCode: String,
    qrUrl: String,
    playlistId: String,
    deviceId: String,
    playlistFormat: String,
    status: String,
    isBusy: Boolean,
    autoPolling: Boolean,
    paired: Boolean,
    playlistLoaded: Boolean,
    onFormatChanged: (String) -> Unit,
    onRegister: () -> Unit,
    onCheckPairing: () -> Unit,
    onLoadSelectedFormat: () -> Unit,
    onAutoPollingChanged: (Boolean) -> Unit,
    onNewCode: () -> Unit,
    onOpenTv: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Text("XEEV TV", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Connect your playlist", color = TextPrimary, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                "Create an app code, pair it on the XEEV website, then load either the XEEV or standard M3U playlist.",
                color = TextMuted, fontSize = 12.sp
            )
            Spacer(Modifier.height(18.dp))
            Text("PLAYLIST FORMAT", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = playlistFormat == FORMAT_XEEV,
                    onClick = { onFormatChanged(FORMAT_XEEV) },
                    label = { Text("XEEV") }
                )
                FilterChip(
                    selected = playlistFormat == FORMAT_M3U,
                    onClick = { onFormatChanged(FORMAT_M3U) },
                    label = { Text("M3U") }
                )
            }
            Text(
                if (playlistFormat == FORMAT_M3U)
                    "Uses /load/m3u/{playlist ID} and reads the XMLTV guide address from the M3U header."
                else
                    "Uses the existing XEEV custom playlist at /load/apk/{playlist ID}.",
                color = TextMuted, fontSize = 11.sp
            )
            Spacer(Modifier.height(18.dp))

            if (appCode.isBlank()) {
                Button(onClick = onRegister, enabled = !isBusy) { Text("Create app code") }
            } else {
                Text("APP CODE", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(appCode, color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onCheckPairing, enabled = !isBusy) { Text("Check pairing") }
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(appCode)) }) { Text("Copy code") }
                    OutlinedButton(onClick = onNewCode, enabled = !isBusy) { Text("New code") }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = autoPolling, onCheckedChange = onAutoPollingChanged, enabled = !paired)
                    Spacer(Modifier.width(8.dp))
                    Text("Auto-check every 20 seconds", color = TextMuted, fontSize = 13.sp)
                }
                if (paired) {
                    Text("Playlist ID received", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onLoadSelectedFormat, enabled = !isBusy) {
                            Text("Load ${playlistFormat.uppercase()} playlist")
                        }
                        if (playlistLoaded) OutlinedButton(onClick = onOpenTv) { Text("Open Live TV") }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(status, color = if (paired && playlistLoaded) Accent else Color(0xFFFFD28A), fontSize = 12.sp)
            if (isBusy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://xeev.app/")))
            }) { Text("Open XEEV website") }
            Spacer(Modifier.height(8.dp))
            Text("Device ID: $deviceId", color = TextMuted, fontSize = 10.sp)
            Text("Keep playlist IDs and stream URLs private; they may contain access credentials.", color = TextMuted, fontSize = 10.sp)
        }

        Card(
            Modifier.width(190.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                Modifier.padding(10.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (qrUrl.isNotBlank()) {
                    AsyncImage(model = qrUrl, contentDescription = "XEEV app code QR image", modifier = Modifier.size(156.dp))
                } else {
                    Box(
                        Modifier.size(156.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE9EDF3)),
                        contentAlignment = Alignment.Center
                    ) { Text("XEEV QR", color = Color.DarkGray, fontWeight = FontWeight.Bold) }
                }
                Text(if (appCode.isBlank()) "APP CODE QR" else appCode, color = Color(0xFF111827), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Use this code in XEEV Playlist Manager", color = Color(0xFF4B5563), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun TvHomeScreen(
    categories: List<PlaylistCategory>,
    channels: List<TvChannel>,
    epgByChannelId: Map<String, List<EpgProgramme>>,
    epgAvailable: Boolean,
    playlistFormat: String,
    selectedCategoryId: String,
    selectedChannel: TvChannel?,
    searchText: String,
    status: String,
    onCategorySelected: (String) -> Unit,
    onChannelSelected: (TvChannel) -> Unit,
    onSearchChanged: (String) -> Unit,
    onOpenPairing: () -> Unit,
    onRefresh: () -> Unit,
    isBusy: Boolean,
    isFullScreen: Boolean,
    onToggleFullScreen: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val categoryCounts = remember(categories, channels) { channels.groupingBy { it.categoryId }.eachCount() }
    val filtered = remember(channels, selectedCategoryId, searchText) {
        channels.filter { channel ->
            (selectedCategoryId == ALL_CATEGORY_ID || channel.categoryId == selectedCategoryId) &&
                (searchText.isBlank() || channel.name.contains(searchText, ignoreCase = true))
        }
    }

    val playerUiState = rememberTvPlayer(selectedChannel)
    if (isFullScreen && selectedChannel != null && playerUiState != null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            TvPlayer(playerUiState, true, onToggleFullScreen, Modifier.fillMaxSize())
        }
    } else {
    Column(Modifier.fillMaxSize().background(AppBackground)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).background(PanelBackground).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("XEEV", color = Accent, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(10.dp))
            Text("TV", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.width(24.dp))
            Text("LIVE · ${playlistFormat.uppercase()}", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("${channels.size} channels", color = TextMuted, fontSize = 12.sp)
            Spacer(Modifier.width(14.dp))
            TextButton(onClick = onRefresh, enabled = !isBusy) { Text("Refresh") }
            TextButton(onClick = onOpenPairing) { Text("Pairing") }
        }
        if (isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                Modifier.weight(0.22f).widthIn(min = 140.dp, max = 215.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                    .background(PanelBackground).padding(8.dp)
            ) {
                Text("CATEGORIES", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item {
                        CategoryRow("All channels", channels.size, selectedCategoryId == ALL_CATEGORY_ID) {
                            onCategorySelected(ALL_CATEGORY_ID)
                        }
                    }
                    items(categories, key = { it.id }) { category ->
                        CategoryRow(category.name, categoryCounts[category.id] ?: 0, selectedCategoryId == category.id) {
                            onCategorySelected(category.id)
                        }
                    }
                }
            }
            Column(
                Modifier.weight(0.30f).widthIn(min = 185.dp, max = 300.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                    .background(PanelBackground).padding(8.dp)
            ) {
                Text("CHANNELS", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = searchText,
                    onValueChange = onSearchChanged,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search channels", fontSize = 12.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text("${filtered.size} results", color = TextMuted, fontSize = 10.sp)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filtered, key = { it.recordId.ifBlank { it.playbackUrl } }) { channel ->
                        ChannelRow(channel, selectedChannel?.playbackUrl == channel.playbackUrl) {
                            onChannelSelected(channel)
                        }
                    }
                }
            }
            Column(
                Modifier.weight(0.48f).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                    .background(PanelBackground).padding(12.dp)
            ) {
                if (selectedChannel == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Choose a channel to start watching", color = TextMuted, fontSize = 17.sp)
                    }
                } else {
                    Text(selectedChannel.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    if (playerUiState != null) {
                        TvPlayer(playerUiState, false, onToggleFullScreen, Modifier.weight(1f).fillMaxWidth())
                    }
                    Spacer(Modifier.height(6.dp))
                    EpgProgrammePanel(
                        channel = selectedChannel,
                        programmes = epgByChannelId[selectedChannel.epgChannelId.orEmpty().trim().uppercase(Locale.ROOT)].orEmpty(),
                        epgAvailable = epgAvailable,
                        now = now
                    )
                    Spacer(Modifier.height(6.dp))
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PanelRaised)
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("PLAYBACK URL", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = { clipboard.setText(AnnotatedString(selectedChannel.playbackUrl)) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) { Text("Copy URL", fontSize = 10.sp) }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            Text(selectedChannel.playbackUrl, color = TextPrimary, fontSize = 9.sp, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("LIVE", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(10.dp))
                        Text(categories.firstOrNull { it.id == selectedChannel.categoryId }?.name ?: "TV", color = TextMuted, fontSize = 12.sp)
                        selectedChannel.channelNumber?.let {
                            Spacer(Modifier.width(10.dp))
                            Text("CH $it", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(status, color = TextMuted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    }
}

@Composable
private fun EpgProgrammePanel(
    channel: TvChannel,
    programmes: List<EpgProgramme>,
    epgAvailable: Boolean,
    now: Long
) {
    val current = remember(programmes, now) {
        programmes.firstOrNull { now >= it.startTimeMillis && now < it.stopTimeMillis }
    }
    val next = remember(programmes, now) { programmes.firstOrNull { it.startTimeMillis > now } }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PanelRaised)
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        when {
            !epgAvailable -> Text("EPG not supplied by this playlist", color = TextMuted, fontSize = 10.sp)
            channel.epgChannelId.isNullOrBlank() -> Text("No EPG channel ID for this channel", color = TextMuted, fontSize = 10.sp)
            current != null -> {
                Text("NOW  ${current.title}", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                current.description?.let {
                    Text(it, color = TextMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val fraction = ((now - current.startTimeMillis).toFloat() /
                    (current.stopTimeMillis - current.startTimeMillis).toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = fraction, modifier = Modifier.fillMaxWidth().height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${timeFormat.format(Date(current.startTimeMillis))}–${timeFormat.format(Date(current.stopTimeMillis))}",
                        color = TextMuted, fontSize = 9.sp
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(next?.let { "NEXT  ${it.title}" } ?: "No further programme listed", color = TextMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            next != null -> Text(
                "NEXT  ${timeFormat.format(Date(next.startTimeMillis))}  ${next.title}",
                color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            else -> Text("No current programme information for ${channel.epgChannelId}", color = TextMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun CategoryRow(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFF254337) else Color.Transparent)
            .clickable(onClick = onClick).focusable().padding(horizontal = 7.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, Modifier.weight(1f), color = if (selected) Accent else TextPrimary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(count.toString(), color = TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun ChannelRow(channel: TvChannel, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFF26364A) else Color.Transparent)
            .clickable(onClick = onClick).focusable().padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!channel.logoUrl.isNullOrBlank()) {
            AsyncImage(model = channel.logoUrl, contentDescription = null, modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)))
        } else {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(PanelRaised), contentAlignment = Alignment.Center) {
                Text(channel.name.take(1), color = Accent, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            val label = channel.channelNumber?.let { "$it · ${channel.name}" } ?: channel.name
            Text(label, color = if (selected) Accent else TextPrimary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(channel.epgChannelId.orEmpty(), color = TextMuted, fontSize = 9.sp, maxLines = 1)
        }
    }
}

private data class TvPlayerUiState(val player: ExoPlayer, val playerError: String?, val playerStatus: String, val trackStatus: String)
@Composable
@OptIn(androidx.media3.common.util.UnstableApi::class)
private fun rememberTvPlayer(channel: TvChannel?): TvPlayerUiState? {
    val context = LocalContext.current
    var playerError by remember(channel?.playbackUrl) { mutableStateOf<String?>(null) }
    var playerStatus by remember(channel?.playbackUrl) { mutableStateOf(if (channel == null) "No channel selected" else "Preparing MPEG-TS stream…") }
    var trackStatus by remember(channel?.playbackUrl) { mutableStateOf("Waiting for audio/video tracks") }
    val player = remember(channel?.playbackUrl) {
        channel?.let { selectedChannel ->
            val dataSourceFactory = DefaultHttpDataSource.Factory().setUserAgent("XeevTV/1.0 (Android)")
                .setDefaultRequestProperties(mapOf("Accept" to "*/*")).setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(20_000).setReadTimeoutMs(30_000)
            val mediaItem = MediaItem.Builder().setUri(selectedChannel.playbackUrl).setMimeType(MimeTypes.VIDEO_MP2T).build()
            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        playerStatus = when (playbackState) {
                            Player.STATE_IDLE -> "Idle"
                            Player.STATE_BUFFERING -> "Buffering stream…"
                            Player.STATE_READY -> "Player ready"
                            Player.STATE_ENDED -> "Stream ended"
                            else -> "Player state: $playbackState"
                        }
                    }
                    override fun onTracksChanged(tracks: Tracks) {
                        val hasVideo = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
                        val hasAudio = tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }
                        trackStatus = when {
                            hasVideo && hasAudio -> "Video and audio tracks detected"
                            hasVideo -> "Video track detected · no audio track detected"
                            hasAudio -> "Audio track detected · no video track detected"
                            else -> "No audio/video tracks detected yet"
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        val detail = error.message ?: error.cause?.message
                        playerError = if (detail.isNullOrBlank()) error.errorCodeName else "${error.errorCodeName}: $detail"
                        playerStatus = "Playback failed"
                    }
                })
                setMediaSource(mediaSource); playWhenReady = true; prepare()
            }
        }
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    return player?.let { TvPlayerUiState(it, playerError, playerStatus, trackStatus) }
}
@Composable
@OptIn(androidx.media3.common.util.UnstableApi::class)
private fun TvPlayer(uiState: TvPlayerUiState, isFullScreen: Boolean, onToggleFullScreen: () -> Unit, modifier: Modifier = Modifier) {
    val player = uiState.player
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player; useController = true; keepScreenOn = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setUseArtwork(false)
                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            }
        }, update = { it.player = player; it.setUseArtwork(false) }, modifier = Modifier.fillMaxSize())
        if (isFullScreen) {
            Button(onClick = onToggleFullScreen, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) { Text("Back to Live TV") }
        } else {
            OutlinedButton(onClick = onToggleFullScreen, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) { Text("Full screen") }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xB8000000)).padding(horizontal = 9.dp, vertical = 6.dp)) {
            if (uiState.playerError != null) {
                Text("PLAYBACK ERROR: ${uiState.playerError}", color = Color(0xFFFFB4AB), fontSize = 10.sp, maxLines = 3)
                TextButton(onClick = { player.seekToDefaultPosition(); player.prepare(); player.playWhenReady = true },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("Retry playback", fontSize = 10.sp) }
            } else Text(uiState.playerStatus, color = Color.White, fontSize = 10.sp, maxLines = 1)
            Text(uiState.trackStatus, color = Color(0xFFD0D8E4), fontSize = 9.sp, maxLines = 1)
        }
    }
}

private const val ALL_CATEGORY_ID = "__all__"
