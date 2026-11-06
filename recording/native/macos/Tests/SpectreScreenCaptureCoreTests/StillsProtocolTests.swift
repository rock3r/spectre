import CoreGraphics
import XCTest
@testable import SpectreScreenCaptureCore

final class StillsProtocolTests: XCTestCase {
    func testParsesCommands() {
        XCTAssertEqual(StillsProtocol.parseCommand("c"), .capture)
        XCTAssertEqual(StillsProtocol.parseCommand(" c \n"), .capture)
        XCTAssertEqual(StillsProtocol.parseCommand("q"), .quit)
        XCTAssertEqual(StillsProtocol.parseCommand(nil), .quit)
        XCTAssertEqual(StillsProtocol.parseCommand("x"), .unknown)
    }

    func testHeadersAreSingleLines() {
        XCTAssertEqual(StillsProtocol.frameHeader(width: 480, height: 320), "FRAME 480 320")
        XCTAssertEqual(
            StillsProtocol.staleHeader("the window\nwas resized"), "STALE the window was resized")
    }

    func testGeometryToleratesRoundingButNotAResize() {
        let start = StillGeometry(
            contentRect: CGRect(x: 0, y: 0, width: 240, height: 160), contentScale: 1,
            scaleFactor: 2)
        let rounded = StillGeometry(
            contentRect: CGRect(x: 0, y: 0, width: 240.25, height: 160), contentScale: 1.001,
            scaleFactor: 2)
        let resized = StillGeometry(
            contentRect: CGRect(x: 0, y: 0, width: 240, height: 120), contentScale: 0.75,
            scaleFactor: 2)

        XCTAssertTrue(start.matches(rounded))
        XCTAssertFalse(start.matches(resized))
    }

    func testStillsModeNeedsNoOutput() throws {
        let args = try Arguments.parse([
            "spectre-screencapture", "--mode", "stills", "--pid", "123", "--frame",
            "10,20,240,160",
        ])
        XCTAssertEqual(args.mode, .stills)
    }

    func testStillsModeRejectsRegionSource() {
        XCTAssertThrowsError(
            try Arguments.parse([
                "spectre-screencapture", "--mode", "stills", "--source", "region", "--region",
                "0,0,10,10",
            ]))
    }
}

final class StillsSelectorTests: XCTestCase {
    private func candidate(_ id: Int, title: String? = nil, frame: CGRect, pid: pid_t = 42)
        -> WindowCandidate<Int>
    {
        WindowCandidate(id: id, pid: pid, title: title, frame: frame)
    }

    private let frameA = CGRect(x: 10, y: 20, width: 240, height: 160)
    private let frameB = CGRect(x: 400, y: 20, width: 240, height: 160)

    func testASessionWindowThatStillMatchesItsSelectorIsCurrent() {
        let windows = [candidate(1, title: "Alpha", frame: frameA)]
        XCTAssertNil(
            staleSelectorReason(
                windows, pid: 42, selector: .titleContains("Alpha"), sessionWindow: 1))
        XCTAssertNil(
            staleSelectorReason(
                windows, pid: 42, selector: .frame(WindowFrame(x: 10, y: 20, width: 240, height: 160)),
                sessionWindow: 1))
    }

    func testARetitledWindowIsStaleEvenWhenAnotherWindowNowHasTheTitle() {
        let windows = [
            candidate(1, title: "Beta", frame: frameA), candidate(2, title: "Alpha", frame: frameB),
        ]
        XCTAssertNotNil(
            staleSelectorReason(
                windows, pid: 42, selector: .titleContains("Alpha"), sessionWindow: 1))
    }

    func testWindowsThatSwappedFramesAreStale() {
        let windows = [candidate(1, frame: frameB), candidate(2, frame: frameA)]
        XCTAssertNotNil(
            staleSelectorReason(
                windows, pid: 42, selector: .frame(WindowFrame(x: 10, y: 20, width: 240, height: 160)),
                sessionWindow: 1))
    }

    func testASecondWindowWithTheSameFrameMakesTheSessionStale() {
        let windows = [candidate(1, frame: frameA), candidate(2, frame: frameA)]
        XCTAssertNotNil(
            staleSelectorReason(
                windows, pid: 42, selector: .frame(WindowFrame(x: 10, y: 20, width: 240, height: 160)),
                sessionWindow: 1))
    }

    func testAClosedWindowIsStale() {
        let windows = [candidate(2, title: "Alpha", frame: frameA)]
        XCTAssertNotNil(
            staleSelectorReason(
                windows, pid: 42, selector: .titleContains("Alpha"), sessionWindow: 1))
    }

    func testStillsModeRejectsAnOutputPath() {
        XCTAssertThrowsError(
            try Arguments.parse([
                "spectre-screencapture", "--mode", "stills", "--pid", "123", "--frame",
                "10,20,240,160", "--output", "/tmp/keep-me.png",
            ]))
    }
}
