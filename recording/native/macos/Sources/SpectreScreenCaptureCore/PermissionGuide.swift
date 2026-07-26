import AppKit
import ApplicationServices
import CoreGraphics
import Foundation
import SwiftUI
import UniformTypeIdentifiers

// MARK: - Guided Screen Recording permission UI (#192)
//
// Human-only path: `spectre permissions request` launches
// `spectre-screencapture --mode guide-permissions`. Capture/record paths never
// enter this mode (they stay fail-fast preflight from #187).
//
// Visual reference: ChatGPT/Codex Computer Use chrome-less floating bar — docks
// under the System Settings privacy *list* with a draggable app affordance.

/// Pure copy selection so unit tests do not need a display.
public enum PermissionGuideCopy {
    public static let windowTitle = "Spectre Capture Helper"

    public static let firstRunHeadline = "Allow Screen Recording"
    public static let reapprovalHeadline = "Re-enable Screen Recording"

    public static let firstRunBody =
        "Spectre uses this helper only when you or an agent asks to capture a window or screen. "
        + "Grant Screen & System Audio Recording to Spectre Capture Helper in System Settings."

    public static let reapprovalBody =
        "macOS sometimes asks again after updates or when a previous grant lapses. "
        + "Turn Spectre Capture Helper back on in System Settings (same list as first-time setup)."

    /// Compact bar line (Codex-style): drag into the list Settings is showing.
    public static let dragHint =
        "Drag Spectre Capture Helper to the list above to allow Screen Recording"
    public static let dragSlotAccessibility = "Drag Spectre Capture Helper.app"
    public static let waitingLabel = "Waiting…"
    public static let grantedLabel = "Done ✓"
    public static let closeLabel = "Close"
    public static let doneLabel = "Done"

    public static func headline(reapproval: Bool) -> String {
        reapproval ? reapprovalHeadline : firstRunHeadline
    }

    public static func body(reapproval: Bool) -> String {
        reapproval ? reapprovalBody : firstRunBody
    }
}

/// Builds pasteboard / item-provider payloads for dragging the helper `.app`.
public enum PermissionGuideDragPayload {
    public static func itemProvider(for appURL: URL) -> NSItemProvider {
        let provider = NSItemProvider()
        provider.suggestedName = appURL.deletingPathExtension().lastPathComponent

        provider.registerFileRepresentation(
            forTypeIdentifier: UTType.applicationBundle.identifier,
            fileOptions: [.openInPlace],
            visibility: .all
        ) { completion in
            completion(appURL, true, nil)
            return nil
        }

        provider.registerDataRepresentation(
            forTypeIdentifier: UTType.fileURL.identifier,
            visibility: .all
        ) { completion in
            completion(appURL.dataRepresentation, nil)
            return nil
        }

        return provider
    }

    public static func pasteboardWriters(for appURL: URL) -> [any NSPasteboardWriting] {
        [appURL as NSURL]
    }
}

/// Polling state machine for the guide HUD (testable without AppKit run loop).
///
/// UI "Done" is **not** raw `CGPreflightScreenCaptureAccess`. Ad-hoc rebuilds often leave a
/// TCC ghost grant (preflight true) while Settings shows no row — that must stay "Waiting"
/// until the helper actually appears in the Screen Recording apps list (or preflight
/// flips false→true after we observed denied).
public struct PermissionGuidePollState: Equatable {
    /// Shown in the HUD (Waiting vs Done).
    public var granted: Bool
    /// Last raw CGPreflight value (exit code / JSON still use this).
    public var preflightGranted: Bool
    public var listedInSettings: Bool
    public var pollCount: Int
    /// True once this session has observed preflight == false.
    public var sawPreflightDenied: Bool

    public init(
        granted: Bool = false,
        preflightGranted: Bool = false,
        listedInSettings: Bool = false,
        pollCount: Int = 0,
        sawPreflightDenied: Bool = false
    ) {
        self.granted = granted
        self.preflightGranted = preflightGranted
        self.listedInSettings = listedInSettings
        self.pollCount = pollCount
        self.sawPreflightDenied = sawPreflightDenied
    }

        /// Combine preflight + Settings row *enabled* into HUD granted state.
    /// `enabledInSettings` is nil when Settings / AX cannot be inspected.
    /// `true` means the helper row is present **and** its toggle is on.
    public static func uiGranted(
        preflight: Bool,
        listedInSettings: Bool?,
        sawPreflightDenied: Bool
    ) -> Bool {
        // `listedInSettings` parameter name kept for call-site stability; means enabled.
        guard preflight else { return false }
        switch listedInSettings {
        case true:
            return true
        case false:
            // Missing row **or** toggle off → Waiting.
            return false
        case nil:
            // Cannot inspect Settings (no AX). Do **not** trust a lone preflight true —
            // ad-hoc rebuilds leave ghost grants. Require a false→true edge this session.
            return sawPreflightDenied
        }
    }

    public mutating func apply(preflight: Bool, listedInSettings: Bool?) {
        pollCount += 1
        if !preflight { sawPreflightDenied = true }
        preflightGranted = preflight
        self.listedInSettings = listedInSettings ?? false
        granted = Self.uiGranted(
            preflight: preflight,
            listedInSettings: listedInSettings,
            sawPreflightDenied: sawPreflightDenied
        )
    }

    /// Back-compat for tests that only drive preflight.
    public mutating func applyPreflight(_ isGranted: Bool) {
        apply(preflight: isGranted, listedInSettings: isGranted ? true : false)
    }
}

// MARK: - Placement (dock under Settings privacy list)

/// Geometry for the floating guide. All rects use **AppKit** coordinates (origin bottom-left).
public enum PermissionGuidePlacement {
    /// Fallback when Settings / list geometry is unknown.
    public static let defaultBarSize = NSSize(width: 448, height: 108)
    public static let minBarWidth: CGFloat = 380
    public static let maxBarWidth: CGFloat = 560
    public static let barHeight: CGFloat = 108
    public static let screenMargin: CGFloat = 12
    /// Tight gap under the app list (Codex sits almost flush below the rows).
    public static let listGap: CGFloat = 4
    /// Detail-pane list card: left inset and width as fractions of the Settings window.
    /// Slightly wider than the geometric 0.58 so the bar matches the rounded list card.
    public static let detailPaneLeading: CGFloat = 0.37
    public static let detailPaneWidth: CGFloat = 0.60

