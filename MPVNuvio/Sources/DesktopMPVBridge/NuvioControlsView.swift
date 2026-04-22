import SwiftUI

struct ControlMetrics {
    let horizontalPadding: CGFloat
    let verticalPadding: CGFloat
    let titleSize: CGFloat
    let episodeInfoSize: CGFloat
    let metadataSize: CGFloat
    let centerGap: CGFloat
    let centerLift: CGFloat
    let sliderBottomOffset: CGFloat
    let timeSize: CGFloat
    let headerIconSize: CGFloat
    let sideIconSize: CGFloat
    let playIconSize: CGFloat

    static func from(width: CGFloat) -> ControlMetrics {
        if width >= 1440 {
            return ControlMetrics(
                horizontalPadding: 28, verticalPadding: 24,
                titleSize: 28, episodeInfoSize: 16, metadataSize: 14,
                centerGap: 112, centerLift: 24, sliderBottomOffset: 28,
                timeSize: 14, headerIconSize: 24, sideIconSize: 34, playIconSize: 44
            )
        } else if width >= 1024 {
            return ControlMetrics(
                horizontalPadding: 24, verticalPadding: 20,
                titleSize: 24, episodeInfoSize: 15, metadataSize: 13,
                centerGap: 88, centerLift: 18, sliderBottomOffset: 24,
                timeSize: 13, headerIconSize: 22, sideIconSize: 32, playIconSize: 42
            )
        } else if width >= 768 {
            return ControlMetrics(
                horizontalPadding: 20, verticalPadding: 16,
                titleSize: 22, episodeInfoSize: 14, metadataSize: 12,
                centerGap: 72, centerLift: 14, sliderBottomOffset: 20,
                timeSize: 12, headerIconSize: 20, sideIconSize: 30, playIconSize: 38
            )
        } else {
            return ControlMetrics(
                horizontalPadding: 20, verticalPadding: 16,
                titleSize: 18, episodeInfoSize: 14, metadataSize: 12,
                centerGap: 56, centerLift: 10, sliderBottomOffset: 16,
                timeSize: 12, headerIconSize: 20, sideIconSize: 26, playIconSize: 34
            )
        }
    }
}

struct NuvioControlsView: View {
    @ObservedObject var state: NuvioPlayerState

    var onPlay: () -> Void
    var onPause: () -> Void
    var onSeekBack: () -> Void
    var onSeekForward: () -> Void
    var onSeekTo: (Int64) -> Void
    var onCycleResize: () -> Void
    var onCycleSpeed: () -> Void
    var onSelectAudioTrack: (Int) -> Void
    var onSelectSubtitleTrack: (Int) -> Void
    var onClose: () -> Void
    var onSkip: () -> Void
    var onNextEpisode: () -> Void
    var onApplySubtitleStyle: ((String, Float, Float, Int) -> Void)?
    var onAddSubtitleUrl: ((String) -> Void)?
    var onRemoveExternalAndSelect: ((Int) -> Void)?
    var onFetchAddonSubtitles: (() -> Void)?

    @State private var isDragging = false
    @State private var dragPosition: Double = 0

