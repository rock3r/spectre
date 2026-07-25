import Foundation
import SpectreScreenCaptureCore

@main
enum SpectreScreenCapture {
    /// Async entry for capture modes. Guide mode transfers permanently to the AppKit main
    /// run loop without parking `DispatchGroup.wait()` on the main actor (Codex P1).
    static func main() async {
        let argv = CommandLine.arguments
        if isGuidePermissionsInvocation(argv) {
            let binary = argv.first ?? "spectre-screencapture"
            let reapproval = argv.contains("--reapproval")
            if Thread.isMainThread {
                MainActor.assumeIsolated {
                    PermissionGuideApp.run(binaryPath: binary, reapproval: reapproval)
                }
            } else {
                // Hand off to the main queue and never return; guide exits the process.
                await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
                    DispatchQueue.main.async {
                        // cont intentionally never resumed — PermissionGuideApp.run ends in exit().
                        _ = cont
                        MainActor.assumeIsolated {
                            PermissionGuideApp.run(binaryPath: binary, reapproval: reapproval)
                        }
                    }
                }
            }
            return
        }
        await SpectreScreenCaptureCommand.main(argv)
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
