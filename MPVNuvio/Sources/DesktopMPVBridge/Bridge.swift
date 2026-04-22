import Foundation
import AppKit

private var _lastReturnedString: NSString?

private func retainAndReturn(_ s: String) -> UnsafePointer<CChar>? {
    let ns = s as NSString
    _lastReturnedString = ns
    return ns.utf8String
}

private func player(_ ptr: UnsafeMutableRawPointer) -> NuvioPlayerWindow {
    return Unmanaged<NuvioPlayerWindow>.fromOpaque(ptr).takeUnretainedValue()
}

@_cdecl("nuvio_player_create")
public func nuvio_player_create() -> UnsafeMutableRawPointer {
    let p = NuvioPlayerWindow()
    return Unmanaged.passRetained(p).toOpaque()
}

@_cdecl("nuvio_player_destroy")
public func nuvio_player_destroy(_ ptr: UnsafeMutableRawPointer) {
    let p = Unmanaged<NuvioPlayerWindow>.fromOpaque(ptr).takeRetainedValue()
    p.close()
}

@_cdecl("nuvio_player_show")
public func nuvio_player_show(_ ptr: UnsafeMutableRawPointer) {
    player(ptr).show()
}

@_cdecl("nuvio_player_set_metadata")
public func nuvio_player_set_metadata(
    _ ptr: UnsafeMutableRawPointer,
    _ title: UnsafePointer<CChar>,
    _ streamTitle: UnsafePointer<CChar>,
    _ providerName: UnsafePointer<CChar>,
    _ season: Int32,
    _ episode: Int32,
    _ episodeTitle: UnsafePointer<CChar>?,
    _ artwork: UnsafePointer<CChar>?,
    _ logo: UnsafePointer<CChar>?
) {
    let p = player(ptr)
    DispatchQueue.main.async {
        p.state.title = String(cString: title)
        p.state.streamTitle = String(cString: streamTitle)
        p.state.providerName = String(cString: providerName)
        p.state.seasonNumber = season > 0 ? Int(season) : nil
        p.state.episodeNumber = episode > 0 ? Int(episode) : nil
        p.state.episodeTitle = episodeTitle.map { String(cString: $0) }
        p.state.artwork = artwork.map { String(cString: $0) }
        p.state.logo = logo.map { String(cString: $0) }
    }
}

@_cdecl("nuvio_player_set_has_video_id")
public func nuvio_player_set_has_video_id(_ ptr: UnsafeMutableRawPointer, _ value: Bool) {
    let p = player(ptr)
    DispatchQueue.main.async { p.state.hasVideoId = value }
}

@_cdecl("nuvio_player_set_is_series")
public func nuvio_player_set_is_series(_ ptr: UnsafeMutableRawPointer, _ value: Bool) {
    let p = player(ptr)
    DispatchQueue.main.async { p.state.isSeries = value }
}

@_cdecl("nuvio_player_load_file")
public func nuvio_player_load_file(
    _ ptr: UnsafeMutableRawPointer,
    _ url: UnsafePointer<CChar>,
    _ audioUrl: UnsafePointer<CChar>?,
    _ headersJson: UnsafePointer<CChar>?
) {
    let p = player(ptr)
    let urlStr = String(cString: url)
    let audioUrlStr = audioUrl.map { String(cString: $0) }
    var headers: [String: String] = [:]
    if let hj = headersJson {
        let jsonStr = String(cString: hj)
        if let data = jsonStr.data(using: .utf8),
           let raw = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            raw.forEach { key, value in
                if let v = value as? String { headers[key] = v }
            }
        }
    }
    DispatchQueue.main.async {
        p.mpvView?.loadFile(urlStr, audioUrl: audioUrlStr, requestHeaders: headers)
    }
}

@_cdecl("nuvio_player_play")
public func nuvio_player_play(_ ptr: UnsafeMutableRawPointer) {
    DispatchQueue.main.async { player(ptr).mpvView?.playPlayback() }
}

@_cdecl("nuvio_player_pause")
public func nuvio_player_pause(_ ptr: UnsafeMutableRawPointer) {
    DispatchQueue.main.async { player(ptr).mpvView?.pausePlayback() }
}

@_cdecl("nuvio_player_seek_to")
public func nuvio_player_seek_to(_ ptr: UnsafeMutableRawPointer, _ positionMs: Int64) {
    DispatchQueue.main.async { player(ptr).mpvView?.seekToMs(positionMs) }
}

