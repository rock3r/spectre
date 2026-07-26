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

    func testGuideCopyMatchesApprovedOpenSettingsAndDragUX() {
        // #192 / user guide: Open Settings → drag icon if missing → live Done.
        // Do not teach Settings + / Show in Finder as the primary path without product approval.
        XCTAssertEqual(PermissionGuideCopy.openSettingsLabel, "Open Settings")
        XCTAssertTrue(
            PermissionGuideCopy.dragHint.lowercased().contains("drag"),
            "dragHint must teach dragging the icon"
        )
        XCTAssertFalse(
            PermissionGuideCopy.dragHint.contains("+"),
            "dragHint must not steer to Settings + as the primary affordance"
        )
        XCTAssertTrue(
            PermissionGuideCopy.firstRunBody.contains("Spectre Capture Helper"),
            "body must name the helper app for Settings list matching"
        )
        XCTAssertTrue(
            PermissionGuideCopy.firstRunBody.contains("System Settings")
                || PermissionGuideCopy.firstRunBody.contains("Screen"),
            "body must point at System Settings / Screen Recording grant"
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

    func testPasteboardWritersAreFileURLs() {
        let appURL = URL(fileURLWithPath: "/Applications/Safari.app")
        let writers = PermissionGuideDragPayload.pasteboardWriters(for: appURL)
        XCTAssertFalse(writers.isEmpty)
        let types = writers[0].writableTypes(for: NSPasteboard.general)
        XCTAssertTrue(
            types.contains(where: { $0.rawValue.contains("file") || $0.rawValue.contains("url") }),
            "expected file/url pasteboard types, got \(types)"
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
