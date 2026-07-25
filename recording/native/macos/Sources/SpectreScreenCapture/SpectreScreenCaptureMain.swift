import Dispatch
import Foundation
import SpectreScreenCaptureCore

@main
enum SpectreScreenCapture {
    /// Sync entry so guide mode runs on the real AppKit main thread.
    /// Capture/recording still use the async CLI path via a Task.
    static func main() {
        let argv = CommandLine.arguments
        if isGuidePermissionsInvocation(argv) {
            // @main without `async` is already on the main thread — safe for NSApplication.
            MainActor.assumeIsolated {
                PermissionGuideApp.run(
                    binaryPath: argv.first ?? "spectre-screencapture",
                    reapproval: argv.contains("--reapproval")
                )
            }
            return
        }
        let group = DispatchGroup()
        group.enter()
        Task {
            defer { group.leave() }
            await SpectreScreenCaptureCommand.main(argv)
        }
        group.wait()
    }

    private static func isGuidePermissionsInvocation(_ argv: [String]) -> Bool {
        if argv.contains("--guide-permissions") {
            return true
        }
        if let modeIndex = argv.firstIndex(of: "--mode"), modeIndex + 1 < argv.count {
            return argv[modeIndex + 1] == "guide-permissions"
        }
        return false
    }
}