@_cdecl("nuvio_player_seek_by")
public func nuvio_player_seek_by(_ ptr: UnsafeMutableRawPointer, _ offsetMs: Int64) {
    DispatchQueue.main.async { player(ptr).mpvView?.seekByMs(offsetMs) }
}

@_cdecl("nuvio_player_set_speed")
public func nuvio_player_set_speed(_ ptr: UnsafeMutableRawPointer, _ speed: Float) {
    DispatchQueue.main.async { player(ptr).mpvView?.setSpeed(speed) }
}

@_cdecl("nuvio_player_set_resize_mode")
public func nuvio_player_set_resize_mode(_ ptr: UnsafeMutableRawPointer, _ mode: Int32) {
    let p = player(ptr)
    DispatchQueue.main.async {
        p.state.resizeMode = Int(mode)
        p.mpvView?.setResize(Int(mode))
    }
}

@_cdecl("nuvio_player_retry")
public func nuvio_player_retry(_ ptr: UnsafeMutableRawPointer) {
    DispatchQueue.main.async { player(ptr).mpvView?.retryPlayback() }
}

@_cdecl("nuvio_player_refresh_state")
public func nuvio_player_refresh_state(_ ptr: UnsafeMutableRawPointer) {
    let p = player(ptr)
    p.mpvView?.refreshPlaybackState()
}

@_cdecl("nuvio_player_is_loading")
public func nuvio_player_is_loading(_ ptr: UnsafeMutableRawPointer) -> Bool {
    return player(ptr).mpvView?.isPlayerLoading ?? true
}

@_cdecl("nuvio_player_is_playing")
public func nuvio_player_is_playing(_ ptr: UnsafeMutableRawPointer) -> Bool {
    return player(ptr).mpvView?.isPlayerPlaying ?? false
}

@_cdecl("nuvio_player_is_ended")
public func nuvio_player_is_ended(_ ptr: UnsafeMutableRawPointer) -> Bool {
    return player(ptr).mpvView?.isPlayerEnded ?? false
}

@_cdecl("nuvio_player_get_position_ms")
public func nuvio_player_get_position_ms(_ ptr: UnsafeMutableRawPointer) -> Int64 {
    return player(ptr).mpvView?.positionMs ?? 0
}

@_cdecl("nuvio_player_get_duration_ms")
public func nuvio_player_get_duration_ms(_ ptr: UnsafeMutableRawPointer) -> Int64 {
    return player(ptr).mpvView?.durationMs ?? 0
}

@_cdecl("nuvio_player_get_buffered_ms")
public func nuvio_player_get_buffered_ms(_ ptr: UnsafeMutableRawPointer) -> Int64 {
    return player(ptr).mpvView?.bufferedMs ?? 0
}

@_cdecl("nuvio_player_get_speed")
public func nuvio_player_get_speed(_ ptr: UnsafeMutableRawPointer) -> Float {
    return player(ptr).mpvView?.currentSpeed ?? 1.0
}

@_cdecl("nuvio_player_get_error")
public func nuvio_player_get_error(_ ptr: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
    guard let err = player(ptr).mpvView?.currentErrorMessage, !err.isEmpty else { return nil }
    return retainAndReturn(err)
}

@_cdecl("nuvio_player_get_audio_track_count")
public func nuvio_player_get_audio_track_count(_ ptr: UnsafeMutableRawPointer) -> Int32 {
    return Int32(player(ptr).mpvView?.audioTracks.count ?? 0)
}

@_cdecl("nuvio_player_get_audio_track_id")
public func nuvio_player_get_audio_track_id(_ ptr: UnsafeMutableRawPointer, _ index: Int32) -> Int32 {
    let tracks = player(ptr).mpvView?.audioTracks ?? []
    guard Int(index) < tracks.count else { return 0 }
    return Int32(tracks[Int(index)].id)
}

@_cdecl("nuvio_player_get_audio_track_label")
public func nuvio_player_get_audio_track_label(_ ptr: UnsafeMutableRawPointer, _ index: Int32) -> UnsafePointer<CChar>? {
    let tracks = player(ptr).mpvView?.audioTracks ?? []
    guard Int(index) < tracks.count else { return nil }
    return retainAndReturn(tracks[Int(index)].title)
}