    /// Back-compat alias for tests / call sites.
    public static var barSize: NSSize { defaultBarSize }

    public struct Anchor: Equatable {
        /// Preferred: union of Screen Recording checkboxes / list scroll area.
        public var listBounds: CGRect?
        /// Fallback: System Settings window.
        public var settingsWindowBounds: CGRect?

        public init(listBounds: CGRect? = nil, settingsWindowBounds: CGRect? = nil) {
            self.listBounds = listBounds
            self.settingsWindowBounds = settingsWindowBounds
        }
    }

    /// Bar size: match the Screen Recording list card width when known.
    public static func barSize(for anchor: Anchor) -> NSSize {
        if let list = anchor.listBounds, list.width > 40 {
            let w = clamp(list.width, minBarWidth, maxBarWidth)
            return NSSize(width: w, height: barHeight)
        }
        if let settings = anchor.settingsWindowBounds, settings.width > 80 {
            // Detail pane ~60% of Settings — match that card width.
            let w = clamp(settings.width * 0.58, minBarWidth, maxBarWidth)
            return NSSize(width: w, height: barHeight)
        }
        return defaultBarSize
    }

    /// Preferred frame: same width as list, left-aligned under the list; else Settings detail; else bottom-center.
    public static func barFrame(
        anchor: Anchor,
        visibleFrame: CGRect,
        barSize: NSSize? = nil
    ) -> CGRect {
        let size = barSize ?? Self.barSize(for: anchor)
        if let list = anchor.listBounds, list.width > 40, list.height > 20 {
            // Match list width & x — Codex tip spans the privacy list card.
            var x = list.minX
            var y = list.minY - listGap - size.height
            if y < visibleFrame.minY + screenMargin {
                y = max(visibleFrame.minY + screenMargin, list.minY - size.height - listGap)
            }
            // Prefer exact list left edge; only nudge if off-screen.
            x = clamp(x, visibleFrame.minX + screenMargin, visibleFrame.maxX - size.width - screenMargin)
            y = clamp(y, visibleFrame.minY + screenMargin, visibleFrame.maxY - size.height - screenMargin)
            // Width already matched to list in barSize(for:); keep frame size.
            return CGRect(origin: CGPoint(x: x, y: y), size: size)
        }

        if let settings = anchor.settingsWindowBounds, settings.width > 80, settings.height > 80 {
            let contentLeft = settings.minX + settings.width * 0.38
            var x = contentLeft
            var y = settings.minY - listGap - size.height
            if y < visibleFrame.minY + screenMargin {
                y = settings.minY + listGap
            }
            x = clamp(x, visibleFrame.minX + screenMargin, visibleFrame.maxX - size.width - screenMargin)
            y = clamp(y, visibleFrame.minY + screenMargin, visibleFrame.maxY - size.height - screenMargin)
            return CGRect(origin: CGPoint(x: x, y: y), size: size)
        }

        let x = visibleFrame.midX - size.width / 2
        let y = visibleFrame.minY + screenMargin + 40
        return CGRect(x: x, y: y, width: size.width, height: size.height)
    }

    private static func clamp(_ v: CGFloat, _ lo: CGFloat, _ hi: CGFloat) -> CGFloat {
        min(max(v, lo), max(lo, hi))
    }

    /// Resolve live anchors via Accessibility (preferred) + window list fallback.
    public static func resolveAnchor() -> Anchor {
        if var ax = systemSettingsAnchorFromAX() {
            // If we only got the window (no AX checkboxes — common without Accessibility
            // permission for this helper), invent a list rect in the detail pane.
            if ax.listBounds == nil, let window = ax.settingsWindowBounds {
                ax.listBounds = syntheticListBounds(in: window)
            }
            return ax
        }
        if let window = systemSettingsWindowBoundsFromCG() {
            return Anchor(
                listBounds: syntheticListBounds(in: window),
                settingsWindowBounds: window
            )
        }
        return Anchor()
    }

    /// Detail-pane list card x/width inside a Settings window (AppKit coords).
    public static func detailPaneListFrame(
        in settingsWindow: CGRect,
        top: CGFloat,
        bottom: CGFloat
    ) -> CGRect {
        let left = settingsWindow.minX + settingsWindow.width * detailPaneLeading
        let width = settingsWindow.width * detailPaneWidth
        return CGRect(x: left, y: bottom, width: width, height: max(40, top - bottom))
    }

    /// Approx. Screen Recording *apps* list only (top card — not System Audio Only).
    /// Calibrated from live System Settings geometry: card ends under the +/− strip,
    /// above the "System Audio Recording Only" heading.
    public static func syntheticListBounds(in settingsWindow: CGRect) -> CGRect {
        // Fractions of window height from the top edge (AppKit maxY).
        // ~title+desc+2–3 rows+± — measured against macOS 15 Screen Recording pane.
        let top = settingsWindow.maxY - settingsWindow.height * 0.16
        let bottom = settingsWindow.maxY - settingsWindow.height * 0.34
        return detailPaneListFrame(in: settingsWindow, top: top, bottom: bottom)
    }

    /// Card floor under the last Screen Recording toggle (+/− allowance).
    /// `audioSectionTop` is the AppKit maxY of "System Audio Recording Only" when known —
    /// used only as a clamp so we never extend into the audio-only list.
    public static func screenRecordingListBottom(
        clusterMinY: CGFloat,
        audioSectionTop: CGFloat?,
        plusMinusAllowance: CGFloat = 36
    ) -> CGFloat {
        // Under last checkbox + +/− strip (higher AppKit y = higher on screen).
        let underCluster = clusterMinY - plusMinusAllowance
        guard let sectionTop = audioSectionTop else { return underCluster }
        // Stay strictly above the audio heading — never pin *down* to it.
        return max(underCluster, sectionTop + 16)
    }

