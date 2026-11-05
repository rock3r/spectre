import CoreGraphics
import XCTest
@testable import SpectreScreenCaptureCore

final class WindowFrameSelectorTests: XCTestCase {
    func testArgumentsAcceptFrameSelectorWithNegativeOrigin() throws {
        let args = try Arguments.parse([
            "spectre-screencapture",
            "--mode",
            "screenshot",
            "--pid",
            "123",
            "--frame",
            "-1440,-20,300,200",
            "--output",
            "/tmp/spectre-frame.png",
        ])

        XCTAssertNil(args.titleContains)
        XCTAssertEqual(args.frame, WindowFrame(x: -1440, y: -20, width: 300, height: 200))
    }

    func testArgumentsRejectFrameTogetherWithTitleContains() {
        XCTAssertThrowsError(
            try Arguments.parse([
                "spectre-screencapture",
                "--pid",
                "123",
                "--title-contains",
                "Spectre/test",
                "--frame",
                "0,0,300,200",
                "--output",
                "/tmp/out.png",
            ])
        ) { error in
            XCTAssertEqual((error as? CLIError)?.code, 2)
            XCTAssertTrue((error as? CLIError)?.message.contains("mutually exclusive") == true)
        }
    }

    func testArgumentsRequireTitleOrFrameForWindowSource() {
        XCTAssertThrowsError(
            try Arguments.parse([
                "spectre-screencapture",
                "--pid",
                "123",
                "--output",
                "/tmp/out.png",
            ])
        ) { error in
            XCTAssertEqual((error as? CLIError)?.code, 2)
        }
    }

    func testArgumentsRejectEmptyFrame() {
        XCTAssertThrowsError(
            try Arguments.parse([
                "spectre-screencapture",
                "--pid",
                "123",
                "--frame",
                "0,0,0,200",
                "--output",
                "/tmp/out.png",
            ])
        ) { error in
            XCTAssertEqual((error as? CLIError)?.code, 2)
        }
    }

    func testArgumentsRejectFrameOnRegionSource() {
        XCTAssertThrowsError(
            try Arguments.parse([
                "spectre-screencapture",
                "--source",
                "region",
                "--region",
                "0,0,100,100",
                "--frame",
                "0,0,50,50",
                "--output",
                "/tmp/out.mp4",
            ])
        )
    }

    func testFrameSelectorPicksTheOnlyWindowWithThatFrame() throws {
        let candidates = [
            WindowCandidate(
                id: 1, pid: 42, title: "Main", frame: CGRect(x: 0, y: 0, width: 800, height: 600)),
            WindowCandidate(
                id: 2, pid: 42, title: nil, frame: CGRect(x: 100, y: 120, width: 300, height: 200)),
            WindowCandidate(
                id: 3, pid: 7, title: nil, frame: CGRect(x: 100, y: 120, width: 300, height: 200)),
        ]

        let selected = try selectWindow(
            candidates,
            pid: 42,
            selector: .frame(WindowFrame(x: 100, y: 120, width: 300, height: 200)))

        XCTAssertEqual(selected, 2)
    }

    func testFrameSelectorToleratesOnePointOfRounding() throws {
        let candidates = [
            WindowCandidate(
                id: 9, pid: 42, title: nil,
                frame: CGRect(x: 100.5, y: 119, width: 301, height: 199.5))
        ]

        let selected = try selectWindow(
            candidates,
            pid: 42,
            selector: .frame(WindowFrame(x: 100, y: 120, width: 300, height: 200)))

        XCTAssertEqual(selected, 9)
    }

    func testFrameSelectorReturnsNilWhenNoWindowMatches() throws {
        let candidates = [
            WindowCandidate(
                id: 1, pid: 42, title: nil, frame: CGRect(x: 0, y: 0, width: 800, height: 600)),
            WindowCandidate(
                id: 2, pid: 42, title: nil, frame: CGRect(x: 103, y: 120, width: 300, height: 200)),
        ]

        let selected = try selectWindow(
            candidates,
            pid: 42,
            selector: .frame(WindowFrame(x: 100, y: 120, width: 300, height: 200)))

        XCTAssertNil(selected)
    }

    func testFrameSelectorFailsWhenTwoWindowsShareTheFrame() {
        let candidates = [
            WindowCandidate(
                id: 1, pid: 42, title: nil, frame: CGRect(x: 100, y: 120, width: 300, height: 200)),
            WindowCandidate(
                id: 2, pid: 42, title: "Other",
                frame: CGRect(x: 100, y: 120, width: 300, height: 200)),
        ]

        XCTAssertThrowsError(
            try selectWindow(
                candidates,
                pid: 42,
                selector: .frame(WindowFrame(x: 100, y: 120, width: 300, height: 200)))
        ) { error in
            XCTAssertEqual((error as? CLIError)?.code, 7)
            XCTAssertTrue((error as? CLIError)?.message.contains("2 windows") == true)
        }
    }

    func testTitleSelectorKeepsFirstSubstringMatch() throws {
        let candidates = [
            WindowCandidate(
                id: 1, pid: 42, title: nil, frame: CGRect(x: 0, y: 0, width: 10, height: 10)),
            WindowCandidate(
                id: 2, pid: 42, title: "App Spectre/abc",
                frame: CGRect(x: 0, y: 0, width: 10, height: 10)),
            WindowCandidate(
                id: 3, pid: 42, title: "Spectre/abc too",
                frame: CGRect(x: 0, y: 0, width: 10, height: 10)),
        ]

        let selected = try selectWindow(
            candidates, pid: 42, selector: .titleContains("Spectre/abc"))

        XCTAssertEqual(selected, 2)
    }
}
