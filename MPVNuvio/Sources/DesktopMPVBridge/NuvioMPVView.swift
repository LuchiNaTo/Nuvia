import AppKit
import OpenGL.GL
import OpenGL.GL3
import Libmpv

final class NuvioMPVView: NSOpenGLView {
    private(set) var mpv: OpaquePointer!
    private var mpvGL: OpaquePointer!
    private var defaultFBO: GLint = -1
    private let eventQueue = DispatchQueue(label: "nuvio-mpv", qos: .userInteractive)
    private let renderQueue = DispatchQueue(label: "nuvio-mpv-render", qos: .userInteractive)
    private var renderPending = false
    private var isResizing = false
    private var cachedPixelWidth: Int32 = 0
    private var cachedPixelHeight: Int32 = 0
    private var activeRequestHeaders: [String: String] = [:]

    var audioTracks: [NuvioTrackInfo] = []
    var subtitleTracks: [NuvioTrackInfo] = []

    var isPlayerLoading: Bool = true
    var isPlayerPlaying: Bool = false
    var isPlayerEnded: Bool = false
    var durationMs: Int64 = 0
    var positionMs: Int64 = 0
    var bufferedMs: Int64 = 0
    var currentSpeed: Float = 1.0
    var currentErrorMessage: String?

    var onStateChanged: (() -> Void)?

    override class func defaultPixelFormat() -> NSOpenGLPixelFormat {
        let attributes: [NSOpenGLPixelFormatAttribute] = [
            NSOpenGLPixelFormatAttribute(NSOpenGLPFADoubleBuffer),
            NSOpenGLPixelFormatAttribute(NSOpenGLPFAColorSize), 32,
            NSOpenGLPixelFormatAttribute(NSOpenGLPFADepthSize), 24,
            NSOpenGLPixelFormatAttribute(NSOpenGLPFAStencilSize), 8,
            NSOpenGLPixelFormatAttribute(NSOpenGLPFAMultisample),
            NSOpenGLPixelFormatAttribute(NSOpenGLPFASampleBuffers), 1,
            NSOpenGLPixelFormatAttribute(NSOpenGLPFASamples), 4,
            0,
        ]
        return NSOpenGLPixelFormat(attributes: attributes)!
    }

    func setup() {
        wantsBestResolutionOpenGLSurface = true
        autoresizingMask = [.width, .height]
        openGLContext!.makeCurrentContext()
        setupMpv()
    }

    private func setupMpv() {
        mpv = mpv_create()
        guard mpv != nil else { return }

        checkError(mpv_request_log_messages(mpv, "warn"))
        checkError(mpv_set_option_string(mpv, "input-media-keys", "yes"))
        checkError(mpv_set_option_string(mpv, "subs-match-os-language", "yes"))
        checkError(mpv_set_option_string(mpv, "subs-fallback", "yes"))
        checkError(mpv_set_option_string(mpv, "hwdec", "auto-safe"))
        checkError(mpv_set_option_string(mpv, "vo", "libmpv"))
        checkError(mpv_set_option_string(mpv, "keep-open", "yes"))

        checkError(mpv_initialize(mpv))

        let api = UnsafeMutableRawPointer(mutating: (MPV_RENDER_API_TYPE_OPENGL as NSString).utf8String)
        var initParams = mpv_opengl_init_params(
            get_proc_address: { (ctx, name) in
                return NuvioMPVView.getProcAddress(ctx, name)
            },
            get_proc_address_ctx: nil
        )

        withUnsafeMutablePointer(to: &initParams) { ip in
            var params = [
                mpv_render_param(type: MPV_RENDER_PARAM_API_TYPE, data: api),
                mpv_render_param(type: MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, data: ip),
                mpv_render_param()
            ]
            if mpv_render_context_create(&mpvGL, mpv, &params) < 0 {
                return
            }
            mpv_render_context_set_update_callback(
                mpvGL,
                { ctx in
                    let view = unsafeBitCast(ctx, to: NuvioMPVView.self)
                    view.scheduleRender()
                },
                UnsafeMutableRawPointer(Unmanaged.passUnretained(self).toOpaque())
            )
        }

        mpv_observe_property(mpv, 0, "pause", MPV_FORMAT_FLAG)
        mpv_observe_property(mpv, 0, "paused-for-cache", MPV_FORMAT_FLAG)
        mpv_observe_property(mpv, 0, "core-idle", MPV_FORMAT_FLAG)
        mpv_observe_property(mpv, 0, "eof-reached", MPV_FORMAT_FLAG)
        mpv_observe_property(mpv, 0, "seeking", MPV_FORMAT_FLAG)
        mpv_observe_property(mpv, 0, "track-list/count", MPV_FORMAT_INT64)

        mpv_set_wakeup_callback(mpv, { ctx in
            let view = unsafeBitCast(ctx, to: NuvioMPVView.self)
            view.readEvents()
        }, UnsafeMutableRawPointer(Unmanaged.passUnretained(self).toOpaque()))
    }