    /// Screen that should host the bar (Settings' screen, else main).
    public static func targetScreen(for anchor: Anchor) -> NSScreen {
        let point: CGPoint
        if let list = anchor.listBounds {
            point = CGPoint(x: list.midX, y: list.midY)
        } else if let settings = anchor.settingsWindowBounds {
            point = CGPoint(x: settings.midX, y: settings.midY)
        } else {
            return NSScreen.main ?? NSScreen.screens[0]
        }
        return NSScreen.screens.first { NSMouseInRect(point, $0.frame, false) }
            ?? NSScreen.main
            ?? NSScreen.screens[0]
    }

    // MARK: AX discovery

    /// System Settings window + checkbox-list union in AppKit coordinates.
    public static func systemSettingsAnchorFromAX() -> Anchor? {
        let apps = NSRunningApplication.runningApplications(withBundleIdentifier: "com.apple.systempreferences")
            + NSRunningApplication.runningApplications(withBundleIdentifier: "com.apple.SystemSettings")
            + NSWorkspace.shared.runningApplications.filter {
                $0.localizedName == "System Settings" || $0.localizedName == "System Preferences"
            }
        guard let pid = apps.first?.processIdentifier else { return nil }

        let appEl = AXUIElementCreateApplication(pid)
        guard let windows = axArray(appEl, kAXWindowsAttribute) else { return nil }

        var bestWindow: CGRect?
        var bestList: CGRect?

        for window in windows {
            guard let role = axString(window, kAXRoleAttribute), role == kAXWindowRole as String else { continue }
            guard let winFrame = axFrameAppKit(window) else { continue }
            guard winFrame.width > 200, winFrame.height > 200 else { continue }

            // Prefer the privacy / screen-recording window when titled that way.
            let title = axString(window, kAXTitleAttribute) ?? ""
            let isPrivacy =
                title.localizedCaseInsensitiveContains("Screen")
                || title.localizedCaseInsensitiveContains("Privacy")
                || title.localizedCaseInsensitiveContains("Recording")
                || title.localizedCaseInsensitiveContains("Security")

            if bestWindow == nil || isPrivacy {
                bestWindow = winFrame
            }
            if !isPrivacy, bestWindow != nil, bestList != nil { continue }

            // Union of AXCheckBox frames in this window (the app list toggles).
            // IMPORTANT: the Screen Recording pane has TWO lists —
            // 1) Screen & System Audio Recording (apps) — top
            // 2) System Audio Recording Only — lower
            // Dock only to the top list; use the "System Audio…" heading as a hard floor.
            if let list = screenRecordingAppListBounds(in: window, windowFrame: winFrame) {
                if isPrivacy || bestList == nil {
                    bestList = list
                    bestWindow = winFrame
                }
            }
        }

        guard bestWindow != nil || bestList != nil else { return nil }
        return Anchor(listBounds: bestList, settingsWindowBounds: bestWindow)
    }

    /// Bounds of the *Screen Recording apps* list only (not System Audio Only).
    public static func screenRecordingAppListBounds(
        in root: AXUIElement,
        windowFrame: CGRect
    ) -> CGRect? {
        // Hard separator: "System Audio Recording Only" heading. AppKit maxY = top of that
        // label; Screen Recording app rows sit entirely above it.
        // Match only the *second* section heading — never "Screen & System Audio Recording".
        let audioSectionTop = staticTextTopY(
            in: root,
            matching: {
                let t = $0
                if t.localizedCaseInsensitiveContains("System Audio Recording Only") {
                    return true
                }
                // Locale fallbacks: must include "Only" / not the Screen&… title.
                return t.localizedCaseInsensitiveContains("System Audio")
                    && t.localizedCaseInsensitiveContains("Only")
                    && !t.localizedCaseInsensitiveContains("Screen &")
            }
        )

        var boxes: [CGRect] = []
        collectRoles(root, role: kAXCheckBoxRole as String, depth: 0, into: &boxes)
        // Detail pane only; discard toggles at/below the audio-only section.
        let detailBoxes = boxes.filter { box in
            guard box.midX > windowFrame.midX - 20 else { return false }
            if let sectionTop = audioSectionTop {
                return box.minY > sectionTop + 4  // fully above the audio heading
            }
            return true
        }

        if let cluster = topCheckboxCluster(detailBoxes) {
            // Always use the detail-pane card width (matches Settings list card).
            // Do not derive x from checkbox frames — those sit on the far right and
            // previously collapsed the bar to ~half width.
            let top = cluster.maxY + 8
            let bottom = screenRecordingListBottom(
                clusterMinY: cluster.minY,
                audioSectionTop: audioSectionTop
            )
            return detailPaneListFrame(in: windowFrame, top: top, bottom: bottom)
        }

        // Fallback: topmost short right-hand scroll area above the audio heading.
        var areas: [CGRect] = []
        collectRoles(root, role: kAXScrollAreaRole as String, depth: 0, into: &areas)
        let candidates = areas.filter { area in
            guard area.minX > windowFrame.midX - 40 else { return false }
            guard area.width > 160, area.height > 40, area.height < windowFrame.height * 0.40 else {
                return false
            }
            if let sectionTop = audioSectionTop {
                return area.minY >= sectionTop + 4
            }
            return area.midY > windowFrame.midY
        }
        return candidates.max(by: { $0.maxY < $1.maxY })
    }

