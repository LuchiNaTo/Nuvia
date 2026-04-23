import AppKit
import SwiftUI

final class NuvioPlayerWindow {
    let state = NuvioPlayerState()
    var mpvView: NuvioMPVView!
    private var containerView: PlayerContainerView?
    private var stateTimer: Timer?
    private var hideTimer: Timer?
    private var hostingView: NSHostingView<NuvioControlsView>!
    private var keyMonitor: Any?
    private var mouseMonitor: Any?
    private var gestureDismissWork: DispatchWorkItem?

    func show() {
        DispatchQueue.main.async { [self] in
            guard let window = NSApp.keyWindow ?? NSApp.mainWindow,
                  let parentView = window.contentView else { return }

            window.acceptsMouseMovedEvents = true

            let containerView = PlayerContainerView(frame: parentView.bounds)
            containerView.translatesAutoresizingMaskIntoConstraints = false
            containerView.playerWindow = self
            self.containerView = containerView
            parentView.addSubview(containerView)
            NSLayoutConstraint.activate([
                containerView.leadingAnchor.constraint(equalTo: parentView.leadingAnchor),
                containerView.trailingAnchor.constraint(equalTo: parentView.trailingAnchor),
                containerView.topAnchor.constraint(equalTo: parentView.topAnchor),
                containerView.bottomAnchor.constraint(equalTo: parentView.bottomAnchor),
            ])

            mpvView = NuvioMPVView(frame: containerView.bounds)
            mpvView.translatesAutoresizingMaskIntoConstraints = false
            containerView.addSubview(mpvView)
            NSLayoutConstraint.activate([
                mpvView.leadingAnchor.constraint(equalTo: containerView.leadingAnchor),
                mpvView.trailingAnchor.constraint(equalTo: containerView.trailingAnchor),
                mpvView.topAnchor.constraint(equalTo: containerView.topAnchor),
                mpvView.bottomAnchor.constraint(equalTo: containerView.bottomAnchor),
            ])
            mpvView.setup()
            mpvView.onStateChanged = { [weak self] in
                self?.syncStateFromMPV()
            }

            let controlsView = NuvioControlsView(
                state: state,
                onPlay: { [weak self] in self?.mpvView.playPlayback() },
                onPause: { [weak self] in self?.mpvView.pausePlayback() },
                onSeekBack: { [weak self] in self?.mpvView.seekByMs(-10000) },
                onSeekForward: { [weak self] in self?.mpvView.seekByMs(10000) },
                onSeekTo: { [weak self] ms in self?.mpvView.seekToMs(ms) },
                onCycleResize: { [weak self] in self?.cycleResizeMode() },
                onCycleSpeed: { [weak self] in self?.cycleSpeed() },
                onSelectAudioTrack: { [weak self] trackId in self?.mpvView.selectAudio(trackId) },
                onSelectSubtitleTrack: { [weak self] trackId in self?.mpvView.selectSubtitle(trackId) },
                onClose: { [weak self] in self?.close() },
                onSkip: { [weak self] in
                    guard let self else { return }
                    self.mpvView.seekToMs(self.state.skipEndTimeMs)
                    self.state.skipButtonType = nil
                },
                onNextEpisode: { [weak self] in
                    self?.state.nextEpisodePressed = true
                },
                onApplySubtitleStyle: { [weak self] color, outline, fontSize, subPos in
                    self?.mpvView.applySubtitleStyle(textColor: color, outlineSize: outline, fontSize: fontSize, subPos: subPos)
                },
                onAddSubtitleUrl: { [weak self] url in
                    self?.mpvView.addSubtitleUrl(url)
                },
                onRemoveExternalAndSelect: { [weak self] trackId in
                    self?.mpvView.removeExternalSubtitlesAndSelect(trackId)
                },
                onFetchAddonSubtitles: { [weak self] in
                    self?.state.addonSubtitlesFetchRequested = true
                }
            )
            hostingView = NSHostingView(rootView: controlsView)
            hostingView.translatesAutoresizingMaskIntoConstraints = false
            hostingView.layer?.backgroundColor = .clear
            containerView.addSubview(hostingView)
            NSLayoutConstraint.activate([
                hostingView.leadingAnchor.constraint(equalTo: containerView.leadingAnchor),
                hostingView.trailingAnchor.constraint(equalTo: containerView.trailingAnchor),
                hostingView.topAnchor.constraint(equalTo: containerView.topAnchor),
                hostingView.bottomAnchor.constraint(equalTo: containerView.bottomAnchor),
            ])

            let area = NSTrackingArea(
                rect: containerView.bounds,
                options: [.mouseMoved, .activeAlways, .inVisibleRect, .mouseEnteredAndExited],
                owner: containerView,
                userInfo: nil
            )
            containerView.addTrackingArea(area)

            stateTimer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
                self?.syncStateFromMPV()
            }

            scheduleHideControls()

            keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
                guard let self else { return event }
                return self.handleKeyDown(event) ? nil : event
            }