    func loadFile(_ urlString: String, audioUrl: String? = nil, requestHeaders: [String: String] = [:]) {
        guard mpv != nil else { return }
        currentErrorMessage = nil
        activeRequestHeaders = sanitizeHeaders(requestHeaders)
        applyHeaders(activeRequestHeaders)
        isPlayerLoading = true
        isPlayerEnded = false
        command("loadfile", args: [urlString, "replace"])
        if let audioUrl, !audioUrl.isEmpty {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
                self?.command("audio-add", args: [audioUrl, "select"], check: false)
            }
        }
    }

    func playPlayback() {
        guard mpv != nil else { return }
        setFlag("pause", false)
    }

    func pausePlayback() {
        guard mpv != nil else { return }
        setFlag("pause", true)
    }

    func seekToMs(_ ms: Int64) {
        guard mpv != nil else { return }
        let seconds = Double(ms) / 1000.0
        command("seek", args: [String(format: "%.3f", seconds), "absolute"])
    }

    func seekByMs(_ ms: Int64) {
        guard mpv != nil else { return }
        let seconds = Double(ms) / 1000.0
        command("seek", args: [String(format: "%.3f", seconds), "relative"])
    }

    func retryPlayback() {
        guard mpv != nil else { return }
        if let path = getString("path") {
            currentErrorMessage = nil
            applyHeaders(activeRequestHeaders)
            let pos = getDouble("time-pos")
            command("loadfile", args: [path, "replace"])
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                self?.command("seek", args: [String(format: "%.3f", pos), "absolute"])
            }
        }
    }

    func setSpeed(_ speed: Float) {
        guard mpv != nil else { return }
        var s = Double(speed)
        mpv_set_property(mpv, "speed", MPV_FORMAT_DOUBLE, &s)
    }

    func setResize(_ mode: Int) {
        guard mpv != nil else { return }
        switch mode {
        case 1:
            checkError(mpv_set_option_string(mpv, "panscan", "1.0"))
            checkError(mpv_set_option_string(mpv, "video-unscaled", "no"))
        case 2:
            checkError(mpv_set_option_string(mpv, "panscan", "0.0"))
            checkError(mpv_set_option_string(mpv, "video-unscaled", "downscale-big"))
        default:
            checkError(mpv_set_option_string(mpv, "panscan", "0.0"))
            checkError(mpv_set_option_string(mpv, "video-unscaled", "no"))
        }
    }

    func selectAudio(_ trackId: Int) {
        guard mpv != nil else { return }
        var id = Int64(trackId)
        mpv_set_property(mpv, "aid", MPV_FORMAT_INT64, &id)
    }

    func selectSubtitle(_ trackId: Int) {
        guard mpv != nil else { return }
        if trackId < 0 {
            checkError(mpv_set_option_string(mpv, "sid", "no"))
        } else {
            var id = Int64(trackId)
            mpv_set_property(mpv, "sid", MPV_FORMAT_INT64, &id)
        }
    }

    func addSubtitleUrl(_ url: String) {
        guard mpv != nil else { return }
        command("sub-add", args: [url, "select"])
    }

    func removeExternalSubtitles() {
        guard mpv != nil else { return }
        let count = getInt("track-list/count")
        for i in stride(from: count - 1, through: 0, by: -1) {
            let type = getString("track-list/\(i)/type") ?? ""
            let external = getFlag("track-list/\(i)/external")
            if type == "sub" && external {
                let id = getInt("track-list/\(i)/id")
                command("sub-remove", args: ["\(id)"], check: false)
            }
        }
        checkError(mpv_set_option_string(mpv, "sid", "no"))
    }

    func removeExternalSubtitlesAndSelect(_ trackId: Int) {
        guard mpv != nil else { return }
        let count = getInt("track-list/count")
        for i in stride(from: count - 1, through: 0, by: -1) {
            let type = getString("track-list/\(i)/type") ?? ""
            let external = getFlag("track-list/\(i)/external")
            if type == "sub" && external {
                let id = getInt("track-list/\(i)/id")
                command("sub-remove", args: ["\(id)"], check: false)
            }
        }
        if trackId >= 0 {
            selectSubtitle(trackId)
        } else {
            checkError(mpv_set_option_string(mpv, "sid", "no"))
        }
    }

    private var pendingSubStyle: (String, Float, Float, Int)?

    func applySubtitleStyle(textColor: String, outlineSize: Float, fontSize: Float, subPos: Int) {
        pendingSubStyle = (textColor, outlineSize, fontSize, subPos)
        guard mpv != nil else { return }
        checkError(mpv_set_property_string(mpv, "sub-ass-override", "yes"))
        checkError(mpv_set_property_string(mpv, "sub-color", textColor))
        checkError(mpv_set_property_string(mpv, "sub-outline-color", "#000000"))
        var outline = Double(outlineSize)
        checkError(mpv_set_property(mpv, "sub-outline-size", MPV_FORMAT_DOUBLE, &outline))
        var size = Double(fontSize)
        checkError(mpv_set_property(mpv, "sub-font-size", MPV_FORMAT_DOUBLE, &size))
        var position = Int64(subPos)
        checkError(mpv_set_property(mpv, "sub-pos", MPV_FORMAT_INT64, &position))
    }

    func reapplyPendingSubtitleStyle() {
        guard let (color, outline, size, pos) = pendingSubStyle else { return }
        applySubtitleStyle(textColor: color, outlineSize: outline, fontSize: size, subPos: pos)
    }

    func refreshPlaybackState() {
        guard mpv != nil else { return }
        let duration = getDouble("duration")
        let position = getDouble("time-pos")
        let cached = getDouble("demuxer-cache-time")
        let speed = getDouble("speed")
        let paused = getFlag("pause")
        let eofReached = getFlag("eof-reached")
        let idle = getFlag("core-idle")
        let seeking = getFlag("seeking")
        let bufferingCache = getFlag("paused-for-cache")

        isPlayerLoading = (idle && !paused && !eofReached) || seeking || bufferingCache
        isPlayerPlaying = !paused && !idle && !eofReached
        isPlayerEnded = eofReached
        durationMs = Int64(duration * 1000)
        positionMs = Int64(max(position, 0) * 1000)
        bufferedMs = Int64(max(position + cached, 0) * 1000)
        currentSpeed = Float(speed > 0 ? speed : 1.0)
    }

    func refreshTracks() {
        guard mpv != nil else { return }
        var audio = [NuvioTrackInfo]()
        var subs = [NuvioTrackInfo]()
        let count = getInt("track-list/count")
        var audioIdx = 0
        var subIdx = 0

        for i in 0..<count {
            let type = getString("track-list/\(i)/type") ?? ""
            let id = getInt("track-list/\(i)/id")
            let t = getString("track-list/\(i)/title") ?? ""
            let lang = getString("track-list/\(i)/lang") ?? ""
            let selected = getFlag("track-list/\(i)/selected")

            if type == "audio" {
                audio.append(NuvioTrackInfo(index: audioIdx, id: id, type: type, title: t, lang: lang, selected: selected))
                audioIdx += 1
            } else if type == "sub" {
                subs.append(NuvioTrackInfo(index: subIdx, id: id, type: type, title: t, lang: lang, selected: selected))
                subIdx += 1
            }
        }
        audioTracks = audio
        subtitleTracks = subs
    }

    func destroyPlayer() {
        guard let ctx = mpv else { return }
        mpv = nil
        if let gl = mpvGL {
            mpvGL = nil
            mpv_render_context_free(gl)
        }
        mpv_terminate_destroy(ctx)
    }

    private func updateCachedSize() {
        let scale = window?.backingScaleFactor ?? 1.0
        cachedPixelWidth = Int32(bounds.width * scale)
        cachedPixelHeight = Int32(bounds.height * scale)
    }

    private func scheduleRender() {
        if isResizing { return }
        guard !renderPending else { return }
        renderPending = true
        renderQueue.async { [weak self] in
            guard let self else { return }
            self.renderPending = false
            if self.isResizing { return }
            self.renderFrame()
        }
    }

    private func renderFrame() {
        guard let ctx = self.openGLContext, let mpvGL = self.mpvGL else { return }
        ctx.makeCurrentContext()
        ctx.lock()

        let w = cachedPixelWidth
        let h = cachedPixelHeight
        guard w > 0, h > 0 else {
            ctx.unlock()
            return
        }

        glViewport(0, 0, w, h)
        glClearColor(0, 0, 0, 0)
        glClear(UInt32(GL_COLOR_BUFFER_BIT))
        glGetIntegerv(UInt32(GL_FRAMEBUFFER_BINDING), &defaultFBO)

        var data = mpv_opengl_fbo(
            fbo: Int32(defaultFBO),
            w: w,
            h: h,
            internal_format: 0
        )
        var flip: CInt = 1

        withUnsafeMutablePointer(to: &flip) { flipPtr in
            withUnsafeMutablePointer(to: &data) { dataPtr in
                var params = [
                    mpv_render_param(type: MPV_RENDER_PARAM_OPENGL_FBO, data: dataPtr),
                    mpv_render_param(type: MPV_RENDER_PARAM_FLIP_Y, data: flipPtr),
                    mpv_render_param()
                ]
                mpv_render_context_render(mpvGL, &params)
            }
        }

        ctx.flushBuffer()
        ctx.unlock()
    }

    override func viewWillStartLiveResize() {
        super.viewWillStartLiveResize()
        isResizing = true
    }

    override func viewDidEndLiveResize() {
        super.viewDidEndLiveResize()
        isResizing = false
        updateCachedSize()
        openGLContext?.update()
        renderFrame()
        scheduleRender()
    }

    override func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        if isResizing {
            openGLContext?.update()
            return
        }
        updateCachedSize()
        openGLContext?.update()
        renderFrame()
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        updateCachedSize()
    }

    override func update() {
        super.update()
        if isResizing {
            openGLContext?.update()
            return
        }
        updateCachedSize()
        renderFrame()
    }

    override func reshape() {
        super.reshape()
        if isResizing {
            openGLContext?.update()
            return
        }
        updateCachedSize()
        renderFrame()
    }

    override func draw(_ dirtyRect: NSRect) {
        if isResizing { return }
        updateCachedSize()
        renderFrame()
    }

    private func readEvents() {
        eventQueue.async { [weak self] in
            guard let self, let mpv = self.mpv else { return }
            while true {
                let event = mpv_wait_event(mpv, 0)
                guard let eventPtr = event else { break }
                if eventPtr.pointee.event_id == MPV_EVENT_NONE { break }

                switch eventPtr.pointee.event_id {
                case MPV_EVENT_PROPERTY_CHANGE:
                    DispatchQueue.main.async {
                        self.refreshPlaybackState()
                        self.refreshTracks()
                        self.onStateChanged?()
                    }
                case MPV_EVENT_FILE_LOADED:
                    DispatchQueue.main.async {
                        self.currentErrorMessage = nil
                        self.isPlayerLoading = false
                        self.reapplyPendingSubtitleStyle()
                        self.refreshPlaybackState()
                        self.refreshTracks()
                        self.onStateChanged?()
                    }
                case MPV_EVENT_END_FILE:
                    if let d = eventPtr.pointee.data {
                        let endFile = UnsafePointer<mpv_event_end_file>(OpaquePointer(d)).pointee
                        if endFile.reason == MPV_END_FILE_REASON_ERROR {
                            let errorText = String(cString: mpv_error_string(endFile.error))
                            self.currentErrorMessage = errorText
                        }
                    }
                    DispatchQueue.main.async { self.onStateChanged?() }
                case MPV_EVENT_SHUTDOWN:
                    return
                case MPV_EVENT_LOG_MESSAGE:
                    break
                default:
                    break
                }
            }
        }
    }

    private func command(_ cmd: String, args: [String?] = [], check: Bool = true) {
        guard mpv != nil else { return }
        var cargs = makeCArgs(cmd, args).map { $0.flatMap { UnsafePointer<CChar>(strdup($0)) } }
        defer { for ptr in cargs where ptr != nil { free(UnsafeMutablePointer(mutating: ptr!)) } }
        let ret = mpv_command(mpv, &cargs)
        if check { checkError(ret) }
    }

    private func makeCArgs(_ cmd: String, _ args: [String?]) -> [String?] {
        var strArgs = args
        strArgs.insert(cmd, at: 0)
        strArgs.append(nil)
        return strArgs
    }

    private func getDouble(_ name: String) -> Double {
        guard mpv != nil else { return 0.0 }
        var data = Double()
        mpv_get_property(mpv, name, MPV_FORMAT_DOUBLE, &data)
        return data
    }

    private func getString(_ name: String) -> String? {
        guard mpv != nil else { return nil }
        let cstr = mpv_get_property_string(mpv, name)
        let str: String? = cstr == nil ? nil : String(cString: cstr!)
        mpv_free(cstr)
        return str
    }

    private func getFlag(_ name: String) -> Bool {
        guard mpv != nil else { return false }
        var data = Int64()
        mpv_get_property(mpv, name, MPV_FORMAT_FLAG, &data)
        return data > 0
    }

    private func setFlag(_ name: String, _ flag: Bool) {
        guard mpv != nil else { return }
        var data: Int = flag ? 1 : 0
        mpv_set_property(mpv, name, MPV_FORMAT_FLAG, &data)
    }

    @discardableResult
    func adjustVolume(by delta: Double) -> Double {
        guard mpv != nil else { return 0 }
        let current = getDouble("volume")
        var newVol = min(max(current + delta, 0), 100)
        mpv_set_property(mpv, "volume", MPV_FORMAT_DOUBLE, &newVol)
        return newVol
    }

    private func getInt(_ name: String) -> Int {
        guard mpv != nil else { return 0 }
        var data = Int64()
        mpv_get_property(mpv, name, MPV_FORMAT_INT64, &data)
        return Int(data)
    }

    private func checkError(_ status: CInt) {
        if status < 0 {
            print("[NuvioMPV] error: \(String(cString: mpv_error_string(status)))")
        }
    }

    private func sanitizeHeaders(_ headers: [String: String]) -> [String: String] {
        guard !headers.isEmpty else { return [:] }
        var sanitized: [String: String] = [:]
        headers.forEach { rawKey, rawValue in
            let key = rawKey.trimmingCharacters(in: .whitespacesAndNewlines)
            let value = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !key.isEmpty, !value.isEmpty else { return }
            guard key.caseInsensitiveCompare("Range") != .orderedSame else { return }
            sanitized[key] = value
        }
        return sanitized
    }

    private func applyHeaders(_ headers: [String: String]) {
        guard mpv != nil else { return }
        if headers.isEmpty {
            checkError(mpv_set_property_string(mpv, "http-header-fields", ""))
            return
        }
        let serialized = headers
            .sorted { $0.key.localizedCaseInsensitiveCompare($1.key) == .orderedAscending }
            .map { key, value in
                let escapedValue = value
                    .replacingOccurrences(of: "\\", with: "\\\\")
                    .replacingOccurrences(of: ",", with: "\\,")
                return "\(key): \(escapedValue)"
            }
            .joined(separator: ",")
        checkError(mpv_set_property_string(mpv, "http-header-fields", serialized))
    }

    private static func getProcAddress(_: UnsafeMutableRawPointer?, _ name: UnsafePointer<Int8>?) -> UnsafeMutableRawPointer? {
        let symbolName = CFStringCreateWithCString(kCFAllocatorDefault, name, CFStringBuiltInEncodings.ASCII.rawValue)
        let identifier = CFBundleGetBundleWithIdentifier("com.apple.opengl" as CFString)
        return CFBundleGetFunctionPointerForName(identifier, symbolName)
    }
}
