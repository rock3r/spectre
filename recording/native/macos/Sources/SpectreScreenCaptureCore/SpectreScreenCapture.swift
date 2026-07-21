import AVFoundation
import AppKit
import CoreGraphics
import CoreImage
import CoreMedia
import Foundation
import ImageIO
import ScreenCaptureKit
import UniformTypeIdentifiers

// MARK: - CLI contract
//
// spectre-screencapture
//   --mode <recording|screenshot|preflight|request>
//                              Optional. Default recording.
//                              preflight: CGPreflightScreenCaptureAccess only (never prompts).
//                              request:   CGRequestScreenCaptureAccess (human-invoked only).
//   --source <window|region>   Optional. Default window. Ignored for preflight/request.
//   --pid <jvm pid>            Required for window source. Filters CGWindowList by owner PID so we never
//                              capture another process's window matching the discriminator.
//   --title-contains <suffix>  Required for window source. Substring match on the target window's kCGWindowName.
//                              Spectre stamps a `Spectre/<uuid>` suffix on the ComposeWindow's
//                              title for the recording's lifetime so this is unique.
//   --region <x,y,w,h>         Required for region source. Coordinates use the selected
//                              display's top-left ScreenCaptureKit sourceRect space.
//   --crop <x,y,w,h>           Optional for window source. Crop rect relative to the window's
//                              top-left in the same point space as SCWindow.frame / AWT user
//                              space. Fixed for the recording lifetime (issue #186).
//   --display-index <int>      Optional for region source. Default 0; primary display first.
//   --output <path>            Required for recording/screenshot. Destination .mov/.mp4/.png.
//   --fps <int>                Optional. Default 30.
//   --cursor <true|false>      Optional. Default true.
//   --file-type <mov|mp4>      Optional. AVAssetWriter container type. Defaults from output path.
//   --discovery-timeout-ms <int>  Optional. Default 2000.
//
// Lifecycle (recording/screenshot):
//   - On start, scans CGWindowListCopyWindowInfo until a window matching (pid, title-contains)
//     appears or the discovery timeout elapses.
//   - Streams frames via SCStream into AVAssetWriter (H.264) until either:
//       * stdin reads `q\n` (graceful, mirrors FfmpegRecorder's shutdown contract)
//       * stdin closes
//       * SIGTERM
//   - Always finalises the writer before exit so the requested movie file is playable.
//
// Preflight / request:
//   - Emit one JSON object on stdout (see ScreenCaptureAccessResult).
//   - Never start ScreenCaptureKit capture.
//
// Exit codes:
//   0  clean shutdown / access granted (preflight/request)
//   2  bad arguments
//   3  window not found within the discovery timeout
//   4  permission denied mid-capture (Screen Recording TCC during SCStream)
//   5  capture pipeline error after start
//   6  preflight/request: Screen Recording not granted (structured JSON on stdout)

public enum SpectreScreenCaptureCommand {
    public static func main(_ argv: [String] = CommandLine.arguments) async -> Never {
        do {
            let args = try Arguments.parse(argv)
            switch args.mode {
            case .preflight:
                let result = ScreenCaptureAccess.preflight(binaryPath: argv.first ?? "spectre-screencapture")
                FileHandle.standardOutput.write(Data(result.jsonLine.utf8))
                exit(result.granted ? 0 : 6)
            case .request:
                let result = ScreenCaptureAccess.request(binaryPath: argv.first ?? "spectre-screencapture")
                FileHandle.standardOutput.write(Data(result.jsonLine.utf8))
                exit(result.granted ? 0 : 6)
            case .recording, .screenshot:
                let recorder = Recorder(arguments: args)
                try await recorder.run()
                exit(0)
            }
        } catch let error as CLIError {
            FileHandle.standardError.write(Data("spectre-screencapture: \(error.message)\n".utf8))
            exit(error.code)
        } catch {
            FileHandle.standardError.write(
                Data("spectre-screencapture: unexpected error: \(error)\n".utf8))
            exit(5)
        }
    }
}

/// Structured TCC preflight / request result. One JSON object per line on stdout.
struct ScreenCaptureAccessResult {
    let granted: Bool
    let binaryPath: String
    let settingsPath: String
    let deepLink: String
    let guidance: String

