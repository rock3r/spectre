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
        XCTAssertFalse(PermissionGuideCopy.dragHint.isEmpty)
        XCTAssertFalse(PermissionGuideCopy.grantedLabel.isEmpty)
    }

    func testGuideCopyIsDragPrimaryWithoutSettingsPlus() {
        // Compact bar: drag into list. Settings is opened automatically — no + affordance.
        XCTAssertTrue(
            PermissionGuideCopy.dragHint.lowercased().contains("drag"),
            "dragHint must teach dragging"
        )
        XCTAssertFalse(
            PermissionGuideCopy.dragHint.contains("+"),
            "dragHint must not steer to Settings +"
        )
        XCTAssertTrue(
            PermissionGuideCopy.dragHint.contains("Spectre Capture Helper"),
            "bar must name the helper"
        )
        XCTAssertTrue(
            PermissionGuideCopy.firstRunBody.contains("Spectre Capture Helper")
        )
    }

    func testBarPlacementFallsBackToBottomCenterWithoutAnchor() {
        let visible = CGRect(x: 0, y: 28, width: 1440, height: 850)
        let frame = PermissionGuidePlacement.barFrame(
            anchor: .init(),
            visibleFrame: visible
        )
        XCTAssertEqual(frame.width, PermissionGuidePlacement.defaultBarSize.width)
        XCTAssertEqual(frame.midX, visible.midX, accuracy: 1)
        XCTAssertGreaterThan(frame.minY, visible.minY)
    }

    func testBarPlacementDocksUnderListMatchingWidth() {
        let visible = CGRect(x: 0, y: 0, width: 1800, height: 1100)
        // AppKit coords: list in upper-right content area
        let list = CGRect(x: 1190, y: 700, width: 460, height: 120)
        let anchor = PermissionGuidePlacement.Anchor(listBounds: list)
        let size = PermissionGuidePlacement.barSize(for: anchor)
        XCTAssertEqual(size.width, 460, accuracy: 1)
        let frame = PermissionGuidePlacement.barFrame(
            anchor: anchor,
            visibleFrame: visible,
            barSize: size
        )
        // Same left edge and width as the list card
        XCTAssertEqual(frame.minX, list.minX, accuracy: 2)
        XCTAssertEqual(frame.width, list.width, accuracy: 2)
        // Just below the list (list.minY is bottom edge in AppKit)
        XCTAssertLessThan(frame.maxY, list.minY + 1)
        XCTAssertGreaterThan(frame.maxY, list.minY - 40)
    }

    func testTopCheckboxClusterIgnoresLowerSystemAudioSection() {
        // AppKit: higher maxY = higher on screen.
        let screenApps = [
            CGRect(x: 1600, y: 780, width: 36, height: 20),  // Codex
            CGRect(x: 1600, y: 740, width: 36, height: 20),  // Ghostty
            CGRect(x: 1600, y: 700, width: 36, height: 20),  // Spectre
        ]
        let audioOnly = CGRect(x: 1600, y: 520, width: 36, height: 20)  // Chrome — big gap
        let cluster = PermissionGuidePlacement.topCheckboxCluster(screenApps + [audioOnly])
        XCTAssertNotNil(cluster)
        // Must not extend down to Chrome.
        XCTAssertGreaterThan(cluster!.minY, audioOnly.maxY + 40)
        XCTAssertEqual(cluster!.maxY, screenApps[0].maxY, accuracy: 1)
        XCTAssertEqual(cluster!.minY, screenApps[2].minY, accuracy: 1)
    }

    func testSyntheticListIsShortCardUnderDetailTitle() {
        let settings = CGRect(x: 600, y: 300, width: 720, height: 620)
        let list = PermissionGuidePlacement.syntheticListBounds(in: settings)
        // Detail pane starts ~38% across Settings; width ~58%.
        XCTAssertEqual(
            list.minX,
            settings.minX + settings.width * PermissionGuidePlacement.detailPaneLeading,
            accuracy: 1
        )
        XCTAssertEqual(
            list.width,
            settings.width * PermissionGuidePlacement.detailPaneWidth,
            accuracy: 1
        )
        // Compact apps card — bar docks under Screen Recording +/−.
        XCTAssertLessThanOrEqual(list.height, settings.height * 0.22)
        XCTAssertGreaterThan(list.maxY, settings.midY)
        let bar = PermissionGuidePlacement.barFrame(
            anchor: .init(listBounds: list),
            visibleFrame: CGRect(x: 0, y: 0, width: 1800, height: 1100)
        )
        XCTAssertLessThan(bar.maxY, list.minY + 1)
        XCTAssertEqual(bar.width, list.width, accuracy: 1)
        // Bar sits in upper half of Settings, not under System Audio list.
        XCTAssertGreaterThan(bar.midY, settings.minY + settings.height * 0.30)
    }

    func testListBottomStaysUnderClusterNotAudioHeading() {
        // Ghostty cluster bottom 700; audio heading top 620 → floor under cluster.
        let bottom = PermissionGuidePlacement.screenRecordingListBottom(
            clusterMinY: 700,
            audioSectionTop: 620
        )
        XCTAssertEqual(bottom, 700 - 36, accuracy: 0.5)
        // If cluster somehow extends below the heading, clamp above it.
        let clamped = PermissionGuidePlacement.screenRecordingListBottom(
            clusterMinY: 600,
            audioSectionTop: 620
        )
        XCTAssertEqual(clamped, 636, accuracy: 0.5)
    }

    func testDetailPaneListFrameMatchesSettingsFractions() {
        let settings = CGRect(x: 100, y: 200, width: 800, height: 600)
        let frame = PermissionGuidePlacement.detailPaneListFrame(
            in: settings,
            top: 700,
            bottom: 500
        )
        XCTAssertEqual(
            frame.minX,
            100 + 800 * PermissionGuidePlacement.detailPaneLeading,
            accuracy: 0.5
        )
        XCTAssertEqual(
            frame.width,
            800 * PermissionGuidePlacement.detailPaneWidth,
            accuracy: 0.5
        )
        XCTAssertEqual(frame.height, 200, accuracy: 0.5)
    }

    func testFrameChangedMeaningfullyUsesDeadband() {
        let a = CGRect(x: 100, y: 200, width: 400, height: 120)
        XCTAssertFalse(
            PermissionGuidePlacement.frameChangedMeaningfully(
                a,
                a.offsetBy(dx: 2, dy: -2)
            )
        )
        XCTAssertTrue(
            PermissionGuidePlacement.frameChangedMeaningfully(
                a,
                a.offsetBy(dx: 10, dy: 0)
            )
        )
    }

    func testSettingsOriginMovedUsesSmallThreshold() {
        let o = CGPoint(x: 100, y: 200)
        XCTAssertFalse(
            PermissionGuidePlacement.settingsOriginMoved(o, CGPoint(x: 102, y: 201))
        )
        XCTAssertTrue(
            PermissionGuidePlacement.settingsOriginMoved(o, CGPoint(x: 100, y: 220))
        )
    }


    func testBarSizeMatchesListWidth() {
        let list = CGRect(x: 100, y: 200, width: 448, height: 100)
        let size = PermissionGuidePlacement.barSize(
            for: .init(listBounds: list)
        )
        XCTAssertEqual(size.width, 448, accuracy: 0.5)
    }

    func testBarPlacementDocksUnderSettingsContentWhenNoList() {
        let visible = CGRect(x: 0, y: 0, width: 1800, height: 1100)
        let settings = CGRect(x: 950, y: 400, width: 720, height: 620)
        let frame = PermissionGuidePlacement.barFrame(
            anchor: .init(settingsWindowBounds: settings),
            visibleFrame: visible
        )
        // Prefer right/detail side of Settings
        XCTAssertGreaterThan(frame.midX, settings.minX + settings.width * 0.3)
        XCTAssertLessThan(frame.midX, settings.maxX + 20)
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

    func testPollStateMachineReturnsToWaitingAfterRevoke() {
        var state = PermissionGuidePollState()
        state.apply(preflight: false, listedInSettings: false)
        state.apply(preflight: true, listedInSettings: true)
        XCTAssertTrue(state.granted)
        // Toggle off / remove from list.
        state.apply(preflight: false, listedInSettings: false)
        XCTAssertFalse(state.granted)
        XCTAssertFalse(state.preflightGranted)
        // Re-grant path still works.
        state.apply(preflight: true, listedInSettings: true)
        XCTAssertTrue(state.granted)
    }

    func testUiGrantedIgnoresGhostPreflightWithoutListRow() {
        // TCC ghost: preflight true, not in list → Waiting (even after a prior deny).
        XCTAssertFalse(
            PermissionGuidePollState.uiGranted(
                preflight: true,
                listedInSettings: false,
                sawPreflightDenied: false
            )
        )
        XCTAssertFalse(
            PermissionGuidePollState.uiGranted(
                preflight: true,
                listedInSettings: false,
                sawPreflightDenied: true
            )
        )
        // Listed + preflight → Done.
        XCTAssertTrue(
            PermissionGuidePollState.uiGranted(
                preflight: true,
                listedInSettings: true,
                sawPreflightDenied: false
            )
        )
        // Cannot inspect list + ghost preflight → still Waiting.
        XCTAssertFalse(
            PermissionGuidePollState.uiGranted(
                preflight: true,
                listedInSettings: nil,
                sawPreflightDenied: false
            )
        )
        // Cannot inspect list but saw real false→true this session → Done.
        XCTAssertTrue(
            PermissionGuidePollState.uiGranted(
                preflight: true,
                listedInSettings: nil,
                sawPreflightDenied: true
            )
        )
        XCTAssertFalse(
            PermissionGuidePollState.uiGranted(
                preflight: false,
                listedInSettings: true,
                sawPreflightDenied: false
            )
        )
    }

    func testShouldYieldToSystemUIForAuthAndSettingsSheets() {
        let main = PermissionGuidePlacement.OnScreenWindow(
            owner: "System Settings",
            name: "Screen & System Audio Recording",
            layer: 0,
            bounds: CGRect(x: 400, y: 200, width: 720, height: 620)
        )
        // Alone: no yield.
        XCTAssertFalse(
            PermissionGuidePlacement.shouldYieldToSystemUI(windows: [main])
        )
        // Touch ID / SecurityAgent sheet.
        let security = PermissionGuidePlacement.OnScreenWindow(
            owner: "SecurityAgent",
            layer: 0,
            bounds: CGRect(x: 500, y: 300, width: 280, height: 320)
        )
        XCTAssertTrue(
            PermissionGuidePlacement.shouldYieldToSystemUI(windows: [main, security])
        )
        // Settings-owned quit/reopen style dialog (smaller secondary window).
        let quitDialog = PermissionGuidePlacement.OnScreenWindow(
            owner: "System Settings",
            name: "",
            layer: 0,
            bounds: CGRect(x: 520, y: 340, width: 360, height: 220)
        )
        XCTAssertTrue(
            PermissionGuidePlacement.shouldYieldToSystemUI(windows: [main, quitDialog])
        )
        // Elevated layer Settings surface.
        let sheet = PermissionGuidePlacement.OnScreenWindow(
            owner: "System Settings",
            layer: 8,
            bounds: CGRect(x: 500, y: 300, width: 400, height: 250)
        )
        XCTAssertTrue(
            PermissionGuidePlacement.shouldYieldToSystemUI(windows: [main, sheet])
        )
    }

    func testHelperNameInScreenRecordingLabelsAboveAudioSection() {
        let audioTop: CGFloat = 500
        let labels: [(text: String, frame: CGRect)] = [
            ("Codex Computer Use.app", CGRect(x: 0, y: 700, width: 100, height: 20)),
            ("Ghostty.app", CGRect(x: 0, y: 660, width: 100, height: 20)),
            // Below audio section — must not count (wrong list).
            ("Spectre Capture Helper", CGRect(x: 0, y: 400, width: 100, height: 20)),
        ]
        XCTAssertFalse(
            PermissionGuidePlacement.isHelperNameInScreenRecordingLabels(
                labels: labels,
                nameFragments: ["Spectre Capture Helper"],
                audioSectionTop: audioTop
            )
        )
        let withRow = labels + [
            ("Spectre Capture Helper", CGRect(x: 0, y: 620, width: 100, height: 20))
        ]
        XCTAssertTrue(
            PermissionGuidePlacement.isHelperNameInScreenRecordingLabels(
                labels: withRow,
                nameFragments: ["Spectre Capture Helper"],
                audioSectionTop: audioTop
            )
        )
    }

    func testHelperEnabledRequiresToggleOn() {
        let audioTop: CGFloat = 500
        let label = (
            text: "SpectreCaptureHelper.app",
            frame: CGRect(x: 100, y: 620, width: 200, height: 20)
        )
        let offBox = (
            frame: CGRect(x: 400, y: 622, width: 36, height: 16),
            enabled: false
        )
        let onBox = (
            frame: CGRect(x: 400, y: 622, width: 36, height: 16),
            enabled: true
        )
        // Present but disabled → not Done.
        XCTAssertFalse(
            PermissionGuidePlacement.isHelperEnabledInScreenRecordingList(
                labels: [label],
                checkboxes: [offBox],
                nameFragments: ["SpectreCaptureHelper", "Spectre Capture Helper"],
                audioSectionTop: audioTop
            )
        )
        // Toggle on → enabled.
        XCTAssertTrue(
            PermissionGuidePlacement.isHelperEnabledInScreenRecordingList(
                labels: [label],
                checkboxes: [onBox],
                nameFragments: ["SpectreCaptureHelper", "Spectre Capture Helper"],
                audioSectionTop: audioTop
            )
        )
        // Preflight alone is not enough when list is inspectable — uiGranted false if not enabled.
        XCTAssertFalse(
            PermissionGuidePollState.uiGranted(
                preflight: true,
                listedInSettings: false,
                sawPreflightDenied: true
            )
        )
    }
}
