import XCTest
@testable import SpectreScreenCaptureCore

final class PermissionGuideTests: XCTestCase {
    func testGuidePermissionsModeFromFlag() throws {
        let args = try Arguments.parse([
            "spectre-screencapture",
            "--guide-permissions",
        ])
        XCTAssertEqual(args.mode, .guidePermissions)
        XCTAssertFalse(args.reapproval)
    }

    func testGuidePermissionsModeFromModeValue() throws {
        let args = try Arguments.parse([
            "spectre-screencapture",
            "--mode",
            "guide-permissions",
            "--reapproval",
        ])
        XCTAssertEqual(args.mode, .guidePermissions)
        XCTAssertTrue(args.reapproval)
    }

    func testGuideCopyDiffersForReapproval() {
        XCTAssertNotEqual(
            PermissionGuideCopy.headline(reapproval: false),
            PermissionGuideCopy.headline(reapproval: true)
        )
        XCTAssertNotEqual(
            PermissionGuideCopy.body(reapproval: false),
            PermissionGuideCopy.body(reapproval: true)
        )
        XCTAssertFalse(PermissionGuideCopy.openSettingsLabel.isEmpty)
        XCTAssertFalse(PermissionGuideCopy.dragHint.isEmpty)
        XCTAssertFalse(PermissionGuideCopy.grantedLabel.isEmpty)
    }

    func testPollStateMachineFlipsToGranted() {
        var state = PermissionGuidePollState()
        state.applyPreflight(false)
        XCTAssertFalse(state.granted)
        XCTAssertEqual(state.pollCount, 1)
        state.applyPreflight(true)
        XCTAssertTrue(state.granted)
        XCTAssertEqual(state.pollCount, 2)
    }
}
