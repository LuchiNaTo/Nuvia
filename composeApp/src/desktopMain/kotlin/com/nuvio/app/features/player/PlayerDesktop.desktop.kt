package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.nuvio.app.LocalDesktopWindow
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncFloat
import com.nuvio.app.core.sync.decodeSyncInt
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.decodeSyncStringSet
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncFloat
import com.nuvio.app.core.sync.encodeSyncInt
import com.nuvio.app.core.sync.encodeSyncString
import com.nuvio.app.core.sync.encodeSyncStringSet
import com.nuvio.app.desktop.DesktopPaths
import com.nuvio.app.desktop.DesktopRuntimeDiagnostics
import com.nuvio.app.desktop.DesktopPreferences
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Component
import java.awt.Panel
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlinx.coroutines.CancellationException
import com.nuvio.app.core.ui.LocalAmoledEnabled
import com.nuvio.app.core.ui.LocalAppTheme
import com.nuvio.app.core.ui.NuvioTheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.MpvMediampPlayerInitOptions
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.metadata.AudioTrack as MediampAudioTrack
import org.openani.mediamp.metadata.SubtitleTrack as MediampSubtitleTrack
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.Subtitle as MediampSubtitleFile
import org.openani.mediamp.source.UriMediaData
import org.openani.mediamp.vlc.VlcMediampPlayer

private val isMacOS: Boolean by lazy {
    System.getProperty("os.name")?.lowercase()?.contains("mac") == true
}

private val hasWindowsNativeBridge: Boolean
    get() = !isMacOS && WindowsDesktopMPVBridgeLib.isAvailable

private const val DESKTOP_PLAYBACK_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

private const val VLC_RUNTIME_NOT_FOUND_MESSAGE =
    "VLC runtime not found / install VLC 64-bit or configure bundled VLC runtime."

private enum class WindowsDesktopBackend(val logName: String) {
    VLC("windows-mediamp-vlc"),
    MPV("windows-mediamp-mpv"),
    NATIVE("windows-native-bridge"),
}

private data class ResolvedWindowsDesktopBackend(
    val backend: WindowsDesktopBackend,
    val source: String,
)

private fun resolveWindowsDesktopBackend(): ResolvedWindowsDesktopBackend {
    val explicitBackend = (System.getProperty("nuvio.desktopPlayerBackend")
        ?: System.getenv("NUVIO_DESKTOP_PLAYER_BACKEND"))
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf(String::isNotEmpty)

    return when (explicitBackend) {
        "vlc" -> ResolvedWindowsDesktopBackend(WindowsDesktopBackend.VLC, "desktopPlayerBackend")
        "mpv" -> ResolvedWindowsDesktopBackend(WindowsDesktopBackend.MPV, "desktopPlayerBackend")
        "native" -> ResolvedWindowsDesktopBackend(WindowsDesktopBackend.NATIVE, "desktopPlayerBackend")
        else -> {
            val nativeBridgeCompatibilityEnabled =
                (System.getProperty("nuvio.enableNativeBridge")
                    ?: System.getenv("NUVIO_ENABLE_NATIVE_BRIDGE"))
                    ?.toBooleanStrictOrNull() == true
            if (nativeBridgeCompatibilityEnabled) {
                ResolvedWindowsDesktopBackend(WindowsDesktopBackend.NATIVE, "nativeBridgeAlias")
            } else {
                ResolvedWindowsDesktopBackend(WindowsDesktopBackend.VLC, "default")
            }
        }
    }
}

val activeVlcOverlayContainerState = mutableStateOf<JLayeredPane?>(null)

@Composable
actual fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    useNativeController: Boolean,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val resolvedWindowsBackend = remember {
        if (isMacOS) null else resolveWindowsDesktopBackend()
    }

    LaunchedEffect(isMacOS, resolvedWindowsBackend?.backend, resolvedWindowsBackend?.source) {
        val selectedBackend = if (isMacOS) {
            "macos-native-bridge"
        } else {
            resolvedWindowsBackend?.backend?.logName ?: WindowsDesktopBackend.VLC.logName
        }
        DesktopRuntimeDiagnostics.info(
            tag = "PlayerDesktop",
            message = buildString {
                append("Selected player backend=")
                append(selectedBackend)
                if (!isMacOS && resolvedWindowsBackend != null) {
                    append(" (source=")
                    append(resolvedWindowsBackend.source)
                    append(')')
                }
            },
        )
    }

    if (isMacOS) {
        MacOSPlayerSurface(
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl,
            sourceHeaders = sourceHeaders,
            sourceResponseHeaders = sourceResponseHeaders,
            useYoutubeChunkedPlayback = useYoutubeChunkedPlayback,
            modifier = modifier,
            playWhenReady = playWhenReady,
            resizeMode = resizeMode,
            useNativeController = useNativeController,
            onControllerReady = onControllerReady,
            onSnapshot = onSnapshot,
            onError = onError,
        )
    } else {
        when (resolvedWindowsBackend?.backend ?: WindowsDesktopBackend.VLC) {
            WindowsDesktopBackend.VLC -> {
                WindowsVlcPlayerSurface(
                    sourceUrl = sourceUrl,
                    sourceAudioUrl = sourceAudioUrl,
                    sourceHeaders = sourceHeaders,
                    modifier = modifier,
                    playWhenReady = playWhenReady,
                    resizeMode = resizeMode,
                    onControllerReady = onControllerReady,
                    onSnapshot = onSnapshot,
                    onError = onError,
                )
            }

            WindowsDesktopBackend.MPV -> {
                WindowsMpvPlayerSurface(
                    sourceUrl = sourceUrl,
                    sourceAudioUrl = sourceAudioUrl,
                    sourceHeaders = sourceHeaders,
                    modifier = modifier,
                    playWhenReady = playWhenReady,
                    resizeMode = resizeMode,
                    onControllerReady = onControllerReady,
                    onSnapshot = onSnapshot,
                    onError = onError,
                )
            }

            WindowsDesktopBackend.NATIVE -> if (hasWindowsNativeBridge) {
                WindowsNativePlayerSurface(
                    sourceUrl = sourceUrl,
                    sourceAudioUrl = sourceAudioUrl,
                    sourceHeaders = sourceHeaders,
                    sourceResponseHeaders = sourceResponseHeaders,
                    useYoutubeChunkedPlayback = useYoutubeChunkedPlayback,
                    modifier = modifier,
                    playWhenReady = playWhenReady,
                    resizeMode = resizeMode,
                    useNativeController = useNativeController,
                    onControllerReady = onControllerReady,
                    onSnapshot = onSnapshot,
                    onError = onError,
                )
            } else {
                DesktopControlledErrorSurface(
                    modifier = modifier,
                    errorMessage = "Windows native bridge requested but unavailable.",
                    onControllerReady = onControllerReady,
                    onSnapshot = onSnapshot,
                    onError = onError,
                )
            }
        }
    }
}

private data class WindowsBridgePollState(
    val isClosed: Boolean,
    val snapshot: PlayerPlaybackSnapshot,
    val error: String?,
    val addonSubtitlesFetchRequested: Boolean,
    val subtitleStyleChanged: Boolean,
    val subtitleStyleColorIndex: Int,
    val subtitleStyleOutlineEnabled: Boolean,
    val subtitleStyleFontSize: Int,
    val subtitleStyleBottomOffset: Int,
    val nextEpisodePressed: Boolean,
    val sourcesOpenRequested: Boolean,
    val episodesOpenRequested: Boolean,
    val selectedSourceUrl: String?,
    val sourceFilterChanged: Boolean,
    val sourceFilterValue: String?,
    val sourceReloadRequested: Boolean,
    val selectedEpisodeId: String?,
    val selectedEpisodeStreamUrl: String?,
    val episodeFilterChanged: Boolean,
    val episodeFilterValue: String?,
    val episodeReloadRequested: Boolean,
    val episodeBackRequested: Boolean,
)