    var jsonLine: String {
        // Manual JSON — keep the helper free of extra dependencies.
        let esc: (String) -> String = { raw in
            raw
                .replacingOccurrences(of: "\\", with: "\\\\")
                .replacingOccurrences(of: "\"", with: "\\\"")
                .replacingOccurrences(of: "\n", with: "\\n")
        }
        return
            "{"
            + "\"granted\":\(granted ? "true" : "false"),"
            + "\"api\":\"CGPreflightScreenCaptureAccess\","
            + "\"binary\":\"\(esc(binaryPath))\","
            + "\"settings_path\":\"\(esc(settingsPath))\","
            + "\"deep_link\":\"\(esc(deepLink))\","
            + "\"guidance\":\"\(esc(guidance))\""
            + "}\n"
    }
}

enum ScreenCaptureAccess {
    static let settingsPath =
        "System Settings → Privacy & Security → Screen & System Audio Recording"
    static let deepLink =
        "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture"

    /// Never prompts. Uses CGPreflightScreenCaptureAccess only.
    static func preflight(binaryPath: String) -> ScreenCaptureAccessResult {
        let granted = CGPreflightScreenCaptureAccess()
        return result(granted: granted, binaryPath: binaryPath)
    }

    /// May prompt / create a System Settings row. Human-invoked only.
    static func request(binaryPath: String) -> ScreenCaptureAccessResult {
        _ = CGRequestScreenCaptureAccess()
        // Re-check; the user may still deny.
        let granted = CGPreflightScreenCaptureAccess()
        return result(granted: granted, binaryPath: binaryPath)
    }

    private static func result(granted: Bool, binaryPath: String) -> ScreenCaptureAccessResult {
        let guidance: String
        if granted {
            guidance =
                "Screen Recording is granted for \(binaryPath). Capture may proceed."
        } else {
            guidance =
                "Screen Recording is NOT granted for \(binaryPath). "
                + "An agent cannot click the TCC prompt (SecurityAgent is not automatable). "
                + "A human must open \(settingsPath) (deep link: \(deepLink)), enable "
                + "\(binaryPath) (or the host JVM that launches it), then restart that process. "
                + "Run `spectre permissions request` only when a human is present to approve."
        }
        return ScreenCaptureAccessResult(
            granted: granted,
            binaryPath: binaryPath,
            settingsPath: settingsPath,
            deepLink: deepLink,
            guidance: guidance
        )
    }
}

struct CLIError: Error {
    let code: Int32
    let message: String
}

struct Arguments {
    let mode: CaptureMode
    let source: CaptureSource
    let pid: pid_t?
    let titleContains: String?
    let region: CaptureRegion?
    /// Optional window-relative crop (points). Only valid for window source.
    let crop: CaptureRegion?
    let displayIndex: Int
    let output: URL
    let fps: Int
    let captureCursor: Bool
    let fileType: RecordingFileType
    let discoveryTimeoutMs: Int