    /// Whether Settings' Screen Recording *apps* list has the helper **enabled**.
    /// - `true`: row above System Audio section **and** its toggle is on
    /// - `false`: Settings readable but row missing **or** toggle off
    /// - `nil`: Settings / AX not inspectable
    public static func helperListedInScreenRecordingApps(
        matching nameFragments: [String] = [
            "Spectre Capture Helper",
            "SpectreCaptureHelper",
            "spectre-screencapture",
            "SpectreCaptureHelper.app",
        ]
    ) -> Bool? {
        let apps = NSRunningApplication.runningApplications(withBundleIdentifier: "com.apple.systempreferences")
            + NSRunningApplication.runningApplications(withBundleIdentifier: "com.apple.SystemSettings")
            + NSWorkspace.shared.runningApplications.filter {
                $0.localizedName == "System Settings" || $0.localizedName == "System Preferences"
            }
        guard let pid = apps.first?.processIdentifier else { return nil }
        let appEl = AXUIElementCreateApplication(pid)
        guard let windows = axArray(appEl, kAXWindowsAttribute), !windows.isEmpty else {
            return nil
        }

        var inspected = false
        for window in windows {
            var texts: [(String, CGRect)] = []
            collectStaticTexts(window, depth: 0, into: &texts)
            if texts.isEmpty { continue }
            inspected = true
            let audioSectionTop = staticTextTopY(
                in: window,
                matching: {
                    $0.localizedCaseInsensitiveContains("System Audio Recording Only")
                        || ($0.localizedCaseInsensitiveContains("System Audio")
                            && $0.localizedCaseInsensitiveContains("Only")
                            && !$0.localizedCaseInsensitiveContains("Screen &"))
                }
            )
            var checkboxes: [(frame: CGRect, enabled: Bool)] = []
            collectCheckboxes(window, depth: 0, into: &checkboxes)
            let labels = texts.map { (text: $0.0, frame: $0.1) }
            if isHelperEnabledInScreenRecordingList(
                labels: labels,
                checkboxes: checkboxes,
                nameFragments: nameFragments,
                audioSectionTop: audioSectionTop
            ) {
                return true
            }
        }
        // Readable UI without an *enabled* helper row → not granted in the list.
        return inspected ? false : nil
    }

    /// Pure: helper name above the audio section with a same-row toggle that is on.
    public static func isHelperEnabledInScreenRecordingList(
        labels: [(text: String, frame: CGRect)],
        checkboxes: [(frame: CGRect, enabled: Bool)],
        nameFragments: [String],
        audioSectionTop: CGFloat?,
        rowYTolerance: CGFloat = 28
    ) -> Bool {
        let matches = labels.filter { label in
            nameFragments.contains { frag in
                label.text.localizedCaseInsensitiveContains(frag)
            }
        }
        let inScreenSection = matches.filter { label in
            guard let sectionTop = audioSectionTop else { return true }
            return label.frame.minY > sectionTop + 4
        }
        guard !inScreenSection.isEmpty else { return false }

        for label in inScreenSection {
            // Pair with the checkbox on the same row (similar midY).
            let rowBoxes = checkboxes.filter {
                abs($0.frame.midY - label.frame.midY) <= rowYTolerance
            }
            if rowBoxes.contains(where: \.enabled) {
                return true
            }
            // Name present but toggle off (or no checkbox found) → not enabled.
        }
        return false
    }

    /// Back-compat alias used by older tests — name present above audio section only.
    public static func isHelperNameInScreenRecordingLabels(
        labels: [(text: String, frame: CGRect)],
        nameFragments: [String],
        audioSectionTop: CGFloat?
    ) -> Bool {
        let matches = labels.filter { label in
            nameFragments.contains { frag in
                label.text.localizedCaseInsensitiveContains(frag)
            }
        }
        guard !matches.isEmpty else { return false }
        guard let sectionTop = audioSectionTop else { return true }
        return matches.contains { $0.frame.minY > sectionTop + 4 }
    }

    /// AppKit maxY (top edge) of a matching static text, if any.
    private static func staticTextTopY(
        in root: AXUIElement,
        matching predicate: (String) -> Bool
    ) -> CGFloat? {
        var texts: [(String, CGRect)] = []
        collectStaticTexts(root, depth: 0, into: &texts)
        return texts
            .filter { predicate($0.0) }
            .map(\.1.maxY)
            .max()  // topmost matching label
    }

    private static func collectStaticTexts(
        _ el: AXUIElement,
        depth: Int,
        into out: inout [(String, CGRect)]
    ) {
        guard depth < 18 else { return }
        if axString(el, kAXRoleAttribute) == (kAXStaticTextRole as String),
            let frame = axFrameAppKit(el)
        {
            let value =
                axString(el, kAXValueAttribute)
                ?? axString(el, kAXTitleAttribute)
                ?? axString(el, kAXDescriptionAttribute)
                ?? ""
            if !value.isEmpty {
                out.append((value, frame))
            }
        }
        guard let children = axArray(el, kAXChildrenAttribute) else { return }
        for child in children {
            collectStaticTexts(child, depth: depth + 1, into: &out)
        }
    }

    /// Group checkboxes into vertical clusters (section breaks are large Y gaps).
    /// Returns the topmost cluster — Screen Recording apps, not System Audio Only.
    public static func topCheckboxCluster(
        _ boxes: [CGRect],
        maxRowGap: CGFloat = 56
    ) -> CGRect? {
        guard !boxes.isEmpty else { return nil }
        // Sort by top edge descending (AppKit: higher maxY = higher on screen).
        let sorted = boxes.sorted { $0.maxY > $1.maxY }
        var cluster: [CGRect] = [sorted[0]]
        for box in sorted.dropFirst() {
            let prev = cluster[cluster.count - 1]
            // Distance between bottom of higher row and top of lower row.
            let gap = prev.minY - box.maxY
            if gap > maxRowGap {
                break  // next section (e.g. System Audio Only)
            }
            cluster.append(box)
        }
        return cluster.dropFirst().reduce(cluster[0]) { $0.union($1) }
    }

    private static func collectRoles(
        _ el: AXUIElement,
        role: String,
        depth: Int,
        into frames: inout [CGRect]
    ) {
        guard depth < 18 else { return }
        if axString(el, kAXRoleAttribute) == role, let frame = axFrameAppKit(el) {
            frames.append(frame)
        }
        guard let children = axArray(el, kAXChildrenAttribute) else { return }
        for child in children {
            collectRoles(child, role: role, depth: depth + 1, into: &frames)
        }
    }

    /// Collect AXCheckBox frames + on/off state (Screen Recording toggles).
    private static func collectCheckboxes(
        _ el: AXUIElement,
        depth: Int,
        into out: inout [(frame: CGRect, enabled: Bool)]
    ) {
        guard depth < 18 else { return }
        if axString(el, kAXRoleAttribute) == (kAXCheckBoxRole as String),
            let frame = axFrameAppKit(el)
        {
            out.append((frame, axCheckboxIsOn(el)))
        }
        guard let children = axArray(el, kAXChildrenAttribute) else { return }
        for child in children {
            collectCheckboxes(child, depth: depth + 1, into: &out)
        }
    }