@_cdecl("nuvio_player_get_audio_track_lang")
public func nuvio_player_get_audio_track_lang(_ ptr: UnsafeMutableRawPointer, _ index: Int32) -> UnsafePointer<CChar>? {
    let tracks = player(ptr).mpvView?.audioTracks ?? []
    guard Int(index) < tracks.count else { return nil }
    return retainAndReturn(tracks[Int(index)].lang)
}

@_cdecl("nuvio_player_is_audio_track_selected")
public func nuvio_player_is_audio_track_selected(_ ptr: UnsafeMutableRawPointer, _ index: Int32) -> Bool {
    let tracks = player(ptr).mpvView?.audioTracks ?? []
    guard Int(index) < tracks.count else { return false }
    return tracks[Int(index)].selected
}

@_cdecl("nuvio_player_select_audio_track")
public func nuvio_player_select_audio_track(_ ptr: UnsafeMutableRawPointer, _ trackId: Int32) {
    DispatchQueue.main.async { player(ptr).mpvView?.selectAudio(Int(trackId)) }
}

@_cdecl("nuvio_player_get_subtitle_track_count")
public func nuvio_player_get_subtitle_track_count(_ ptr: UnsafeMutableRawPointer) -> Int32 {
    return Int32(player(ptr).mpvView?.subtitleTracks.count ?? 0)
}

@_cdecl("nuvio_player_get_subtitle_track_id")
public func nuvio_player_get_subtitle_track_id(_ ptr: UnsafeMutableRawPointer, _ index: Int32) -> Int32 {
    let tracks = player(ptr).mpvView?.subtitleTracks ?? []
    guard Int(index) < tracks.count else { return 0 }
    return Int32(tracks[Int(index)].id)
}

@_cdecl("nuvio_player_get_subtitle_track_label")
public func nuvio_player_get_subtitle_track_label(_ ptr: UnsafeMutableRawPointer, _ index: Int32) -> UnsafePointer<CChar>? {
    let tracks = player(ptr).mpvView?.subtitleTracks ?? []
    guard Int(index) < tracks.count else { return nil }
    return retainAndReturn(tracks[Int(index)].title)
}

@_cdecl("nuvio_player_get_subtitle_track_lang")
public func nuvio_player_get_subtitle_track_lang(_ ptr: UnsafeMutableRawPointer, _ index: Int32) -> UnsafePointer<CChar>? {
    let tracks = player(ptr).mpvView?.subtitleTracks ?? []
    guard Int(index) < tracks.count else { return nil }
    return retainAndReturn(tracks[Int(index)].lang)
}

@_cdecl("nuvio_player_is_subtitle_track_selected")
public func nuvio_player_is_subtitle_track_selected(_ ptr: UnsafeMutableRawPointer, _ index: Int32) -> Bool {
    let tracks = player(ptr).mpvView?.subtitleTracks ?? []
    guard Int(index) < tracks.count else { return false }
    return tracks[Int(index)].selected
}

@_cdecl("nuvio_player_select_subtitle_track")
public func nuvio_player_select_subtitle_track(_ ptr: UnsafeMutableRawPointer, _ trackId: Int32) {
    DispatchQueue.main.async { player(ptr).mpvView?.selectSubtitle(Int(trackId)) }
}

@_cdecl("nuvio_player_set_subtitle_url")
public func nuvio_player_set_subtitle_url(_ ptr: UnsafeMutableRawPointer, _ url: UnsafePointer<CChar>) {
    let urlStr = String(cString: url)
    DispatchQueue.main.async { player(ptr).mpvView?.addSubtitleUrl(urlStr) }
}

@_cdecl("nuvio_player_clear_external_subtitle")
public func nuvio_player_clear_external_subtitle(_ ptr: UnsafeMutableRawPointer) {
    DispatchQueue.main.async { player(ptr).mpvView?.removeExternalSubtitles() }
}

@_cdecl("nuvio_player_clear_external_subtitle_and_select")
public func nuvio_player_clear_external_subtitle_and_select(_ ptr: UnsafeMutableRawPointer, _ trackId: Int32) {
    DispatchQueue.main.async { player(ptr).mpvView?.removeExternalSubtitlesAndSelect(Int(trackId)) }
}

