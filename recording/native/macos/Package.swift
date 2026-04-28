// swift-tools-version:5.9
import PackageDescription

// Out-of-process helper that Spectre forks per recording. The JVM side
// (`ScreenCaptureKitRecorder`) extracts the built executable from
// `recording/src/main/resources/native/macos/`, hands it window-discovery
// arguments + an output path on stdin/argv, and stops it by writing `q\n`
// to stdin (mirrors the existing `FfmpegRecorder` shutdown contract).
//
// macOS 13 is the floor: ScreenCaptureKit shipped in 12.3, but the
// `SCStreamConfiguration.queueDepth` / `SCStreamOutput` async surface we
// rely on stabilized in 13.
let package = Package(
    name: "SpectreScreenCapture",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "SpectreScreenCapture",
            path: "Sources/SpectreScreenCapture"
        )
    ]
)