@Composable
private fun WindowsNativePlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    useNativeController: Boolean,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val bridge = remember { WindowsDesktopMPVBridgeLib.loadOrNull() }
    if (bridge == null) {
        DesktopControlledErrorSurface(
            modifier = modifier,
            errorMessage = "Windows native bridge requested but unavailable.",
            onControllerReady = onControllerReady,
            onSnapshot = onSnapshot,
            onError = onError,
        )
        return
    }
    val desktopWindow = LocalDesktopWindow.current
    val playerPtr = remember { bridge.nuvio_player_create() }
    val playerAttached = remember(playerPtr) { booleanArrayOf(false) }
    val lastBounds = remember(playerPtr) { arrayOfNulls<Rect>(1) }
    var onCloseCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onAddonSubtitlesFetchCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onSourcesRequestedCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onSourceStreamSelectedCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var onSourceFilterChangedCallback by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    var onSourceReloadCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onEpisodesRequestedCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onEpisodeSelectedCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var onEpisodeStreamSelectedCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var onEpisodeFilterChangedCallback by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    var onEpisodeReloadCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onEpisodeBackCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

    DisposableEffect(playerPtr) {
        onDispose {
            bridge.nuvio_player_destroy(playerPtr)
        }
    }

    LaunchedEffect(desktopWindow, playerPtr) {
        val window = desktopWindow ?: return@LaunchedEffect
        val nativePtr = Native.getComponentPointer(window) ?: return@LaunchedEffect
        bridge.nuvio_player_show(playerPtr, Pointer.nativeValue(nativePtr))
        playerAttached[0] = true
    }

    LaunchedEffect(sourceUrl, sourceAudioUrl) {
        val headersJson = if (sourceHeaders.isNotEmpty()) {
            buildJsonObject {
                sourceHeaders.forEach { (key, value) -> put(key, value) }
            }.toString()
        } else null
        bridge.nuvio_player_load_file(playerPtr, sourceUrl, sourceAudioUrl, headersJson)
        if (playWhenReady) {
            bridge.nuvio_player_play(playerPtr)
        }
    }

    LaunchedEffect(playWhenReady) {
        if (playWhenReady) bridge.nuvio_player_play(playerPtr) else bridge.nuvio_player_pause(playerPtr)
    }

    LaunchedEffect(resizeMode) {
        val mode = when (resizeMode) {
            PlayerResizeMode.Fit -> 0
            PlayerResizeMode.Fill -> 1
            PlayerResizeMode.Zoom -> 2
        }
        bridge.nuvio_player_set_resize_mode(playerPtr, mode)
    }

    val controller = remember(playerPtr) {
        object : PlayerEngineController {
            override fun play() = bridge.nuvio_player_play(playerPtr)
            override fun pause() = bridge.nuvio_player_pause(playerPtr)
            override fun seekTo(positionMs: Long) = bridge.nuvio_player_seek_to(playerPtr, positionMs)
            override fun seekBy(offsetMs: Long) = bridge.nuvio_player_seek_by(playerPtr, offsetMs)
            override fun retry() = bridge.nuvio_player_retry(playerPtr)
            override fun setPlaybackSpeed(speed: Float) = bridge.nuvio_player_set_speed(playerPtr, speed)
            override fun getAudioTracks(): List<AudioTrack> {
                val count = bridge.nuvio_player_get_audio_track_count(playerPtr)
                return (0 until count).map { index ->
                    AudioTrack(
                        index = index,
                        id = bridge.nuvio_player_get_audio_track_id(playerPtr, index).toString(),
                        label = bridge.nuvio_player_get_audio_track_label(playerPtr, index) ?: "",
                        language = bridge.nuvio_player_get_audio_track_lang(playerPtr, index),
                        isSelected = bridge.nuvio_player_is_audio_track_selected(playerPtr, index),
                    )
                }
            }
            override fun getSubtitleTracks(): List<SubtitleTrack> {
                val count = bridge.nuvio_player_get_subtitle_track_count(playerPtr)
                return (0 until count).map { index ->
                    SubtitleTrack(
                        index = index,
                        id = bridge.nuvio_player_get_subtitle_track_id(playerPtr, index).toString(),
                        label = bridge.nuvio_player_get_subtitle_track_label(playerPtr, index) ?: "",
                        language = bridge.nuvio_player_get_subtitle_track_lang(playerPtr, index),
                        isSelected = bridge.nuvio_player_is_subtitle_track_selected(playerPtr, index),
                    )
                }
            }
            override fun selectAudioTrack(index: Int) {
                val count = bridge.nuvio_player_get_audio_track_count(playerPtr)
                if (index in 0 until count) {
                    bridge.nuvio_player_select_audio_track(playerPtr, bridge.nuvio_player_get_audio_track_id(playerPtr, index))
                }
            }
            override fun selectSubtitleTrack(index: Int) {
                if (index < 0) {
                    bridge.nuvio_player_select_subtitle_track(playerPtr, -1)
                    return
                }
                val count = bridge.nuvio_player_get_subtitle_track_count(playerPtr)
                if (index in 0 until count) {
                    bridge.nuvio_player_select_subtitle_track(playerPtr, bridge.nuvio_player_get_subtitle_track_id(playerPtr, index))
                }
            }
            override fun setSubtitleUri(url: String) = bridge.nuvio_player_set_subtitle_url(playerPtr, url)
            override fun clearExternalSubtitle() = bridge.nuvio_player_clear_external_subtitle(playerPtr)
            override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
                val trackId = if (trackIndex >= 0) {
                    val count = bridge.nuvio_player_get_subtitle_track_count(playerPtr)
                    if (trackIndex < count) bridge.nuvio_player_get_subtitle_track_id(playerPtr, trackIndex) else -1
                } else -1
                bridge.nuvio_player_clear_external_subtitle_and_select(playerPtr, trackId)
            }
            override fun applySubtitleStyle(style: SubtitleStyleState) {
                val colorHex = style.textColor.toMpvColorString()
                val outline = if (style.outlineEnabled) 2.0f else 0.0f
                val subPos = 100 - style.bottomOffset
                bridge.nuvio_player_apply_subtitle_style(playerPtr, colorHex, outline, style.fontSizeSp.toFloat(), subPos)
            }
            override fun setMetadata(title: String, streamTitle: String, providerName: String, seasonNumber: Int?, episodeNumber: Int?, episodeTitle: String?, artwork: String?, logo: String?) {
                bridge.nuvio_player_set_metadata(playerPtr, title, streamTitle, providerName, seasonNumber ?: 0, episodeNumber ?: 0, episodeTitle, artwork, logo)
            }
            override fun setPlayerFlags(hasVideoId: Boolean, isSeries: Boolean) {
                bridge.nuvio_player_set_has_video_id(playerPtr, hasVideoId)
                bridge.nuvio_player_set_is_series(playerPtr, isSeries)
            }
            override fun showSkipButton(type: String, endTimeMs: Long) = bridge.nuvio_player_show_skip_button(playerPtr, type, endTimeMs)
            override fun hideSkipButton() = bridge.nuvio_player_hide_skip_button(playerPtr)
            override fun showNextEpisode(season: Int, episode: Int, title: String, thumbnail: String?, hasAired: Boolean) = bridge.nuvio_player_show_next_episode(playerPtr, season, episode, title, thumbnail, hasAired)
            override fun hideNextEpisode() = bridge.nuvio_player_hide_next_episode(playerPtr)
            override fun setOnCloseCallback(callback: () -> Unit) { onCloseCallback = callback }
            override fun setOnAddonSubtitlesFetchCallback(callback: () -> Unit) { onAddonSubtitlesFetchCallback = callback }
            override fun pushAddonSubtitles(subtitles: List<AddonSubtitle>, isLoading: Boolean) {
                bridge.nuvio_player_set_addon_subtitles_loading(playerPtr, isLoading)
                if (!isLoading) {
                    bridge.nuvio_player_clear_addon_subtitles(playerPtr)
                    subtitles.forEach { addon -> bridge.nuvio_player_add_addon_subtitle(playerPtr, addon.id, addon.url, addon.language, addon.display) }
                }
            }
            override fun setOnSourcesRequestedCallback(callback: () -> Unit) { onSourcesRequestedCallback = callback }
            override fun setOnSourceStreamSelectedCallback(callback: (String) -> Unit) { onSourceStreamSelectedCallback = callback }
            override fun setOnSourceFilterChangedCallback(callback: (String?) -> Unit) { onSourceFilterChangedCallback = callback }
            override fun setOnSourceReloadCallback(callback: () -> Unit) { onSourceReloadCallback = callback }
            override fun setOnEpisodesRequestedCallback(callback: () -> Unit) { onEpisodesRequestedCallback = callback }
            override fun setOnEpisodeSelectedCallback(callback: (String) -> Unit) { onEpisodeSelectedCallback = callback }
            override fun setOnEpisodeStreamSelectedCallback(callback: (String) -> Unit) { onEpisodeStreamSelectedCallback = callback }
            override fun setOnEpisodeFilterChangedCallback(callback: (String?) -> Unit) { onEpisodeFilterChangedCallback = callback }
            override fun setOnEpisodeReloadCallback(callback: () -> Unit) { onEpisodeReloadCallback = callback }
            override fun setOnEpisodeBackCallback(callback: () -> Unit) { onEpisodeBackCallback = callback }
            override fun pushSourceData(streams: List<StreamItem>, groups: List<AddonStreamGroup>, loading: Boolean, selectedFilter: String?, currentStreamUrl: String?) {
                bridge.nuvio_player_set_sources_loading(playerPtr, loading)
                bridge.nuvio_player_set_source_selected_filter(playerPtr, selectedFilter)
                bridge.nuvio_player_clear_source_addon_groups(playerPtr)
                groups.forEach { group -> bridge.nuvio_player_add_source_addon_group(playerPtr, group.addonId, group.addonName, group.addonId, group.isLoading, group.error != null) }
                bridge.nuvio_player_clear_source_streams(playerPtr)
                streams.forEach { stream -> bridge.nuvio_player_add_source_stream(playerPtr, stream.addonId + "_" + (stream.url ?: stream.infoHash ?: ""), stream.streamLabel, stream.streamSubtitle, stream.addonName, stream.addonId, stream.directPlaybackUrl ?: "", stream.directPlaybackUrl == currentStreamUrl) }
            }
            override fun pushEpisodes(episodes: List<MetaVideo>) {
                bridge.nuvio_player_clear_episodes(playerPtr)
                episodes.forEach { episode -> bridge.nuvio_player_add_episode(playerPtr, episode.id, episode.title, episode.overview, episode.thumbnail, episode.season ?: 0, episode.episode ?: 0) }
            }
            override fun pushEpisodeStreamsData(streams: List<StreamItem>, groups: List<AddonStreamGroup>, loading: Boolean, selectedFilter: String?, currentStreamUrl: String?) {
                bridge.nuvio_player_set_episode_streams_loading(playerPtr, loading)
                bridge.nuvio_player_set_episode_selected_filter(playerPtr, selectedFilter)
                bridge.nuvio_player_clear_episode_addon_groups(playerPtr)
                groups.forEach { group -> bridge.nuvio_player_add_episode_addon_group(playerPtr, group.addonId, group.addonName, group.addonId, group.isLoading, group.error != null) }
                bridge.nuvio_player_clear_episode_streams(playerPtr)
                streams.forEach { stream -> bridge.nuvio_player_add_episode_stream(playerPtr, stream.addonId + "_" + (stream.url ?: stream.infoHash ?: ""), stream.streamLabel, stream.streamSubtitle, stream.addonName, stream.addonId, stream.directPlaybackUrl ?: "", stream.directPlaybackUrl == currentStreamUrl) }
            }
            override fun showEpisodeStreamsView(season: Int?, episode: Int?, title: String?) = bridge.nuvio_player_show_episode_streams(playerPtr, season ?: 0, episode ?: 0, title)
            override fun switchSource(url: String, audioUrl: String?, headersJson: String?) = bridge.nuvio_player_load_file(playerPtr, url, audioUrl, headersJson)
        }
    }

    LaunchedEffect(controller) {
        onControllerReady(controller)
    }

    LaunchedEffect(playerPtr) {
        while (true) {
            delay(250)
            val pollState = withContext(Dispatchers.IO) {
                bridge.nuvio_player_refresh_state(playerPtr)
                WindowsBridgePollState(
                    isClosed = bridge.nuvio_player_is_closed(playerPtr),
                    snapshot = PlayerPlaybackSnapshot(
                        isLoading = bridge.nuvio_player_is_loading(playerPtr),
                        isPlaying = bridge.nuvio_player_is_playing(playerPtr),
                        isEnded = bridge.nuvio_player_is_ended(playerPtr),
                        positionMs = bridge.nuvio_player_get_position_ms(playerPtr),
                        durationMs = bridge.nuvio_player_get_duration_ms(playerPtr),
                        bufferedPositionMs = bridge.nuvio_player_get_buffered_ms(playerPtr),
                        playbackSpeed = bridge.nuvio_player_get_speed(playerPtr),
                    ),
                    error = bridge.nuvio_player_get_error(playerPtr),
                    addonSubtitlesFetchRequested = bridge.nuvio_player_is_addon_subtitles_fetch_requested(playerPtr),
                    subtitleStyleChanged = bridge.nuvio_player_pop_subtitle_style_changed(playerPtr),
                    subtitleStyleColorIndex = bridge.nuvio_player_get_subtitle_style_color_index(playerPtr),
                    subtitleStyleOutlineEnabled = bridge.nuvio_player_get_subtitle_style_outline_enabled(playerPtr),
                    subtitleStyleFontSize = bridge.nuvio_player_get_subtitle_style_font_size(playerPtr),
                    subtitleStyleBottomOffset = bridge.nuvio_player_get_subtitle_style_bottom_offset(playerPtr),
                    nextEpisodePressed = bridge.nuvio_player_pop_next_episode_pressed(playerPtr),
                    sourcesOpenRequested = bridge.nuvio_player_pop_sources_open_requested(playerPtr),
                    episodesOpenRequested = bridge.nuvio_player_pop_episodes_open_requested(playerPtr),
                    selectedSourceUrl = bridge.nuvio_player_pop_source_stream_selected(playerPtr),
                    sourceFilterChanged = bridge.nuvio_player_pop_source_filter_changed(playerPtr),
                    sourceFilterValue = bridge.nuvio_player_get_source_filter_value(playerPtr),
                    sourceReloadRequested = bridge.nuvio_player_pop_source_reload(playerPtr),
                    selectedEpisodeId = bridge.nuvio_player_pop_episode_selected(playerPtr),
                    selectedEpisodeStreamUrl = bridge.nuvio_player_pop_episode_stream_selected(playerPtr),
                    episodeFilterChanged = bridge.nuvio_player_pop_episode_filter_changed(playerPtr),
                    episodeFilterValue = bridge.nuvio_player_get_episode_filter_value(playerPtr),
                    episodeReloadRequested = bridge.nuvio_player_pop_episode_reload(playerPtr),
                    episodeBackRequested = bridge.nuvio_player_pop_episode_back(playerPtr),
                )
            }
            if (pollState.isClosed) {
                onCloseCallback?.invoke()
                break
            }
            onSnapshot(pollState.snapshot)
            onError(pollState.error)
            if (pollState.addonSubtitlesFetchRequested) onAddonSubtitlesFetchCallback?.invoke()
            if (pollState.subtitleStyleChanged) {
                val colorIndex = pollState.subtitleStyleColorIndex.coerceIn(0, SubtitleColorSwatches.lastIndex)
                PlayerSettingsRepository.setSubtitleStyle(
                    SubtitleStyleState(
                        textColor = SubtitleColorSwatches[colorIndex],
                        outlineEnabled = pollState.subtitleStyleOutlineEnabled,
                        fontSizeSp = pollState.subtitleStyleFontSize,
                        bottomOffset = pollState.subtitleStyleBottomOffset,
                    ),
                )
            }
            if (pollState.sourcesOpenRequested) onSourcesRequestedCallback?.invoke()
            if (pollState.episodesOpenRequested) onEpisodesRequestedCallback?.invoke()
            pollState.selectedSourceUrl?.let { onSourceStreamSelectedCallback?.invoke(it) }
            if (pollState.sourceFilterChanged) onSourceFilterChangedCallback?.invoke(pollState.sourceFilterValue)
            if (pollState.sourceReloadRequested) onSourceReloadCallback?.invoke()
            pollState.selectedEpisodeId?.let { onEpisodeSelectedCallback?.invoke(it) }
            pollState.selectedEpisodeStreamUrl?.let { onEpisodeStreamSelectedCallback?.invoke(it) }
            if (pollState.episodeFilterChanged) onEpisodeFilterChangedCallback?.invoke(pollState.episodeFilterValue)
            if (pollState.episodeReloadRequested) onEpisodeReloadCallback?.invoke()
            if (pollState.episodeBackRequested) onEpisodeBackCallback?.invoke()
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .onGloballyPositioned { coordinates ->
                if (!playerAttached[0]) return@onGloballyPositioned
                val bounds = coordinates.boundsInWindow()
                if (lastBounds[0] == bounds) return@onGloballyPositioned
                lastBounds[0] = bounds
                bridge.nuvio_player_set_bounds(
                    playerPtr,
                    bounds.left.toInt(),
                    bounds.top.toInt(),
                    bounds.width.toInt().coerceAtLeast(1),
                    bounds.height.toInt().coerceAtLeast(1),
                )
            },
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// Windows: mediamp-mpv backed inline Compose rendering
// ──────────────────────────────────────────────────────────────────────────────

private data class WindowsMpvSession(
    val player: MpvMediampPlayer,
    val handle: MPVHandle,
    val renderHost: JPanel,
    val renderSurface: Component,
)

private fun closeWindowsMpvSessionAsync(session: WindowsMpvSession?) {
    if (session == null) return
    Thread(
        {
            runCatching { detachMpvRenderSurface(session.handle) }
            runCatching { session.player.close() }
        },
        "Nuvio-mpv-close",
    ).apply {
        isDaemon = true
        start()
    }
}

private object DesktopPlayerGestureBridge {
    private val lock = Any()
    private var token: Any? = null
    private var currentVolumeProvider: (() -> PlayerAudioLevel?)? = null
    private var setVolumeProvider: ((Float) -> PlayerAudioLevel?)? = null

    fun register(
        token: Any,
        currentVolumeProvider: () -> PlayerAudioLevel?,
        setVolumeProvider: (Float) -> PlayerAudioLevel?,
    ) {
        synchronized(lock) {
            this.token = token
            this.currentVolumeProvider = currentVolumeProvider
            this.setVolumeProvider = setVolumeProvider
        }
    }

    fun unregister(token: Any) {
        synchronized(lock) {
            if (this.token != token) return
            this.token = null
            currentVolumeProvider = null
            setVolumeProvider = null
        }
    }

    fun currentVolume(): PlayerAudioLevel? {
        val provider = synchronized(lock) { currentVolumeProvider }
        return provider?.invoke()
    }

    fun setVolume(level: Float): PlayerAudioLevel? {
        val provider = synchronized(lock) { setVolumeProvider }
        return provider?.invoke(level.coerceIn(0f, 1f))
    }
}

private object DesktopPlayerGestureController : PlayerGestureController {
    override fun currentBrightness(): Float? = null

    override fun setBrightness(level: Float): Float? = null

    override fun currentVolume(): PlayerAudioLevel? =
        DesktopPlayerGestureBridge.currentVolume()

    override fun setVolume(level: Float): PlayerAudioLevel? =
        DesktopPlayerGestureBridge.setVolume(level)
}

@Composable
private fun DesktopControlledErrorSurface(
    modifier: Modifier,
    errorMessage: String,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val currentOnControllerReady by rememberUpdatedState(onControllerReady)
    val currentOnSnapshot by rememberUpdatedState(onSnapshot)
    val currentOnError by rememberUpdatedState(onError)

    LaunchedEffect(errorMessage) {
        DesktopRuntimeDiagnostics.warn(
            tag = "PlayerDesktop",
            message = "Player fallback/error UI path triggered: $errorMessage",
        )
        currentOnControllerReady(NoOpPlayerEngineController)
        currentOnSnapshot(PlayerPlaybackSnapshot())
        currentOnError(errorMessage)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    )
}

private object NoOpPlayerEngineController : PlayerEngineController {
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun seekBy(offsetMs: Long) = Unit
    override fun retry() = Unit
    override fun setPlaybackSpeed(speed: Float) = Unit
    override fun getAudioTracks(): List<AudioTrack> = emptyList()
    override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()
    override fun selectAudioTrack(index: Int) = Unit
    override fun selectSubtitleTrack(index: Int) = Unit
    override fun setSubtitleUri(url: String) = Unit
    override fun clearExternalSubtitle() = Unit
    override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
    override fun switchSource(url: String, audioUrl: String?, headersJson: String?) = Unit
}

private data class WindowsVlcMediaRequest(
    val url: String,
    val audioUrl: String?,
    val headers: Map<String, String>,
    val externalSubtitles: List<MediampSubtitleFile> = emptyList(),
    val reloadNonce: Int = 0,
)

private fun createWindowsVlcMediaRequest(
    url: String,
    audioUrl: String?,
    headers: Map<String, String>,
    externalSubtitles: List<MediampSubtitleFile> = emptyList(),
    reloadNonce: Int = 0,
): WindowsVlcMediaRequest = WindowsVlcMediaRequest(
    url = url,
    audioUrl = audioUrl,
    headers = headers.toMap(),
    externalSubtitles = externalSubtitles,
    reloadNonce = reloadNonce,
)

@OptIn(InternalMediampApi::class)
@Composable
private fun WindowsVlcPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val currentOnControllerReady by rememberUpdatedState(onControllerReady)
    val currentOnSnapshot by rememberUpdatedState(onSnapshot)
    val currentOnError by rememberUpdatedState(onError)
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var surfaceAttached by remember { mutableStateOf(false) }

    var fatalErrorMessage by remember { mutableStateOf<String?>(null) }
    var playerResult by remember { mutableStateOf<Result<VlcMediampPlayer>?>(null) }
    var pendingSelectedSubtitleId by remember { mutableStateOf<String?>(null) }
    var mediaRequest by remember(sourceUrl, sourceAudioUrl, sourceHeaders) {
        mutableStateOf(
            createWindowsVlcMediaRequest(
                url = sourceUrl,
                audioUrl = sourceAudioUrl,
                headers = sourceHeaders,
            ),
        )
    }
    val renderSurface = remember {
        Canvas().apply {
            background = java.awt.Color.BLACK
            foreground = java.awt.Color.BLACK
            isFocusable = false
            ignoreRepaint = true
        }
    }
    val renderHost = remember(renderSurface) {
        JPanel(BorderLayout()).apply {
            background = java.awt.Color.BLACK
            foreground = java.awt.Color.BLACK
            isOpaque = true
            add(renderSurface, BorderLayout.CENTER)
        }
    }

    val layeredPane = remember {
        object : JLayeredPane() {
            override fun doLayout() {
                super.doLayout()
                renderHost.setBounds(0, 0, width, height)
            }
        }.apply {
            isOpaque = false
            layout = null
        }
    }

    DisposableEffect(layeredPane) {
        DesktopRuntimeDiagnostics.info("PlayerDesktop", "WindowsVlcPlayerSurface: Initializing JLayeredPane")
        layeredPane.add(renderHost, java.lang.Integer(0)) // DEFAULT_LAYER
        activeVlcOverlayContainerState.value = layeredPane
        onDispose {
            DesktopRuntimeDiagnostics.info("PlayerDesktop", "WindowsVlcPlayerSurface: Disposing JLayeredPane")
            activeVlcOverlayContainerState.value = null
            layeredPane.remove(renderHost)
        }
    }

    fun reportFatalFailure(
        phase: String,
        throwable: Throwable,
        userFacingMessage: String = throwable.message?.takeIf(String::isNotBlank)
            ?: "Windows mediamp/vlc failed during $phase",
    ) {
        if (fatalErrorMessage == userFacingMessage) return

        DesktopRuntimeDiagnostics.error(
            tag = "PlayerDesktop",
            message = "Windows mediamp/vlc failure during $phase; switching to controlled error UI.",
            throwable = throwable,
        )
        fatalErrorMessage = userFacingMessage
        currentOnError(userFacingMessage)
    }

    val initializationFailure = playerResult?.exceptionOrNull()
    LaunchedEffect(initializationFailure) {
        initializationFailure?.let {
            reportFatalFailure("initialization", it, VLC_RUNTIME_NOT_FOUND_MESSAGE)
        }
    }

    LaunchedEffect(fatalErrorMessage) {
        if (fatalErrorMessage != null) {
            currentOnControllerReady(NoOpPlayerEngineController)
            currentOnSnapshot(PlayerPlaybackSnapshot())
        }
    }

    LaunchedEffect(fatalErrorMessage) {
        if (fatalErrorMessage != null || playerResult != null) return@LaunchedEffect

        val discoverySource = System.getProperty("compose.application.resources.dir")
            ?.takeIf(String::isNotBlank)
            ?.let { "compose.application.resources.dir=$it" }
            ?: "system-installed runtime discovery"

        playerResult = runCatching {
            DesktopRuntimeDiagnostics.info(
                tag = "PlayerDesktop",
                message = "Preparing VLC runtime using $discoverySource",
            )
            VlcMediampPlayer.prepareLibraries()
            DesktopRuntimeDiagnostics.info(
                tag = "PlayerDesktop",
                message = "VLC runtime discovery succeeded.",
            )
            VlcMediampPlayer(kotlin.coroutines.EmptyCoroutineContext)
        }
    }

    val player = playerResult?.getOrNull()
    val playerInstanceId = player?.let { Integer.toHexString(System.identityHashCode(it)) }
    if (fatalErrorMessage != null) {
        DesktopControlledErrorSurface(
            modifier = modifier,
            errorMessage = fatalErrorMessage ?: "Playback error",
            onControllerReady = onControllerReady,
            onSnapshot = onSnapshot,
            onError = onError,
        )
        return
    }

    if (player == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black),
        )
        return
    }

    DisposableEffect(player) {
        DesktopRuntimeDiagnostics.info(
            tag = "PlayerDesktop",
            message = "VLC player instance created id=${Integer.toHexString(System.identityHashCode(player))}",
        )
        val audioLevelController = player.features[AudioLevelController.Key]
        DesktopPlayerGestureBridge.register(
            token = player,
            currentVolumeProvider = {
                audioLevelController?.let {
                    PlayerAudioLevel(
                        fraction = it.volume.value.coerceIn(0f, 1f),
                        isMuted = it.isMute.value || it.volume.value <= 0f,
                    )
                }
            },
            setVolumeProvider = { level ->
                audioLevelController?.let {
                    it.setVolume(level.coerceIn(0f, 1f))
                    PlayerAudioLevel(
                        fraction = it.volume.value.coerceIn(0f, 1f),
                        isMuted = it.isMute.value || it.volume.value <= 0f,
                    )
                }
            },
        )
        onDispose {
            surfaceAttached = false
            DesktopPlayerGestureBridge.unregister(player)
            runCatching { player.close() }
                .onFailure { error ->
                    DesktopRuntimeDiagnostics.warn(
                        tag = "PlayerDesktop",
                        message = "Failed to close VLC player cleanly.",
                        throwable = error,
                    )
                }
        }
    }

    LaunchedEffect(player, renderSurface, fatalErrorMessage) {
        if (fatalErrorMessage != null || surfaceAttached) return@LaunchedEffect

        try {
            awaitVlcRenderSurfaceReadyOrThrow(renderSurface)
            player.attachNativeEmbeddedVideoSurface(renderSurface)
            surfaceAttached = true
            DesktopRuntimeDiagnostics.info(
                tag = "PlayerDesktop",
                message = "VLC native embedded surface attached. playerId=$playerInstanceId size=${renderSurface.width}x${renderSurface.height}",
            )
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            reportFatalFailure("native surface attach", e)
        }
    }

    LaunchedEffect(player, mediaRequest, surfaceAttached) {
        if (!surfaceAttached) return@LaunchedEffect

        try {
            currentOnError(null)

            val normalizedHeaders = mediaRequest.headers.toMutableMap()
            if (normalizedHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                normalizedHeaders["User-Agent"] = DESKTOP_PLAYBACK_USER_AGENT
            }

            if (!mediaRequest.audioUrl.isNullOrBlank()) {
                DesktopRuntimeDiagnostics.warn(
                    tag = "PlayerDesktop",
                    message = "VLC backend received a separate audio URL; external audio is not wired in this first pass.",
                )
            }

            DesktopRuntimeDiagnostics.info(
                tag = "PlayerDesktop",
                message = "Initializing mediamp/vlc playback path. playerId=$playerInstanceId, urlHost=${runCatching { URI(mediaRequest.url).host }.getOrNull() ?: "<unknown>"}, subtitles=${mediaRequest.externalSubtitles.size}",
            )
            player.setMediaData(
                UriMediaData(
                    uri = mediaRequest.url,
                    headers = normalizedHeaders,
                    extraFiles = MediaExtraFiles(mediaRequest.externalSubtitles),
                ),
            )
            if (playWhenReady) {
                DesktopRuntimeDiagnostics.info(
                    tag = "PlayerDesktop",
                    message = "Calling VLC player.resume() for playerId=$playerInstanceId",
                )
                player.resume()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            reportFatalFailure("media load", e)
        }
    }

    LaunchedEffect(player, playWhenReady, surfaceAttached) {
        if (!surfaceAttached) return@LaunchedEffect

        runCatching {
            val state = player.getCurrentPlaybackState()
            if (playWhenReady && (state == PlaybackState.READY || state == PlaybackState.PAUSED || state == PlaybackState.PAUSED_BUFFERING)) {
                player.resume()
            } else if (!playWhenReady && state == PlaybackState.PLAYING) {
                player.pause()
            }
        }.onFailure {
            reportFatalFailure("playback toggle", it)
        }
    }

    LaunchedEffect(player, resizeMode) {
        val aspectRatio = player.features[VideoAspectRatio.Key] ?: return@LaunchedEffect
        val mode = when (resizeMode) {
            PlayerResizeMode.Fit -> AspectRatioMode.FIT
            PlayerResizeMode.Fill, PlayerResizeMode.Zoom -> AspectRatioMode.CROP
        }
        aspectRatio.setMode(mode)
    }

    LaunchedEffect(player, pendingSelectedSubtitleId) {
        val subtitleId = pendingSelectedSubtitleId ?: return@LaunchedEffect
        val subtitleGroup = player.features[MediaMetadata]?.subtitleTracks ?: return@LaunchedEffect
        val candidates = subtitleGroup.candidates as? StateFlow<List<MediampSubtitleTrack>> ?: return@LaunchedEffect
        candidates.collectLatest { tracks ->
            val selectedTrack = tracks.firstOrNull { it.id == subtitleId } ?: return@collectLatest
            if (subtitleGroup.select(selectedTrack)) {
                pendingSelectedSubtitleId = null
            }
        }
    }

    val controller = remember(player) {
        WindowsVlcController(
            player = player,
            currentRequest = { mediaRequest },
            updateRequest = { mediaRequest = it },
            setPendingSubtitleSelection = { pendingSelectedSubtitleId = it },
        )
    }

    LaunchedEffect(controller) {
        DesktopRuntimeDiagnostics.info(
            tag = "PlayerDesktop",
            message = "VLC controller bound to playerId=$playerInstanceId controllerId=${Integer.toHexString(System.identityHashCode(controller))}",
        )
        currentOnControllerReady(controller)
    }

    LaunchedEffect(player) {
        combine(
            player.playbackState,
            player.currentPositionMillis,
            player.mediaProperties,
            player.features[PlaybackSpeed.Key]?.valueFlow ?: flowOf(1f),
        ) { state, position, props, playbackSpeed ->
            PlayerPlaybackSnapshot(
                isLoading = state == PlaybackState.READY || state == PlaybackState.PAUSED_BUFFERING,
                isPlaying = state == PlaybackState.PLAYING,
                isEnded = state == PlaybackState.FINISHED,
                positionMs = position,
                durationMs = props?.durationMillis?.takeIf { it > 0 } ?: 0L,
                bufferedPositionMs = 0L,
                playbackSpeed = playbackSpeed,
            )
        }.collectLatest { snapshot ->
            currentOnSnapshot(snapshot)
        }
    }

    LaunchedEffect(player) {
        player.playbackState.collectLatest { state ->
            DesktopRuntimeDiagnostics.info(
                tag = "PlayerDesktop",
                message = "VLC playbackState=$state playerId=$playerInstanceId surfaceMode=${player.currentVideoSurfaceMode()} surfaceAttached=$surfaceAttached surfaceSize=${surfaceSize.width}x${surfaceSize.height}",
            )
            if (state == PlaybackState.ERROR) {
                currentOnError("Playback error")
            } else if (fatalErrorMessage == null) {
                currentOnError(null)
            }
        }
    }

    SwingPanel(
        factory = { layeredPane },
        modifier = modifier
            .background(Color.Black)
            .onGloballyPositioned { coordinates ->
                val newSize = coordinates.size
                if (newSize != surfaceSize) {
                    surfaceSize = newSize
                    DesktopRuntimeDiagnostics.info(
                        tag = "PlayerDesktop",
                        message = "VLC compose surface measured playerId=$playerInstanceId size=${newSize.width}x${newSize.height}",
                    )
                }
            },
        update = { host ->
            host.isOpaque = false
            layeredPane.isOpaque = false
            renderHost.isOpaque = true
            renderSurface.background = java.awt.Color.BLACK
            host.revalidate()
        },
    )
}