@_cdecl("nuvio_player_apply_subtitle_style")
public func nuvio_player_apply_subtitle_style(
    _ ptr: UnsafeMutableRawPointer,
    _ textColor: UnsafePointer<CChar>,
    _ outlineSize: Float,
    _ fontSize: Float,
    _ subPos: Int32
) {
    let color = String(cString: textColor)
    DispatchQueue.main.async {
        let p = player(ptr)
        p.mpvView?.applySubtitleStyle(textColor: color, outlineSize: outlineSize, fontSize: fontSize, subPos: Int(subPos))
        let colorIndex = subtitleColorSwatches.firstIndex(where: { $0.0.uppercased() == color.uppercased() }) ?? 0
        p.state.subtitleStyleTextColor = colorIndex
        p.state.subtitleStyleOutlineEnabled = outlineSize > 0
        p.state.subtitleStyleFontSize = Int(fontSize)
        p.state.subtitleStyleBottomOffset = 100 - Int(subPos)
    }
}

@_cdecl("nuvio_player_show_skip_button")
public func nuvio_player_show_skip_button(_ ptr: UnsafeMutableRawPointer, _ type: UnsafePointer<CChar>, _ endTimeMs: Int64) {
    let typeStr = String(cString: type)
    let p = player(ptr)
    DispatchQueue.main.async {
        p.state.skipButtonType = typeStr
        p.state.skipEndTimeMs = endTimeMs
    }
}

@_cdecl("nuvio_player_hide_skip_button")
public func nuvio_player_hide_skip_button(_ ptr: UnsafeMutableRawPointer) {
    DispatchQueue.main.async { player(ptr).state.skipButtonType = nil }
}

@_cdecl("nuvio_player_show_next_episode")
public func nuvio_player_show_next_episode(
    _ ptr: UnsafeMutableRawPointer,
    _ season: Int32,
    _ episode: Int32,
    _ title: UnsafePointer<CChar>,
    _ thumbnail: UnsafePointer<CChar>?,
    _ hasAired: Bool
) {
    let p = player(ptr)
    let titleStr = String(cString: title)
    let thumbStr = thumbnail.map { String(cString: $0) }
    DispatchQueue.main.async {
        p.state.nextEpisodeSeason = Int(season)
        p.state.nextEpisodeEpisode = Int(episode)
        p.state.nextEpisodeTitle = titleStr
        p.state.nextEpisodeThumbnail = thumbStr
        p.state.nextEpisodeHasAired = hasAired
        p.state.showNextEpisode = true
    }
}

@_cdecl("nuvio_player_hide_next_episode")
public func nuvio_player_hide_next_episode(_ ptr: UnsafeMutableRawPointer) {
    DispatchQueue.main.async { player(ptr).state.showNextEpisode = false }
}

@_cdecl("nuvio_player_is_closed")
public func nuvio_player_is_closed(_ ptr: UnsafeMutableRawPointer) -> Bool {
    return player(ptr).state.isClosed
}

@_cdecl("nuvio_player_pop_next_episode_pressed")
public func nuvio_player_pop_next_episode_pressed(_ ptr: UnsafeMutableRawPointer) -> Bool {
    let p = player(ptr)
    if p.state.nextEpisodePressed {
        p.state.nextEpisodePressed = false
        return true
    }
    return false
}

@_cdecl("nuvio_player_is_addon_subtitles_fetch_requested")
public func nuvio_player_is_addon_subtitles_fetch_requested(_ ptr: UnsafeMutableRawPointer) -> Bool {
    let p = player(ptr)
    if p.state.addonSubtitlesFetchRequested {
        p.state.addonSubtitlesFetchRequested = false
        return true
    }
    return false
}

@_cdecl("nuvio_player_set_addon_subtitles_loading")
public func nuvio_player_set_addon_subtitles_loading(_ ptr: UnsafeMutableRawPointer, _ loading: Bool) {
    DispatchQueue.main.async { player(ptr).state.addonSubtitlesLoading = loading }
}

@_cdecl("nuvio_player_clear_addon_subtitles")
public func nuvio_player_clear_addon_subtitles(_ ptr: UnsafeMutableRawPointer) {
    DispatchQueue.main.async { player(ptr).state.addonSubtitles = [] }
}

@_cdecl("nuvio_player_pop_subtitle_style_changed")
public func nuvio_player_pop_subtitle_style_changed(_ ptr: UnsafeMutableRawPointer) -> Bool {
    let p = player(ptr)
    if p.state.subtitleStyleDirty {
        p.state.subtitleStyleDirty = false
        return true
    }
    return false
}