    /// AX checkbox value: "1" / 1 / true → on.
    private static func axCheckboxIsOn(_ el: AXUIElement) -> Bool {
        var value: CFTypeRef?
        guard AXUIElementCopyAttributeValue(el, kAXValueAttribute as CFString, &value) == .success,
            let value
        else {
            return false
        }
        if let n = value as? NSNumber {
            return n.intValue != 0
        }
        if let s = value as? String {
            return s == "1" || s.localizedCaseInsensitiveContains("true")
                || s.localizedCaseInsensitiveContains("on")
                || s.localizedCaseInsensitiveContains("checked")
        }
        if let b = value as? Bool {
            return b
        }
        return false
    }

    private static func axArray(_ el: AXUIElement, _ attr: String) -> [AXUIElement]? {
        var value: CFTypeRef?
        guard AXUIElementCopyAttributeValue(el, attr as CFString, &value) == .success,
            let arr = value as? [AXUIElement]
        else {
            return nil
        }
        return arr
    }

    private static func axString(_ el: AXUIElement, _ attr: String) -> String? {
        var value: CFTypeRef?
        guard AXUIElementCopyAttributeValue(el, attr as CFString, &value) == .success else {
            return nil
        }
        return value as? String
    }

    /// AX position is top-left; convert to AppKit bottom-left global.
    private static func axFrameAppKit(_ el: AXUIElement) -> CGRect? {
        var posValue: CFTypeRef?
        var sizeValue: CFTypeRef?
        guard AXUIElementCopyAttributeValue(el, kAXPositionAttribute as CFString, &posValue) == .success,
            AXUIElementCopyAttributeValue(el, kAXSizeAttribute as CFString, &sizeValue) == .success
        else {
            return nil
        }
        var pos = CGPoint.zero
        var size = CGSize.zero
        // AXValue is not bridged cleanly; use AXValueGetValue.
        guard let posRef = posValue, let sizeRef = sizeValue else { return nil }
        // swift-format-ignore
        let posOK = AXValueGetValue(posRef as! AXValue, .cgPoint, &pos)
        let sizeOK = AXValueGetValue(sizeRef as! AXValue, .cgSize, &size)
        guard posOK, sizeOK, size.width > 0, size.height > 0 else { return nil }

        // System Events / AX: y grows downward from top of main display.
        // AppKit: y grows upward from bottom of main display.
        let main = NSScreen.screens.first(where: { $0.frame.origin == .zero }) ?? NSScreen.main
            ?? NSScreen.screens[0]
        let cocoaY = main.frame.maxY - pos.y - size.height
        let cocoaX = pos.x + main.frame.minX
        return CGRect(x: cocoaX, y: cocoaY, width: size.width, height: size.height)
    }

    // MARK: CGWindowList fallback

    public static func systemSettingsWindowBoundsFromCG() -> CGRect? {
        guard
            let infoList = CGWindowListCopyWindowInfo(
                [.optionOnScreenOnly, .excludeDesktopElements],
                kCGNullWindowID
            ) as? [[String: Any]]
        else {
            return nil
        }

        for info in infoList {
            let owner = info[kCGWindowOwnerName as String] as? String ?? ""
            guard owner == "System Settings" || owner == "System Preferences" else { continue }
            let layer = info[kCGWindowLayer as String] as? Int ?? 0
            guard layer == 0 else { continue }
            guard let boundsDict = info[kCGWindowBounds as String] as? NSDictionary,
                let cgRect = CGRect(dictionaryRepresentation: boundsDict)
            else {
                continue
            }
            guard cgRect.width > 100, cgRect.height > 100 else { continue }
            return cgWindowBoundsToAppKit(cgRect)
        }
        return nil
    }

    /// CGWindow bounds: origin top-left of main display.
    public static func cgWindowBoundsToAppKit(_ cg: CGRect) -> CGRect {
        let main = NSScreen.screens.first(where: { $0.frame.origin == .zero }) ?? NSScreen.main
            ?? NSScreen.screens[0]
        let cocoaY = main.frame.maxY - cg.origin.y - cg.height
        return CGRect(x: cg.origin.x, y: cocoaY, width: cg.width, height: cg.height)
    }

    /// On-screen window snapshot used for system-UI detection (testable).
    public struct OnScreenWindow: Equatable {
        public var owner: String
        public var name: String
        public var layer: Int
        public var bounds: CGRect

        public init(owner: String, name: String = "", layer: Int = 0, bounds: CGRect) {
            self.owner = owner
            self.name = name
            self.layer = layer
            self.bounds = bounds
        }
    }

    /// Touch ID, password, and System Settings sheets/alerts — bar must yield (hide).
    public static func isAuthenticationUIVisible() -> Bool {
        shouldYieldToSystemUI(windows: onScreenWindows())
    }

    public static func onScreenWindows() -> [OnScreenWindow] {
        guard
            let infoList = CGWindowListCopyWindowInfo(
                [.optionOnScreenOnly, .excludeDesktopElements],
                kCGNullWindowID
            ) as? [[String: Any]]
        else {
            return []
        }
        var result: [OnScreenWindow] = []
        for info in infoList {
            let owner = info[kCGWindowOwnerName as String] as? String ?? ""
            let name = info[kCGWindowName as String] as? String ?? ""
            let layer = info[kCGWindowLayer as String] as? Int ?? 0
            guard let boundsDict = info[kCGWindowBounds as String] as? NSDictionary,
                let cgRect = CGRect(dictionaryRepresentation: boundsDict),
                cgRect.width > 2,
                cgRect.height > 2
            else {
                continue
            }
            result.append(
                OnScreenWindow(owner: owner, name: name, layer: layer, bounds: cgRect)
            )
        }
        return result
    }