    var body: some View {
        GeometryReader { geometry in
            let metrics = ControlMetrics.from(width: geometry.size.width)
            ZStack {
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture {
                        state.controlsVisible.toggle()
                    }

                Group {
                    topGradient
                    bottomGradient

                    VStack {
                        headerView(metrics: metrics)
                        Spacer()
                    }

                    centerControls(metrics: metrics)
                        .offset(y: -metrics.centerLift)

                    VStack {
                        Spacer()
                        progressControls(metrics: metrics)
                            .padding(.horizontal, metrics.horizontalPadding)
                            .padding(.bottom, metrics.sliderBottomOffset)
                    }
                }
                .opacity(state.controlsVisible ? 1 : 0)
                .animation(.easeInOut(duration: 0.25), value: state.controlsVisible)

                if state.skipButtonType != nil {
                    VStack {
                        Spacer()
                        HStack {
                            skipButton
                                .padding(.leading, metrics.horizontalPadding)
                                .padding(.bottom, metrics.sliderBottomOffset + 120)
                            Spacer()
                        }
                    }
                }

                if state.showNextEpisode {
                    VStack {
                        Spacer()
                        HStack {
                            Spacer()
                            nextEpisodeCard
                                .padding(.trailing, metrics.horizontalPadding)
                                .padding(.bottom, 40)
                        }
                    }
                }

                if state.showSubtitlePanel {
                    subtitleModal
                }

                if state.showAudioPanel {
                    trackSelectionPanel(
                        title: "Audio",
                        tracks: state.audioTracks,
                        showNone: false,
                        onSelect: { trackId in
                            onSelectAudioTrack(trackId)
                            state.showAudioPanel = false
                        },
                        onDismiss: { state.showAudioPanel = false }
                    )
                }

                if state.showSourcesPanel {
                    NuvioSourcesPanel(state: state) {
                        state.showSourcesPanel = false
                        state.controlsVisible = true
                    }
                }

                if state.showEpisodesPanel {
                    NuvioEpisodesPanel(state: state) {
                        state.showEpisodesPanel = false
                        state.showEpisodeStreams = false
                        state.controlsVisible = true
                    }
                }

                if let feedback = state.gestureFeedback {
                    VStack {
                        GestureFeedbackPill(feedback: feedback)
                            .padding(.top, 40)
                            .transition(.opacity)
                        Spacer()
                    }
                    .animation(.easeInOut(duration: 0.15), value: state.gestureFeedback == nil)
                }

                if let error = state.errorMessage, !error.isEmpty {
                    errorOverlay(message: error)
                }

                if !state.initialLoadCompleted && state.errorMessage == nil {
                    openingOverlay
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .transition(.opacity)
                        .animation(.easeInOut(duration: 0.5), value: state.initialLoadCompleted)
                }
            }
        }
    }

    var topGradient: some View {
        VStack {
            LinearGradient(
                colors: [Color.black.opacity(0.7), Color.clear],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: 160)
            .allowsHitTesting(false)
            Spacer()
        }
    }

    var bottomGradient: some View {
        VStack {
            Spacer()
            LinearGradient(
                colors: [Color.clear, Color.black.opacity(0.7)],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: 220)
            .allowsHitTesting(false)
        }
    }