@OptIn(InternalMediampApi::class)
@Composable
private fun WindowsMpvPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val currentOnControllerReady by rememberUpdatedState(onControllerReady)
    val currentOnSnapshot by rememberUpdatedState(onSnapshot)
    val currentOnError by rememberUpdatedState(onError)

    var fatalErrorMessage by remember { mutableStateOf<String?>(null) }
    var surfaceAttached by remember { mutableStateOf(false) }
    var sessionClosed by remember { mutableStateOf(false) }

    val renderSurface = remember {
        object : Panel(BorderLayout()) {
            override fun paint(g: java.awt.Graphics?) = Unit

            override fun update(g: java.awt.Graphics?) = Unit
        }.apply {
            background = java.awt.Color.BLACK
            isFocusable = false
            ignoreRepaint = true
        }
    }
    val renderHost = remember(renderSurface) {
        JPanel(BorderLayout()).apply {
            background = java.awt.Color.BLACK
            isOpaque = true
            add(renderSurface, BorderLayout.CENTER)
        }
    }
    val mpvLogFile = remember {
        DesktopPaths.logsRoot
            .resolve("mpv-${System.currentTimeMillis()}.log")
            .toAbsolutePath()
            .normalize()
    }
    var sessionResult by remember { mutableStateOf<Result<WindowsMpvSession>?>(null) }

    fun reportFatalFailure(
        phase: String,
        throwable: Throwable,
    ) {
        val message = throwable.message?.takeIf { it.isNotBlank() }
            ?: "Windows mediamp/mpv failed during $phase"

        if (fatalErrorMessage == message) return

        DesktopRuntimeDiagnostics.error(
            tag = "PlayerDesktop",
            message = "Windows mediamp/mpv failure during $phase; switching to controlled error UI.",
            throwable = throwable,
        )

        surfaceAttached = false

        if (!sessionClosed) {
            closeWindowsMpvSessionAsync(sessionResult?.getOrNull())
            sessionClosed = true
        }

        fatalErrorMessage = message
        currentOnError(message)
    }

    val sessionFailure = sessionResult?.exceptionOrNull()

    LaunchedEffect(sessionFailure) {
        sessionFailure?.let { error ->
            reportFatalFailure("initialization", error)
        }
    }

    LaunchedEffect(fatalErrorMessage) {
        if (fatalErrorMessage != null) {
            DesktopRuntimeDiagnostics.warn(
                tag = "PlayerDesktop",
                message = "Player fallback/error UI path triggered.",
            )
            currentOnControllerReady(NoOpPlayerEngineController)
            currentOnSnapshot(PlayerPlaybackSnapshot())
        }
    }

    LaunchedEffect(renderSurface, fatalErrorMessage, sessionClosed) {
        if (fatalErrorMessage != null || sessionClosed || sessionResult != null) return@LaunchedEffect

        try {
            val wid = awaitMpvRenderSurfaceWindowIdOrThrow(renderSurface)
            val result = runCatching {
                DesktopRuntimeDiagnostics.info(
                    tag = "PlayerDesktop",
                    message = "mpv native log file=$mpvLogFile",
                )
                val player = MpvMediampPlayer(
                    MpvMediampPlayerInitOptions(
                        platformContext = Unit,
                        windowId = wid,
                        logFilePath = mpvLogFile.toString(),
                    ),
                    kotlin.coroutines.EmptyCoroutineContext,
                )
                val handle = player.impl as? MPVHandle
                    ?: error("mediamp player did not expose an MPVHandle")
                WindowsMpvSession(
                    player = player,
                    handle = handle,
                    renderHost = renderHost,
                    renderSurface = renderSurface,
                )
            }
            sessionResult = result
            result.getOrThrow()
            surfaceAttached = true
            DesktopRuntimeDiagnostics.info(
                tag = "PlayerDesktop",
                message = "MPV initialized with pre-attached render surface.",
            )
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            reportFatalFailure("pre-initialize_render-surface_attach", e)
        }
    }

    val session = sessionResult?.getOrNull()
    if (fatalErrorMessage != null || sessionClosed) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black),
        )
        return
    }

    if (session == null) {
        SwingPanel(
            factory = { renderHost },
            modifier = modifier.background(Color.Black),
            update = { host ->
                host.background = java.awt.Color.BLACK
                renderSurface.background = java.awt.Color.BLACK
                host.revalidate()
            },
        )
        return
    }

    val player = session.player
    val handle = session.handle

    DisposableEffect(player, renderSurface) {
        DesktopPlayerGestureBridge.register(
            token = player,
            currentVolumeProvider = { handle.readVolumeLevel() },
            setVolumeProvider = { level -> handle.writeVolumeLevel(level) },
        )
        onDispose {
            DesktopPlayerGestureBridge.unregister(player)
            sessionClosed = true
            surfaceAttached = false
            closeWindowsMpvSessionAsync(session)
        }
    }

    LaunchedEffect(sourceUrl, sourceAudioUrl, surfaceAttached, fatalErrorMessage, sessionClosed) {
        if (!surfaceAttached || fatalErrorMessage != null || sessionClosed) return@LaunchedEffect

        try {
            currentOnError(null)
            withContext(Dispatchers.IO) {
                DesktopRuntimeDiagnostics.info(
                    tag = "PlayerDesktop",
                    message = "Initializing mediamp/mpv playback path.",
                )

                val headers = sourceHeaders.toMutableMap()
                if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    headers["User-Agent"] = DESKTOP_PLAYBACK_USER_AGENT
                }

                DesktopRuntimeDiagnostics.info(
                    tag = "PlayerDesktop",
                    message = "Source preflight: ${preflightDesktopStream(sourceUrl, headers)}",
                )

                DesktopRuntimeDiagnostics.info(
                    tag = "PlayerDesktop",
                    message = "Calling player.setMediaData(...)",
                )
                player.setMediaData(UriMediaData(sourceUrl, headers))
                DesktopRuntimeDiagnostics.info(
                    tag = "PlayerDesktop",
                    message = "player.setMediaData(...) returned",
                )

                if (!sourceAudioUrl.isNullOrEmpty()) {
                    DesktopRuntimeDiagnostics.info(
                        tag = "PlayerDesktop",
                        message = "Calling audio-add for external audio track.",
                    )
                    handle.command("audio-add", sourceAudioUrl, "auto")
                    DesktopRuntimeDiagnostics.info(
                        tag = "PlayerDesktop",
                        message = "audio-add returned",
                    )
                }

                if (playWhenReady) {
                    DesktopRuntimeDiagnostics.info(
                        tag = "PlayerDesktop",
                        message = "Calling player.resume()",
                    )
                    player.resume()
                    DesktopRuntimeDiagnostics.info(
                        tag = "PlayerDesktop",
                        message = "player.resume() returned",
                    )
                } else {
                    DesktopRuntimeDiagnostics.info(
                        tag = "PlayerDesktop",
                        message = "Skipping auto-resume because playWhenReady=false",
                    )
                }

                if (playWhenReady) {
                    val interopBlendingEnabled =
                        System.getProperty("compose.interop.blending")?.equals("true", ignoreCase = true) == true

                    var startupState: WindowsMpvStartupState? = null
                    var videoRecoveryAttempted = false
                    var probeIndex = 0
                    var elapsedMs = 0L
                    val startupDeadlineMs = 75_000L
                    while (elapsedMs < startupDeadlineMs) {
                        val probeDelayMs = if (probeIndex == 0) 2500L else 5000L
                        delay(probeDelayMs)
                        elapsedMs += probeDelayMs
                        probeIndex += 1
                        startupState = handle.readWindowsMpvStartupState(player)
                        DesktopRuntimeDiagnostics.info(
                            tag = "PlayerDesktop",
                            message = "mpv startup probe #$probeIndex/${startupDeadlineMs / 1000}s: ${startupState?.toLogMessage()}",
                        )
                        if (!videoRecoveryAttempted && startupState?.indicatesAudioOnlyVideoFailure() == true) {
                            videoRecoveryAttempted = true
                            recoverWindowsMpvVideoOutput(handle, startupState)
                            continue
                        }
                        if (startupState?.hasUsablePlaybackStartup() == true) {
                            break
                        }
                    }

                    val finalStartupState = startupState
                        ?: handle.readWindowsMpvStartupState(player)

                    if (interopBlendingEnabled && finalStartupState.usesDirectXContext()) {
                        error("Unsupported mpv gpu context for Compose interop blending: ${finalStartupState.toLogMessage()}")
                    }

                    check(!finalStartupState.indicatesFailedStartup()) {
                        "mpv stayed idle after startup; ${finalStartupState.toLogMessage()}"
                    }

                    check(!finalStartupState.indicatesStalledVideoStartup()) {
                        "mpv did not expose a usable video output after startup; ${finalStartupState.toLogMessage()}"
                    }
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            reportFatalFailure("media load", e)
        }
    }

    LaunchedEffect(playWhenReady, surfaceAttached, fatalErrorMessage, sessionClosed) {
        if (!surfaceAttached || fatalErrorMessage != null || sessionClosed) return@LaunchedEffect

        runCatching {
            withContext(Dispatchers.IO) {
                val state = player.getCurrentPlaybackState()
                DesktopRuntimeDiagnostics.info(
                    tag = "PlayerDesktop",
                    message = "Applying playWhenReady=$playWhenReady on state=$state",
                )

                if (playWhenReady && (state == PlaybackState.PAUSED || state == PlaybackState.PAUSED_BUFFERING)) {
                    player.resume()
                    DesktopRuntimeDiagnostics.info(
                        tag = "PlayerDesktop",
                        message = "player.resume() from toggle returned",
                    )
                } else if (!playWhenReady && state == PlaybackState.PLAYING) {
                    player.pause()
                    DesktopRuntimeDiagnostics.info(
                        tag = "PlayerDesktop",
                        message = "player.pause() from toggle returned",
                    )
                }
            }
        }.onFailure {
            reportFatalFailure("playback toggle", it)
        }
    }

    LaunchedEffect(resizeMode, surfaceAttached, fatalErrorMessage, sessionClosed) {
        if (!surfaceAttached || fatalErrorMessage != null || sessionClosed) return@LaunchedEffect

        when (resizeMode) {
            PlayerResizeMode.Fit -> {
                handle.setProperty("panscan", 0.0)
                handle.setProperty("keepaspect", true)
            }
            PlayerResizeMode.Fill, PlayerResizeMode.Zoom -> {
                handle.setProperty("panscan", 1.0)
                handle.setProperty("keepaspect", true)
            }
        }
    }

    val controller = remember(player) {
        WindowsMpvController(player, handle)
    }

    LaunchedEffect(controller, surfaceAttached, fatalErrorMessage, sessionClosed) {
        if (!surfaceAttached || fatalErrorMessage != null || sessionClosed) {
            currentOnControllerReady(NoOpPlayerEngineController)
            return@LaunchedEffect
        }
        currentOnControllerReady(controller)
    }

    LaunchedEffect(player, surfaceAttached, fatalErrorMessage, sessionClosed) {
        if (!surfaceAttached || fatalErrorMessage != null || sessionClosed) return@LaunchedEffect

        combine(
            player.playbackState,
            player.currentPositionMillis,
            player.mediaProperties,
        ) { state, position, props ->
            PlayerPlaybackSnapshot(
                isLoading = state == PlaybackState.PAUSED_BUFFERING || state == PlaybackState.READY,
                isPlaying = state == PlaybackState.PLAYING,
                isEnded = state == PlaybackState.FINISHED,
                positionMs = position,
                durationMs = props?.durationMillis?.takeIf { it > 0 } ?: 0L,
                bufferedPositionMs = 0L,
                playbackSpeed = handle.readPlaybackSpeed(),
            )
        }.collectLatest { snapshot ->
            currentOnSnapshot(snapshot)
        }
    }

    LaunchedEffect(player, surfaceAttached, fatalErrorMessage, sessionClosed) {
        if (!surfaceAttached || fatalErrorMessage != null || sessionClosed) return@LaunchedEffect

        player.playbackState.collectLatest { state ->
            if (state == PlaybackState.ERROR) {
                DesktopRuntimeDiagnostics.warn(
                    tag = "PlayerDesktop",
                    message = "mediamp reported PlaybackState.ERROR.",
                )
                currentOnError("Playback error")
            } else if (fatalErrorMessage == null) {
                currentOnError(null)
            }
        }
    }

    SwingPanel(
        factory = { renderHost },
        modifier = modifier.background(Color.Black),
        update = { host ->
            host.background = java.awt.Color.BLACK
            renderSurface.background = java.awt.Color.BLACK
            host.revalidate()
        },
    )
}

