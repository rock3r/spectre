import AVFoundation
import AppKit
import CoreGraphics
import CoreMedia
import Foundation
import ScreenCaptureKit

// MARK: - CLI contract
//
// spectre-screencapture
//   --pid <jvm pid>            Required. Filters CGWindowList by owner PID so we never
//                              capture another process's window matching the discriminator.
//   --title-contains <suffix>  Required. Substring match on the target window's kCGWindowName.
//                              Spectre stamps a `Spectre/<uuid>` suffix on the ComposeWindow's
//                              title for the recording's lifetime so this is unique.
//   --output <path>            Required. Destination .mov file. Overwritten if it exists.
//   --fps <int>                Optional. Default 30.
//   --cursor <true|false>      Optional. Default true.
//   --discovery-timeout-ms <int>  Optional. Default 2000.
//
// Lifecycle:
//   - On start, scans CGWindowListCopyWindowInfo until a window matching (pid, title-contains)
//     appears or the discovery timeout elapses.
//   - Streams frames via SCStream into AVAssetWriter (H.264) until either:
//       * stdin reads `q\n` (graceful, mirrors FfmpegRecorder's shutdown contract)
//       * stdin closes
//       * SIGTERM
//   - Always finalises the writer before exit so the .mov is playable.
//
// Exit codes:
//   0  clean shutdown, file finalised
//   2  bad arguments
//   3  window not found within the discovery timeout
//   4  permission denied (Screen Recording TCC)
//   5  capture pipeline error after start

@main
struct SpectreScreenCapture {
    static func main() async {
        do {
            let args = try Arguments.parse(CommandLine.arguments)
            let recorder = Recorder(arguments: args)
            try await recorder.run()
            exit(0)
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

struct CLIError: Error {
    let code: Int32
    let message: String
}

struct Arguments {
    let pid: pid_t
    let titleContains: String
    let output: URL
    let fps: Int
    let captureCursor: Bool
    let discoveryTimeoutMs: Int

    static func parse(_ argv: [String]) throws -> Arguments {
        var pid: pid_t?
        var titleContains: String?
        var output: String?
        var fps = 30
        var cursor = true
        var discoveryTimeoutMs = 2000

        var i = 1
        while i < argv.count {
            let key = argv[i]
            guard i + 1 < argv.count else {
                throw CLIError(code: 2, message: "missing value for \(key)")
            }
            let value = argv[i + 1]
            switch key {
            case "--pid":
                guard let parsed = pid_t(value) else {
                    throw CLIError(code: 2, message: "--pid must be an integer")
                }
                pid = parsed
            case "--title-contains":
                titleContains = value
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

        guard let pid else { throw CLIError(code: 2, message: "--pid is required") }
        guard let titleContains, !titleContains.isEmpty else {
            throw CLIError(code: 2, message: "--title-contains is required")
        }
        guard let output, !output.isEmpty else {
            throw CLIError(code: 2, message: "--output is required")
        }

        return Arguments(
            pid: pid,
            titleContains: titleContains,
            output: URL(fileURLWithPath: output),
            fps: fps,
            captureCursor: cursor,
            discoveryTimeoutMs: discoveryTimeoutMs
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

        let target = try await discoverTargetWindow()
        try await startCapture(targetWindow: target)
        await waitForStop()
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

    private func discoverTargetWindow() async throws -> SCWindow {
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
                guard window.owningApplication?.processID == args.pid else { return false }
                guard let title = window.title, !title.isEmpty else { return false }
                return title.contains(args.titleContains)
            }) {
                return match
            }

            try await Task.sleep(nanoseconds: 50_000_000)  // 50 ms
        } while Date() < deadline

        throw CLIError(
            code: 3,
            message:
                "no window with pid=\(args.pid) and title containing '\(args.titleContains)' found within \(args.discoveryTimeoutMs) ms"
        )
    }

    private func startCapture(targetWindow: SCWindow) async throws {
        // Use the backing-scale of the screen the target window actually lives on, not
        // `NSScreen.main`'s. Mixed-DPI setups (Retina laptop + non-Retina external) would
        // otherwise pick the wrong scale and produce stretched / cropped output for windows
        // off the main screen. Falls back to NSScreen.main and finally a hardcoded 2.0 if
        // neither lookup succeeds — neither degrades worse than the previous behaviour.
        let scale = backingScaleFactor(for: targetWindow.frame)
        let width = max(2, Int((targetWindow.frame.width * scale).rounded()))
        let height = max(2, Int((targetWindow.frame.height * scale).rounded()))

        // Window-attached filter — output is exactly the target window's pixels, sized to
        // `config.width` × `config.height`, with no display-coordinate masking. Display-mode
        // filters (`SCContentFilter(display:including:)`) deliver frames just as well but
        // place the window inside the display's coordinate space, leaving large black areas
        // outside the window in the output buffer.
        //
        // Earlier diagnostic runs suggested this filter dropped frames; that was actually the
        // FrameOutput being released right after `startCapture` returned (we hadn't retained
        // it as a member). With the strong reference in place SCStream delivers frames at the
        // configured rate even for window-attached filters.
        let filter = SCContentFilter(desktopIndependentWindow: targetWindow)
        let config = SCStreamConfiguration()
        config.width = width
        config.height = height
        config.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(args.fps))
        config.showsCursor = args.captureCursor
        config.queueDepth = 8
        config.pixelFormat = kCVPixelFormatType_32BGRA

        // Refuse to delete a directory the caller mistakenly handed us as `--output`. AVAssetWriter
        // would also fail in that case, but blowing away an entire directory tree first because of
        // a typo is a much worse failure mode. Only remove a regular file (or a symlink, which is
        // probably fine).
        var isDir: ObjCBool = false
        if FileManager.default.fileExists(atPath: args.output.path, isDirectory: &isDir) {
            if isDir.boolValue {
                throw CLIError(
                    code: 2,
                    message:
                        "--output points at an existing directory (\(args.output.path)); refusing to overwrite. Pass a file path."
                )
            }
            try? FileManager.default.removeItem(at: args.output)
        }

        let writer = try AVAssetWriter(outputURL: args.output, fileType: .mov)
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

        let delegate = StreamLogger()
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
        guard sampleBuffer.isValid,
            let writer = self.writer,
            let videoInput = self.videoInput,
            let adaptor = self.pixelBufferAdaptor
        else {
            framesDropped += 1
            return
        }

        // SCK tags each frame with an SCFrameStatus. We accept `complete` (content changed) and
        // `idle` (same pixels as last frame but a fresh timestamp at the configured cadence).
        // Filtering out `idle` produces a video that only contains transition frames — fine
        // for change-detection, useless for recording. The remaining states (`blank`,
        // `suspended`, `started`, `stopped`) carry no pixels worth writing.
        if let attachments =
            CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false)
            as? [[SCStreamFrameInfo: Any]],
            let status = attachments.first?[.status] as? Int
        {
            let acceptable: Set<Int> = [
                SCFrameStatus.complete.rawValue,
                SCFrameStatus.idle.rawValue,
            ]
            guard acceptable.contains(status) else {
                framesDropped += 1
                return
            }
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

// Logs SCStream-level errors to stderr. SCStream.delegate is a weak reference, so the recorder
// retains an instance for the stream's lifetime via `streamDelegate`.
final class StreamLogger: NSObject, SCStreamDelegate {
    func stream(_ stream: SCStream, didStopWithError error: Error) {
        FileHandle.standardError.write(
            Data("spectre-screencapture: SCStream stopped with error: \(error)\n".utf8))
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
