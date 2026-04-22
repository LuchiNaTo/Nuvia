import Foundation
import AppKit

enum GestureFeedbackIcon {
    case speed
    case volume
    case volumeMuted
    case seekForward
    case seekBackward
}

struct GestureFeedbackState {
    let message: String
    let icon: GestureFeedbackIcon
    let isDanger: Bool

    init(message: String, icon: GestureFeedbackIcon = .speed, isDanger: Bool = false) {
        self.message = message
        self.icon = icon
        self.isDanger = isDanger
    }
}

struct NuvioTrackInfo {
    let index: Int
    let id: Int
    let type: String
    let title: String
    let lang: String
    var selected: Bool
}

struct NuvioStreamInfo: Identifiable {
    let id: String
    let label: String
    let subtitle: String?
    let addonName: String
    let addonId: String
    let url: String
    let isCurrent: Bool
}

struct NuvioAddonGroupInfo: Identifiable {
    let id: String
    let addonName: String
    let addonId: String
    let isLoading: Bool
    let hasError: Bool
}

struct NuvioEpisodeInfo: Identifiable {
    let id: String
    let title: String
    let overview: String?
    let thumbnail: String?
    let season: Int?
    let episode: Int?
}

final class NuvioPlayerState: ObservableObject {
    @Published var isLoading: Bool = true
    @Published var isPlaying: Bool = false
    @Published var isEnded: Bool = false
    @Published var positionMs: Int64 = 0
    @Published var durationMs: Int64 = 0
    @Published var bufferedMs: Int64 = 0
    @Published var currentSpeed: Float = 1.0
    @Published var resizeMode: Int = 0

    @Published var controlsVisible: Bool = true
    @Published var cursorHidden: Bool = false

    @Published var title: String = ""
    @Published var streamTitle: String = ""
    @Published var providerName: String = ""
    @Published var seasonNumber: Int? = nil
    @Published var episodeNumber: Int? = nil
    @Published var episodeTitle: String? = nil
    @Published var artwork: String? = nil
    @Published var logo: String? = nil

    @Published var initialLoadCompleted: Bool = false

    @Published var audioTracks: [NuvioTrackInfo] = []
    @Published var subtitleTracks: [NuvioTrackInfo] = []

    @Published var errorMessage: String? = nil

    @Published var showSubtitlePanel: Bool = false
    @Published var showAudioPanel: Bool = false

    @Published var skipButtonType: String? = nil
    var skipEndTimeMs: Int64 = 0

    @Published var nextEpisodeSeason: Int? = nil
    @Published var nextEpisodeEpisode: Int? = nil
    @Published var nextEpisodeTitle: String? = nil
    @Published var nextEpisodeThumbnail: String? = nil
    @Published var nextEpisodeHasAired: Bool = true
    @Published var showNextEpisode: Bool = false

    var isClosed: Bool = false
    var nextEpisodePressed: Bool = false

    @Published var gestureFeedback: GestureFeedbackState? = nil

    @Published var subtitleStyleTextColor: Int = 0
    @Published var subtitleStyleFontSize: Int = 30
    @Published var subtitleStyleOutlineEnabled: Bool = true
    @Published var subtitleStyleBottomOffset: Int = 5

    @Published var addonSubtitles: [AddonSubtitleInfo] = []
    @Published var addonSubtitlesLoading: Bool = false
    @Published var selectedAddonSubtitleId: String? = nil
    @Published var subtitleTab: Int = 0
    var addonSubtitlesFetchRequested: Bool = false
    var subtitleStyleDirty: Bool = false

    @Published var showSourcesPanel: Bool = false
    @Published var showEpisodesPanel: Bool = false
    @Published var hasVideoId: Bool = false
    @Published var isSeries: Bool = false

    @Published var sourceStreams: [NuvioStreamInfo] = []
    @Published var sourceAddonGroups: [NuvioAddonGroupInfo] = []
    @Published var sourceSelectedFilter: String? = nil
    @Published var sourcesLoading: Bool = false