/**
 * PlayerEngineController implementation backed by MpvMediampPlayer.
 * Uses direct mpv property/command access for full control.
 */
private class WindowsMpvController(
    private val player: MpvMediampPlayer,
    private val handle: MPVHandle,
) : PlayerEngineController {

    private val isReady: Boolean
        get() = player.getCurrentPlaybackState() != PlaybackState.FINISHED

    override fun play() {
        runCatching { player.resume() }
    }

    override fun pause() {
        runCatching { player.pause() }
    }

    override fun seekTo(positionMs: Long) {
        runCatching { player.seekTo(positionMs) }
    }

    override fun seekBy(offsetMs: Long) {
        runCatching { player.skip(offsetMs) }
    }

    override fun retry() {
        runCatching { player.resume() }
    }

    override fun setPlaybackSpeed(speed: Float) {
        runCatching {
            val normalizedSpeed = speed.coerceIn(0.25f, 4f)
            handle.setPropertyDouble("speed", normalizedSpeed.toDouble())
        }
    }

    override fun getAudioTracks(): List<AudioTrack> =
        runCatching {
            if (!isReady) return@runCatching emptyList()

            val count = handle.getPropertyIntSafe("track-list/count")
            val tracks = mutableListOf<AudioTrack>()
            for (i in 0 until count) {
                val type = handle.readPropertyStringOrNull("track-list/$i/type")
                if (type != "audio") continue
                val id = handle.getPropertyIntSafe("track-list/$i/id")
                val title = handle.readPropertyStringOrNull("track-list/$i/title").orEmpty()
                val lang = handle.readPropertyStringOrNull("track-list/$i/lang")
                val selected = runCatching { handle.getPropertyBoolean("track-list/$i/selected") }.getOrDefault(false)
                tracks.add(
                    AudioTrack(
                        index = tracks.size,
                        id = id.toString(),
                        label = title.ifEmpty { lang ?: "Track $id" },
                        language = lang,
                        isSelected = selected,
                    ),
                )
            }
            tracks
        }.getOrElse { emptyList() }

    override fun getSubtitleTracks(): List<SubtitleTrack> =
        runCatching {
            if (!isReady) return@runCatching emptyList()

            val count = handle.getPropertyIntSafe("track-list/count")
            val tracks = mutableListOf<SubtitleTrack>()
            for (i in 0 until count) {
                val type = handle.readPropertyStringOrNull("track-list/$i/type")
                if (type != "sub") continue
                val id = handle.getPropertyIntSafe("track-list/$i/id")
                val title = handle.readPropertyStringOrNull("track-list/$i/title").orEmpty()
                val lang = handle.readPropertyStringOrNull("track-list/$i/lang")
                val selected = runCatching { handle.getPropertyBoolean("track-list/$i/selected") }.getOrDefault(false)
                val forced = runCatching { handle.getPropertyBoolean("track-list/$i/forced") }.getOrDefault(false)
                tracks.add(
                    SubtitleTrack(
                        index = tracks.size,
                        id = id.toString(),
                        label = title.ifEmpty { lang ?: "Subtitle $id" },
                        language = lang,
                        isSelected = selected,
                        isForced = forced,
                    ),
                )
            }
            tracks
        }.getOrElse { emptyList() }

    override fun selectAudioTrack(index: Int) {
        runCatching {
            val tracks = getAudioTracks()
            if (index in tracks.indices) {
                handle.setProperty("aid", tracks[index].id)
            }
        }
    }

    override fun selectSubtitleTrack(index: Int) {
        runCatching {
            if (index < 0) {
                handle.setProperty("sid", "no")
                return@runCatching
            }
            val tracks = getSubtitleTracks()
            if (index in tracks.indices) {
                handle.setProperty("sid", tracks[index].id)
            }
        }
    }

    override fun setSubtitleUri(url: String) {
        runCatching {
            handle.command("sub-add", url, "auto")
        }
    }

    override fun clearExternalSubtitle() {
        runCatching {
            if (!isReady) return@runCatching
            val count = handle.getPropertyIntSafe("track-list/count")
            for (i in count - 1 downTo 0) {
                val type = handle.getPropertyString("track-list/$i/type")
                val external = handle.getPropertyBoolean("track-list/$i/external")
                if (type == "sub" && external) {
                    val id = handle.getPropertyIntSafe("track-list/$i/id")
                    handle.command("sub-remove", id.toString())
                    return@runCatching
                }
            }
        }
    }

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        runCatching {
            clearExternalSubtitle()
            selectSubtitleTrack(trackIndex)
        }
    }

    override fun applySubtitleStyle(style: SubtitleStyleState) {
        runCatching {
            val colorHex = style.textColor.toMpvColorString()
            val outline = if (style.outlineEnabled) 2.0 else 0.0
            val subPos = 100 - style.bottomOffset
            handle.option("sub-color", colorHex)
            handle.setProperty("sub-border-size", outline)
            handle.setProperty("sub-font-size", style.fontSizeSp.toDouble())
            handle.setProperty("sub-pos", subPos)
        }
    }

    override fun switchSource(url: String, audioUrl: String?, headersJson: String?) {
        runCatching {
            handle.option("user-agent", DESKTOP_PLAYBACK_USER_AGENT)
            if (headersJson != null) {
                handle.option("http-header-fields-clr", "")
                val headerPattern = Regex(""""([^"]+)"\s*:\s*"([^"]+)"""")
                headerPattern.findAll(headersJson).forEach { match ->
                    val (key, value) = match.destructured
                    if (key.equals("User-Agent", ignoreCase = true)) {
                        handle.option("user-agent", value)
                        return@forEach
                    }
                    handle.option("http-header-fields", "$key: $value")
                }
            }
            handle.command("stop")
            handle.command("playlist-clear")
            handle.setPropertyString("vid", "auto")
            handle.setPropertyString("aid", "auto")
            handle.setPropertyString("sid", "auto")
            handle.command("loadfile", url)
            if (!audioUrl.isNullOrEmpty()) {
                handle.command("audio-add", audioUrl, "auto")
            }
            handle.setPropertyBoolean("pause", false)
        }
    }
}

private class WindowsVlcController(
    private val player: VlcMediampPlayer,
    private val currentRequest: () -> WindowsVlcMediaRequest,
    private val updateRequest: (WindowsVlcMediaRequest) -> Unit,
    private val setPendingSubtitleSelection: (String?) -> Unit,
) : PlayerEngineController {

    override fun play() {
        runCatching { player.resume() }
    }

    override fun pause() {
        runCatching { player.pause() }
    }

    override fun seekTo(positionMs: Long) {
        runCatching { player.seekTo(positionMs) }
    }

    override fun seekBy(offsetMs: Long) {
        runCatching { player.skip(offsetMs) }
    }

    override fun retry() {
        val request = currentRequest()
        updateRequest(request.copy(reloadNonce = request.reloadNonce + 1))
    }

    override fun setPlaybackSpeed(speed: Float) {
        runCatching {
            player.features[PlaybackSpeed.Key]
                ?.set(speed.coerceIn(0.25f, 4f))
        }
    }

    override fun getAudioTracks(): List<AudioTrack> =
        readAudioTracks()

    override fun getSubtitleTracks(): List<SubtitleTrack> =
        readSubtitleTracks()

    override fun selectAudioTrack(index: Int) {
        runCatching {
            val audioTracks = player.features[MediaMetadata]?.audioTracks ?: return@runCatching
            val candidates = audioTracks.candidates as? StateFlow<List<MediampAudioTrack>> ?: return@runCatching
            audioTracks.select(candidates.value.getOrNull(index))
        }
    }

    override fun selectSubtitleTrack(index: Int) {
        runCatching {
            val subtitleTracks = player.features[MediaMetadata]?.subtitleTracks ?: return@runCatching
            if (index < 0) {
                subtitleTracks.select(null)
                return@runCatching
            }
            val candidates = subtitleTracks.candidates as? StateFlow<List<MediampSubtitleTrack>> ?: return@runCatching
            subtitleTracks.select(candidates.value.getOrNull(index))
        }
    }

    override fun setSubtitleUri(url: String) {
        val request = currentRequest()
        updateRequest(
            request.copy(
                externalSubtitles = listOf(MediampSubtitleFile(uri = url)),
                reloadNonce = request.reloadNonce + 1,
            ),
        )
        setPendingSubtitleSelection(null)
    }

    override fun clearExternalSubtitle() {
        val request = currentRequest()
        updateRequest(
            request.copy(
                externalSubtitles = emptyList(),
                reloadNonce = request.reloadNonce + 1,
            ),
        )
        setPendingSubtitleSelection(null)
    }

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        val selectedSubtitleId = getSubtitleTracks().getOrNull(trackIndex)?.id
        val request = currentRequest()
        updateRequest(
            request.copy(
                externalSubtitles = emptyList(),
                reloadNonce = request.reloadNonce + 1,
            ),
        )
        setPendingSubtitleSelection(selectedSubtitleId)
    }

    override fun applySubtitleStyle(style: SubtitleStyleState) = Unit

    override fun switchSource(url: String, audioUrl: String?, headersJson: String?) {
        val current = currentRequest()
        updateRequest(
            createWindowsVlcMediaRequest(
                url = url,
                audioUrl = audioUrl,
                headers = parsePlaybackHeadersJson(headersJson),
                reloadNonce = current.reloadNonce + 1,
            ),
        )
        setPendingSubtitleSelection(null)
    }

    private fun readAudioTracks(): List<AudioTrack> {
        val audioTracks = player.features[MediaMetadata]?.audioTracks ?: return emptyList()
        val candidates = audioTracks.candidates as? StateFlow<List<MediampAudioTrack>> ?: return emptyList()
        val selected = audioTracks.selected.value
        return candidates.value.mapIndexed { index, track ->
            AudioTrack(
                index = index,
                id = track.id,
                label = track.name
                    ?: track.labels.firstOrNull()?.value
                    ?: "Track ${index + 1}",
                language = track.labels.firstOrNull()?.language,
                isSelected = selected?.id == track.id,
            )
        }
    }

    private fun readSubtitleTracks(): List<SubtitleTrack> {
        val subtitleTracks = player.features[MediaMetadata]?.subtitleTracks ?: return emptyList()
        val candidates = subtitleTracks.candidates as? StateFlow<List<MediampSubtitleTrack>> ?: return emptyList()
        val selected = subtitleTracks.selected.value
        return candidates.value.mapIndexed { index, track ->
            SubtitleTrack(
                index = index,
                id = track.id,
                label = track.labels.firstOrNull()?.value ?: "Subtitle ${index + 1}",
                language = track.language ?: track.labels.firstOrNull()?.language,
                isSelected = selected?.id == track.id,
                isForced = false,
            )
        }
    }
}