    static func parse(_ argv: [String]) throws -> Arguments {
        var mode = CaptureMode.recording
        var source = CaptureSource.window
        var pid: pid_t?
        var titleContains: String?
        var region: CaptureRegion?
        var crop: CaptureRegion?
        var displayIndex = 0
        var output: String?
        var fps = 30
        var cursor = true
        var fileType: RecordingFileType?
        var discoveryTimeoutMs = 2000

        var i = 1
        while i < argv.count {
            let key = argv[i]
            // Boolean flag form for preflight (issue #187): --preflight with no value.
            if key == "--preflight" {
                mode = .preflight
                i += 1
                continue
            }
            if key == "--request-access" {
                mode = .request
                i += 1
                continue
            }
            guard i + 1 < argv.count else {
                throw CLIError(code: 2, message: "missing value for \(key)")
            }
            let value = argv[i + 1]
            switch key {
            case "--mode":
                guard let parsed = CaptureMode(rawValue: value) else {
                    throw CLIError(
                        code: 2,
                        message: "--mode must be recording, screenshot, preflight, or request")
                }
                mode = parsed
            case "--source":
                guard let parsed = CaptureSource(rawValue: value) else {
                    throw CLIError(code: 2, message: "--source must be window or region")
                }
                source = parsed
            case "--pid":
                guard let parsed = pid_t(value) else {
                    throw CLIError(code: 2, message: "--pid must be an integer")
                }
                pid = parsed
            case "--title-contains":
                titleContains = value
            case "--region":
                region = try CaptureRegion.parse(value, flag: "--region")
            case "--crop":
                crop = try CaptureRegion.parse(value, flag: "--crop")
            case "--display-index":
                guard let parsed = Int(value), parsed >= 0 else {
                    throw CLIError(code: 2, message: "--display-index must be >= 0")
                }
                displayIndex = parsed
            case "--output":
                output = value
            case "--fps":
                guard let parsed = Int(value), parsed > 0 else {
                    throw CLIError(code: 2, message: "--fps must be a positive integer")
                }
                fps = parsed
            case "--cursor":
                guard let parsed = Bool(value) else {
                    throw CLIError(code: 2, message: "--cursor must be true or false")
                }
                cursor = parsed
            case "--file-type":
                guard let parsed = RecordingFileType(rawValue: value) else {
                    throw CLIError(code: 2, message: "--file-type must be mov or mp4")
                }
                fileType = parsed
            case "--discovery-timeout-ms":
                guard let parsed = Int(value), parsed >= 0 else {
                    throw CLIError(code: 2, message: "--discovery-timeout-ms must be >= 0")
                }
                discoveryTimeoutMs = parsed
            default:
                throw CLIError(code: 2, message: "unknown argument: \(key)")
            }
            i += 2
        }

        if mode == .preflight || mode == .request {
            // Dummy output — preflight/request never open the file.
            let dummy = URL(fileURLWithPath: "/dev/null")
            return Arguments(
                mode: mode,
                source: source,
                pid: pid,
                titleContains: titleContains,
                region: region,
                crop: crop,
                displayIndex: displayIndex,
                output: dummy,
                fps: fps,
                captureCursor: cursor,
                fileType: fileType ?? .mp4,
                discoveryTimeoutMs: discoveryTimeoutMs
            )
        }

        switch source {
        case .window:
            guard pid != nil else { throw CLIError(code: 2, message: "--pid is required") }
            guard let titleContains, !titleContains.isEmpty else {
                throw CLIError(code: 2, message: "--title-contains is required")
            }
        case .region:
            guard region != nil else { throw CLIError(code: 2, message: "--region is required") }
            guard mode == .recording else {
                throw CLIError(code: 2, message: "region source only supports --mode recording")
            }
            if crop != nil {
                throw CLIError(
                    code: 2,
                    message: "--crop is only valid with --source window (region uses --region)")
            }
        }
        guard let output, !output.isEmpty else {
            throw CLIError(code: 2, message: "--output is required")
        }
        let outputUrl = URL(fileURLWithPath: output)

        return Arguments(
            mode: mode,
            source: source,
            pid: pid,
            titleContains: titleContains,
            region: region,
            crop: crop,
            displayIndex: displayIndex,
            output: outputUrl,
            fps: fps,
            captureCursor: cursor,
            fileType: fileType ?? RecordingFileType.forOutput(outputUrl),
            discoveryTimeoutMs: discoveryTimeoutMs
        )
    }
}

enum CaptureMode: String {
    case recording
    case screenshot
    case preflight
    case request
}

enum CaptureSource: String {
    case window
    case region
}

enum RecordingFileType: String {
    case mov
    case mp4

    static func forOutput(_ output: URL) -> RecordingFileType {
        if output.pathExtension.lowercased() == "mp4" {
            return .mp4
        }
        return .mov
    }

    var assetWriterFileType: AVFileType {
        switch self {
        case .mov:
            return .mov
        case .mp4:
            return .mp4
        }
    }
}

struct CaptureRegion {
    let x: Int
    let y: Int
    let width: Int
    let height: Int

    static func parse(_ value: String, flag: String = "--region") throws -> CaptureRegion {
        let parts = value.split(separator: ",", omittingEmptySubsequences: false)
        guard parts.count == 4, let x = Int(parts[0]), let y = Int(parts[1]),
            let width = Int(parts[2]), let height = Int(parts[3])
        else {
            throw CLIError(code: 2, message: "\(flag) must be x,y,width,height")
        }
        guard x >= 0, y >= 0, width > 0, height > 0 else {
            throw CLIError(
                code: 2,
                message:
                    "\(flag) x/y must be non-negative and width/height must be positive")
        }
        return CaptureRegion(x: x, y: y, width: width, height: height)
    }

    func sourceRect() -> CGRect {
        CGRect(
            x: CGFloat(x),
            y: CGFloat(y),
            width: CGFloat(width),
            height: CGFloat(height)
        )
    }
}

