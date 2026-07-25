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

    func testGuideCopyTeachesPlusButtonAndShowInFinder() {
        // System Settings' Screen Recording list uses + to add apps; drag is best-effort.
        XCTAssertTrue(
            PermissionGuideCopy.addSteps.contains("+")
                || PermissionGuideCopy.addSteps.lowercased().contains("plus"),
            "addSteps must mention the Settings + control"
        )
        XCTAssertFalse(PermissionGuideCopy.showInFinderLabel.isEmpty)
        XCTAssertTrue(
            PermissionGuideCopy.dragHint.lowercased().contains("drag")
                || PermissionGuideCopy.dragHint.contains("+"),
            "dragHint must explain drag and/or + fallback"
        )
        // Users must enable this helper by name — not only their terminal.
        XCTAssertTrue(
            PermissionGuideCopy.firstRunBody.contains("Spectre Capture Helper"),
            "body must name the helper app for Settings list matching"
        )
    }

    func testItemProviderRegistersApplicationBundleTypes() throws {
        let appURL = URL(fileURLWithPath: "/Applications/Safari.app")
        let provider = PermissionGuideDragPayload.itemProvider(for: appURL)
        let ids = Set(provider.registeredTypeIdentifiers)
        XCTAssertTrue(
            ids.contains("com.apple.application-bundle") || ids.contains("public.file-url"),
            "expected app-bundle or file-url types, got \(ids)"
        )
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
