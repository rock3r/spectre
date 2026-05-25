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
}