// Frame handling lives in a final class with a NSLock rather than an actor. Two reasons:
//
// 1. SCStream invokes `stream(_:didOutputSampleBuffer:of:)` synchronously on the dispatch
//    queue we hand it. CMSampleBuffer instances handed in are owned by the SCStream and freed
//    when the callback returns — bouncing them across an actor boundary via `Task { await }`
//    races against that deallocation and produces SIGABRT during AVAssetWriter consumption.
//    Processing on-queue with a lock keeps the buffer alive for the whole append.
//
// 2. AVAssetWriter / AVAssetWriterInput don't tolerate concurrent calls. A serial lock is
//    cheaper than serialising through an actor's executor and avoids the actor-reentrancy
//    foot-gun on `await writer.finishWriting()`.
final class Recorder {
    private let args: Arguments
    private let lock = NSLock()
    private var stream: SCStream?
    private var writer: AVAssetWriter?
    private var videoInput: AVAssetWriterInput?
    private var pixelBufferAdaptor: AVAssetWriterInputPixelBufferAdaptor?
    private var sessionStarted = false
    private var pipelineError: Error?
    private var framesSeen: Int = 0
    private var framesAppended: Int = 0
    private var framesDropped: Int = 0
    private var streamDelegate: StreamLogger?
    private var frameOutput: FrameOutput?
    private let ciContext = CIContext()
    // Retained for the lifetime of `run()` so the SIGTERM dispatch source isn't deallocated
    // when `waitForStop`'s continuation-setup closure returns. Without this, the source goes
    // away — and `signal(SIGTERM, SIG_IGN)` is still in effect, which means SIGTERM gets
    // silently swallowed and the JVM-side `process.destroy()` fallback does nothing useful.
    // (Same FrameOutput-style retain bug pattern, second instance.)
    private var sigtermSource: DispatchSourceSignal?
    // Signalled by either the stdin reader (q on stdin / EOF) or the SIGTERM dispatch source.
    // `waitForStop` blocks on this; whichever signal arrives first wakes the recorder up so
    // `finalize()` can run. Created up front so the SIGTERM handler installed in `run()`
    // before READY is emitted has a valid target — otherwise there'd be a race between READY
    // and the handler being wired up.
    private let stopRequested = DispatchSemaphore(value: 0)

    init(arguments: Arguments) {
        self.args = arguments
    }

    func run() async throws {
        // Install the SIGTERM handler BEFORE startCapture writes the READY marker. The
        // JVM-side recorder treats READY as "you may now call stop() / process.destroy()", so
        // by the time the JVM is allowed to send SIGTERM the dispatch source is guaranteed
        // to be in place. Without this ordering, there'd be a brief window where SIGTERM
        // could arrive after `signal(SIGTERM, SIG_IGN)` was set but before the dispatch
        // source resumed — silently dropping the signal.
        installSigtermHandler()

        // Validate output path before doing any expensive setup. Catches the common
        // typo case (caller passed a directory path) without first paying the SCK init
        // + window discovery cost. Pure precondition check, doesn't depend on a window
        // existing.
        try validateOutputPath(args.output)

        let target = try await discoverTarget()
        try await startCapture(target: target)
        var waitError: Error?
        if args.mode == .recording {
            await waitForStop()
        } else {
            do {
                try await waitForScreenshot()
            } catch {
                waitError = error
            }
        }
        try await finalize()
        // Diagnostic counters always printed to stderr so the JVM-side smoke can see whether
        // the helper actually saw frames or was just sat there idle.
        FileHandle.standardError.write(
            Data(
                "spectre-screencapture: frames seen=\(framesSeen) accepted=\(framesAppended) dropped=\(framesDropped)\n"
                    .utf8)
        )
        if let pipelineError {
            throw CLIError(
                code: 5, message: "capture pipeline error: \(pipelineError.localizedDescription)")
        }
        if let waitError {
            throw waitError
        }
    }

    /// Pick the backing-scale factor of the NSScreen with the largest overlap area with the
    /// target window. For windows that straddle displays in a mixed-DPI setup (Retina laptop +
    /// non-Retina external), `NSScreen.screens` ordering would otherwise let us pick the wrong
    /// scale and produce stretched / cropped output. Falls back to NSScreen.main, then to a
    /// 2.0 default for environments where AppKit isn't initialised yet.
    private func backingScaleFactor(for windowFrame: CGRect) -> CGFloat {
        let dominant = NSScreen.screens
            .map { screen -> (NSScreen, CGFloat) in
                let overlap = windowFrame.intersection(screen.frame)
                let area = overlap.isNull ? 0 : overlap.width * overlap.height
                return (screen, area)
            }
            .filter { $0.1 > 0 }
            .max(by: { $0.1 < $1.1 })?
            .0
        return dominant?.backingScaleFactor ?? NSScreen.main?.backingScaleFactor ?? 2.0
    }