@_cdecl("nuvio_player_get_subtitle_style_color_index")
public func nuvio_player_get_subtitle_style_color_index(_ ptr: UnsafeMutableRawPointer) -> Int32 {
    return Int32(player(ptr).state.subtitleStyleTextColor)
}

@_cdecl("nuvio_player_get_subtitle_style_font_size")
public func nuvio_player_get_subtitle_style_font_size(_ ptr: UnsafeMutableRawPointer) -> Int32 {
    return Int32(player(ptr).state.subtitleStyleFontSize)
}

@_cdecl("nuvio_player_get_subtitle_style_outline_enabled")
public func nuvio_player_get_subtitle_style_outline_enabled(_ ptr: UnsafeMutableRawPointer) -> Bool {
    return player(ptr).state.subtitleStyleOutlineEnabled
}

@_cdecl("nuvio_player_get_subtitle_style_bottom_offset")
public func nuvio_player_get_subtitle_style_bottom_offset(_ ptr: UnsafeMutableRawPointer) -> Int32 {
    return Int32(player(ptr).state.subtitleStyleBottomOffset)
}

@_cdecl("nuvio_player_add_addon_subtitle")
public func nuvio_player_add_addon_subtitle(
    _ ptr: UnsafeMutableRawPointer,
    _ id: UnsafePointer<CChar>,
    _ url: UnsafePointer<CChar>,
    _ language: UnsafePointer<CChar>,
    _ display: UnsafePointer<CChar>
) {
    let idStr = String(cString: id)
    let urlStr = String(cString: url)
    let langStr = String(cString: language)
    let displayStr = String(cString: display)
    DispatchQueue.main.async {
        player(ptr).state.addonSubtitles.append(
            AddonSubtitleInfo(id: idStr, url: urlStr, language: langStr, display: displayStr)
        )
    }
}

@_cdecl("nuvio_player_pop_sources_open_requested")
public func nuvio_player_pop_sources_open_requested(_ ptr: UnsafeMutableRawPointer) -> Bool {
    let p = player(ptr)
    let v = p.state.sourcesOpenRequested
    p.state.sourcesOpenRequested = false
    return v
}

@_cdecl("nuvio_player_pop_episodes_open_requested")
public func nuvio_player_pop_episodes_open_requested(_ ptr: UnsafeMutableRawPointer) -> Bool {
    let p = player(ptr)
    let v = p.state.episodesOpenRequested
    p.state.episodesOpenRequested = false
    return v
}

@_cdecl("nuvio_player_pop_source_stream_selected")
public func nuvio_player_pop_source_stream_selected(_ ptr: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
    let p = player(ptr)
    guard let url = p.state.sourceStreamSelectedUrl else { return nil }
    p.state.sourceStreamSelectedUrl = nil
    return retainAndReturn(url)
}

@_cdecl("nuvio_player_pop_source_filter_changed")
public func nuvio_player_pop_source_filter_changed(_ ptr: UnsafeMutableRawPointer) -> Bool {
    let p = player(ptr)
    let v = p.state.sourceFilterChanged
    p.state.sourceFilterChanged = false
    return v
}

@_cdecl("nuvio_player_get_source_filter_value")
public func nuvio_player_get_source_filter_value(_ ptr: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
    guard let v = player(ptr).state.sourceFilterSelectedValue else { return nil }
    return retainAndReturn(v)
}

@_cdecl("nuvio_player_pop_source_reload")
public func nuvio_player_pop_source_reload(_ ptr: UnsafeMutableRawPointer) -> Bool {
    let p = player(ptr)
    let v = p.state.sourceReloadRequested
    p.state.sourceReloadRequested = false
    return v
}

@_cdecl("nuvio_player_pop_episode_selected")
public func nuvio_player_pop_episode_selected(_ ptr: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
    let p = player(ptr)
    guard let id = p.state.episodeSelectedId else { return nil }
    p.state.episodeSelectedId = nil
    return retainAndReturn(id)
}

@_cdecl("nuvio_player_pop_episode_stream_selected")
public func nuvio_player_pop_episode_stream_selected(_ ptr: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
    let p = player(ptr)
    guard let url = p.state.episodeStreamSelectedUrl else { return nil }
    p.state.episodeStreamSelectedUrl = nil
    return retainAndReturn(url)
}

