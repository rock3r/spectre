import AppKit
import CoreGraphics
import Foundation
import SwiftUI
import UniformTypeIdentifiers

// MARK: - Guided Screen Recording permission UI (#192)
//
// Human-only path: `spectre permissions request` launches
// `spectre-screencapture --mode guide-permissions`. Capture/record paths never
// enter this mode (they stay fail-fast preflight from #187).
//
// Approved UX (docs + #192): explain → Open Settings → drag icon into the list
// if missing → live re-check until Done. Do not pivot primary guidance to the
// Settings + button without an explicit product decision.

/// Pure copy selection so unit tests do not need a display.
public enum PermissionGuideCopy {
    public static let windowTitle = "Spectre Capture Helper"

    public static let firstRunHeadline = "Allow Screen Recording"
    public static let reapprovalHeadline = "Re-enable Screen Recording"

    public static let firstRunBody =
        "Spectre uses this helper only when you or an agent asks to capture a window or screen. "
        + "Grant Screen & System Audio Recording to Spectre Capture Helper in System Settings."

    public static let reapprovalBody =
        "macOS sometimes asks again after updates or when a previous grant lapses. "
        + "Turn Spectre Capture Helper back on in System Settings (same list as first-time setup)."

    public static let openSettingsLabel = "Open Settings"
    public static let dragHint = "Drag this icon into the Screen Recording list if it is missing."
    public static let waitingLabel = "Waiting for permission…"
    public static let grantedLabel = "Done ✓ Screen Recording granted"
    public static let closeLabel = "Close"
    public static let doneLabel = "Done"

    public static func headline(reapproval: Bool) -> String {
        reapproval ? reapprovalHeadline : firstRunHeadline
    }

    public static func body(reapproval: Bool) -> String {
        reapproval ? reapprovalBody : firstRunBody
    }
}

/// Builds pasteboard / item-provider payloads for dragging the helper `.app`.
public enum PermissionGuideDragPayload {
    /// SwiftUI / NSItemProvider path (type registration for tests and fallbacks).
    public static func itemProvider(for appURL: URL) -> NSItemProvider {
        let provider = NSItemProvider()
        provider.suggestedName = appURL.deletingPathExtension().lastPathComponent

        // coordinatedRead: true = in-place URL (must match .openInPlace). Passing false would
        // tell the system the file is a disposable copy and can delete Bundle.main.bundleURL.
        provider.registerFileRepresentation(
            forTypeIdentifier: UTType.applicationBundle.identifier,
            fileOptions: [.openInPlace],
            visibility: .all
        ) { completion in
            completion(appURL, true, nil)
            return nil
        }

        provider.registerDataRepresentation(
            forTypeIdentifier: UTType.fileURL.identifier,
            visibility: .all
        ) { completion in
            completion(appURL.dataRepresentation, nil)
            return nil
        }

        return provider
    }

    /// AppKit pasteboard writers that Privacy Settings historically accept for app drops.
    public static func pasteboardWriters(for appURL: URL) -> [any NSPasteboardWriting] {
        // NSURL as file URL is the primary writer (public.file-url + file promise semantics).
        [appURL as NSURL]
    }
}

/// Polling state machine for live preflight checks (testable without AppKit run loop).
public struct PermissionGuidePollState: Equatable {
    public var granted: Bool
    public var pollCount: Int

    public init(granted: Bool = false, pollCount: Int = 0) {
        self.granted = granted
        self.pollCount = pollCount
    }

    public mutating func applyPreflight(_ isGranted: Bool) {
        pollCount += 1
        granted = isGranted
    }
}

public enum PermissionGuideApp {
    /// Runs the guide UI on the main thread until the user finishes, then exits the process.
    /// Caller must be on the AppKit main thread (`@main` without async).
    @MainActor
    public static func run(binaryPath: String, reapproval: Bool) {
        let app = NSApplication.shared
        // LSUIElement is set in Info.plist; accessory keeps us out of the Dock.
        app.setActivationPolicy(.accessory)

        let appURL = Bundle.main.bundleURL
        let model = PermissionGuideModel(
            binaryPath: binaryPath,
            reapproval: reapproval,
            preflight: { CGPreflightScreenCaptureAccess() },
            openSettings: {
                if let url = URL(string: ScreenCaptureAccess.deepLink) {
                    NSWorkspace.shared.open(url)
                }
            },
            appURL: appURL
        )

        let rootView = PermissionGuideView(model: model)
        let hosting = NSHostingController(rootView: rootView)
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 440, height: 360),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.title = PermissionGuideCopy.windowTitle
        window.contentViewController = hosting
        window.isReleasedWhenClosed = false
        window.center()
        // Show + activate the guide *before* CGRequest. That call may block on the system
        // consent sheet; if the user opens System Settings from it, we must not activate
        // over Settings afterward.
        window.makeKeyAndOrderFront(nil)
        app.activate(ignoringOtherApps: true)

        // Register a TCC / Settings row for this helper identity. Preflight alone does not
        // create a list entry; without a request the drag target has nothing to attach to.
        _ = CGRequestScreenCaptureAccess()
        model.startPolling()

        model.onFinished = { granted in
            let result = ScreenCaptureAccess.result(granted: granted, binaryPath: binaryPath)
            FileHandle.standardOutput.write(Data(result.jsonLine.utf8))
            exit(granted ? 0 : 6)
        }

