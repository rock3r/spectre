import CoreGraphics

/// The line protocol of `--mode stills` (#605).
///
/// The helper keeps one ScreenCaptureKit stream on the target window. It writes `READY` once the
/// first frame has arrived. Each `c` line on stdin asks for a still. The helper answers with a
/// `FRAME <width> <height>` line followed by `width * height * 4` bytes of BGRA pixels, or with a
/// `STALE <reason>` line when the stream no longer shows the window as it was when the session
/// started. `q` or the end of stdin stops the helper.
///
/// The JVM side is `HelperStillSessions` in the recording module. The Windows helper speaks the
/// same protocol.
enum StillsProtocol {
    static let ready = "READY"

    enum Command: Equatable {
        case capture
        case quit
        case unknown
    }

    static func parseCommand(_ line: String?) -> Command {
        guard let line else { return .quit }
        switch line.trimmingCharacters(in: .whitespacesAndNewlines) {
        case "q": return .quit
        case "c": return .capture
        default: return .unknown
        }
    }

    static func frameHeader(width: Int, height: Int) -> String { "FRAME \(width) \(height)" }

    static func staleHeader(_ reason: String) -> String {
        "STALE "
            + reason
            .replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
    }
}

/// Where the window's pixels sit in a stream frame, from the frame's `SCStreamFrameInfo`.
///
/// The stream output keeps the size the session started with. When the window is resized,
/// ScreenCaptureKit scales its content into that output, so the content rect or scale changes. A
/// still from that frame would not be the window at its real size, so the session is stale.
struct StillGeometry: Equatable {
    let contentRect: CGRect
    let contentScale: CGFloat
    let scaleFactor: CGFloat

    /// Same geometry within half a point / a hundredth of a scale step: the window server
    /// reports these as floating-point values.
    func matches(_ other: StillGeometry) -> Bool {
        abs(contentRect.minX - other.contentRect.minX) <= 0.5
            && abs(contentRect.minY - other.contentRect.minY) <= 0.5
            && abs(contentRect.width - other.contentRect.width) <= 0.5
            && abs(contentRect.height - other.contentRect.height) <= 0.5
            && abs(contentScale - other.contentScale) <= 0.01
            && abs(scaleFactor - other.scaleFactor) <= 0.01
    }
}