    /// Refuse to overwrite a directory if the caller mistakenly passed one as `--output`.
    /// AVAssetWriter would later fail on this anyway, but `try? FileManager.removeItem` would
    /// silently `rm -rf` the directory first — much worse failure mode for a typo. Run this
    /// up-front in `run()` so we fail fast on a precondition error before paying for SCK init
    /// and window discovery.
    private func validateOutputPath(_ url: URL) throws {
        var isDir: ObjCBool = false
        if FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir), isDir.boolValue {
            throw CLIError(
                code: 2,
                message:
                    "--output points at an existing directory (\(url.path)); refusing to overwrite. Pass a file path."
            )
        }
    }

    private enum CaptureTarget {
        case window(SCWindow)
        case region(display: SCDisplay, region: CaptureRegion)
    }

    private func requirePid() throws -> pid_t {
        guard let pid = args.pid else { throw CLIError(code: 2, message: "--pid is required") }
        return pid
    }

    private func requireTitleContains() throws -> String {
        guard let titleContains = args.titleContains, !titleContains.isEmpty else {
            throw CLIError(code: 2, message: "--title-contains is required")
        }
        return titleContains
    }

    private func requireRegion() throws -> CaptureRegion {
        guard let region = args.region else {
            throw CLIError(code: 2, message: "--region is required")
        }
        return region
    }

    private func discoverTarget() async throws -> CaptureTarget {
        switch args.source {
        case .window:
            return .window(try await discoverTargetWindow())
        case .region:
            let region = try requireRegion()
            return .region(display: try await discoverTargetDisplay(), region: region)
        }
    }

    private func discoverTargetWindow() async throws -> SCWindow {
        let pid = try requirePid()
        let titleContains = try requireTitleContains()
        let deadline = Date().addingTimeInterval(Double(args.discoveryTimeoutMs) / 1000.0)
        repeat {
            let content: SCShareableContent
            do {
                content = try await SCShareableContent.excludingDesktopWindows(
                    false, onScreenWindowsOnly: true)
            } catch {
                throw CLIError(
                    code: 4, message: "screen recording permission denied: \(error)")
            }

            if let match = content.windows.first(where: { window in
                guard window.owningApplication?.processID == pid else { return false }
                guard let title = window.title, !title.isEmpty else { return false }
                return title.contains(titleContains)
            }) {
                return match
            }

            try await Task.sleep(nanoseconds: 50_000_000)  // 50 ms
        } while Date() < deadline

        throw CLIError(
            code: 3,
            message:
                "no window with pid=\(pid) and title containing '\(titleContains)' found within \(args.discoveryTimeoutMs) ms"
        )
    }

    private func discoverTargetDisplay() async throws -> SCDisplay {
        let content: SCShareableContent
        do {
            content = try await SCShareableContent.excludingDesktopWindows(
                false, onScreenWindowsOnly: true)
        } catch {
            throw CLIError(code: 4, message: "screen recording permission denied: \(error)")
        }
        let displays = sortedDisplays(content.displays)
        guard args.displayIndex < displays.count else {
            throw CLIError(
                code: 3,
                message:
                    "display index \(args.displayIndex) is out of range; only \(displays.count) display(s) are shareable"
            )
        }
        return displays[args.displayIndex]
    }

    private func sortedDisplays(_ displays: [SCDisplay]) -> [SCDisplay] {
        let mainDisplayId = CGMainDisplayID()
        return displays.sorted { lhs, rhs in
            if lhs.displayID == mainDisplayId { return true }
            if rhs.displayID == mainDisplayId { return false }
            if lhs.frame.minX != rhs.frame.minX { return lhs.frame.minX < rhs.frame.minX }
            return lhs.frame.minY < rhs.frame.minY
        }
    }

    private func startCapture(target: CaptureTarget) async throws {
        let filter: SCContentFilter
        let sourceRect: CGRect?
        let width: Int
        let height: Int

        switch target {
        case .window(let targetWindow):
            // Use the backing-scale of the screen the target window actually lives on, not
            // `NSScreen.main`'s. Mixed-DPI setups (Retina laptop + non-Retina external) would
            // otherwise pick the wrong scale and produce stretched / cropped output for windows
            // off the main screen. Falls back to NSScreen.main and finally a hardcoded 2.0 if
            // neither lookup succeeds — neither degrades worse than the previous behaviour.
            let scale = backingScaleFactor(for: targetWindow.frame)

            // Window-attached filter — output is the target window's pixels (optionally cropped
            // via sourceRect for embedded Compose surfaces / handle-less panels, issue #186).
            // Display-mode filters leave large black areas outside the window in the buffer.
            filter = SCContentFilter(desktopIndependentWindow: targetWindow)
            if let crop = args.crop {
                // Crop is window-relative points (same space as SCWindow.frame / AWT user space).
                // Fixed at start for v1 — mid-recording resize/move of the surface is not followed.
                sourceRect = crop.sourceRect()
                width = max(2, Int((CGFloat(crop.width) * scale).rounded()))
                height = max(2, Int((CGFloat(crop.height) * scale).rounded()))
            } else {
                sourceRect = nil
                width = max(2, Int((targetWindow.frame.width * scale).rounded()))
                height = max(2, Int((targetWindow.frame.height * scale).rounded()))
            }
        case .region(let display, let region):
            width = max(2, region.width)
            height = max(2, region.height)
            filter = SCContentFilter(display: display, excludingWindows: [])
            sourceRect = region.sourceRect()
        }
        let config = SCStreamConfiguration()
        config.width = width
        config.height = height
        if let sourceRect {
            config.sourceRect = sourceRect
        }
        config.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(args.fps))
        config.showsCursor = args.captureCursor
        config.queueDepth = 8
        config.pixelFormat = kCVPixelFormatType_32BGRA

        // Re-validate the output path immediately before removing whatever's there. The
        // up-front `validateOutputPath` call in `run()` happens before window discovery, so
        // a directory could theoretically appear at the path during the discovery window.
        // Without this re-check, the unconditional `removeItem` would silently `rm -rf`
        // that directory tree — the destructive failure mode the guard is supposed to
        // prevent. Cheap stat call; runs once per recording start.
        try validateOutputPath(args.output)
        try? FileManager.default.removeItem(at: args.output)

        if args.mode == .recording {
            let writer = try AVAssetWriter(
                outputURL: args.output, fileType: args.fileType.assetWriterFileType)
            let videoSettings: [String: Any] = [
                AVVideoCodecKey: AVVideoCodecType.h264,
                AVVideoWidthKey: width,
                AVVideoHeightKey: height,
            ]
            let videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
            videoInput.expectsMediaDataInRealTime = true

            let adaptor = AVAssetWriterInputPixelBufferAdaptor(
                assetWriterInput: videoInput,
                sourcePixelBufferAttributes: [
                    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                    kCVPixelBufferWidthKey as String: width,
                    kCVPixelBufferHeightKey as String: height,
                ]
            )

            guard writer.canAdd(videoInput) else {
                throw CLIError(
                    code: 5, message: "AVAssetWriter cannot accept the configured video input")
            }
            writer.add(videoInput)

            // Start writing BEFORE the stream so we never see a frame before the writer is ready.
            guard writer.startWriting() else {
                throw CLIError(
                    code: 5,
                    message:
                        "AVAssetWriter.startWriting returned false: \(writer.error?.localizedDescription ?? "unknown")"
                )
            }

            self.writer = writer
            self.videoInput = videoInput
            self.pixelBufferAdaptor = adaptor
        }

        let delegate = StreamLogger()
        delegate.recorder = self
        let stream = SCStream(filter: filter, configuration: config, delegate: delegate)
        // Keep the delegate alive for the stream's lifetime — SCStream stores it weakly.
        self.streamDelegate = delegate
        // Retain the frame output as a member — although `addStreamOutput` documents holding a
        // strong reference, in practice the only delivered frame in repeated runs was the very
        // first sample buffer, suggesting the output was being released right after start.
        // Holding a strong reference here removes the ambiguity.
        let output = FrameOutput { [weak self] sampleBuffer in
            self?.handleFrame(sampleBuffer)
        }
        self.frameOutput = output
        try stream.addStreamOutput(
            output, type: .screen,
            sampleHandlerQueue: DispatchQueue(label: "dev.sebastiano.spectre.frames"))

        try await stream.startCapture()
        self.stream = stream

        // Signal the JVM-side recorder that capture is fully running. The JVM blocks on
        // reading this line from stdout in `start()` so it can return a recording handle
        // synchronously rather than racing against the helper's window-discovery + SCK init.
        // Keep this line single-token + newline-terminated; the JVM matches via `trim() ==
        // "READY"`.
        FileHandle.standardOutput.write(Data("READY\n".utf8))
    }

    // Called on the SCStream's frame queue. Holds a lock for the whole pixel append so writer
    // mutations stay serial. Sample buffer is consumed synchronously, so we don't need to
    // CFRetain it across an async boundary.
    private func handleFrame(_ sampleBuffer: CMSampleBuffer) {
        lock.lock()
        defer { lock.unlock() }

        framesSeen += 1
        guard sampleBuffer.isValid else {
            framesDropped += 1
            return
        }

        if args.mode == .screenshot {
            guard framesAppended == 0 else { return }
            guard isAcceptableFrameStatus(sampleBuffer) else {
                framesDropped += 1
                return
            }
            guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
                framesDropped += 1
                return
            }
            do {
                try writePng(imageBuffer)
                framesAppended += 1
                pipelineError = nil
                stopRequested.signal()
            } catch {
                framesDropped += 1
                if pipelineError == nil { pipelineError = error }
            }
            return
        }

        guard
            let writer = self.writer,
            let videoInput = self.videoInput,
            let adaptor = self.pixelBufferAdaptor
        else {
            framesDropped += 1
            return
        }

        guard isAcceptableFrameStatus(sampleBuffer) else {
            framesDropped += 1
            return
        }

        guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            framesDropped += 1
            return
        }

        let presentationTime = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        if !sessionStarted {
            writer.startSession(atSourceTime: presentationTime)
            sessionStarted = true
        }
        guard videoInput.isReadyForMoreMediaData else {
            framesDropped += 1
            return
        }
        if adaptor.append(imageBuffer, withPresentationTime: presentationTime) {
            framesAppended += 1
        } else {
            framesDropped += 1
            if pipelineError == nil { pipelineError = writer.error }
        }
    }

    // SCK tags each frame with an SCFrameStatus. We accept `complete` (content changed) and
    // `idle` (same pixels as last frame but a fresh timestamp at the configured cadence).
    // Filtering out `idle` produces a video that only contains transition frames — fine
    // for change-detection, useless for recording. The remaining states (`blank`,
    // `suspended`, `started`, `stopped`) carry no pixels worth writing.
    private func isAcceptableFrameStatus(_ sampleBuffer: CMSampleBuffer) -> Bool {
        guard
            let attachments =
                CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false)
                as? [[SCStreamFrameInfo: Any]],
            let status = attachments.first?[.status] as? Int
        else {
            return true
        }
        let acceptable: Set<Int> = [
            SCFrameStatus.complete.rawValue,
            SCFrameStatus.idle.rawValue,
        ]
        return acceptable.contains(status)
    }

    private func writePng(_ imageBuffer: CVImageBuffer) throws {
        let ciImage = CIImage(cvImageBuffer: imageBuffer)
        guard let cgImage = ciContext.createCGImage(ciImage, from: ciImage.extent) else {
            throw CLIError(code: 5, message: "could not create CGImage from ScreenCaptureKit frame")
        }
        guard
            let destination = CGImageDestinationCreateWithURL(
                args.output as CFURL, UTType.png.identifier as CFString, 1, nil)
        else {
            throw CLIError(code: 5, message: "could not create PNG destination at \(args.output.path)")
        }
        CGImageDestinationAddImage(destination, cgImage, nil)
        guard CGImageDestinationFinalize(destination) else {
            throw CLIError(code: 5, message: "could not write PNG screenshot to \(args.output.path)")
        }
    }

    /// Called by [StreamLogger.stream(_:didStopWithError:)] when SCStream stops unexpectedly
    /// (target window closed, display reconfigured, source invalidated, etc.). Stashes the
    /// error so [finalize] surfaces it as exit 5 AND signals [stopRequested] so the helper
    /// exits promptly rather than waiting for the JVM-side stop. Without the stop signal a
    /// dead-stream helper would hang on stdin until the JVM-side `RecordingHandle.stop()` was
    /// called (which might be much later, or never, depending on caller behaviour).
    func recordPipelineError(_ error: Error) {
        lock.lock()
        if pipelineError == nil { pipelineError = error }
        lock.unlock()
        stopRequested.signal()
    }

    /// Installs a SIGTERM handler that wakes [waitForStop] via [stopRequested]. Called from
    /// [run] before [startCapture] writes the READY marker, so by the time the JVM is allowed
    /// to send SIGTERM the dispatch source is guaranteed to be in place.
    ///
    /// `signal(SIGTERM, SIG_IGN)` prevents the default action from firing; the dispatch source
    /// then picks up the signal instead. The source is retained on the recorder so the
    /// closure-scope lifetime issue that bit the FrameOutput / earlier sigtermSource retain
    /// bugs doesn't repeat — see `frameOutput` / earlier `sigtermSource` doc above.
    private func installSigtermHandler() {
        signal(SIGTERM, SIG_IGN)
        let source = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .global())
        source.setEventHandler { [weak self] in
            self?.stopRequested.signal()
        }
        source.resume()
        self.sigtermSource = source
    }

    private func waitForStop() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let resumed = AtomicFlag()

            // Stdin reader — primary clean shutdown path. JVM-side `RecordingHandle.stop()`
            // writes `q` to stdin (mirrors `FfmpegRecorder`).
            DispatchQueue.global(qos: .utility).async {
                let handle = FileHandle.standardInput
                while true {
                    let chunk = try? handle.read(upToCount: 1)
                    guard let chunk, !chunk.isEmpty else {
                        if resumed.set() { continuation.resume() }
                        return
                    }
                    if chunk == Data([0x71]) {  // 'q'
                        if resumed.set() { continuation.resume() }
                        return
                    }
                }
            }

            // SIGTERM listener — wakes when the dispatch source installed in
            // `installSigtermHandler()` (called before READY) signals the semaphore. By
            // putting the wait on its own dispatch queue we don't block the continuation
            // setup closure.
            DispatchQueue.global(qos: .utility).async { [stopRequested = self.stopRequested] in
                stopRequested.wait()
                if resumed.set() { continuation.resume() }
            }
        }
    }

    private func waitForScreenshot() async throws {
        let completed = await withCheckedContinuation {
            (continuation: CheckedContinuation<Bool, Never>) in
            DispatchQueue.global(qos: .utility).async { [stopRequested = self.stopRequested] in
                let result = stopRequested.wait(timeout: .now() + 10)
                continuation.resume(returning: result == .success)
            }
        }
        if !completed {
            throw CLIError(code: 5, message: "timed out waiting for first screenshot frame")
        }
        if !didAppendFrame() {
            throw CLIError(code: 5, message: "screenshot capture stopped before writing a PNG")
        }
    }

    private func didAppendFrame() -> Bool {
        lock.lock()
        let didAppendFrame = framesAppended > 0
        lock.unlock()
        return didAppendFrame
    }

    private func finalize() async throws {
        if let stream {
            try? await stream.stopCapture()
        }
        // After stopCapture, drain any frame callbacks already in flight by acquiring + releasing
        // the lock once. The frame queue is serial so by the time we hold the lock there's no
        // append pending behind us.
        lock.lock()
        let writer = self.writer
        let videoInput = self.videoInput
        // Null out so any late frame callback after we release the lock is a no-op.
        self.writer = nil
        self.videoInput = nil
        self.pixelBufferAdaptor = nil
        lock.unlock()

        guard let writer, let videoInput else { return }
        videoInput.markAsFinished()
        await writer.finishWriting()
        if writer.status == .failed {
            throw CLIError(
                code: 5,
                message:
                    "AVAssetWriter finalize failed: \(writer.error?.localizedDescription ?? "unknown")"
            )
        }
    }
}