private fun parsePlaybackHeadersJson(headersJson: String?): Map<String, String> {
    if (headersJson.isNullOrBlank()) return emptyMap()

    val headers = LinkedHashMap<String, String>()
    val headerPattern = Regex(""""([^"]+)"\s*:\s*"([^"]*)"""")
    headerPattern.findAll(headersJson).forEach { match ->
        val (key, value) = match.destructured
        if (key.isNotBlank() && value.isNotBlank()) {
            headers[key] = value
        }
    }
    return headers
}

private fun MPVHandle.setProperty(name: String, value: Boolean): Boolean =
    setPropertyBoolean(name, value)

private fun MPVHandle.setProperty(name: String, value: Double): Boolean =
    setPropertyDouble(name, value)

private fun MPVHandle.setProperty(name: String, value: Int): Boolean =
    setPropertyInt(name, value)

private fun MPVHandle.setProperty(name: String, value: String): Boolean =
    setPropertyString(name, value)

private fun MPVHandle.readVolumeLevel(): PlayerAudioLevel? {
    val volume = runCatching {
        getPropertyString("volume")
            .trim()
            .toFloatOrNull()
    }.getOrNull() ?: return null

    val clampedFraction = (volume / 100f).coerceIn(0f, 1f)
    val muted = runCatching { getPropertyBoolean("mute") }.getOrDefault(clampedFraction <= 0f)
    return PlayerAudioLevel(
        fraction = clampedFraction,
        isMuted = muted,
    )
}

private fun MPVHandle.writeVolumeLevel(level: Float): PlayerAudioLevel? {
    val clampedLevel = level.coerceIn(0f, 1f)
    runCatching { setPropertyDouble("volume", clampedLevel * 100.0) }
    runCatching { setPropertyBoolean("mute", clampedLevel <= 0f) }
    return readVolumeLevel() ?: PlayerAudioLevel(
        fraction = clampedLevel,
        isMuted = clampedLevel <= 0f,
    )
}

private fun MPVHandle.readPlaybackSpeed(): Float =
    readPropertyStringOrNull("speed")
        ?.toFloatOrNull()
        ?.takeIf { it.isFinite() && it > 0f }
        ?: 1f

private data class WindowsMpvStartupState(
    val playbackState: PlaybackState,
    val wid: String?,
    val vo: String?,
    val currentVo: String?,
    val gpuContext: String?,
    val currentGpuContext: String?,
    val gpuApi: String?,
    val width: String?,
    val height: String?,
    val dwidth: String?,
    val dheight: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val timePosition: String?,
    val duration: String?,
    val percentPosition: String?,
    val hwdecCurrent: String?,
    val ao: String?,
    val currentAo: String?,
    val audioDevice: String?,
    val audioParams: String?,
    val videoParams: String?,
    val selectedVideoId: String?,
    val selectedAudioId: String?,
    val selectedSubtitleId: String?,
    val pausedForCache: String?,
    val cacheBufferingState: String?,
    val volume: String?,
    val mute: String?,
    val pause: String?,
    val coreIdle: String?,
    val idleActive: String?,
    val eofReached: String?,
    val path: String?,
    val streamOpenFilename: String?,
    val trackCount: String?,
    val trackSummary: String?,
    val mpvVersion: String?,
)

private fun MPVHandle.readPropertyStringOrNull(name: String): String? =
    runCatching {
        getPropertyString(name)
            .trim()
            .takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
    }.getOrNull()

private fun MPVHandle.readWindowsMpvStartupState(player: MpvMediampPlayer): WindowsMpvStartupState =
    WindowsMpvStartupState(
        playbackState = player.getCurrentPlaybackState(),
        wid = readPropertyStringOrNull("wid"),
        vo = readPropertyStringOrNull("vo"),
        currentVo = readPropertyStringOrNull("current-vo"),
        gpuContext = readPropertyStringOrNull("gpu-context"),
        currentGpuContext = readPropertyStringOrNull("current-gpu-context"),
        gpuApi = readPropertyStringOrNull("gpu-api"),
        width = readPropertyStringOrNull("width"),
        height = readPropertyStringOrNull("height"),
        dwidth = readPropertyStringOrNull("dwidth"),
        dheight = readPropertyStringOrNull("dheight"),
        videoCodec = readPropertyStringOrNull("video-codec"),
        audioCodec = readPropertyStringOrNull("audio-codec-name") ?: readSelectedAudioCodec(),
        timePosition = readPropertyStringOrNull("time-pos/full") ?: readPropertyStringOrNull("time-pos"),
        duration = readPropertyStringOrNull("duration/full") ?: readPropertyStringOrNull("duration"),
        percentPosition = readPropertyStringOrNull("percent-pos"),
        hwdecCurrent = readPropertyStringOrNull("hwdec-current"),
        ao = readPropertyStringOrNull("ao"),
        currentAo = readPropertyStringOrNull("current-ao"),
        audioDevice = readPropertyStringOrNull("audio-device"),
        audioParams = readPropertyStringOrNull("audio-params"),
        videoParams = readPropertyStringOrNull("video-params"),
        selectedVideoId = readPropertyStringOrNull("vid"),
        selectedAudioId = readPropertyStringOrNull("aid"),
        selectedSubtitleId = readPropertyStringOrNull("sid"),
        pausedForCache = readPropertyStringOrNull("paused-for-cache"),
        cacheBufferingState = readPropertyStringOrNull("cache-buffering-state"),
        volume = readPropertyStringOrNull("volume"),
        mute = readPropertyStringOrNull("mute"),
        pause = readPropertyStringOrNull("pause"),
        coreIdle = readPropertyStringOrNull("core-idle"),
        idleActive = readPropertyStringOrNull("idle-active"),
        eofReached = readPropertyStringOrNull("eof-reached"),
        path = readPropertyStringOrNull("path"),
        streamOpenFilename = readPropertyStringOrNull("stream-open-filename"),
        trackCount = readPropertyStringOrNull("track-list/count"),
        trackSummary = readTrackSummary(),
        mpvVersion = readPropertyStringOrNull("mpv-version"),
    )

private fun MPVHandle.readSelectedAudioCodec(): String? {
    val selectedAudioId = readPropertyStringOrNull("aid") ?: return null
    val count = readPropertyStringOrNull("track-list/count")?.toIntOrNull() ?: return null
    for (index in 0 until count.coerceAtMost(32)) {
        val type = readPropertyStringOrNull("track-list/$index/type")
        val selected = readPropertyStringOrNull("track-list/$index/selected")
        val id = readPropertyStringOrNull("track-list/$index/id")
        if (type == "audio" && selected.equals("yes", ignoreCase = true) && id == selectedAudioId) {
            return readPropertyStringOrNull("track-list/$index/codec")
        }
    }
    return null
}

private fun MPVHandle.readTrackSummary(): String? {
    val count = readPropertyStringOrNull("track-list/count")?.toIntOrNull() ?: return null
    if (count <= 0) return null

    return (0 until count.coerceAtMost(16)).joinToString(separator = " | ") { index ->
        val type = readPropertyStringOrNull("track-list/$index/type") ?: "?"
        val selected = readPropertyStringOrNull("track-list/$index/selected") ?: "?"
        val id = readPropertyStringOrNull("track-list/$index/id") ?: "?"
        val codec = readPropertyStringOrNull("track-list/$index/codec") ?: "?"
        val lang = readPropertyStringOrNull("track-list/$index/lang") ?: "?"
        "#$index:$type:id=$id:selected=$selected:codec=$codec:lang=$lang"
    }
}

private fun WindowsMpvStartupState.indicatesFailedStartup(): Boolean =
    coreIdle.equals("yes", ignoreCase = true) && idleActive.equals("yes", ignoreCase = true)

private fun WindowsMpvStartupState.hasUsablePlaybackStartup(): Boolean {
    val trackCountValue = trackCount?.toIntOrNull() ?: 0
    return playbackState == PlaybackState.PLAYING ||
        trackCountValue > 0 ||
        !timePosition.isNullOrBlank() ||
        !duration.isNullOrBlank() ||
        !audioParams.isNullOrBlank() ||
        !videoParams.isNullOrBlank() ||
        !currentVo.isNullOrBlank()
}

private fun WindowsMpvStartupState.usesDirectXContext(): Boolean {
    val context = currentGpuContext ?: gpuContext
    return context?.contains("d3d11", ignoreCase = true) == true
}

private fun WindowsMpvStartupState.indicatesStalledVideoStartup(): Boolean {
    val noVideoOutput = currentVo.isNullOrBlank()
    val noTracks = trackCount.isNullOrBlank() || trackCount == "0"
    val noVideoMetrics = width.isNullOrBlank() && dwidth.isNullOrBlank() && videoCodec.isNullOrBlank()
    val hasVideoTrack = trackSummary?.contains(":video:", ignoreCase = true) == true
    val videoDisabled = selectedVideoId.equals("no", ignoreCase = true)
    val loadingState = playbackState == PlaybackState.PAUSED_BUFFERING || playbackState == PlaybackState.READY
    val stillIdle = coreIdle.equals("yes", ignoreCase = true)
    return (noVideoOutput && noTracks && noVideoMetrics && loadingState && stillIdle) ||
        (hasVideoTrack && noVideoOutput && noVideoMetrics && videoDisabled)
}

private fun WindowsMpvStartupState.indicatesAudioOnlyVideoFailure(): Boolean {
    val hasLoadedMedia = !path.isNullOrBlank() || !streamOpenFilename.isNullOrBlank()
    val hasVideoTrack = trackSummary?.contains(":video:", ignoreCase = true) == true
    val videoDisabled = selectedVideoId.equals("no", ignoreCase = true)
    val noVideoOutput = currentVo.isNullOrBlank() && videoParams.isNullOrBlank()
    return hasLoadedMedia && hasVideoTrack && videoDisabled && noVideoOutput
}

private suspend fun recoverWindowsMpvVideoOutput(
    handle: MPVHandle,
    state: WindowsMpvStartupState?,
) {
    val targetVo = state?.vo?.takeIf { it.isNotBlank() } ?: "gpu"
    DesktopRuntimeDiagnostics.warn(
        tag = "PlayerDesktop",
        message = "mpv loaded audio but disabled video; forcing video track and rebuilding VO. state=${state?.toLogMessage()}",
    )
    runCatching { handle.setPropertyString("vid", "auto") }
    runCatching { handle.setPropertyString("vo", "null") }
    delay(250)
    runCatching { handle.setPropertyString("vo", targetVo) }
    runCatching { handle.command("video-reload") }
    runCatching { handle.setPropertyBoolean("pause", false) }
}

private fun preflightDesktopStream(
    sourceUrl: String,
    headers: Map<String, String>,
): String {
    var connection: HttpURLConnection? = null
    return runCatching {
        connection = (URI(sourceUrl).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty(
                "User-Agent",
                headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                    ?: DESKTOP_PLAYBACK_USER_AGENT,
            )
            headers.forEach { (key, value) ->
                if (key.equals("Range", ignoreCase = true)) return@forEach
                if (key.equals("User-Agent", ignoreCase = true)) return@forEach
                setRequestProperty(key, value)
            }
            setRequestProperty("Range", "bytes=0-0")
        }

        val status = connection!!.responseCode
        val stream = if (status >= 400) connection!!.errorStream else connection!!.inputStream
        val firstByteRead = stream?.use { it.read(ByteArray(1)) } ?: -1
        val finalUrl = connection!!.url
        "status=$status, host=${finalUrl.host}, " +
            "contentType=${connection!!.contentType ?: "<blank>"}, " +
            "contentLength=${connection!!.contentLengthLong}, " +
            "acceptRanges=${connection!!.getHeaderField("Accept-Ranges") ?: "<blank>"}, " +
            "contentRange=${connection!!.getHeaderField("Content-Range") ?: "<blank>"}, " +
            "firstByteRead=$firstByteRead"
    }.getOrElse { throwable ->
        "failed=${throwable::class.simpleName}: ${throwable.message ?: "<no message>"}"
    }.also {
        connection?.disconnect()
    }
}

private fun WindowsMpvStartupState.toLogMessage(): String =
    "playbackState=$playbackState, " +
        "wid=${wid ?: "<blank>"}, " +
        "vo=${vo ?: "<blank>"}, " +
        "current-vo=${currentVo ?: "<blank>"}, " +
        "gpu-context=${gpuContext ?: "<blank>"}, " +
        "current-gpu-context=${currentGpuContext ?: "<blank>"}, " +
        "gpu-api=${gpuApi ?: "<blank>"}, " +
        "width=${width ?: "<blank>"}, " +
        "height=${height ?: "<blank>"}, " +
        "dwidth=${dwidth ?: "<blank>"}, " +
        "dheight=${dheight ?: "<blank>"}, " +
        "video-codec=${videoCodec ?: "<blank>"}, " +
        "audio-codec=${audioCodec ?: "<blank>"}, " +
        "time-pos=${timePosition ?: "<blank>"}, " +
        "duration=${duration ?: "<blank>"}, " +
        "percent-pos=${percentPosition ?: "<blank>"}, " +
        "hwdec-current=${hwdecCurrent ?: "<blank>"}, " +
        "ao=${ao ?: "<blank>"}, " +
        "current-ao=${currentAo ?: "<blank>"}, " +
        "audio-device=${audioDevice ?: "<blank>"}, " +
        "audio-params=${audioParams ?: "<blank>"}, " +
        "video-params=${videoParams ?: "<blank>"}, " +
        "vid=${selectedVideoId ?: "<blank>"}, " +
        "aid=${selectedAudioId ?: "<blank>"}, " +
        "sid=${selectedSubtitleId ?: "<blank>"}, " +
        "paused-for-cache=${pausedForCache ?: "<blank>"}, " +
        "cache-buffering-state=${cacheBufferingState ?: "<blank>"}, " +
        "volume=${volume ?: "<blank>"}, " +
        "mute=${mute ?: "<blank>"}, " +
        "pause=${pause ?: "<blank>"}, " +
        "core-idle=${coreIdle ?: "<blank>"}, " +
        "idle-active=${idleActive ?: "<blank>"}, " +
        "eof-reached=${eofReached ?: "<blank>"}, " +
        "path=${path ?: "<blank>"}, " +
        "stream-open-filename=${streamOpenFilename ?: "<blank>"}, " +
        "track-list/count=${trackCount ?: "<blank>"}, " +
        "tracks=${trackSummary ?: "<blank>"}, " +
        "mpv-version=${mpvVersion ?: "<blank>"}"

/**
 * Safe replacement for [MPVHandle.getPropertyInt].
 *
 * The native `nGetPropertyInt` binding in mediamp-mpv has a stack buffer
 * overflow: it declares a 4-byte `int` on the native stack and then asks mpv
 * to fill it using `MPV_FORMAT_INT64`, which writes 8 bytes. The overflow
 * corrupts adjacent stack memory and reliably crashes the JVM with
 * `EXCEPTION_ACCESS_VIOLATION` inside `nGetPropertyInt` (see hs_err_pid log).
 *
 * [MPVHandle.getPropertyString] is unaffected because its underlying C++
 * buffer is `char *` (8 bytes on x64). We therefore fetch the value as a
 * string and parse it ourselves. If mpv is not ready (track list not yet
 * populated, property absent, etc.), we return `0` / `null` instead of
 * propagating an error.
 */
private fun MPVHandle.getPropertyIntSafe(name: String): Int {
    val raw = runCatching { getPropertyString(name) }.getOrNull() ?: return 0
    if (raw.isEmpty()) return 0
    return raw.trim().toIntOrNull() ?: 0
}

private fun readMpvRenderSurfaceWindowId(surface: Component): Long? {
    if (!surface.isDisplayable) return null
    if (!surface.isShowing) return null
    if (surface.width <= 0 || surface.height <= 0) return null

    val nativePtr = Native.getComponentPointer(surface) ?: return null
    return Pointer.nativeValue(nativePtr).takeIf { it != 0L }
}

private suspend fun awaitVlcRenderSurfaceReadyOrThrow(surface: Component) {
    repeat(40) { attempt ->
        val ready = withContext(Dispatchers.Main) {
            surface.isDisplayable && surface.isShowing && surface.width > 0 && surface.height > 0
        }
        if (ready) {
            DesktopRuntimeDiagnostics.info(
                tag = "PlayerDesktop",
                message = "VLC render surface ready before player init on attempt ${attempt + 1}: displayable=${surface.isDisplayable}, showing=${surface.isShowing}, size=${surface.width}x${surface.height}",
            )
            return
        }
        delay(250)
    }

    error(
        "VLC render surface never became ready; " +
            "displayable=${surface.isDisplayable}, showing=${surface.isShowing}, size=${surface.width}x${surface.height}",
    )
}

private suspend fun awaitMpvRenderSurfaceWindowIdOrThrow(surface: Component): Long {
    repeat(60) { attempt ->
        val wid = withContext(Dispatchers.Main) {
            readMpvRenderSurfaceWindowId(surface)
        }
        if (wid != null) {
            DesktopRuntimeDiagnostics.info(
                tag = "PlayerDesktop",
                message = "MPV render surface ready before player init on attempt ${attempt + 1}: displayable=${surface.isDisplayable}, showing=${surface.isShowing}, size=${surface.width}x${surface.height}, wid=$wid",
            )
            return wid
        }
        delay(50)
    }

    error("Failed to obtain native MPV render surface window before player initialization.")
}

private fun detachMpvRenderSurface(handle: MPVHandle): Boolean {
    val ok = runCatching { handle.setPropertyString("wid", "0") }.getOrDefault(false)
    if (!ok) {
        runCatching { handle.option("wid", "0") }
    }
    return true
}

// ──────────────────────────────────────────────────────────────────────────────
// macOS: existing JNA bridge (unchanged)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun MacOSPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    useNativeController: Boolean,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val bridge = remember { DesktopMPVBridgeLib.INSTANCE }
    val playerPtr = remember { bridge.nuvio_player_create() }
    var onCloseCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onAddonSubtitlesFetchCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onSourcesRequestedCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onSourceStreamSelectedCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var onSourceFilterChangedCallback by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    var onSourceReloadCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onEpisodesRequestedCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onEpisodeSelectedCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var onEpisodeStreamSelectedCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var onEpisodeFilterChangedCallback by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    var onEpisodeReloadCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onEpisodeBackCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

    DisposableEffect(playerPtr) {
        bridge.nuvio_player_show(playerPtr)
        onDispose {
            bridge.nuvio_player_destroy(playerPtr)
        }
    }

    LaunchedEffect(sourceUrl, sourceAudioUrl) {
        val headersJson = if (sourceHeaders.isNotEmpty()) {
            buildJsonObject {
                sourceHeaders.forEach { (k, v) -> put(k, v) }
            }.toString()
        } else null
        bridge.nuvio_player_load_file(playerPtr, sourceUrl, sourceAudioUrl, headersJson)
        if (playWhenReady) {
            bridge.nuvio_player_play(playerPtr)
        }
    }

    LaunchedEffect(resizeMode) {
        val mode = when (resizeMode) {
            PlayerResizeMode.Fit -> 0
            PlayerResizeMode.Fill -> 1
            PlayerResizeMode.Zoom -> 2
        }
        bridge.nuvio_player_set_resize_mode(playerPtr, mode)
    }

    val controller = remember(playerPtr) {
        object : PlayerEngineController {
            override fun play() = bridge.nuvio_player_play(playerPtr)
            override fun pause() = bridge.nuvio_player_pause(playerPtr)
            override fun seekTo(positionMs: Long) = bridge.nuvio_player_seek_to(playerPtr, positionMs)
            override fun seekBy(offsetMs: Long) = bridge.nuvio_player_seek_by(playerPtr, offsetMs)
            override fun retry() = bridge.nuvio_player_retry(playerPtr)
            override fun setPlaybackSpeed(speed: Float) = bridge.nuvio_player_set_speed(playerPtr, speed)

            override fun getAudioTracks(): List<AudioTrack> {
                val count = bridge.nuvio_player_get_audio_track_count(playerPtr)
                return (0 until count).map { i ->
                    AudioTrack(
                        index = i,
                        id = bridge.nuvio_player_get_audio_track_id(playerPtr, i).toString(),
                        label = bridge.nuvio_player_get_audio_track_label(playerPtr, i) ?: "",
                        language = bridge.nuvio_player_get_audio_track_lang(playerPtr, i),
                        isSelected = bridge.nuvio_player_is_audio_track_selected(playerPtr, i),
                    )
                }
            }

            override fun getSubtitleTracks(): List<SubtitleTrack> {
                val count = bridge.nuvio_player_get_subtitle_track_count(playerPtr)
                return (0 until count).map { i ->
                    SubtitleTrack(
                        index = i,
                        id = bridge.nuvio_player_get_subtitle_track_id(playerPtr, i).toString(),
                        label = bridge.nuvio_player_get_subtitle_track_label(playerPtr, i) ?: "",
                        language = bridge.nuvio_player_get_subtitle_track_lang(playerPtr, i),
                        isSelected = bridge.nuvio_player_is_subtitle_track_selected(playerPtr, i),
                    )
                }
            }

            override fun selectAudioTrack(index: Int) {
                val count = bridge.nuvio_player_get_audio_track_count(playerPtr)
                if (index in 0 until count) {
                    val trackId = bridge.nuvio_player_get_audio_track_id(playerPtr, index)
                    bridge.nuvio_player_select_audio_track(playerPtr, trackId)
                }
            }

            override fun selectSubtitleTrack(index: Int) {
                if (index < 0) {
                    bridge.nuvio_player_select_subtitle_track(playerPtr, -1)
                    return
                }
                val count = bridge.nuvio_player_get_subtitle_track_count(playerPtr)
                if (index in 0 until count) {
                    val trackId = bridge.nuvio_player_get_subtitle_track_id(playerPtr, index)
                    bridge.nuvio_player_select_subtitle_track(playerPtr, trackId)
                }
            }

            override fun setSubtitleUri(url: String) =
                bridge.nuvio_player_set_subtitle_url(playerPtr, url)

            override fun clearExternalSubtitle() =
                bridge.nuvio_player_clear_external_subtitle(playerPtr)

            override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
                val trackId = if (trackIndex >= 0) {
                    val count = bridge.nuvio_player_get_subtitle_track_count(playerPtr)
                    if (trackIndex < count) bridge.nuvio_player_get_subtitle_track_id(playerPtr, trackIndex) else -1
                } else -1
                bridge.nuvio_player_clear_external_subtitle_and_select(playerPtr, trackId)
            }

            override fun applySubtitleStyle(style: SubtitleStyleState) {
                val colorHex = style.textColor.toMpvColorString()
                val outline = if (style.outlineEnabled) 2.0f else 0.0f
                val subPos = 100 - style.bottomOffset
                bridge.nuvio_player_apply_subtitle_style(
                    playerPtr, colorHex, outline, style.fontSizeSp.toFloat(), subPos,
                )
            }

            override fun setMetadata(
                title: String,
                streamTitle: String,
                providerName: String,
                seasonNumber: Int?,
                episodeNumber: Int?,
                episodeTitle: String?,
                artwork: String?,
                logo: String?,
            ) {
                bridge.nuvio_player_set_metadata(
                    playerPtr, title, streamTitle, providerName,
                    seasonNumber ?: 0, episodeNumber ?: 0, episodeTitle,
                    artwork, logo,
                )
            }

            override fun setPlayerFlags(hasVideoId: Boolean, isSeries: Boolean) {
                bridge.nuvio_player_set_has_video_id(playerPtr, hasVideoId)
                bridge.nuvio_player_set_is_series(playerPtr, isSeries)
            }

            override fun showSkipButton(type: String, endTimeMs: Long) {
                bridge.nuvio_player_show_skip_button(playerPtr, type, endTimeMs)
            }

            override fun hideSkipButton() {
                bridge.nuvio_player_hide_skip_button(playerPtr)
            }

            override fun showNextEpisode(
                season: Int,
                episode: Int,
                title: String,
                thumbnail: String?,
                hasAired: Boolean,
            ) {
                bridge.nuvio_player_show_next_episode(playerPtr, season, episode, title, thumbnail, hasAired)
            }

            override fun hideNextEpisode() {
                bridge.nuvio_player_hide_next_episode(playerPtr)
            }

            override fun setOnCloseCallback(callback: () -> Unit) {
                onCloseCallback = callback
            }

            override fun setOnAddonSubtitlesFetchCallback(callback: () -> Unit) {
                onAddonSubtitlesFetchCallback = callback
            }

            override fun pushAddonSubtitles(subtitles: List<AddonSubtitle>, isLoading: Boolean) {
                bridge.nuvio_player_set_addon_subtitles_loading(playerPtr, isLoading)
                if (!isLoading) {
                    bridge.nuvio_player_clear_addon_subtitles(playerPtr)
                    subtitles.forEach { addon ->
                        bridge.nuvio_player_add_addon_subtitle(
                            playerPtr, addon.id, addon.url, addon.language, addon.display,
                        )
                    }
                }
            }

            override fun setOnSourcesRequestedCallback(callback: () -> Unit) {
                onSourcesRequestedCallback = callback
            }

            override fun setOnSourceStreamSelectedCallback(callback: (String) -> Unit) {
                onSourceStreamSelectedCallback = callback
            }

            override fun setOnSourceFilterChangedCallback(callback: (String?) -> Unit) {
                onSourceFilterChangedCallback = callback
            }

            override fun setOnSourceReloadCallback(callback: () -> Unit) {
                onSourceReloadCallback = callback
            }

            override fun setOnEpisodesRequestedCallback(callback: () -> Unit) {
                onEpisodesRequestedCallback = callback
            }

            override fun setOnEpisodeSelectedCallback(callback: (String) -> Unit) {
                onEpisodeSelectedCallback = callback
            }

            override fun setOnEpisodeStreamSelectedCallback(callback: (String) -> Unit) {
                onEpisodeStreamSelectedCallback = callback
            }

            override fun setOnEpisodeFilterChangedCallback(callback: (String?) -> Unit) {
                onEpisodeFilterChangedCallback = callback
            }

            override fun setOnEpisodeReloadCallback(callback: () -> Unit) {
                onEpisodeReloadCallback = callback
            }

            override fun setOnEpisodeBackCallback(callback: () -> Unit) {
                onEpisodeBackCallback = callback
            }

            override fun pushSourceData(
                streams: List<StreamItem>,
                groups: List<AddonStreamGroup>,
                loading: Boolean,
                selectedFilter: String?,
                currentStreamUrl: String?,
            ) {
                bridge.nuvio_player_set_sources_loading(playerPtr, loading)
                bridge.nuvio_player_set_source_selected_filter(playerPtr, selectedFilter)
                bridge.nuvio_player_clear_source_addon_groups(playerPtr)
                groups.forEach { g ->
                    bridge.nuvio_player_add_source_addon_group(
                        playerPtr, g.addonId, g.addonName, g.addonId, g.isLoading, g.error != null,
                    )
                }
                bridge.nuvio_player_clear_source_streams(playerPtr)
                streams.forEach { s ->
                    bridge.nuvio_player_add_source_stream(
                        playerPtr, s.addonId + "_" + (s.url ?: s.infoHash ?: ""),
                        s.streamLabel, s.streamSubtitle, s.addonName, s.addonId,
                        s.directPlaybackUrl ?: "", s.directPlaybackUrl == currentStreamUrl,
                    )
                }
            }

            override fun pushEpisodes(episodes: List<MetaVideo>) {
                bridge.nuvio_player_clear_episodes(playerPtr)
                episodes.forEach { ep ->
                    bridge.nuvio_player_add_episode(
                        playerPtr, ep.id, ep.title, ep.overview, ep.thumbnail,
                        ep.season ?: 0, ep.episode ?: 0,
                    )
                }
            }

            override fun pushEpisodeStreamsData(
                streams: List<StreamItem>,
                groups: List<AddonStreamGroup>,
                loading: Boolean,
                selectedFilter: String?,
                currentStreamUrl: String?,
            ) {
                bridge.nuvio_player_set_episode_streams_loading(playerPtr, loading)
                bridge.nuvio_player_set_episode_selected_filter(playerPtr, selectedFilter)
                bridge.nuvio_player_clear_episode_addon_groups(playerPtr)
                groups.forEach { g ->
                    bridge.nuvio_player_add_episode_addon_group(
                        playerPtr, g.addonId, g.addonName, g.addonId, g.isLoading, g.error != null,
                    )
                }
                bridge.nuvio_player_clear_episode_streams(playerPtr)
                streams.forEach { s ->
                    bridge.nuvio_player_add_episode_stream(
                        playerPtr, s.addonId + "_" + (s.url ?: s.infoHash ?: ""),
                        s.streamLabel, s.streamSubtitle, s.addonName, s.addonId,
                        s.directPlaybackUrl ?: "", s.directPlaybackUrl == currentStreamUrl,
                    )
                }
            }

            override fun showEpisodeStreamsView(season: Int?, episode: Int?, title: String?) {
                bridge.nuvio_player_show_episode_streams(playerPtr, season ?: 0, episode ?: 0, title)
            }

            override fun switchSource(url: String, audioUrl: String?, headersJson: String?) {
                bridge.nuvio_player_load_file(playerPtr, url, audioUrl, headersJson)
            }
        }
    }

    LaunchedEffect(controller) {
        onControllerReady(controller)
    }

    LaunchedEffect(playerPtr) {
        while (true) {
            delay(250)
            if (bridge.nuvio_player_is_closed(playerPtr)) {
                onCloseCallback?.invoke()
                break
            }
            bridge.nuvio_player_refresh_state(playerPtr)
            val snapshot = PlayerPlaybackSnapshot(
                isLoading = bridge.nuvio_player_is_loading(playerPtr),
                isPlaying = bridge.nuvio_player_is_playing(playerPtr),
                isEnded = bridge.nuvio_player_is_ended(playerPtr),
                positionMs = bridge.nuvio_player_get_position_ms(playerPtr),
                durationMs = bridge.nuvio_player_get_duration_ms(playerPtr),
                bufferedPositionMs = bridge.nuvio_player_get_buffered_ms(playerPtr),
                playbackSpeed = bridge.nuvio_player_get_speed(playerPtr),
            )
            onSnapshot(snapshot)
            val error = bridge.nuvio_player_get_error(playerPtr)
            onError(error)
            if (bridge.nuvio_player_is_addon_subtitles_fetch_requested(playerPtr)) {
                onAddonSubtitlesFetchCallback?.invoke()
            }
            if (bridge.nuvio_player_pop_subtitle_style_changed(playerPtr)) {
                val colorIndex = bridge.nuvio_player_get_subtitle_style_color_index(playerPtr)
                    .coerceIn(0, SubtitleColorSwatches.lastIndex)
                val style = SubtitleStyleState(
                    textColor = SubtitleColorSwatches[colorIndex],
                    outlineEnabled = bridge.nuvio_player_get_subtitle_style_outline_enabled(playerPtr),
                    fontSizeSp = bridge.nuvio_player_get_subtitle_style_font_size(playerPtr),
                    bottomOffset = bridge.nuvio_player_get_subtitle_style_bottom_offset(playerPtr),
                )
                PlayerSettingsRepository.setSubtitleStyle(style)
            }
            if (bridge.nuvio_player_pop_next_episode_pressed(playerPtr)) {
            }
            if (bridge.nuvio_player_pop_sources_open_requested(playerPtr)) {
                onSourcesRequestedCallback?.invoke()
            }
            if (bridge.nuvio_player_pop_episodes_open_requested(playerPtr)) {
                onEpisodesRequestedCallback?.invoke()
            }
            bridge.nuvio_player_pop_source_stream_selected(playerPtr)?.let { url ->
                onSourceStreamSelectedCallback?.invoke(url)
            }
            if (bridge.nuvio_player_pop_source_filter_changed(playerPtr)) {
                val filterValue = bridge.nuvio_player_get_source_filter_value(playerPtr)
                onSourceFilterChangedCallback?.invoke(filterValue)
            }
            if (bridge.nuvio_player_pop_source_reload(playerPtr)) {
                onSourceReloadCallback?.invoke()
            }
            bridge.nuvio_player_pop_episode_selected(playerPtr)?.let { episodeId ->
                onEpisodeSelectedCallback?.invoke(episodeId)
            }
            bridge.nuvio_player_pop_episode_stream_selected(playerPtr)?.let { url ->
                onEpisodeStreamSelectedCallback?.invoke(url)
            }
            if (bridge.nuvio_player_pop_episode_filter_changed(playerPtr)) {
                val filterValue = bridge.nuvio_player_get_episode_filter_value(playerPtr)
                onEpisodeFilterChangedCallback?.invoke(filterValue)
            }
            if (bridge.nuvio_player_pop_episode_reload(playerPtr)) {
                onEpisodeReloadCallback?.invoke()
            }
            if (bridge.nuvio_player_pop_episode_back(playerPtr)) {
                onEpisodeBackCallback?.invoke()
            }
        }
    }

    Box(modifier = modifier.background(Color.Black))
}

