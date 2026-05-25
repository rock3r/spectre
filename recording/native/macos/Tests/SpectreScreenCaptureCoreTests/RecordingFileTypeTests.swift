import AVFoundation
import XCTest
@testable import SpectreScreenCaptureCore

final class RecordingFileTypeTests: XCTestCase {
    func testMp4OutputUsesMp4AssetWriterType() {
        let output = URL(fileURLWithPath: "/tmp/spectre-capture.mp4")

        let fileType = RecordingFileType.forOutput(output)

        XCTAssertEqual(fileType, .mp4)
        XCTAssertEqual(fileType.assetWriterFileType, .mp4)
    }

    func testMovOutputUsesQuickTimeMovieAssetWriterType() {
        let output = URL(fileURLWithPath: "/tmp/spectre-capture.mov")

        let fileType = RecordingFileType.forOutput(output)

        XCTAssertEqual(fileType, .mov)
        XCTAssertEqual(fileType.assetWriterFileType, .mov)
    }

    func testOutputExtensionMatchingIsCaseInsensitive() {
        let output = URL(fileURLWithPath: "/tmp/spectre-capture.MP4")

        XCTAssertEqual(RecordingFileType.forOutput(output), .mp4)
    }
}
