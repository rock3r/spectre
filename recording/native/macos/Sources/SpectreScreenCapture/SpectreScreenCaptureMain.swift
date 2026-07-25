import SpectreScreenCaptureCore

@main
enum SpectreScreenCapture {
    /// Async entry for capture modes. Guide mode hops to `@MainActor` and enters
    /// `NSApplication.run()` there (process exits from the guide UI — no main-thread
    /// `DispatchGroup.wait()` parking for normal modes).
    static func main() async {
        let argv = CommandLine.arguments
        if isGuidePermissionsInvocation(argv) {
            let binary = argv.first ?? "spectre-screencapture"
            let reapproval = argv.contains("--reapproval")
            await MainActor.run {
                PermissionGuideApp.run(binaryPath: binary, reapproval: reapproval)
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
