import XCTest
@testable import SpectreScreenCaptureCore

final class ArgumentsTests: XCTestCase {
    func testArgumentsDefaultFileTypeFromMp4Output() throws {
        let args = try Arguments.parse([
            "spectre-screencapture",
            "--pid",
            "123",
            "--title-contains",
            "Spectre/test",
            "--output",
            "/tmp/spectre-capture.mp4",
        ])

        XCTAssertEqual(args.fileType, .mp4)
    }

    func testArgumentsAcceptExplicitFileType() throws {
        let args = try Arguments.parse([
            "spectre-screencapture",
            "--pid",
            "123",
            "--title-contains",
            "Spectre/test",
            "--file-type",
            "mov",
            "--output",
            "/tmp/spectre-capture.mp4",
        ])

        XCTAssertEqual(args.fileType, .mov)
    }

    func testArgumentsRejectUnknownFileType() {
        XCTAssertThrowsError(
            try Arguments.parse([
                "spectre-screencapture",
                "--pid",
                "123",
                "--title-contains",
                "Spectre/test",
                "--file-type",
                "avi",
                "--output",
                "/tmp/spectre-capture.avi",
            ])
        )
    }

    func testArgumentsAcceptPreflightModeWithoutOutput() throws {
        let args = try Arguments.parse([
            "spectre-screencapture",
            "--mode",
            "preflight",
        ])
        XCTAssertEqual(args.mode, .preflight)
    }

    func testArgumentsAcceptPreflightFlag() throws {
        let args = try Arguments.parse([
            "spectre-screencapture",
            "--preflight",
        ])
        XCTAssertEqual(args.mode, .preflight)
    }

    func testArgumentsAcceptRequestMode() throws {
        let args = try Arguments.parse([
            "spectre-screencapture",
            "--mode",
            "request",
        ])
        XCTAssertEqual(args.mode, .request)
    }

    func testArgumentsAcceptWindowCrop() throws {
        let args = try Arguments.parse([
            "spectre-screencapture",
            "--pid",
            "123",
            "--title-contains",
            "Spectre/crop",
            "--crop",
            "10,20,300,200",
            "--output",
            "/tmp/spectre-crop.mp4",
        ])
        XCTAssertEqual(args.crop?.x, 10)
        XCTAssertEqual(args.crop?.y, 20)
        XCTAssertEqual(args.crop?.width, 300)
        XCTAssertEqual(args.crop?.height, 200)
    }


    func testWindowCropMustFitTheSelectedWindow() {
        XCTAssertThrowsError(
            try validateWindowCrop(
                CaptureRegion(x: 50, y: 20, width: 200, height: 100),
                windowSize: CGSize(width: 240, height: 200)
            )
        )
    }

    func testWindowCropClampsEverySinglePixelEdgeInset() throws {
        let window = CGSize(width: 240, height: 200)
        XCTAssertEqual(
            try validateWindowCrop(
                CaptureRegion(x: -1, y: 20, width: 101, height: 100), windowSize: window),
            CaptureRegion(x: 0, y: 20, width: 100, height: 100))
        XCTAssertEqual(
            try validateWindowCrop(
                CaptureRegion(x: 20, y: -1, width: 100, height: 101), windowSize: window),
            CaptureRegion(x: 20, y: 0, width: 100, height: 100))
        XCTAssertEqual(
            try validateWindowCrop(
                CaptureRegion(x: 140, y: 20, width: 101, height: 100), windowSize: window),
            CaptureRegion(x: 140, y: 20, width: 100, height: 100))
        XCTAssertEqual(
            try validateWindowCrop(
                CaptureRegion(x: 20, y: 100, width: 100, height: 101), windowSize: window),
            CaptureRegion(x: 20, y: 100, width: 100, height: 100))
    }

    func testWindowCropRejectsEmptyIntersectionAndOverflowingArithmetic() {
        XCTAssertThrowsError(
            try validateWindowCrop(
                CaptureRegion(x: -1, y: 0, width: 1, height: 10),
                windowSize: CGSize(width: 240, height: 200)))
        XCTAssertThrowsError(
            try validateWindowCrop(
                CaptureRegion(x: Int.max, y: 0, width: 2, height: 10),
                windowSize: CGSize(width: 240, height: 200)))
    }

    func testArgumentsRejectCropOnRegionSource() {
        XCTAssertThrowsError(
            try Arguments.parse([
                "spectre-screencapture",
                "--source",
                "region",
                "--region",
                "0,0,100,100",
                "--crop",
                "0,0,50,50",
                "--output",
                "/tmp/out.mp4",
            ])
        )
    }

    func testArgumentsAcceptRegionScreenshot() throws {
        let args = try Arguments.parse([
            "spectre-screencapture",
            "--mode",
            "screenshot",
            "--source",
            "region",
            "--region",
            "0,0,2,2",
            "--display-index",
            "0",
            "--fps",
            "30",
            "--cursor",
            "false",
            "--file-type",
            "mov",
            "--discovery-timeout-ms",
            "2000",
            "--output",
            "/tmp/test.png",
        ])

        XCTAssertEqual(args.mode, .screenshot)
        XCTAssertEqual(args.source, .region)
        XCTAssertEqual(args.region?.x, 0)
        XCTAssertEqual(args.region?.y, 0)
        XCTAssertEqual(args.region?.width, 2)
        XCTAssertEqual(args.region?.height, 2)
        XCTAssertEqual(args.displayIndex, 0)
        XCTAssertEqual(args.fps, 30)
        XCTAssertFalse(args.captureCursor)
        XCTAssertEqual(args.fileType, .mov)
        XCTAssertEqual(args.discoveryTimeoutMs, 2000)
        XCTAssertEqual(args.output.path, "/tmp/test.png")
    }

    func testArgumentsRejectStillsOnRegionSource() {
        assertInvalidRegionArguments(
            ["--mode", "stills"],
            message: "--mode stills only supports --source window")
    }

    func testArgumentsRejectCropOnRegionScreenshot() {
        assertInvalidRegionArguments(
            ["--mode", "screenshot", "--crop", "0,0,1,1"],
            message: "--crop is only valid with --source window (region uses --region)")
    }

    func testArgumentsRejectWindowFrameOnRegionScreenshot() {
        assertInvalidRegionArguments(
            ["--mode", "screenshot", "--frame", "0,0,2,2"],
            message: "--frame is only valid with --source window")
    }

    private func assertInvalidRegionArguments(
        _ extraArguments: [String],
        message expectedMessage: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertThrowsError(
            try Arguments.parse(
                [
                    "spectre-screencapture",
                    "--source",
                    "region",
                    "--region",
                    "0,0,2,2",
                    "--output",
                    "/tmp/test.png",
                ] + extraArguments),
            file: file,
            line: line
        ) { error in
            XCTAssertEqual((error as? CLIError)?.message, expectedMessage, file: file, line: line)
        }
    }
}