    /// Pure: whether the guide HUD should hide so system UI is not obscured.
    public static func shouldYieldToSystemUI(windows: [OnScreenWindow]) -> Bool {
        let authOwners: Set<String> = [
            "SecurityAgent",
            "coreautha",
            "coreauthd",
            "localauthenticationd",
        ]
        for w in windows {
            let owner = w.owner
            if authOwners.contains(owner)
                || owner.localizedCaseInsensitiveContains("LocalAuthentication")
                || owner.localizedCaseInsensitiveContains("SecurityAgent")
            {
                // Ignore tiny helper surfaces; real auth sheets are sizable.
                if w.bounds.width > 160, w.bounds.height > 80 {
                    return true
                }
            }
            // Named auth / quit-reopen surfaces (when CG exposes the title).
            let n = w.name
            if n.localizedCaseInsensitiveContains("Touch ID")
                || n.localizedCaseInsensitiveContains("Use Password")
                || n.localizedCaseInsensitiveContains("reopen")
                || n.localizedCaseInsensitiveContains("quit")
            {
                if w.bounds.width > 160, w.bounds.height > 80 {
                    return true
                }
            }
        }

        // System Settings sheets / alerts: extra windows or elevated layer.
        // Touch ID and "quit & reopen" are Settings-owned dialogs, not always SecurityAgent.
        let settings = windows.filter {
            $0.owner == "System Settings" || $0.owner == "System Preferences"
        }
        if settings.contains(where: { $0.layer != 0 }) {
            return true
        }
        if settings.count >= 2 {
            // Main privacy window is large; sheets/alerts are smaller overlays.
            let sorted = settings.sorted {
                ($0.bounds.width * $0.bounds.height) > ($1.bounds.width * $1.bounds.height)
            }
            if let main = sorted.first {
                for dialog in sorted.dropFirst() {
                    let area = dialog.bounds.width * dialog.bounds.height
                    let mainArea = main.bounds.width * main.bounds.height
                    // Dialog-sized secondary Settings window (Touch ID, quit/reopen, etc.).
                    if area < mainArea * 0.55,
                        dialog.bounds.width > 200,
                        dialog.bounds.height > 120
                    {
                        return true
                    }
                }
            }
        }
        return false
    }

    /// Whether two frames differ enough to warrant a setFrame (deadband vs AX jitter).
    public static func frameChangedMeaningfully(
        _ a: CGRect,
        _ b: CGRect,
        originThreshold: CGFloat = 6,
        sizeThreshold: CGFloat = 8
    ) -> Bool {
        abs(a.origin.x - b.origin.x) >= originThreshold
            || abs(a.origin.y - b.origin.y) >= originThreshold
            || abs(a.width - b.width) >= sizeThreshold
            || abs(a.height - b.height) >= sizeThreshold
    }

    /// Settings window origin moved enough that the bar should follow (user drag).
    public static func settingsOriginMoved(
        _ a: CGPoint,
        _ b: CGPoint,
        threshold: CGFloat = 4
    ) -> Bool {
        abs(a.x - b.x) >= threshold || abs(a.y - b.y) >= threshold
    }
}

// MARK: - App entry