// SCStreamOutput needs an NSObject conforming class. Hand the buffer off synchronously so the
// frame queue manages CMSampleBuffer lifetime correctly.
private final class FrameOutput: NSObject, SCStreamOutput {
    private let handler: (CMSampleBuffer) -> Void

    init(handler: @escaping (CMSampleBuffer) -> Void) {
        self.handler = handler
    }

    func stream(
        _ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer,
        of type: SCStreamOutputType
    ) {
        guard type == .screen else { return }
        handler(sampleBuffer)
    }
}

// Bridges SCStream-level events back to the recorder. SCStream.delegate is a weak reference,
// so the recorder retains an instance for the stream's lifetime via `streamDelegate`.
//
// `didStopWithError` fires when ScreenCaptureKit stops unexpectedly — e.g. the target window
// closes, display configuration changes, source becomes invalid. Without propagating this back
// to the recorder, `run()` would complete with exit code 0 after the SIGTERM/stdin shutdown
// path, and the JVM side would treat a mid-recording failure as a successful capture. We
// stash the error on the recorder (so `finalize()` surfaces exit 5) AND trigger graceful
// shutdown so the helper exits promptly rather than waiting for the JVM-side stop.
final class StreamLogger: NSObject, SCStreamDelegate {
    weak var recorder: Recorder?

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        FileHandle.standardError.write(
            Data("spectre-screencapture: SCStream stopped with error: \(error)\n".utf8))
        recorder?.recordPipelineError(error)
    }

    @available(macOS 14.0, *)
    func outputVideoEffectDidStart(for stream: SCStream) {
        FileHandle.standardError.write(
            Data("spectre-screencapture: outputVideoEffectDidStart\n".utf8))
    }
}

// Single-shot atomic flag so a CheckedContinuation can be resumed by exactly one of
// (stdin closes, stdin sees `q`).
final class AtomicFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false
    func set() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if value { return false }
        value = true
        return true
    }
}
