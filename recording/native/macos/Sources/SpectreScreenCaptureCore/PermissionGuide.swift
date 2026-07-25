import AppKit
import CoreGraphics
import Foundation
import SwiftUI

// MARK: - Guided Screen Recording permission UI (#192)
//
// Human-only path: `spectre permissions request` launches
// `spectre-screencapture --mode guide-permissions`. Capture/record paths never
// enter this mode (they stay fail-fast preflight from #187).

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

enum PermissionGuideApp {
    /// Runs the guide UI on the main thread until the user finishes, then exits the process.
    /// Caller must be on the AppKit main thread (`@main` without async).
    @MainActor
    static func run(binaryPath: String, reapproval: Bool) {
        let app = NSApplication.shared
        // LSUIElement is set in Info.plist; accessory keeps us out of the Dock.
        app.setActivationPolicy(.accessory)

        let model = PermissionGuideModel(
            binaryPath: binaryPath,
            reapproval: reapproval,
            preflight: { CGPreflightScreenCaptureAccess() },
            openSettings: {
                if let url = URL(string: ScreenCaptureAccess.deepLink) {
                    NSWorkspace.shared.open(url)
                }
            },
            appURL: Bundle.main.bundleURL
        )
        model.startPolling()

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
        window.makeKeyAndOrderFront(nil)
        app.activate(ignoringOtherApps: true)

        model.onFinished = { granted in
            let result = ScreenCaptureAccess.result(granted: granted, binaryPath: binaryPath)
            FileHandle.standardOutput.write(Data(result.jsonLine.utf8))
            // Exit synchronously on the AppKit run-loop thread (no nested main.async).
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
        // Fallback if the run loop ends without an explicit exit.
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
        // Immediate check, then every 500ms until granted.
        tick()
        // Timer fires on the main run loop; hop explicitly for MainActor isolation.
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

/// Draggable app-icon affordance for Settings' add-to-list gesture.
struct AppIconDragView: View {
    let appURL: URL

    var body: some View {
        Image(nsImage: NSWorkspace.shared.icon(forFile: appURL.path))
            .resizable()
            .aspectRatio(contentMode: .fit)
            .onDrag {
                NSItemProvider(contentsOf: appURL) ?? NSItemProvider()
            }
    }
}