@_cdecl("nuvio_player_pop_episode_filter_changed")
public func nuvio_player_pop_episode_filter_changed(_ ptr: UnsafeMutableRawPointer) -> Bool {
    let p = player(ptr)
    let v = p.state.episodeFilterChanged
    p.state.episodeFilterChanged = false
    return v
}

@_cdecl("nuvio_player_get_episode_filter_value")
public func nuvio_player_get_episode_filter_value(_ ptr: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
    guard let v = player(ptr).state.episodeFilterSelectedValue else { return nil }
    return retainAndReturn(v)
}

@_cdecl("nuvio_player_pop_episode_reload")
public func nuvio_player_pop_episode_reload(_ ptr: UnsafeMutableRawPointer) -> Bool {
    let p = player(ptr)
    let v = p.state.episodeReloadRequested
    p.state.episodeReloadRequested = false
    return v
}

@_cdecl("nuvio_player_pop_episode_back")
public func nuvio_player_pop_episode_back(_ ptr: UnsafeMutableRawPointer) -> Bool {
    let p = player(ptr)
    let v = p.state.episodeBackRequested
    p.state.episodeBackRequested = false
    return v
}

@_cdecl("nuvio_player_set_sources_loading")
public func nuvio_player_set_sources_loading(_ ptr: UnsafeMutableRawPointer, _ loading: Bool) {
    let p = player(ptr)
    DispatchQueue.main.async { p.state.sourcesLoading = loading }
}

@_cdecl("nuvio_player_clear_source_streams")
public func nuvio_player_clear_source_streams(_ ptr: UnsafeMutableRawPointer) {
    let p = player(ptr)
    DispatchQueue.main.async { p.state.sourceStreams = [] }
}

@_cdecl("nuvio_player_add_source_stream")
public func nuvio_player_add_source_stream(
    _ ptr: UnsafeMutableRawPointer,
    _ id: UnsafePointer<CChar>,
    _ label: UnsafePointer<CChar>,
    _ subtitle: UnsafePointer<CChar>?,
    _ addonName: UnsafePointer<CChar>,
    _ addonId: UnsafePointer<CChar>,
    _ url: UnsafePointer<CChar>,
    _ isCurrent: Bool
) {
    let info = NuvioStreamInfo(
        id: String(cString: id),
        label: String(cString: label),
        subtitle: subtitle.map { String(cString: $0) },
        addonName: String(cString: addonName),
        addonId: String(cString: addonId),
        url: String(cString: url),
        isCurrent: isCurrent
    )
    let p = player(ptr)
    DispatchQueue.main.async { p.state.sourceStreams.append(info) }
}

@_cdecl("nuvio_player_clear_source_addon_groups")
public func nuvio_player_clear_source_addon_groups(_ ptr: UnsafeMutableRawPointer) {
    let p = player(ptr)
    DispatchQueue.main.async { p.state.sourceAddonGroups = [] }
}

@_cdecl("nuvio_player_add_source_addon_group")
public func nuvio_player_add_source_addon_group(
    _ ptr: UnsafeMutableRawPointer,
    _ id: UnsafePointer<CChar>,
    _ addonName: UnsafePointer<CChar>,
    _ addonId: UnsafePointer<CChar>,
    _ isLoading: Bool,
    _ hasError: Bool
) {
    let info = NuvioAddonGroupInfo(
        id: String(cString: id),
        addonName: String(cString: addonName),
        addonId: String(cString: addonId),
        isLoading: isLoading,
        hasError: hasError
    )
    let p = player(ptr)
    DispatchQueue.main.async { p.state.sourceAddonGroups.append(info) }
}

@_cdecl("nuvio_player_set_source_selected_filter")
public func nuvio_player_set_source_selected_filter(_ ptr: UnsafeMutableRawPointer, _ addonId: UnsafePointer<CChar>?) {
    let p = player(ptr)
    let v = addonId.map { String(cString: $0) }
    DispatchQueue.main.async { p.state.sourceSelectedFilter = v }
}

@_cdecl("nuvio_player_clear_episodes")
public func nuvio_player_clear_episodes(_ ptr: UnsafeMutableRawPointer) {
    let p = player(ptr)
    DispatchQueue.main.async { p.state.episodes = [] }
}