public enum PermissionGuideApp {
    @MainActor
    public static func run(binaryPath: String, reapproval: Bool) {
        let app = NSApplication.shared
        app.setActivationPolicy(.accessory)

        let appURL = Bundle.main.bundleURL
        let model = PermissionGuideModel(
            binaryPath: binaryPath,
            reapproval: reapproval,
            preflight: { CGPreflightScreenCaptureAccess() },
            openSettings: {
                if let url = URL(string: ScreenCaptureAccess.deepLink) {
                    NSWorkspace.shared.open(url)
                }
            },
            appURL: appURL
        )

        let rootView = PermissionGuideBarView(model: model)
        let hosting = NSHostingController(rootView: rootView)
        hosting.view.wantsLayer = true
        hosting.view.autoresizingMask = [.width, .height]

        let window = NSPanel(
            contentRect: NSRect(origin: .zero, size: PermissionGuidePlacement.defaultBarSize),
            styleMask: [.borderless, .nonactivatingPanel, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        // Floating above normal apps, but never orderFrontRegardless (that jumps over
        // Touch ID / Settings sheets). Hide entirely while system UI is up.
        window.isFloatingPanel = true
        window.level = .floating
        window.hidesOnDeactivate = false
        window.worksWhenModal = false
        window.isOpaque = false
        window.backgroundColor = .clear
        window.hasShadow = true
        window.title = PermissionGuideCopy.windowTitle
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.isMovableByWindowBackground = true
        // Stay on the Space where Settings lives. Never moveToActiveSpace /
        // canJoinAllSpaces — those orphan the bar on virtual desktops without Settings.
        window.collectionBehavior = [.transient, .fullScreenAuxiliary]
        window.contentViewController = hosting
        window.isReleasedWhenClosed = false

        // Open Settings first so we can dock to its list (Codex pattern).
        model.openSystemSettings()

        /// Pixel-snapped dock frame. Nil if Settings is not on-screen (hide the bar).
        /// `settingsOrigin` always comes from CGWindowList (stable), never AX (jitters).
        func computeDockedFrame() -> (frame: CGRect, settingsOrigin: CGPoint)? {
            // Prefer CG for the Settings *window* origin we track for follow-move.
            // AX list bounds are fine for finer docking but oscillate and must not
            // drive the "did Settings move?" signal.
            guard let cgSettings = PermissionGuidePlacement.systemSettingsWindowBoundsFromCG()
            else {
                return nil
            }
            var anchor = PermissionGuidePlacement.resolveAnchor()
            // Ensure settings bounds are the stable CG ones when present.
            anchor.settingsWindowBounds = cgSettings
            if anchor.listBounds == nil {
                anchor.listBounds = PermissionGuidePlacement.syntheticListBounds(in: cgSettings)
            }
            let screen = PermissionGuidePlacement.targetScreen(for: anchor)
            let size = PermissionGuidePlacement.barSize(for: anchor)
            let frame = PermissionGuidePlacement.barFrame(
                anchor: anchor,
                visibleFrame: screen.visibleFrame,
                barSize: size
            )
            let snapped = CGRect(
                x: frame.origin.x.rounded(),
                y: frame.origin.y.rounded(),
                width: frame.size.width.rounded(),
                height: frame.size.height.rounded()
            )
            let origin = CGPoint(
                x: cgSettings.origin.x.rounded(),
                y: cgSettings.origin.y.rounded()
            )
            return (snapped, origin)
        }

        // Use a ref box so the timer can mutate without MainActor capture issues.
        final class PlacementState {
            var lastSettingsOrigin: CGPoint?
            var lastAppliedFrame: CGRect?
            /// Touch ID / password / quit-reopen — bar must stay ordered out.
            var hiddenForSystemUI = false
            /// True when Settings is off this Space/display — bar must stay ordered out.
            var hiddenWithoutSettings = false
        }
        let state = PlacementState()

        func hideBar() {
            window.orderOut(nil)
        }

        /// Show without stealing z-order over system sheets (never orderFrontRegardless).
        func showBar() {
            window.alphaValue = 1
            window.orderFront(nil)
        }

        func applyFrame(_ frame: CGRect, animated: Bool, force: Bool = false) {
            if !force,
                let last = state.lastAppliedFrame,
                !PermissionGuidePlacement.frameChangedMeaningfully(last, frame)
            {
                return
            }
            state.lastAppliedFrame = frame
            model.barWidth = frame.width
            if state.hiddenForSystemUI || state.hiddenWithoutSettings {
                // Keep geometry current while hidden; stay ordered out.
                window.setFrame(frame, display: false)
                return
            }
            if animated {
                window.setFrame(frame.offsetBy(dx: 0, dy: -8), display: false)
                window.alphaValue = 0
                showBar()
                NSAnimationContext.runAnimationGroup { ctx in
                    ctx.duration = 0.2
                    ctx.timingFunction = CAMediaTimingFunction(name: .easeOut)
                    window.animator().alphaValue = 1
                    window.animator().setFrame(frame, display: true)
                }
            } else {
                NSAnimationContext.beginGrouping()
                NSAnimationContext.current.duration = 0
                window.setFrame(frame, display: true)
                NSAnimationContext.endGrouping()
                showBar()
            }
        }

        func updateSystemUIVisibility() {
            let systemUIUp = PermissionGuidePlacement.isAuthenticationUIVisible()
            if systemUIUp, !state.hiddenForSystemUI {
                state.hiddenForSystemUI = true
                hideBar()
            } else if !systemUIUp, state.hiddenForSystemUI {
                state.hiddenForSystemUI = false
                if !state.hiddenWithoutSettings, let frame = state.lastAppliedFrame {
                    window.setFrame(frame, display: true)
                    showBar()
                }
            }
        }

        // --- Initial place: wait for Settings; never orphan a center fallback ---
        let waitDeadline = Date().addingTimeInterval(3.0)
        var placed = false
        while Date() < waitDeadline, !placed {
            if let docked = computeDockedFrame() {
                state.lastSettingsOrigin = docked.settingsOrigin
                state.hiddenWithoutSettings = false
                applyFrame(docked.frame, animated: true, force: true)
                placed = true
                break
            }
            RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.1))
        }
        if !placed {
            // Settings not visible yet (other Space / still launching). Stay hidden.
            state.hiddenWithoutSettings = true
            hideBar()
        }

        // --- Follow Settings; hide for system sheets / when Settings leaves Space ---
        // 0.15s so Touch ID / quit-reopen get the bar out of the way quickly.
        let poll = Timer.scheduledTimer(withTimeInterval: 0.15, repeats: true) { _ in
            updateSystemUIVisibility()
            guard let docked = computeDockedFrame() else {
                // Settings gone from on-screen window list (other Space, closed, etc.).
                if !state.hiddenWithoutSettings {
                    state.hiddenWithoutSettings = true
                    state.lastSettingsOrigin = nil
                    hideBar()
                }
                return
            }
            let wasHidden = state.hiddenWithoutSettings
            state.hiddenWithoutSettings = false
            let settingsMoved: Bool
            if let lastOrigin = state.lastSettingsOrigin {
                settingsMoved = PermissionGuidePlacement.settingsOriginMoved(
                    lastOrigin,
                    docked.settingsOrigin
                )
            } else {
                settingsMoved = true
            }
            let frameMoved =
                state.lastAppliedFrame.map {
                    PermissionGuidePlacement.frameChangedMeaningfully($0, docked.frame)
                } ?? true
            if wasHidden || settingsMoved || frameMoved {
                state.lastSettingsOrigin = docked.settingsOrigin
                applyFrame(
                    docked.frame,
                    animated: wasHidden,
                    force: wasHidden || settingsMoved
                )
            } else {
                state.lastSettingsOrigin = docked.settingsOrigin
            }
        }
        RunLoop.main.add(poll, forMode: .common)

        app.activate(ignoringOtherApps: false)

        // Wire dismiss before startPolling so an already-granted preflight can still show
        // the bar (we already placed above) then auto-close.
        model.onFinished = { _ in
            poll.invalidate()
            // Exit code / JSON always reflect real TCC preflight, not HUD state.
            let granted = CGPreflightScreenCaptureAccess()
            let result = ScreenCaptureAccess.result(granted: granted, binaryPath: binaryPath)
            FileHandle.standardOutput.write(Data(result.jsonLine.utf8))
            exit(granted ? 0 : 6)
        }
        model.onGranted = {
            // HUD reached Done — brief confirmation, then exit (cancellable if revoked).
            model.scheduleAutoFinish(after: 1.6)
        }
        model.onRevoked = {
            // Permission removed while Done was showing — stay open on Waiting.
            model.cancelAutoFinish()
        }

        NotificationCenter.default.addObserver(
            forName: NSWindow.willCloseNotification,
            object: window,
            queue: .main
        ) { _ in
            poll.invalidate()
            let granted = CGPreflightScreenCaptureAccess()
            let result = ScreenCaptureAccess.result(granted: granted, binaryPath: binaryPath)
            FileHandle.standardOutput.write(Data(result.jsonLine.utf8))
            exit(granted ? 0 : 6)
        }

        // Guide is drag-first. Do not call CGRequestScreenCaptureAccess here — that
        // system prompt is a separate path (--mode request) and can flash over Settings.
        model.startPolling()

        app.run()
        exit(6)
    }
}