    func headerView(metrics: ControlMetrics) -> some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 6) {
                Text(state.title)
                    .font(.system(size: metrics.titleSize, weight: .bold))
                    .foregroundColor(.white)
                    .lineLimit(2)

                if let sn = state.seasonNumber, let en = state.episodeNumber,
                   let et = state.episodeTitle, !et.isEmpty {
                    Text("S\(sn)E\(en) • \(et)")
                        .font(.system(size: metrics.episodeInfoSize))
                        .foregroundColor(.white.opacity(0.9))
                        .lineLimit(1)
                }

                HStack(spacing: 8) {
                    Text(state.streamTitle)
                        .font(.system(size: metrics.metadataSize))
                        .foregroundColor(.white.opacity(0.7))
                        .lineLimit(1)
                    Text(state.providerName)
                        .font(.system(size: metrics.metadataSize))
                        .italic()
                        .foregroundColor(.white.opacity(0.7))
                        .lineLimit(1)
                }
            }
            Spacer()
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: metrics.headerIconSize, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(width: metrics.headerIconSize + 16, height: metrics.headerIconSize + 16)
                    .background(Color.black.opacity(0.35))
                    .clipShape(Circle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 28)
        .padding(.top, 16)
    }

    func centerControls(metrics: ControlMetrics) -> some View {
        HStack(spacing: metrics.centerGap) {
            Button(action: onSeekBack) {
                Image(systemName: "gobackward.10")
                    .font(.system(size: metrics.sideIconSize))
                    .foregroundColor(.white)
                    .frame(width: metrics.sideIconSize + 28, height: metrics.sideIconSize + 28)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)

            Button(action: { state.isPlaying ? onPause() : onPlay() }) {
                Group {
                    if state.isLoading {
                        ProgressView()
                            .progressViewStyle(.circular)
                            .scaleEffect(1.2)
                            .frame(width: metrics.playIconSize, height: metrics.playIconSize)
                    } else {
                        Image(systemName: state.isPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: metrics.playIconSize))
                            .foregroundColor(.white)
                    }
                }
                .frame(width: metrics.playIconSize + 36, height: metrics.playIconSize + 36)
                .contentShape(Circle())
            }
            .buttonStyle(.plain)

            Button(action: onSeekForward) {
                Image(systemName: "goforward.10")
                    .font(.system(size: metrics.sideIconSize))
                    .foregroundColor(.white)
                    .frame(width: metrics.sideIconSize + 28, height: metrics.sideIconSize + 28)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
        }
    }

    func progressControls(metrics: ControlMetrics) -> some View {
        let duration = max(state.durationMs, 1)
        let position = isDragging ? Int64(dragPosition) : state.positionMs

        return VStack(spacing: 0) {
            playerSlider(
                value: Binding(
                    get: { Double(position) },
                    set: { newVal in
                        isDragging = true
                        dragPosition = newVal
                    }
                ),
                range: 0...Double(duration),
                onFinished: {
                    let seekPos = Int64(dragPosition.clamped(to: 0...Double(duration)))
                    isDragging = false
                    onSeekTo(seekPos)
                }
            )
            .frame(height: 28)

            HStack {
                timePill(text: state.formattedTime(position), fontSize: metrics.timeSize)
                Spacer()
                timePill(text: state.formattedTime(duration), fontSize: metrics.timeSize)
            }
            .padding(.horizontal, 14)
            .padding(.top, 4)
            .padding(.bottom, 8)

            HStack(spacing: 0) {
                actionPillContainer {
                    actionPillButton(icon: "aspectratio", label: state.resizeModeLabel, action: onCycleResize)
                    actionPillButton(icon: "speedometer", label: state.speedLabel, action: onCycleSpeed)
                    actionPillButton(icon: "captions.bubble", label: "Subs", action: {
                        state.showSubtitlePanel = true
                    })
                    actionPillButton(icon: "speaker.wave.2", label: "Audio", action: {
                        state.showAudioPanel = true
                    })
                    if state.hasVideoId {
                        actionPillButton(icon: "arrow.left.arrow.right", label: "Sources", action: {
                            state.sourcesOpenRequested = true
                            state.showSourcesPanel = true
                            state.showEpisodesPanel = false
                            state.controlsVisible = false
                        })
                    }
                    if state.isSeries {
                        actionPillButton(icon: "rectangle.stack.fill", label: "Episodes", action: {
                            state.episodesOpenRequested = true
                            state.showEpisodesPanel = true
                            state.showSourcesPanel = false
                            state.controlsVisible = false
                        })
                    }
                }
            }
            .frame(maxWidth: .infinity)
        }
    }

    func playerSlider(value: Binding<Double>, range: ClosedRange<Double>, onFinished: @escaping () -> Void) -> some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            let span = range.upperBound - range.lowerBound
            let fraction = span > 0 ? CGFloat((value.wrappedValue - range.lowerBound) / span) : 0
            let clampedFraction = min(max(fraction, 0), 1)

            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 2)
                    .fill(Color.white.opacity(0.3))
                    .frame(height: 4)

                RoundedRectangle(cornerRadius: 2)
                    .fill(Color.white)
                    .frame(width: width * clampedFraction, height: 4)

                Circle()
                    .fill(Color.white)
                    .frame(width: 14, height: 14)
                    .offset(x: width * clampedFraction - 7)
            }
            .frame(height: 28)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { gesture in
                        let newFraction = min(max(gesture.location.x / width, 0), 1)
                        value.wrappedValue = range.lowerBound + Double(newFraction) * span
                    }
                    .onEnded { _ in
                        onFinished()
                    }
            )
        }
    }

    func timePill(text: String, fontSize: CGFloat) -> some View {
        Text(text)
            .font(.system(size: fontSize, weight: .medium))
            .foregroundColor(.white)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Color.black.opacity(0.5))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(Color.white.opacity(0.2), lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    func actionPillContainer<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        HStack(spacing: 0) {
            content()
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 2)
        .background(Color.black.opacity(0.5))
        .overlay(
            RoundedRectangle(cornerRadius: 24)
                .stroke(Color.white.opacity(0.2), lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 24))
    }

    func actionPillButton(icon: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 14))
                    .foregroundColor(.white)
                    .frame(width: 18, height: 18)
                Text(label)
                    .font(.system(size: 12))
                    .foregroundColor(.white)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 12)
            .contentShape(RoundedRectangle(cornerRadius: 22))
        }
        .buttonStyle(.plain)
    }

    var skipButton: some View {
        let labelText: String = {
            switch state.skipButtonType?.lowercased() {
            case "intro", "op", "mixed-op": return "Skip Intro"
            case "outro", "ed", "mixed-ed", "credits": return "Skip Outro"
            case "recap": return "Skip Recap"
            default: return "Skip"
            }
        }()

        return Button(action: onSkip) {
            HStack(spacing: 8) {
                Image(systemName: "forward.end.fill")
                    .font(.system(size: 14))
                    .foregroundColor(.white)
                Text(labelText)
                    .font(.system(size: 14))
                    .foregroundColor(.white)
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
            .background(Color(red: 0.118, green: 0.118, blue: 0.118).opacity(0.85))
            .clipShape(RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
    }

    var nextEpisodeCard: some View {
        let hasAired = state.nextEpisodeHasAired
        return Button(action: { if hasAired { onNextEpisode() } }) {
            HStack(spacing: 8) {
                if let thumb = state.nextEpisodeThumbnail, !thumb.isEmpty {
                    if #available(macOS 12.0, *) {
                        AsyncImage(url: URL(string: thumb)) { image in
                            image.resizable().aspectRatio(contentMode: .fill)
                        } placeholder: {
                            Color.gray.opacity(0.3)
                        }
                        .frame(width: 78, height: 44)
                        .clipShape(RoundedRectangle(cornerRadius: 9))
                    } else {
                        Color.gray.opacity(0.3)
                            .frame(width: 78, height: 44)
                            .clipShape(RoundedRectangle(cornerRadius: 9))
                    }
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text("Next Episode")
                        .font(.system(size: 10, weight: .medium))
                        .foregroundColor(.white.opacity(0.8))
                    if let sn = state.nextEpisodeSeason, let en = state.nextEpisodeEpisode {
                        Text("S\(sn)E\(en) • \(state.nextEpisodeTitle ?? "")")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(.white)
                            .lineLimit(1)
                    }
                }

                HStack(spacing: 3) {
                    Image(systemName: "play.fill")
                        .font(.system(size: 11))
                        .foregroundColor(hasAired ? .white : .white.opacity(0.65))
                    Text(hasAired ? "Play" : "Unaired")
                        .font(.system(size: 11))
                        .foregroundColor(hasAired ? .white : .white.opacity(0.72))
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 5)
                .overlay(
                    Capsule().stroke(Color.white.opacity(0.2), lineWidth: 1)
                )
            }
            .padding(.horizontal, 9)
            .padding(.vertical, 8)
            .background(Color(red: 0.098, green: 0.098, blue: 0.098).opacity(0.89))
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .stroke(Color.white.opacity(0.12), lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .frame(maxWidth: 292)
    }

    func trackSelectionPanel(
        title: String,
        tracks: [NuvioTrackInfo],
        showNone: Bool,
        onSelect: @escaping (Int) -> Void,
        onDismiss: @escaping () -> Void
    ) -> some View {
        ZStack {
            Color.black.opacity(0.6)
                .onTapGesture { onDismiss() }

            VStack(spacing: 0) {
                HStack {
                    Text(title)
                        .font(.system(size: 18, weight: .bold))
                        .foregroundColor(.white)
                    Spacer()
                    Button(action: onDismiss) {
                        Image(systemName: "xmark")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(.white)
                            .frame(width: 32, height: 32)
                            .background(Color.white.opacity(0.15))
                            .clipShape(Circle())
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)
                .padding(.bottom, 12)

                ScrollView {
                    VStack(spacing: 2) {
                        if showNone {
                            trackRow(label: "None", lang: "", isSelected: !tracks.contains(where: { $0.selected })) {
                                onSelect(-1)
                            }
                        }
                        ForEach(Array(tracks.enumerated()), id: \.element.id) { _, track in
                            let label = track.title.isEmpty ? (track.lang.isEmpty ? "Track \(track.id)" : track.lang) : track.title
                            trackRow(label: label, lang: track.lang, isSelected: track.selected) {
                                onSelect(track.id)
                            }
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.bottom, 20)
                }
            }
            .frame(width: 360)
            .frame(maxHeight: 500)
            .background(Color(red: 0.12, green: 0.12, blue: 0.12))
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .stroke(Color.white.opacity(0.1), lineWidth: 1)
            )
        }
    }

    func trackRow(label: String, lang: String, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(label)
                        .font(.system(size: 14))
                        .foregroundColor(.white)
                    if !lang.isEmpty && lang != label {
                        Text(lang)
                            .font(.system(size: 11))
                            .foregroundColor(.white.opacity(0.6))
                    }
                }
                Spacer()
                if isSelected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(.white)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(isSelected ? Color.white.opacity(0.1) : Color.clear)
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
    }

    var openingOverlay: some View {
        GeometryReader { geo in
            ZStack {
                Color.black.opacity(0.85)

                if let artworkUrl = state.artwork, !artworkUrl.isEmpty {
                    if #available(macOS 12.0, *) {
                        AsyncImage(url: URL(string: artworkUrl)) { image in
                            image.resizable().aspectRatio(contentMode: .fill)
                        } placeholder: {
                            Color.clear
                        }
                        .frame(width: geo.size.width, height: geo.size.height)
                        .clipped()
                    }

                    LinearGradient(
                        colors: [
                            Color.black.opacity(0.3),
                            Color.black.opacity(0.6),
                            Color.black.opacity(0.8),
                            Color.black.opacity(0.9),
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                }

                VStack {
                    HStack {
                        Spacer()
                        Button(action: onClose) {
                            Image(systemName: "xmark")
                                .font(.system(size: 24, weight: .semibold))
                                .foregroundColor(.white)
                                .frame(width: 44, height: 44)
                                .background(Color.black.opacity(0.3))
                                .clipShape(Circle())
                        }
                        .buttonStyle(.plain)
                        .padding(.top, 20)
                        .padding(.trailing, 48)
                    }
                    Spacer()
                }

                OpeningOverlayContent(logo: state.logo, title: state.title)
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
    }

    var subtitleModal: some View {
        ZStack {
            Color.black.opacity(0.6)
                .onTapGesture { state.showSubtitlePanel = false }

            VStack(spacing: 0) {
                HStack {
                    Text("Subtitles")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundColor(.white)
                    Spacer()
                    Button(action: { state.showSubtitlePanel = false }) {
                        Image(systemName: "xmark")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(.white)
                            .frame(width: 32, height: 32)
                            .background(Color.white.opacity(0.15))
                            .clipShape(Circle())
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)
                .padding(.bottom, 12)

                HStack(spacing: 4) {
                    subtitleTabButton(title: "Built-In", index: 0)
                    subtitleTabButton(title: "Addons", index: 1)
                    subtitleTabButton(title: "Style", index: 2)
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 12)

                ScrollView {
                    switch state.subtitleTab {
                    case 0:
                        builtInSubtitleList
                    case 1:
                        addonSubtitleList
                    case 2:
                        subtitleStylePanel
                    default:
                        EmptyView()
                    }
                }
            }
            .frame(width: 420)
            .frame(maxHeight: 520)
            .background(Color(red: 0.12, green: 0.12, blue: 0.12))
            .clipShape(RoundedRectangle(cornerRadius: 24))
            .overlay(
                RoundedRectangle(cornerRadius: 24)
                    .stroke(Color.white.opacity(0.1), lineWidth: 1)
            )
        }
    }

    func subtitleTabButton(title: String, index: Int) -> some View {
        let selected = state.subtitleTab == index
        return Button(action: { state.subtitleTab = index }) {
            Text(title)
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(.white)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(selected ? Color.white.opacity(0.15) : Color.clear)
                .clipShape(RoundedRectangle(cornerRadius: selected ? 10 : 40))
        }
        .buttonStyle(.plain)
    }

    var builtInSubtitleList: some View {
        VStack(spacing: 2) {
            trackRow(
                label: "None",
                lang: "",
                isSelected: !state.subtitleTracks.contains(where: { $0.selected })
            ) {
                onRemoveExternalAndSelect?(-1)
                state.showSubtitlePanel = false
            }
            ForEach(Array(state.subtitleTracks.enumerated()), id: \.element.id) { _, track in
                let label = track.title.isEmpty ? (track.lang.isEmpty ? "Track \(track.id)" : track.lang) : track.title
                trackRow(label: label, lang: track.lang, isSelected: track.selected) {
                    onRemoveExternalAndSelect?(track.id)
                    state.showSubtitlePanel = false
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 20)
    }

    var addonSubtitleList: some View {
        VStack(spacing: 8) {
            if state.addonSubtitlesLoading {
                ProgressView()
                    .progressViewStyle(.circular)
                    .scaleEffect(0.8)
                    .frame(height: 80)
            } else if state.addonSubtitles.isEmpty {
                Button(action: { onFetchAddonSubtitles?() }) {
                    VStack(spacing: 12) {
                        Image(systemName: "icloud.and.arrow.down")
                            .font(.system(size: 28))
                            .foregroundColor(.white.opacity(0.6))
                        Text("Tap to fetch subtitles")
                            .font(.system(size: 14))
                            .foregroundColor(.white.opacity(0.6))
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 120)
                }
                .buttonStyle(.plain)
            } else {
                ForEach(state.addonSubtitles) { addon in
                    let selected = state.selectedAddonSubtitleId == addon.id
                    Button(action: {
                        state.selectedAddonSubtitleId = addon.id
                        onAddSubtitleUrl?(addon.url)
                        state.showSubtitlePanel = false
                    }) {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(addon.display)
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundColor(.white)
                                    .lineLimit(1)
                                Text(addon.language)
                                    .font(.system(size: 11))
                                    .foregroundColor(.white.opacity(0.6))
                            }
                            Spacer()
                            if selected {
                                Image(systemName: "checkmark")
                                    .font(.system(size: 14, weight: .bold))
                                    .foregroundColor(.white)
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(selected ? Color.white.opacity(0.1) : Color.clear)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 20)
    }

    var subtitleStylePanel: some View {
        VStack(spacing: 16) {
            VStack(spacing: 12) {
                styleRow(label: "Font Size") {
                    stepperControl(
                        value: state.subtitleStyleFontSize,
                        label: "\(state.subtitleStyleFontSize)sp",
                        onMinus: {
                            state.subtitleStyleFontSize = max(12, state.subtitleStyleFontSize - 2)
                            applyCurrentSubtitleStyle()
                        },
                        onPlus: {
                            state.subtitleStyleFontSize = min(40, state.subtitleStyleFontSize + 2)
                            applyCurrentSubtitleStyle()
                        }
                    )
                }

                styleRow(label: "Outline") {
                    Button(action: {
                        state.subtitleStyleOutlineEnabled.toggle()
                        applyCurrentSubtitleStyle()
                    }) {
                        Text(state.subtitleStyleOutlineEnabled ? "On" : "Off")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(.white)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 6)
                            .background(state.subtitleStyleOutlineEnabled ? Color.white.opacity(0.2) : Color.white.opacity(0.08))
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    }
                    .buttonStyle(.plain)
                }

                styleRow(label: "Bottom Offset") {
                    stepperControl(
                        value: state.subtitleStyleBottomOffset,
                        label: "\(state.subtitleStyleBottomOffset)",
                        onMinus: {
                            state.subtitleStyleBottomOffset = max(0, state.subtitleStyleBottomOffset - 5)
                            applyCurrentSubtitleStyle()
                        },
                        onPlus: {
                            state.subtitleStyleBottomOffset = min(200, state.subtitleStyleBottomOffset + 5)
                            applyCurrentSubtitleStyle()
                        }
                    )
                }
            }
            .padding(16)
            .background(Color.white.opacity(0.06))
            .clipShape(RoundedRectangle(cornerRadius: 16))

            VStack(alignment: .leading, spacing: 10) {
                Text("Color")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(.white.opacity(0.8))

                LazyVGrid(columns: Array(repeating: GridItem(.fixed(28), spacing: 10), count: 5), spacing: 10) {
                    ForEach(0..<subtitleColorSwatches.count, id: \.self) { i in
                        let (_, nsColor) = subtitleColorSwatches[i]
                        let swiftUIColor = Color(nsColor)
                        let selected = state.subtitleStyleTextColor == i
                        Button(action: {
                            state.subtitleStyleTextColor = i
                            applyCurrentSubtitleStyle()
                        }) {
                            Circle()
                                .fill(swiftUIColor)
                                .frame(width: 22, height: 22)
                                .overlay(
                                    Circle().stroke(selected ? Color.white : Color.white.opacity(0.2), lineWidth: selected ? 2 : 1)
                                )
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(16)
            .background(Color.white.opacity(0.06))
            .clipShape(RoundedRectangle(cornerRadius: 16))

            Button(action: {
                state.subtitleStyleTextColor = 0
                state.subtitleStyleFontSize = 30
                state.subtitleStyleOutlineEnabled = true
                state.subtitleStyleBottomOffset = 5
                applyCurrentSubtitleStyle()
            }) {
                Text("Reset Defaults")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(.white.opacity(0.7))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(Color.white.opacity(0.06))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 20)
    }

    func styleRow<Content: View>(label: String, @ViewBuilder content: () -> Content) -> some View {
        HStack {
            Text(label)
                .font(.system(size: 13, weight: .medium))
                .foregroundColor(.white.opacity(0.8))
            Spacer()
            content()
        }
    }

    func stepperControl(value: Int, label: String, onMinus: @escaping () -> Void, onPlus: @escaping () -> Void) -> some View {
        HStack(spacing: 6) {
            Button(action: onMinus) {
                Image(systemName: "minus")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(.white)
                    .frame(width: 28, height: 28)
                    .background(Color.white.opacity(0.15))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
            }
            .buttonStyle(.plain)

            Text(label)
                .font(.system(size: 13, weight: .bold))
                .foregroundColor(.white)
                .frame(minWidth: 48)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.white.opacity(0.08))
                .clipShape(RoundedRectangle(cornerRadius: 10))

            Button(action: onPlus) {
                Image(systemName: "plus")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(.white)
                    .frame(width: 28, height: 28)
                    .background(Color.white.opacity(0.15))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
            }
            .buttonStyle(.plain)
        }
    }

    private func applyCurrentSubtitleStyle() {
        let (hex, _) = subtitleColorSwatches[state.subtitleStyleTextColor]
        let outline: Float = state.subtitleStyleOutlineEnabled ? 2.0 : 0.0
        let fontSize = Float(state.subtitleStyleFontSize)
        let subPos = 100 - state.subtitleStyleBottomOffset
        onApplySubtitleStyle?(hex, outline, fontSize, subPos)
        state.subtitleStyleDirty = true
    }

    func errorOverlay(message: String) -> some View {
        ZStack {
            Color.black.opacity(0.9)
            VStack(spacing: 16) {
                Text("Playback error")
                    .font(.system(size: 24, weight: .bold))
                    .foregroundColor(.white)
                Text(message)
                    .font(.system(size: 16))
                    .foregroundColor(.white.opacity(0.72))
                    .multilineTextAlignment(.center)
                    .lineLimit(4)
                Button(action: onClose) {
                    Text("Go back")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(.white)
                        .frame(minWidth: 180, maxWidth: 260)
                        .padding(.vertical, 12)
                        .background(Color.red)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 32)
        }
    }
}

private extension Double {
    func clamped(to range: ClosedRange<Double>) -> Double {
        return min(max(self, range.lowerBound), range.upperBound)
    }
}


