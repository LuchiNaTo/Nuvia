import SwiftUI

struct NuvioEpisodesPanel: View {
    @ObservedObject var state: NuvioPlayerState
    var onDismiss: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.52)
                .onTapGesture { onDismiss() }

            VStack(spacing: 0) {
                if state.showEpisodeStreams {
                    episodeStreamsSubView
                } else {
                    episodesListSubView
                }
            }
            .frame(maxWidth: 520)
            .frame(maxHeight: 620)
            .background(Color(red: 0.12, green: 0.12, blue: 0.12))
            .clipShape(RoundedRectangle(cornerRadius: 24))
            .overlay(
                RoundedRectangle(cornerRadius: 24)
                    .stroke(Color.white.opacity(0.1), lineWidth: 1)
            )
        }
    }

    @State private var selectedSeason: Int = 1

    var episodesListSubView: some View {
        let grouped = Dictionary(grouping: state.episodes.filter { $0.season != nil || $0.episode != nil }) {
            ($0.season ?? 0)
        }
        let regular = grouped.keys.filter { $0 > 0 }.sorted()
        let specials = grouped.keys.filter { $0 == 0 }
        let availableSeasons = regular + specials

        let currentSeason: Int = {
            if let sn = state.seasonNumber, availableSeasons.contains(sn) { return sn }
            return availableSeasons.first ?? 1
        }()

        let seasonEpisodes = (grouped[selectedSeason == 0 && !availableSeasons.contains(selectedSeason) ? currentSeason : selectedSeason] ?? [])
            .sorted { ($0.episode ?? 0) < ($1.episode ?? 0) }

        return VStack(spacing: 0) {
            HStack {
                Text("Episodes")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundColor(.white)
                Spacer()
                panelChipButton(label: "Close", icon: nil) {
                    onDismiss()
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 16)
            .padding(.bottom, 12)

            if availableSeasons.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(availableSeasons, id: \.self) { season in
                            let label = season == 0 ? "Specials" : "Season \(season)"
                            addonFilterChip(
                                label: label,
                                isSelected: selectedSeason == season,
                                isLoading: false,
                                hasError: false
                            ) {
                                selectedSeason = season
                            }
                        }
                    }
                    .padding(.horizontal, 20)
                }
                .padding(.bottom, 12)
            }

            if seasonEpisodes.isEmpty {
                Spacer()
                Text("No episodes available")
                    .font(.system(size: 14))
                    .foregroundColor(.white.opacity(0.6))
                    .frame(height: 80)
                Spacer()
            } else {
                ScrollView {
                    VStack(spacing: 4) {
                        ForEach(seasonEpisodes) { episode in
                            let isCurrent = episode.season == state.seasonNumber && episode.episode == state.episodeNumber
                            episodeRow(episode: episode, isCurrent: isCurrent) {
                                state.episodeSelectedId = episode.id
                            }
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.bottom, 16)
                }
            }
        }
        .onAppear {
            if let sn = state.seasonNumber, selectedSeason != sn {
                selectedSeason = sn
            }
        }
    }

    var episodeStreamsSubView: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Streams")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundColor(.white)
                Spacer()
                panelChipButton(label: "Close", icon: nil) {
                    onDismiss()
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 16)
            .padding(.bottom, 12)

            HStack(spacing: 8) {
                panelChipButton(label: "Back", icon: "arrow.left") {
                    state.episodeBackRequested = true
                    state.showEpisodeStreams = false
                    state.episodeStreams = []
                    state.episodeAddonGroups = []
                }
                panelChipButton(label: "Reload", icon: "arrow.clockwise") {
                    state.episodeReloadRequested = true
                }
                let infoText: String = {
                    var s = ""
                    if let sn = state.selectedEpisodeSeason, let en = state.selectedEpisodeNumber {
                        s = "S\(sn)E\(en)"
                    }
                    if let t = state.selectedEpisodeTitle, !t.isEmpty {
                        if !s.isEmpty { s += " • " }
                        s += t
                    }
                    return s
                }()
                Text(infoText)
                    .font(.system(size: 12))
                    .foregroundColor(.white.opacity(0.6))
                    .lineLimit(1)
                Spacer()
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 8)

            let addonGroups = state.episodeAddonGroups
            if addonGroups.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        addonFilterChip(
                            label: "All",
                            isSelected: state.episodeSelectedFilter == nil,
                            isLoading: false,
                            hasError: false
                        ) {
                            state.episodeSelectedFilter = nil
                            state.episodeFilterSelectedValue = nil
                            state.episodeFilterChanged = true
                        }
                        ForEach(addonGroups) { group in
                            addonFilterChip(
                                label: group.addonName,
                                isSelected: state.episodeSelectedFilter == group.addonId,
                                isLoading: group.isLoading,
                                hasError: group.hasError
                            ) {
                                state.episodeSelectedFilter = group.addonId
                                state.episodeFilterSelectedValue = group.addonId
                                state.episodeFilterChanged = true
                            }
                        }
                    }
                    .padding(.horizontal, 20)
                }
                .padding(.bottom, 12)
            }

            let streams = state.filteredEpisodeStreams
            if state.episodeStreamsLoading && streams.isEmpty {
                Spacer()
                ProgressView()
                    .progressViewStyle(.circular)
                    .scaleEffect(0.8)
                    .frame(height: 80)
                Spacer()
            } else if streams.isEmpty {
                Spacer()
                Text("No streams found")
                    .font(.system(size: 14))
                    .foregroundColor(.white.opacity(0.6))
                    .frame(height: 80)
                Spacer()
            } else {
                ScrollView {
                    VStack(spacing: 6) {
                        ForEach(streams) { stream in
                            episodeStreamRow(stream: stream) {
                                state.episodeStreamSelectedUrl = stream.url
                                onDismiss()
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 16)
                }
            }
        }
    }

    func episodeRow(episode: NuvioEpisodeInfo, isCurrent: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                if let thumb = episode.thumbnail, !thumb.isEmpty {
                    if #available(macOS 12.0, *) {
                        AsyncImage(url: URL(string: thumb)) { image in
                            image.resizable().aspectRatio(contentMode: .fill)
                        } placeholder: {
                            Color.gray.opacity(0.3)
                        }
                        .frame(width: 80, height: 48)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                    } else {
                        Color.gray.opacity(0.3)
                            .frame(width: 80, height: 48)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                }

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 8) {
                        let episodeLabel: String = {
                            if let sn = episode.season, let en = episode.episode {
                                return "S\(sn)E\(en)"
                            } else if let en = episode.episode {
                                return "E\(en)"
                            }
                            return ""
                        }()
                        if !episodeLabel.isEmpty {
                            Text(episodeLabel)
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(.white.opacity(0.6))
                        }
                        if isCurrent {
                            Text("Playing")
                                .font(.system(size: 9, weight: .semibold))
                                .foregroundColor(.white)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Color.white.opacity(0.15))
                                .clipShape(Capsule())
                        }
                    }
                    Text(episode.title)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(.white)
                        .lineLimit(1)
                    if let overview = episode.overview, !overview.isEmpty {
                        Text(overview)
                            .font(.system(size: 11))
                            .foregroundColor(.white.opacity(0.6))
                            .lineLimit(2)
                    }
                }
                Spacer()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(isCurrent ? Color.white.opacity(0.12) : Color.clear)
            .overlay(
                isCurrent ?
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(Color.white.opacity(0.2), lineWidth: 1) : nil
            )
            .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }

    func episodeStreamRow(stream: NuvioStreamInfo, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(stream.label)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(.white)
                        .lineLimit(1)
                    if let sub = stream.subtitle, !sub.isEmpty, sub != stream.label {
                        Text(sub)
                            .font(.system(size: 12))
                            .foregroundColor(.white.opacity(0.6))
                            .lineLimit(2)
                    }
                    Text(stream.addonName)
                        .font(.system(size: 11))
                        .italic()
                        .foregroundColor(.white.opacity(0.6))
                        .lineLimit(1)
                }
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Color.white.opacity(0.06))
            .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }
}