private fun androidx.compose.ui.graphics.Color.toMpvColorString(): String {
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    return "#${r.hex()}${g.hex()}${b.hex()}${a.hex()}"
}

private fun Int.hex(): String = toString(16).padStart(2, '0').uppercase()

internal actual object DeviceLanguagePreferences {
    actual fun preferredLanguageCodes(): List<String> =
        listOfNotNull(Locale.getDefault().toLanguageTag().takeIf { it.isNotBlank() })
}

internal actual object PlayerSettingsStorage {
    private const val preferencesName = "nuvio_player_settings"
    private const val showLoadingOverlayKey = "show_loading_overlay"
    private const val resizeModeKey = "resize_mode"
    private const val holdToSpeedEnabledKey = "hold_to_speed_enabled"
    private const val holdToSpeedValueKey = "hold_to_speed_value"
    private const val preferredAudioLanguageKey = "preferred_audio_language"
    private const val secondaryPreferredAudioLanguageKey = "secondary_preferred_audio_language"
    private const val preferredSubtitleLanguageKey = "preferred_subtitle_language"
    private const val secondaryPreferredSubtitleLanguageKey = "secondary_preferred_subtitle_language"
    private const val subtitleTextColorKey = "subtitle_text_color"
    private const val subtitleOutlineEnabledKey = "subtitle_outline_enabled"
    private const val subtitleFontSizeSpKey = "subtitle_font_size_sp"
    private const val subtitleBottomOffsetKey = "subtitle_bottom_offset"
    private const val streamReuseLastLinkEnabledKey = "stream_reuse_last_link_enabled"
    private const val streamReuseLastLinkCacheHoursKey = "stream_reuse_last_link_cache_hours"
    private const val decoderPriorityKey = "decoder_priority"
    private const val mapDV7ToHevcKey = "map_dv7_to_hevc"
    private const val tunnelingEnabledKey = "tunneling_enabled"
    private const val streamAutoPlayModeKey = "stream_auto_play_mode"
    private const val streamAutoPlaySourceKey = "stream_auto_play_source"
    private const val streamAutoPlaySelectedAddonsKey = "stream_auto_play_selected_addons"
    private const val streamAutoPlaySelectedPluginsKey = "stream_auto_play_selected_plugins"
    private const val streamAutoPlayRegexKey = "stream_auto_play_regex"
    private const val streamAutoPlayTimeoutSecondsKey = "stream_auto_play_timeout_seconds"
    private const val skipIntroEnabledKey = "skip_intro_enabled"
    private const val animeSkipEnabledKey = "animeskip_enabled"
    private const val animeSkipClientIdKey = "animeskip_client_id"
    private const val streamAutoPlayNextEpisodeEnabledKey = "stream_auto_play_next_episode_enabled"
    private const val streamAutoPlayPreferBingeGroupKey = "stream_auto_play_prefer_binge_group"
    private const val nextEpisodeThresholdModeKey = "next_episode_threshold_mode"
    private const val nextEpisodeThresholdPercentKey = "next_episode_threshold_percent_v2"
    private const val nextEpisodeThresholdMinutesBeforeEndKey = "next_episode_threshold_minutes_before_end_v2"
    private const val useLibassKey = "use_libass"
    private const val libassRenderTypeKey = "libass_render_type"
    private val syncKeys = listOf(
        showLoadingOverlayKey,
        resizeModeKey,
        holdToSpeedEnabledKey,
        holdToSpeedValueKey,
        preferredAudioLanguageKey,
        secondaryPreferredAudioLanguageKey,
        preferredSubtitleLanguageKey,
        secondaryPreferredSubtitleLanguageKey,
        streamReuseLastLinkEnabledKey,
        streamReuseLastLinkCacheHoursKey,
        decoderPriorityKey,
        mapDV7ToHevcKey,
        tunnelingEnabledKey,
        streamAutoPlayModeKey,
        streamAutoPlaySourceKey,
        streamAutoPlaySelectedAddonsKey,
        streamAutoPlaySelectedPluginsKey,
        streamAutoPlayRegexKey,
        streamAutoPlayTimeoutSecondsKey,
        skipIntroEnabledKey,
        animeSkipEnabledKey,
        animeSkipClientIdKey,
        streamAutoPlayNextEpisodeEnabledKey,
        streamAutoPlayPreferBingeGroupKey,
        nextEpisodeThresholdModeKey,
        nextEpisodeThresholdPercentKey,
        nextEpisodeThresholdMinutesBeforeEndKey,
        useLibassKey,
        libassRenderTypeKey,
    )

    actual fun loadShowLoadingOverlay(): Boolean? = loadBoolean(showLoadingOverlayKey)

    actual fun saveShowLoadingOverlay(enabled: Boolean) {
        saveBoolean(showLoadingOverlayKey, enabled)
    }

    actual fun loadResizeMode(): String? = loadString(resizeModeKey)

    actual fun saveResizeMode(mode: String) {
        saveString(resizeModeKey, mode)
    }

    actual fun loadHoldToSpeedEnabled(): Boolean? = loadBoolean(holdToSpeedEnabledKey)

    actual fun saveHoldToSpeedEnabled(enabled: Boolean) {
        saveBoolean(holdToSpeedEnabledKey, enabled)
    }

    actual fun loadHoldToSpeedValue(): Float? = loadFloat(holdToSpeedValueKey)

    actual fun saveHoldToSpeedValue(speed: Float) {
        saveFloat(holdToSpeedValueKey, speed)
    }

    actual fun loadPreferredAudioLanguage(): String? = loadString(preferredAudioLanguageKey)

    actual fun savePreferredAudioLanguage(language: String) {
        saveString(preferredAudioLanguageKey, language)
    }

    actual fun loadSecondaryPreferredAudioLanguage(): String? = loadString(secondaryPreferredAudioLanguageKey)

    actual fun saveSecondaryPreferredAudioLanguage(language: String?) {
        saveNullableString(secondaryPreferredAudioLanguageKey, language)
    }

    actual fun loadPreferredSubtitleLanguage(): String? = loadString(preferredSubtitleLanguageKey)

    actual fun savePreferredSubtitleLanguage(language: String) {
        saveString(preferredSubtitleLanguageKey, language)
    }

    actual fun loadSecondaryPreferredSubtitleLanguage(): String? = loadString(secondaryPreferredSubtitleLanguageKey)

    actual fun saveSecondaryPreferredSubtitleLanguage(language: String?) {
        saveNullableString(secondaryPreferredSubtitleLanguageKey, language)
    }

    actual fun loadSubtitleTextColor(): String? = loadString(subtitleTextColorKey)

    actual fun saveSubtitleTextColor(colorHex: String) {
        saveString(subtitleTextColorKey, colorHex)
    }

    actual fun loadSubtitleOutlineEnabled(): Boolean? = loadBoolean(subtitleOutlineEnabledKey)

    actual fun saveSubtitleOutlineEnabled(enabled: Boolean) {
        saveBoolean(subtitleOutlineEnabledKey, enabled)
    }

    actual fun loadSubtitleFontSizeSp(): Int? = loadInt(subtitleFontSizeSpKey)

    actual fun saveSubtitleFontSizeSp(fontSizeSp: Int) {
        saveInt(subtitleFontSizeSpKey, fontSizeSp)
    }

    actual fun loadSubtitleBottomOffset(): Int? = loadInt(subtitleBottomOffsetKey)

    actual fun saveSubtitleBottomOffset(bottomOffset: Int) {
        saveInt(subtitleBottomOffsetKey, bottomOffset)
    }

    actual fun loadStreamReuseLastLinkEnabled(): Boolean? = loadBoolean(streamReuseLastLinkEnabledKey)

    actual fun saveStreamReuseLastLinkEnabled(enabled: Boolean) {
        saveBoolean(streamReuseLastLinkEnabledKey, enabled)
    }

    actual fun loadStreamReuseLastLinkCacheHours(): Int? = loadInt(streamReuseLastLinkCacheHoursKey)

    actual fun saveStreamReuseLastLinkCacheHours(hours: Int) {
        saveInt(streamReuseLastLinkCacheHoursKey, hours)
    }

    actual fun loadDecoderPriority(): Int? = loadInt(decoderPriorityKey)

    actual fun saveDecoderPriority(priority: Int) {
        saveInt(decoderPriorityKey, priority)
    }

    actual fun loadMapDV7ToHevc(): Boolean? = loadBoolean(mapDV7ToHevcKey)

    actual fun saveMapDV7ToHevc(enabled: Boolean) {
        saveBoolean(mapDV7ToHevcKey, enabled)
    }

    actual fun loadTunnelingEnabled(): Boolean? = loadBoolean(tunnelingEnabledKey)

    actual fun saveTunnelingEnabled(enabled: Boolean) {
        saveBoolean(tunnelingEnabledKey, enabled)
    }

    actual fun loadStreamAutoPlayMode(): String? = loadString(streamAutoPlayModeKey)

    actual fun saveStreamAutoPlayMode(mode: String) {
        saveString(streamAutoPlayModeKey, mode)
    }

    actual fun loadStreamAutoPlaySource(): String? = loadString(streamAutoPlaySourceKey)

    actual fun saveStreamAutoPlaySource(source: String) {
        saveString(streamAutoPlaySourceKey, source)
    }

    actual fun loadStreamAutoPlaySelectedAddons(): Set<String>? = loadStringSet(streamAutoPlaySelectedAddonsKey)

    actual fun saveStreamAutoPlaySelectedAddons(addons: Set<String>) {
        saveStringSet(streamAutoPlaySelectedAddonsKey, addons)
    }

    actual fun loadStreamAutoPlaySelectedPlugins(): Set<String>? = loadStringSet(streamAutoPlaySelectedPluginsKey)

    actual fun saveStreamAutoPlaySelectedPlugins(plugins: Set<String>) {
        saveStringSet(streamAutoPlaySelectedPluginsKey, plugins)
    }

    actual fun loadStreamAutoPlayRegex(): String? = loadString(streamAutoPlayRegexKey)

    actual fun saveStreamAutoPlayRegex(regex: String) {
        saveString(streamAutoPlayRegexKey, regex)
    }

    actual fun loadStreamAutoPlayTimeoutSeconds(): Int? = loadInt(streamAutoPlayTimeoutSecondsKey)

    actual fun saveStreamAutoPlayTimeoutSeconds(seconds: Int) {
        saveInt(streamAutoPlayTimeoutSecondsKey, seconds)
    }

    actual fun loadSkipIntroEnabled(): Boolean? = loadBoolean(skipIntroEnabledKey)

    actual fun saveSkipIntroEnabled(enabled: Boolean) {
        saveBoolean(skipIntroEnabledKey, enabled)
    }

    actual fun loadAnimeSkipEnabled(): Boolean? = loadBoolean(animeSkipEnabledKey)

    actual fun saveAnimeSkipEnabled(enabled: Boolean) {
        saveBoolean(animeSkipEnabledKey, enabled)
    }

    actual fun loadAnimeSkipClientId(): String? = loadString(animeSkipClientIdKey)

    actual fun saveAnimeSkipClientId(clientId: String) {
        saveString(animeSkipClientIdKey, clientId)
    }

    actual fun loadStreamAutoPlayNextEpisodeEnabled(): Boolean? = loadBoolean(streamAutoPlayNextEpisodeEnabledKey)

    actual fun saveStreamAutoPlayNextEpisodeEnabled(enabled: Boolean) {
        saveBoolean(streamAutoPlayNextEpisodeEnabledKey, enabled)
    }

    actual fun loadStreamAutoPlayPreferBingeGroup(): Boolean? = loadBoolean(streamAutoPlayPreferBingeGroupKey)

    actual fun saveStreamAutoPlayPreferBingeGroup(enabled: Boolean) {
        saveBoolean(streamAutoPlayPreferBingeGroupKey, enabled)
    }

    actual fun loadNextEpisodeThresholdMode(): String? = loadString(nextEpisodeThresholdModeKey)

    actual fun saveNextEpisodeThresholdMode(mode: String) {
        saveString(nextEpisodeThresholdModeKey, mode)
    }

    actual fun loadNextEpisodeThresholdPercent(): Float? = loadFloat(nextEpisodeThresholdPercentKey)

    actual fun saveNextEpisodeThresholdPercent(percent: Float) {
        saveFloat(nextEpisodeThresholdPercentKey, percent)
    }

    actual fun loadNextEpisodeThresholdMinutesBeforeEnd(): Float? = loadFloat(nextEpisodeThresholdMinutesBeforeEndKey)

    actual fun saveNextEpisodeThresholdMinutesBeforeEnd(minutes: Float) {
        saveFloat(nextEpisodeThresholdMinutesBeforeEndKey, minutes)
    }

    actual fun loadUseLibass(): Boolean? = loadBoolean(useLibassKey)

    actual fun saveUseLibass(enabled: Boolean) {
        saveBoolean(useLibassKey, enabled)
    }

    actual fun loadLibassRenderType(): String? = loadString(libassRenderTypeKey)

    actual fun saveLibassRenderType(renderType: String) {
        saveString(libassRenderTypeKey, renderType)
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadShowLoadingOverlay()?.let { put(showLoadingOverlayKey, encodeSyncBoolean(it)) }
        loadResizeMode()?.let { put(resizeModeKey, encodeSyncString(it)) }
        loadHoldToSpeedEnabled()?.let { put(holdToSpeedEnabledKey, encodeSyncBoolean(it)) }
        loadHoldToSpeedValue()?.let { put(holdToSpeedValueKey, encodeSyncFloat(it)) }
        loadPreferredAudioLanguage()?.let { put(preferredAudioLanguageKey, encodeSyncString(it)) }
        loadSecondaryPreferredAudioLanguage()?.let { put(secondaryPreferredAudioLanguageKey, encodeSyncString(it)) }
        loadPreferredSubtitleLanguage()?.let { put(preferredSubtitleLanguageKey, encodeSyncString(it)) }
        loadSecondaryPreferredSubtitleLanguage()?.let { put(secondaryPreferredSubtitleLanguageKey, encodeSyncString(it)) }
        loadStreamReuseLastLinkEnabled()?.let { put(streamReuseLastLinkEnabledKey, encodeSyncBoolean(it)) }
        loadStreamReuseLastLinkCacheHours()?.let { put(streamReuseLastLinkCacheHoursKey, encodeSyncInt(it)) }
        loadDecoderPriority()?.let { put(decoderPriorityKey, encodeSyncInt(it)) }
        loadMapDV7ToHevc()?.let { put(mapDV7ToHevcKey, encodeSyncBoolean(it)) }
        loadTunnelingEnabled()?.let { put(tunnelingEnabledKey, encodeSyncBoolean(it)) }
        loadStreamAutoPlayMode()?.let { put(streamAutoPlayModeKey, encodeSyncString(it)) }
        loadStreamAutoPlaySource()?.let { put(streamAutoPlaySourceKey, encodeSyncString(it)) }
        loadStreamAutoPlaySelectedAddons()?.let { put(streamAutoPlaySelectedAddonsKey, encodeSyncStringSet(it)) }
        loadStreamAutoPlaySelectedPlugins()?.let { put(streamAutoPlaySelectedPluginsKey, encodeSyncStringSet(it)) }
        loadStreamAutoPlayRegex()?.let { put(streamAutoPlayRegexKey, encodeSyncString(it)) }
        loadStreamAutoPlayTimeoutSeconds()?.let { put(streamAutoPlayTimeoutSecondsKey, encodeSyncInt(it)) }
        loadSkipIntroEnabled()?.let { put(skipIntroEnabledKey, encodeSyncBoolean(it)) }
        loadAnimeSkipEnabled()?.let { put(animeSkipEnabledKey, encodeSyncBoolean(it)) }
        loadAnimeSkipClientId()?.let { put(animeSkipClientIdKey, encodeSyncString(it)) }
        loadStreamAutoPlayNextEpisodeEnabled()?.let { put(streamAutoPlayNextEpisodeEnabledKey, encodeSyncBoolean(it)) }
        loadStreamAutoPlayPreferBingeGroup()?.let { put(streamAutoPlayPreferBingeGroupKey, encodeSyncBoolean(it)) }
        loadNextEpisodeThresholdMode()?.let { put(nextEpisodeThresholdModeKey, encodeSyncString(it)) }
        loadNextEpisodeThresholdPercent()?.let { put(nextEpisodeThresholdPercentKey, encodeSyncFloat(it)) }
        loadNextEpisodeThresholdMinutesBeforeEnd()?.let { put(nextEpisodeThresholdMinutesBeforeEndKey, encodeSyncFloat(it)) }
        loadUseLibass()?.let { put(useLibassKey, encodeSyncBoolean(it)) }
        loadLibassRenderType()?.let { put(libassRenderTypeKey, encodeSyncString(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        syncKeys.forEach { DesktopPreferences.remove(preferencesName, ProfileScopedKey.of(it)) }

        payload.decodeSyncBoolean(showLoadingOverlayKey)?.let(::saveShowLoadingOverlay)
        payload.decodeSyncString(resizeModeKey)?.let(::saveResizeMode)
        payload.decodeSyncBoolean(holdToSpeedEnabledKey)?.let(::saveHoldToSpeedEnabled)
        payload.decodeSyncFloat(holdToSpeedValueKey)?.let(::saveHoldToSpeedValue)
        payload.decodeSyncString(preferredAudioLanguageKey)?.let(::savePreferredAudioLanguage)
        payload.decodeSyncString(secondaryPreferredAudioLanguageKey)?.let(::saveSecondaryPreferredAudioLanguage)
        payload.decodeSyncString(preferredSubtitleLanguageKey)?.let(::savePreferredSubtitleLanguage)
        payload.decodeSyncString(secondaryPreferredSubtitleLanguageKey)?.let(::saveSecondaryPreferredSubtitleLanguage)
        payload.decodeSyncBoolean(streamReuseLastLinkEnabledKey)?.let(::saveStreamReuseLastLinkEnabled)
        payload.decodeSyncInt(streamReuseLastLinkCacheHoursKey)?.let(::saveStreamReuseLastLinkCacheHours)
        payload.decodeSyncInt(decoderPriorityKey)?.let(::saveDecoderPriority)
        payload.decodeSyncBoolean(mapDV7ToHevcKey)?.let(::saveMapDV7ToHevc)
        payload.decodeSyncBoolean(tunnelingEnabledKey)?.let(::saveTunnelingEnabled)
        payload.decodeSyncString(streamAutoPlayModeKey)?.let(::saveStreamAutoPlayMode)
        payload.decodeSyncString(streamAutoPlaySourceKey)?.let(::saveStreamAutoPlaySource)
        payload.decodeSyncStringSet(streamAutoPlaySelectedAddonsKey)?.let(::saveStreamAutoPlaySelectedAddons)
        payload.decodeSyncStringSet(streamAutoPlaySelectedPluginsKey)?.let(::saveStreamAutoPlaySelectedPlugins)
        payload.decodeSyncString(streamAutoPlayRegexKey)?.let(::saveStreamAutoPlayRegex)
        payload.decodeSyncInt(streamAutoPlayTimeoutSecondsKey)?.let(::saveStreamAutoPlayTimeoutSeconds)
        payload.decodeSyncBoolean(skipIntroEnabledKey)?.let(::saveSkipIntroEnabled)
        payload.decodeSyncBoolean(animeSkipEnabledKey)?.let(::saveAnimeSkipEnabled)
        payload.decodeSyncString(animeSkipClientIdKey)?.let(::saveAnimeSkipClientId)
        payload.decodeSyncBoolean(streamAutoPlayNextEpisodeEnabledKey)?.let(::saveStreamAutoPlayNextEpisodeEnabled)
        payload.decodeSyncBoolean(streamAutoPlayPreferBingeGroupKey)?.let(::saveStreamAutoPlayPreferBingeGroup)
        payload.decodeSyncString(nextEpisodeThresholdModeKey)?.let(::saveNextEpisodeThresholdMode)
        payload.decodeSyncFloat(nextEpisodeThresholdPercentKey)?.let(::saveNextEpisodeThresholdPercent)
        payload.decodeSyncFloat(nextEpisodeThresholdMinutesBeforeEndKey)?.let(::saveNextEpisodeThresholdMinutesBeforeEnd)
        payload.decodeSyncBoolean(useLibassKey)?.let(::saveUseLibass)
        payload.decodeSyncString(libassRenderTypeKey)?.let(::saveLibassRenderType)
    }

    private fun scopedKey(baseKey: String): String = ProfileScopedKey.of(baseKey)

    private fun loadString(key: String): String? =
        DesktopPreferences.getString(preferencesName, scopedKey(key))

    private fun saveString(key: String, value: String) {
        DesktopPreferences.putString(preferencesName, scopedKey(key), value)
    }

    private fun saveNullableString(key: String, value: String?) {
        if (value.isNullOrBlank()) {
            DesktopPreferences.remove(preferencesName, scopedKey(key))
        } else {
            DesktopPreferences.putString(preferencesName, scopedKey(key), value)
        }
    }

    private fun loadBoolean(key: String): Boolean? =
        DesktopPreferences.getBoolean(preferencesName, scopedKey(key))

    private fun saveBoolean(key: String, value: Boolean) {
        DesktopPreferences.putBoolean(preferencesName, scopedKey(key), value)
    }

    private fun loadInt(key: String): Int? =
        DesktopPreferences.getInt(preferencesName, scopedKey(key))

    private fun saveInt(key: String, value: Int) {
        DesktopPreferences.putInt(preferencesName, scopedKey(key), value)
    }

    private fun loadFloat(key: String): Float? =
        DesktopPreferences.getFloat(preferencesName, scopedKey(key))

    private fun saveFloat(key: String, value: Float) {
        DesktopPreferences.putFloat(preferencesName, scopedKey(key), value)
    }

    private fun loadStringSet(key: String): Set<String>? =
        DesktopPreferences.getStringSet(preferencesName, scopedKey(key))

    private fun saveStringSet(key: String, values: Set<String>) {
        DesktopPreferences.putStringSet(preferencesName, scopedKey(key), values)
    }
}

@Composable
actual fun LockPlayerToLandscape() = Unit

@Composable
actual fun EnterImmersivePlayerMode() = Unit

@Composable
actual fun ManagePlayerPictureInPicture(
    isPlaying: Boolean,
    playerSize: IntSize,
) = Unit

@Composable
actual fun rememberPlayerGestureController(): PlayerGestureController? = remember {
    DesktopPlayerGestureController
}

actual val usesNativePlayerChrome: Boolean
    get() = isMacOS

actual val usesAnimatedPlayerChrome: Boolean = false

// Disable the "split layout" branch in PlayerScreen — with the JWindow-based native overlay
// below, the video Canvas can stay fullscreen and chrome composables float above it as a
// real overlay, just like on Android/iOS.
actual val requiresExternalPlayerControls: Boolean
    get() = false

// Enable the JWindow-based native overlay for Windows VLC. macOS keeps its system chrome,
// Windows MPV / Native bridge backends run their own UI path.
actual val usesNativePlayerOverlay: Boolean
    get() = !isMacOS && resolveWindowsDesktopBackend().backend == WindowsDesktopBackend.VLC

private object Win32Window {
    const val GWL_EXSTYLE = -20
    const val WS_EX_TOOLWINDOW = 0x00000080
    const val WS_EX_APPWINDOW = 0x00040000

    private interface User32 : com.sun.jna.Library {
        fun GetWindowLongPtr(hWnd: Pointer?, nIndex: Int): Long
        fun SetWindowLongPtr(hWnd: Pointer?, nIndex: Int, dwNewLong: Long): Long
    }

    private val user32: User32? = runCatching { Native.load("user32", User32::class.java) as User32 }.getOrNull()

    fun applyToolWindowStyle(hwnd: Pointer?) {
        val u = user32 ?: return
        if (hwnd == null) return
        runCatching {
            val current = u.GetWindowLongPtr(hwnd, GWL_EXSTYLE)
            val newStyle = (current or WS_EX_TOOLWINDOW.toLong()) and WS_EX_APPWINDOW.toLong().inv()
            u.SetWindowLongPtr(hwnd, GWL_EXSTYLE, newStyle)
        }
    }
}

/**
 * Native overlay implementation for Windows VLC.
 *
 * Architecture: a sibling top-level transparent Compose window slaved to the VLC container's
 * on-screen rectangle.
 *
 * Why a separate Compose [androidx.compose.ui.window.Window] instead of an embedded
 * [androidx.compose.ui.awt.ComposePanel]:
 *  - VLC's video output is a heavyweight AWT Canvas painted directly into its native HWND
 *    (Direct3D11/wingdi). On Windows, heavyweight peers win the z-order race against any
 *    lightweight Swing/Compose sibling inside the same JLayeredPane, so a ComposePanel
 *    sibling is invisible.
 *  - A bare [javax.swing.JWindow] hosting a ComposePanel does z-order correctly above the
 *    Canvas, but the embedded SkiaLayer used by ComposePanel is opaque by default — the
 *    overlay paints solid black on top of the video. There is no public API to flip the
 *    SkiaLayer to transparent.
 *  - The supported way to opt into per-pixel translucency in Compose Desktop is
 *    `Window(transparent = true)`. That call configures the underlying Skiko layer for
 *    transparent compositing AND configures the AWT window for alpha. We piggy-back on it
 *    to host the chrome.
 *
 * Implementation notes:
 *  - Bounds: we mirror the VLC container's `locationOnScreen` / `width` / `height` into a
 *    [androidx.compose.ui.window.WindowState] using the parent composition's [Density] so
 *    the overlay tracks the video region across move/resize/iconify of the main window.
 *  - Theming/content: Compose [androidx.compose.ui.window.Window] starts a brand-new
 *    composition with no inherited CompositionLocals, so we re-apply [NuvioTheme] inside.
 *  - Click handling: the chrome's own gesture handlers cover the whole overlay; we never
 *    want click-through, we just want the events to land on PlayerScreen's pointerInput
 *    block. When [visible]==false we omit the Window entirely so input falls back to the
 *    main app.
 *  - Taskbar: we set `window.type = UTILITY` post-creation. On Windows that hides the
 *    overlay from Alt+Tab and (depending on shell) the taskbar. If a stray entry shows up
 *    we can layer a JNA `WS_EX_TOOLWINDOW` patch on top later — kept minimal for now.
 */
@Composable
actual fun NativePlayerOverlay(
    modifier: Modifier,
    visible: Boolean,
    content: @Composable () -> Unit
) {
    if (!usesNativePlayerOverlay) {
        if (visible) {
            androidx.compose.foundation.layout.Box(modifier = modifier) {
                content()
            }
        }
        return
    }

    val mainWindow = LocalDesktopWindow.current
    val container = activeVlcOverlayContainerState.value

    val appTheme = LocalAppTheme.current
    val amoled = LocalAmoledEnabled.current
    val darkTheme = isSystemInDarkTheme()
    val appThemeState = rememberUpdatedState(appTheme)
    val amoledState = rememberUpdatedState(amoled)
    val darkThemeState = rememberUpdatedState(darkTheme)
    val contentState = rememberUpdatedState(content)

    // Placeholder so the parent layout still reserves the same slot as the previous designs.
    androidx.compose.foundation.layout.Box(modifier = modifier)

    if (!visible || mainWindow == null || container == null) return

    val parentDensity = LocalDensity.current

    // (x, y) in user-space px (Java's HiDPI-aware coordinates) of the VLC AWT container.
    var trackedPosition by remember { mutableStateOf<IntOffset?>(null) }
    var trackedSize by remember { mutableStateOf<IntSize?>(null) }

    // Keep a reference to the overlay AWT window so we can manipulate z-order safely.
    val awtWindowRef = remember { java.util.concurrent.atomic.AtomicReference<java.awt.Window?>(null) }

    fun raiseOverlayWindow() {
        val wnd = awtWindowRef.get() ?: return
        javax.swing.SwingUtilities.invokeLater {
            runCatching {
                if (!wnd.isDisplayable || !wnd.isVisible) return@runCatching
                val prev = wnd.isAlwaysOnTop
                try {
                    if (!prev) wnd.isAlwaysOnTop = true
                    wnd.toFront()
                } finally {
                    if (!prev) wnd.isAlwaysOnTop = false
                }
                DesktopRuntimeDiagnostics.info("PlayerDesktop", "Overlay raised to front")
            }
        }
    }

    DisposableEffect(mainWindow, container) {
        fun refresh() {
            if (!container.isShowing || !mainWindow.isShowing) {
                trackedPosition = null
                trackedSize = null
                return
            }
            val w = container.width
            val h = container.height
            if (w <= 0 || h <= 0) return
            val onScreen = try {
                container.locationOnScreen
            } catch (_: java.awt.IllegalComponentStateException) {
                return
            }
            trackedPosition = IntOffset(onScreen.x, onScreen.y)
            trackedSize = IntSize(w, h)
        }

        val componentListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                refresh()
                raiseOverlayWindow()
            }

            override fun componentMoved(e: ComponentEvent) {
                refresh()
                raiseOverlayWindow()
            }

            override fun componentShown(e: ComponentEvent) = refresh()

            override fun componentHidden(e: ComponentEvent) {
                trackedPosition = null
                trackedSize = null
            }
        }
        val mainFrame = mainWindow as? java.awt.Frame
        val windowAdapter = object : java.awt.event.WindowAdapter() {
            override fun windowIconified(e: java.awt.event.WindowEvent?) {
                trackedPosition = null
                trackedSize = null
            }

            override fun windowDeiconified(e: java.awt.event.WindowEvent?) {
                refresh()
                raiseOverlayWindow()
            }

            override fun windowActivated(e: java.awt.event.WindowEvent?) {
                refresh()
                raiseOverlayWindow()
            }

            override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {
                refresh()
                raiseOverlayWindow()
            }
        }

        container.addComponentListener(componentListener)
        mainWindow.addComponentListener(componentListener)
        mainFrame?.addWindowListener(windowAdapter)
        mainFrame?.addWindowStateListener(windowAdapter)
        javax.swing.SwingUtilities.invokeLater {
            refresh()
            raiseOverlayWindow()
        }

        onDispose {
            container.removeComponentListener(componentListener)
            mainWindow.removeComponentListener(componentListener)
            mainFrame?.removeWindowListener(windowAdapter)
            mainFrame?.removeWindowStateListener(windowAdapter)
        }
    }

    val pos = trackedPosition ?: return
    val size = trackedSize ?: return

    val overlayPosition = with(parentDensity) {
        androidx.compose.ui.window.WindowPosition(pos.x.toDp(), pos.y.toDp())
    }
    val overlaySize = with(parentDensity) {
        androidx.compose.ui.unit.DpSize(size.width.toDp(), size.height.toDp())
    }

    val overlayState = androidx.compose.ui.window.rememberWindowState(
        position = overlayPosition,
        size = overlaySize,
    )

    // Update the actual AWT Window bounds in pixel coordinates when the tracked
    // position/size change. This avoids DPI/rounding artefacts from Dp conversions
    // and keeps the overlay tightly synced with the VLC container.
    LaunchedEffect(pos, size) {
        val awtWindow = awtWindowRef.get()
        if (awtWindow != null) {
            javax.swing.SwingUtilities.invokeLater {
                runCatching {
                    awtWindow.setBounds(pos.x, pos.y, size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
                }.onFailure {
                    DesktopRuntimeDiagnostics.warn("PlayerDesktop", "Failed to set overlay bounds", it)
                }
                // Ensure overlay is in front after a bounds change.
                raiseOverlayWindow()
            }
        }
    }

    androidx.compose.ui.window.Window(
        onCloseRequest = {},
        state = overlayState,
        title = "",
        transparent = true,
        undecorated = true,
        resizable = false,
        focusable = true,
        alwaysOnTop = false,
    ) {
        DisposableEffect(window) {
            // Keep a reference to the underlying AWT window so we can set pixel bounds
            // from outside the composition and apply native Win32 tweaks.
            awtWindowRef.set(window)

            // Hide the helper window from the taskbar / alt-tab on systems that honour
            // Window.Type. (Windows partially respects this — fine to call defensively.)
            runCatching { window.type = java.awt.Window.Type.UTILITY }

            // Make sure no stray opaque background bleeds through Skia's transparent layer.
            runCatching {
                window.background = java.awt.Color(0, 0, 0, 0)
                (window.contentPane as? javax.swing.JComponent)?.let {
                    it.isOpaque = false
                    it.background = java.awt.Color(0, 0, 0, 0)
                }
            }

            // Apply lightweight Win32 toolwindow style to avoid taskbar/Alt-Tab entry.
            if (!isMacOS) {
                runCatching {
                    val hwndPtr = Native.getComponentPointer(window)
                    Win32Window.applyToolWindowStyle(hwndPtr)
                }.onFailure {
                    DesktopRuntimeDiagnostics.warn("PlayerDesktop", "Failed to apply Win32 toolwindow style", it)
                }
            }

            onDispose { awtWindowRef.set(null) }
        }
        NuvioTheme(
            appTheme = appThemeState.value,
            amoled = amoledState.value,
            darkTheme = darkThemeState.value,
        ) {
            contentState.value()
        }
    }
}