@_cdecl("nuvio_player_add_episode")
public func nuvio_player_add_episode(
    _ ptr: UnsafeMutableRawPointer,
    _ id: UnsafePointer<CChar>,
    _ title: UnsafePointer<CChar>,
    _ overview: UnsafePointer<CChar>?,
    _ thumbnail: UnsafePointer<CChar>?,
    _ season: Int32,
    _ episode: Int32
) {
    let info = NuvioEpisodeInfo(
        id: String(cString: id),
        title: String(cString: title),
        overview: overview.map { String(cString: $0) },
        thumbnail: thumbnail.map { String(cString: $0) },
        season: season > 0 ? Int(season) : nil,
        episode: episode > 0 ? Int(episode) : nil
    )
    let p = player(ptr)
    DispatchQueue.main.async { p.state.episodes.append(info) }
}

@_cdecl("nuvio_player_set_episode_streams_loading")
public func nuvio_player_set_episode_streams_loading(_ ptr: UnsafeMutableRawPointer, _ loading: Bool) {
    let p = player(ptr)
    DispatchQueue.main.async { p.state.episodeStreamsLoading = loading }
}

@_cdecl("nuvio_player_clear_episode_streams")
public func nuvio_player_clear_episode_streams(_ ptr: UnsafeMutableRawPointer) {
    let p = player(ptr)
    DispatchQueue.main.async { p.state.episodeStreams = [] }
}

@_cdecl("nuvio_player_add_episode_stream")
public func nuvio_player_add_episode_stream(
    _ ptr: UnsafeMutableRawPointer,
    _ id: UnsafePointer<CChar>,
    _ label: UnsafePointer<CChar>,
    _ subtitle: UnsafePointer<CChar>?,
    _ addonName: UnsafePointer<CChar>,
    _ addonId: UnsafePointer<CChar>,
    _ url: UnsafePointer<CChar>,
    _ isCurrent: Bool
) {
    let info = NuvioStreamInfo(
        id: String(cString: id),
        label: String(cString: label),
        subtitle: subtitle.map { String(cString: $0) },
        addonName: String(cString: addonName),
        addonId: String(cString: addonId),
        url: String(cString: url),
        isCurrent: isCurrent
    )
    let p = player(ptr)
    DispatchQueue.main.async { p.state.episodeStreams.append(info) }
}

@_cdecl("nuvio_player_clear_episode_addon_groups")
public func nuvio_player_clear_episode_addon_groups(_ ptr: UnsafeMutableRawPointer) {
    let p = player(ptr)
    DispatchQueue.main.async { p.state.episodeAddonGroups = [] }
}

@_cdecl("nuvio_player_add_episode_addon_group")
public func nuvio_player_add_episode_addon_group(
    _ ptr: UnsafeMutableRawPointer,
    _ id: UnsafePointer<CChar>,
    _ addonName: UnsafePointer<CChar>,
    _ addonId: UnsafePointer<CChar>,
    _ isLoading: Bool,
    _ hasError: Bool
) {
    let info = NuvioAddonGroupInfo(
        id: String(cString: id),
        addonName: String(cString: addonName),
        addonId: String(cString: addonId),
        isLoading: isLoading,
        hasError: hasError
    )
    let p = player(ptr)
    DispatchQueue.main.async { p.state.episodeAddonGroups.append(info) }
}

@_cdecl("nuvio_player_set_episode_selected_filter")
public func nuvio_player_set_episode_selected_filter(_ ptr: UnsafeMutableRawPointer, _ addonId: UnsafePointer<CChar>?) {
    let p = player(ptr)
    let v = addonId.map { String(cString: $0) }
    DispatchQueue.main.async { p.state.episodeSelectedFilter = v }
}

@_cdecl("nuvio_player_show_episode_streams")
public func nuvio_player_show_episode_streams(
    _ ptr: UnsafeMutableRawPointer,
    _ season: Int32,
    _ episode: Int32,
    _ title: UnsafePointer<CChar>?
) {
    let p = player(ptr)
    let t = title.map { String(cString: $0) }
    DispatchQueue.main.async {
        p.state.selectedEpisodeSeason = season > 0 ? Int(season) : nil
        p.state.selectedEpisodeNumber = episode > 0 ? Int(episode) : nil
        p.state.selectedEpisodeTitle = t
        p.state.showEpisodeStreams = true
    }
}