        NotificationCenter.default.addObserver(
            forName: NSWindow.willCloseNotification,
            object: window,
            queue: .main
        ) { _ in
            let granted = CGPreflightScreenCaptureAccess()
            let result = ScreenCaptureAccess.result(granted: granted, binaryPath: binaryPath)
            FileHandle.standardOutput.write(Data(result.jsonLine.utf8))
            exit(granted ? 0 : 6)
        }

        app.run()
        exit(6)
    }

}

@MainActor
final class PermissionGuideModel: ObservableObject {
    @Published var poll = PermissionGuidePollState()
    let reapproval: Bool
    let binaryPath: String
    let appURL: URL
    var onFinished: ((Bool) -> Void)?

    private let preflight: () -> Bool
    private let openSettings: () -> Void
    private var timer: Timer?

    init(
        binaryPath: String,
        reapproval: Bool,
        preflight: @escaping () -> Bool,
        openSettings: @escaping () -> Void,
        appURL: URL
    ) {
        self.binaryPath = binaryPath
        self.reapproval = reapproval
        self.preflight = preflight
        self.openSettings = openSettings
        self.appURL = appURL
    }

    func startPolling() {
        timer?.invalidate()
        tick()
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self else { return }
            MainActor.assumeIsolated { self.tick() }
        }
    }

    func tick() {
        let granted = preflight()
        poll.applyPreflight(granted)
        if granted {
            timer?.invalidate()
            timer = nil
        }
    }

    func openSystemSettings() {
        openSettings()
    }

    func finish() {
        timer?.invalidate()
        timer = nil
        onFinished?(poll.granted)
    }
}

struct PermissionGuideView: View {
    @ObservedObject var model: PermissionGuideModel

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(PermissionGuideCopy.headline(reapproval: model.reapproval))
                .font(.title2.weight(.semibold))
                .accessibilityAddTraits(.isHeader)

            Text(PermissionGuideCopy.body(reapproval: model.reapproval))
                .font(.body)
                .fixedSize(horizontal: false, vertical: true)
                .foregroundStyle(.primary)

            Button(PermissionGuideCopy.openSettingsLabel) {
                model.openSystemSettings()
            }
            .keyboardShortcut(.defaultAction)
            .accessibilityLabel(PermissionGuideCopy.openSettingsLabel)
            .accessibilityHint("Opens System Settings to Screen Recording privacy")

            HStack(spacing: 12) {
                AppIconDragView(appURL: model.appURL)
                    .frame(width: 64, height: 64)
                    .accessibilityLabel("Spectre Capture Helper icon")
                    .accessibilityHint(PermissionGuideCopy.dragHint)
                Text(PermissionGuideCopy.dragHint)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.vertical, 4)

            HStack {
                if model.poll.granted {
                    Text(PermissionGuideCopy.grantedLabel)
                        .font(.headline)
                        .foregroundStyle(.green)
                        .accessibilityLabel(PermissionGuideCopy.grantedLabel)
                } else {
                    ProgressView()
                        .controlSize(.small)
                    Text(PermissionGuideCopy.waitingLabel)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }

            HStack {
                Spacer()
                Button(model.poll.granted ? PermissionGuideCopy.doneLabel : PermissionGuideCopy.closeLabel) {
                    model.finish()
                }
                .keyboardShortcut(.cancelAction)
            }
        }
        .padding(24)
        .frame(minWidth: 400, minHeight: 320)
    }
}

/// AppKit-backed draggable icon so the pasteboard carries a real file URL (Settings drop target).
/// SwiftUI `.onDrag` alone often fails to populate types Privacy & Security accepts.
struct AppIconDragView: NSViewRepresentable {
    let appURL: URL

    func makeNSView(context: Context) -> DraggableAppIconNSView {
        let view = DraggableAppIconNSView()
        view.configure(appURL: appURL)
        return view
    }

    func updateNSView(_ nsView: DraggableAppIconNSView, context: Context) {
        nsView.configure(appURL: appURL)
    }
}

final class DraggableAppIconNSView: NSImageView {
    private(set) var appURL: URL?

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        commonInit()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        commonInit()
    }

    private func commonInit() {
        imageScaling = .scaleProportionallyUpOrDown
        isEditable = false
        // Let mouseDown start a drag instead of moving the window.
        // (NSImageView is not a dragging source by default.)
    }

    func configure(appURL: URL) {
        self.appURL = appURL
        image = NSWorkspace.shared.icon(forFile: appURL.path)
        toolTip = PermissionGuideCopy.dragHint
    }

    override var mouseDownCanMoveWindow: Bool { false }

    override func mouseDown(with event: NSEvent) {
        guard let appURL, let image else { return }

        let writers = PermissionGuideDragPayload.pasteboardWriters(for: appURL)
        let item = NSDraggingItem(pasteboardWriter: writers[0])
        let size = bounds.size
        let origin = convert(event.locationInWindow, from: nil)
        let frame = NSRect(
            x: origin.x - size.width / 2,
            y: origin.y - size.height / 2,
            width: size.width,
            height: size.height
        )
        item.setDraggingFrame(frame, contents: image)
        beginDraggingSession(with: [item], event: event, source: self)
    }
}

extension DraggableAppIconNSView: NSDraggingSource {
    func draggingSession(
        _ session: NSDraggingSession,
        sourceOperationMaskFor context: NSDraggingContext
    ) -> NSDragOperation {
        .copy
    }
}