@MainActor
final class PermissionGuideModel: ObservableObject {
    @Published var poll = PermissionGuidePollState()
    /// Live width matched to the Settings list card (updates as we resolve AX geometry).
    @Published var barWidth: CGFloat = PermissionGuidePlacement.defaultBarSize.width
    let reapproval: Bool
    let binaryPath: String
    let appURL: URL
    var onFinished: ((Bool) -> Void)?
    /// Rising edge of HUD Done (start auto-finish countdown).
    var onGranted: (() -> Void)?
    /// Falling edge back to Waiting (cancel auto-finish).
    var onRevoked: (() -> Void)?

    private let preflight: () -> Bool
    private let listCheck: () -> Bool?
    private let openSettings: () -> Void
    private var timer: Timer?
    private var autoFinishWork: DispatchWorkItem?

    init(
        binaryPath: String,
        reapproval: Bool,
        preflight: @escaping () -> Bool,
        openSettings: @escaping () -> Void,
        appURL: URL,
        listCheck: @escaping () -> Bool? = {
            PermissionGuidePlacement.helperListedInScreenRecordingApps()
        }
    ) {
        self.binaryPath = binaryPath
        self.reapproval = reapproval
        self.preflight = preflight
        self.openSettings = openSettings
        self.appURL = appURL
        self.listCheck = listCheck
    }

    func startPolling() {
        timer?.invalidate()
        tick()
        // Keep polling for the whole guide lifetime so revoke → Waiting again.
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self else { return }
            MainActor.assumeIsolated { self.tick() }
        }
    }

    func tick() {
        let preflightGranted = preflight()
        let listed = listCheck()
        let wasGranted = poll.granted
        poll.apply(preflight: preflightGranted, listedInSettings: listed)
        if poll.granted, !wasGranted {
            onGranted?()
        } else if !poll.granted, wasGranted {
            onRevoked?()
        }
    }

    func scheduleAutoFinish(after delay: TimeInterval) {
        cancelAutoFinish()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            // Only exit if still granted when the delay elapses.
            if self.poll.granted {
                self.finish()
            }
        }
        autoFinishWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    func cancelAutoFinish() {
        autoFinishWork?.cancel()
        autoFinishWork = nil
    }

    func openSystemSettings() {
        openSettings()
    }

    func finish() {
        cancelAutoFinish()
        timer?.invalidate()
        timer = nil
        // Prefer raw preflight for the finished callback / exit path.
        onFinished?(preflight())
    }
}

// MARK: - Compact chrome-less bar

struct PermissionGuideBarView: View {
    @ObservedObject var model: PermissionGuideModel

    private let outerPad: CGFloat = 12
    private let innerPad: CGFloat = 10

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "arrow.up")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(.blue)
                    .frame(width: 20, alignment: .center)
                    .padding(.top, 1)
                    .accessibilityHidden(true)

                Text(PermissionGuideCopy.dragHint)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)

                // Small dismiss (Codex tip has no fat footer button row).
                Button {
                    model.finish()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(.secondary)
                        .frame(width: 20, height: 20)
                        .background(Circle().fill(Color.primary.opacity(0.08)))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(
                    model.poll.granted ? PermissionGuideCopy.doneLabel : PermissionGuideCopy.closeLabel
                )
                .keyboardShortcut(.cancelAction)
            }

            // Drop-slot with real draggable app.
            HStack(spacing: 10) {
                AppIconDragView(appURL: model.appURL)
                    .frame(width: 32, height: 32)
                    .accessibilityLabel(PermissionGuideCopy.dragSlotAccessibility)
                    .accessibilityHint(PermissionGuideCopy.dragHint)

                Text(PermissionGuideCopy.windowTitle)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)

                Spacer(minLength: 8)

                if model.poll.granted {
                    Text(PermissionGuideCopy.grantedLabel)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.green)
                } else {
                    ProgressView()
                        .controlSize(.small)
                    Text(PermissionGuideCopy.waitingLabel)
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, innerPad)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Color(nsColor: .textBackgroundColor).opacity(0.95))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(Color.primary.opacity(0.08), lineWidth: 1)
            )
        }
        .padding(outerPad)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(.regularMaterial)
                .shadow(color: .black.opacity(0.22), radius: 14, y: 5)
        )
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .padding(3)
        // Fill the NSPanel; panel width tracks the Settings list card.
        .frame(width: max(model.barWidth - 6, 340), alignment: .topLeading)
    }
}

/// AppKit-backed draggable icon so Privacy Settings receives a real `.app` file URL.
struct AppIconDragView: NSViewRepresentable {
    let appURL: URL

    func makeNSView(context: Context) -> DraggableAppIconNSView {
        let view = DraggableAppIconNSView()
        view.configure(appURL: appURL)
        return view
    }

    func updateNSView(_ nsView: DraggableAppIconNSView, context: Context) {
        nsView.configure(appURL: appURL)
    }
}

final class DraggableAppIconNSView: NSImageView {
    private(set) var appURL: URL?

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        imageScaling = .scaleProportionallyUpOrDown
        isEditable = false
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        imageScaling = .scaleProportionallyUpOrDown
        isEditable = false
    }

    func configure(appURL: URL) {
        self.appURL = appURL
        image = NSWorkspace.shared.icon(forFile: appURL.path)
        toolTip = PermissionGuideCopy.dragHint
    }

    override var mouseDownCanMoveWindow: Bool { false }

    override func mouseDown(with event: NSEvent) {
        guard let appURL, let image else { return }
        let writers = PermissionGuideDragPayload.pasteboardWriters(for: appURL)
        let item = NSDraggingItem(pasteboardWriter: writers[0])
        let size = bounds.size
        let origin = convert(event.locationInWindow, from: nil)
        let frame = NSRect(
            x: origin.x - size.width / 2,
            y: origin.y - size.height / 2,
            width: size.width,
            height: size.height
        )
        item.setDraggingFrame(frame, contents: image)
        beginDraggingSession(with: [item], event: event, source: self)
    }
}

extension DraggableAppIconNSView: NSDraggingSource {
    func draggingSession(
        _ session: NSDraggingSession,
        sourceOperationMaskFor context: NSDraggingContext
    ) -> NSDragOperation {
        .copy
    }
}