    @Published var episodes: [NuvioEpisodeInfo] = []
    @Published var episodeStreams: [NuvioStreamInfo] = []
    @Published var episodeAddonGroups: [NuvioAddonGroupInfo] = []
    @Published var episodeSelectedFilter: String? = nil
    @Published var episodeStreamsLoading: Bool = false
    @Published var showEpisodeStreams: Bool = false
    @Published var selectedEpisodeSeason: Int? = nil
    @Published var selectedEpisodeNumber: Int? = nil
    @Published var selectedEpisodeTitle: String? = nil

    var sourcesOpenRequested: Bool = false
    var episodesOpenRequested: Bool = false
    var sourceStreamSelectedUrl: String? = nil
    var sourceFilterSelectedValue: String? = nil
    var sourceFilterChanged: Bool = false
    var sourceReloadRequested: Bool = false
    var episodeSelectedId: String? = nil
    var episodeStreamSelectedUrl: String? = nil
    var episodeFilterSelectedValue: String? = nil
    var episodeFilterChanged: Bool = false
    var episodeReloadRequested: Bool = false
    var episodeBackRequested: Bool = false

    var resizeModeLabel: String {
        switch resizeMode {
        case 1: return "Fill"
        case 2: return "Zoom"
        default: return "Fit"
        }
    }

    var speedLabel: String {
        let s = currentSpeed
        if s == Float(Int(s)) {
            return "\(Int(s))x"
        }
        let str = String(format: "%.2f", s)
        let trimmed = str.replacingOccurrences(of: "0+$", with: "", options: .regularExpression)
            .replacingOccurrences(of: "\\.$", with: "", options: .regularExpression)
        return "\(trimmed)x"
    }

    func formattedTime(_ ms: Int64) -> String {
        let totalSeconds = max(ms / 1000, 0)
        let seconds = totalSeconds % 60
        let minutes = (totalSeconds / 60) % 60
        let hours = totalSeconds / 3600
        if hours > 0 {
            return "\(hours):\(String(format: "%02d", minutes)):\(String(format: "%02d", seconds))"
        }
        return "\(String(format: "%02d", minutes)):\(String(format: "%02d", seconds))"
    }

    var filteredSourceStreams: [NuvioStreamInfo] {
        guard let filter = sourceSelectedFilter else { return sourceStreams }
        return sourceStreams.filter { $0.addonId == filter }
    }

    var filteredEpisodeStreams: [NuvioStreamInfo] {
        guard let filter = episodeSelectedFilter else { return episodeStreams }
        return episodeStreams.filter { $0.addonId == filter }
    }
}

struct AddonSubtitleInfo: Identifiable {
    let id: String
    let url: String
    let language: String
    let display: String
}

let subtitleColorSwatches: [(String, NSColor)] = [
    ("#FFFFFF", NSColor.white),
    ("#FFD700", NSColor(red: 1.0, green: 0.843, blue: 0.0, alpha: 1.0)),
    ("#00E5FF", NSColor(red: 0.0, green: 0.898, blue: 1.0, alpha: 1.0)),
    ("#FF5C5C", NSColor(red: 1.0, green: 0.361, blue: 0.361, alpha: 1.0)),
    ("#00FF88", NSColor(red: 0.0, green: 1.0, blue: 0.533, alpha: 1.0)),
    ("#9B59B6", NSColor(red: 0.608, green: 0.349, blue: 0.714, alpha: 1.0)),
    ("#F97316", NSColor(red: 0.976, green: 0.451, blue: 0.086, alpha: 1.0)),
    ("#22C55E", NSColor(red: 0.133, green: 0.773, blue: 0.369, alpha: 1.0)),
    ("#3B82F6", NSColor(red: 0.231, green: 0.510, blue: 0.965, alpha: 1.0)),
    ("#000000", NSColor.black),
]