            mouseMonitor = NSEvent.addLocalMonitorForEvents(matching: [.mouseMoved, .leftMouseDragged]) { [weak self] event in
                self?.handleMouseMoved()
                return event
            }
        }
    }

    func close() {
        DispatchQueue.main.async { [self] in
            if let monitor = keyMonitor {
                NSEvent.removeMonitor(monitor)
                keyMonitor = nil
            }
            if let monitor = mouseMonitor {
                NSEvent.removeMonitor(monitor)
                mouseMonitor = nil
            }
            gestureDismissWork?.cancel()
            gestureDismissWork = nil
            stateTimer?.invalidate()
            stateTimer = nil
            hideTimer?.invalidate()
            hideTimer = nil
            mpvView?.destroyPlayer()
            containerView?.removeFromSuperview()
            containerView = nil
            state.isClosed = true
            NSCursor.unhide()
        }
    }

    func handleMouseMoved() {
        showControls()
        scheduleHideControls()
    }

    func handleMouseClicked() {
        if state.controlsVisible {
            hideControlsNow()
        } else {
            showControls()
            scheduleHideControls()
        }
    }

    func handleKeyDown(_ event: NSEvent) -> Bool {
        switch event.keyCode {
        case 53:
            close()
            return true
        case 49:
            if state.isPlaying { mpvView.pausePlayback() } else { mpvView.playPlayback() }
            return true
        case 123:
            mpvView.seekByMs(-10000)
            showGestureFeedback(GestureFeedbackState(message: "-10s", icon: .seekBackward))
            return true
        case 124:
            mpvView.seekByMs(10000)
            showGestureFeedback(GestureFeedbackState(message: "+10s", icon: .seekForward))
            return true
        case 125:
            let vol = mpvView.adjustVolume(by: -5)
            let muted = vol <= 0
            showGestureFeedback(GestureFeedbackState(
                message: muted ? "Muted" : "Volume \(Int(vol))%",
                icon: muted ? .volumeMuted : .volume,
                isDanger: muted
            ))
            return true
        case 126:
            let vol = mpvView.adjustVolume(by: 5)
            showGestureFeedback(GestureFeedbackState(message: "Volume \(Int(vol))%", icon: .volume))
            return true
        default:
            return false
        }
    }

    private func showGestureFeedback(_ feedback: GestureFeedbackState) {
        state.gestureFeedback = feedback
        gestureDismissWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            self?.state.gestureFeedback = nil
        }
        gestureDismissWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.9, execute: work)
    }

    private func syncStateFromMPV() {
        guard mpvView != nil, mpvView.mpv != nil else { return }
        mpvView.refreshPlaybackState()
        state.isLoading = mpvView.isPlayerLoading
        state.isPlaying = mpvView.isPlayerPlaying
        state.isEnded = mpvView.isPlayerEnded
        state.positionMs = mpvView.positionMs
        state.durationMs = mpvView.durationMs
        state.bufferedMs = mpvView.bufferedMs
        state.currentSpeed = mpvView.currentSpeed
        state.audioTracks = mpvView.audioTracks
        state.subtitleTracks = mpvView.subtitleTracks
        state.errorMessage = mpvView.currentErrorMessage
        if !mpvView.isPlayerLoading && !state.initialLoadCompleted {
            state.initialLoadCompleted = true
        }
    }

    private func cycleResizeMode() {
        let next = (state.resizeMode + 1) % 3
        state.resizeMode = next
        mpvView.setResize(next)
    }

    private func cycleSpeed() {
        let speeds: [Float] = [1.0, 1.25, 1.5, 2.0]
        let current = state.currentSpeed
        var nextSpeed: Float = 1.0
        for s in speeds {
            if s > current + 0.01 {
                nextSpeed = s
                break
            }
        }
        state.currentSpeed = nextSpeed
        mpvView.setSpeed(nextSpeed)
    }

    private func showControls() {
        state.controlsVisible = true
        if state.cursorHidden {
            NSCursor.unhide()
            state.cursorHidden = false
        }
    }

    private func hideControlsNow() {
        hideTimer?.invalidate()
        hideTimer = nil
        state.controlsVisible = false
        if !state.cursorHidden {
            NSCursor.hide()
            state.cursorHidden = true
        }
    }

    private func scheduleHideControls() {
        hideTimer?.invalidate()
        hideTimer = Timer.scheduledTimer(withTimeInterval: 3.5, repeats: false) { [weak self] _ in
            guard let self, self.state.isPlaying else { return }
            if self.state.showSubtitlePanel || self.state.showAudioPanel || self.state.showSourcesPanel || self.state.showEpisodesPanel { return }
            self.state.controlsVisible = false
            if !self.state.cursorHidden {
                NSCursor.hide()
                self.state.cursorHidden = true
            }
        }
    }
}

final class PlayerContainerView: NSView {
    weak var playerWindow: NuvioPlayerWindow?

    override var acceptsFirstResponder: Bool { true }

    override func layout() {
        super.layout()
        playerWindow?.mpvView?.openGLContext?.update()
    }

    override func keyDown(with event: NSEvent) {
    }

    override func mouseMoved(with event: NSEvent) {
        playerWindow?.handleMouseMoved()
    }

    override func mouseDown(with event: NSEvent) {
        if event.clickCount == 2 {
            if let w = window {
                w.toggleFullScreen(nil)
            }
        }
    }
}
