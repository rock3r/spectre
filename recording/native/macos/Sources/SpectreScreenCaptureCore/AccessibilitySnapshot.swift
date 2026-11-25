import AppKit
import ApplicationServices
import CoreGraphics
import Foundation

private let attributes: [String] = [
    kAXRoleAttribute, kAXSubroleAttribute, kAXTitleAttribute, kAXDescriptionAttribute, "AXLabel",
    kAXValueAttribute, kAXEnabledAttribute, kAXFocusedAttribute, kAXSelectedAttribute,
    kAXPositionAttribute, kAXSizeAttribute, kAXIdentifierAttribute, kAXChildrenAttribute,
]
private let accessibilitySettingsPath = "System Settings → Privacy & Security → Accessibility"
let accessibilityDeepLink = "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"

enum AccessibilityTarget: Equatable {
    case processID(pid_t)
    case windowID(String)
    case windowTitle(String, processID: pid_t?)
}

struct AccessibilitySnapshotRequest: Equatable {
    let target: AccessibilityTarget
    let maximumDepth: Int
    let maximumNodeCount: Int
    let timeoutMs: Int

    static func decode(_ data: Data) throws -> Self {
        let wire = try JSONDecoder().decode(WireRequest.self, from: data)
        guard wire.maximumDepth > 0 else { throw RequestError("maximumDepth must be positive") }
        guard wire.maximumNodeCount > 0 else { throw RequestError("maximumNodeCount must be positive") }
        guard wire.timeoutMs > 0 else { throw RequestError("timeoutMs must be positive") }
        let target: AccessibilityTarget
        let targetProcessID = try wire.target.processId.map { value in
            guard value > 0, value <= Int64(Int32.max) else {
                throw RequestError("target processId must be a positive OS PID")
            }
            return pid_t(value)
        }
        switch wire.target.type {
        case "process-id":
            guard case let .integer(value) = wire.target.value, value > 0, value <= Int64(Int32.max)
            else { throw RequestError("process-id value must be a positive OS PID") }
            target = .processID(pid_t(value))
        case "window-id":
            guard case let .string(value) = wire.target.value, !value.isEmpty else {
                throw RequestError("window-id value must be a non-empty string")
            }
            target = .windowID(value)
        case "window-title":
            guard case let .string(value) = wire.target.value, !value.isEmpty else {
                throw RequestError("window-title value must be a non-empty string")
            }
            target = .windowTitle(value, processID: targetProcessID)
        default: throw RequestError("unsupported target type \(wire.target.type)")
        }
        return Self(target: target, maximumDepth: wire.maximumDepth,
                    maximumNodeCount: wire.maximumNodeCount, timeoutMs: wire.timeoutMs)
    }
}

private struct RequestError: Error, CustomStringConvertible {
    let description: String
    init(_ description: String) { self.description = description }
}
private struct WireRequest: Decodable {
    struct Target: Decodable { let type: String; let value: JSONScalar; let processId: Int64? }
    let target: Target; let maximumDepth: Int; let maximumNodeCount: Int; let timeoutMs: Int
}
private enum JSONScalar: Decodable {
    case integer(Int64), string(String)
    init(from decoder: Decoder) throws {
        let value = try decoder.singleValueContainer()
        if let integer = try? value.decode(Int64.self) { self = .integer(integer) }
        else if let string = try? value.decode(String.self) { self = .string(string) }
        else { throw DecodingError.typeMismatch(Self.self, .init(codingPath: decoder.codingPath,
            debugDescription: "expected integer or string")) }
    }
}

struct AccessibilityBounds: Codable, Equatable {
    let x: Int; let y: Int; let width: Int; let height: Int
    func intersects(_ other: Self) -> Bool {
        width > 0 && height > 0 && other.width > 0 && other.height > 0 &&
            x < other.x + other.width && x + width > other.x &&
            y < other.y + other.height && y + height > other.y
    }
}
struct AccessibilityWindowSnapshot: Codable, Equatable {
    let key: String; let title: String?; let bounds: AccessibilityBounds
    let boundsSpace = "screen"

    enum CodingKeys: String, CodingKey { case key, title, bounds, boundsSpace }
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(key, forKey: .key)
        try container.encode(title, forKey: .title)
        try container.encode(bounds, forKey: .bounds)
        try container.encode(boundsSpace, forKey: .boundsSpace)
    }
}
struct AccessibilityNodeSnapshot: Codable, Equatable {
    let key: String; let parentKey: String?; let id: String?; let platformRole: String?
    let role: String?; let name: String?; let states: [String]; let value: String?
    let bounds: AccessibilityBounds; let onScreen: Bool

    enum CodingKeys: String, CodingKey {
        case key, parentKey, id, platformRole, role, name, states, value, bounds, onScreen
    }
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(key, forKey: .key)
        try container.encode(parentKey, forKey: .parentKey)
        try container.encode(id, forKey: .id)
        try container.encode(platformRole, forKey: .platformRole)
        try container.encode(role, forKey: .role)
        try container.encode(name, forKey: .name)
        try container.encode(states, forKey: .states)
        try container.encode(value, forKey: .value)
        try container.encode(bounds, forKey: .bounds)
        try container.encode(onScreen, forKey: .onScreen)
    }
}
struct AccessibilitySnapshot: Codable, Equatable {
    let schemaVersion = 2; let source = "os-accessibility"; let capturedAt: String
    var windows: [AccessibilityWindowSnapshot]; var nodes: [AccessibilityNodeSnapshot]
    let notes: [String]
}
enum AccessibilityCaptureError: Error {
    case targetNotFound(String), noAccessibilityTree(String)
    case limitReached(AccessibilitySnapshot, String), timeout(AccessibilitySnapshot?)
}
enum TraversalTruncation: Equatable { case depth, nodeCount }
struct LoadedAccessibilityNode {
    let id: String?; let role: String?; let subrole: String?; let title: String?
    let description: String?; let label: String?; let value: String?
    let enabled: Bool?; let focusable: Bool?; let focused: Bool?; let selected: Bool?
    let bounds: AccessibilityBounds
    let children: [LoadedAccessibilityNode]; let truncated: TraversalTruncation?
}
protocol AccessibilityTree {
    var windows: [AccessibilityWindowSnapshot] { get }
    var roots: [LoadedAccessibilityNode] { get }
    var trailingTruncation: TraversalTruncation? { get }
}
extension AccessibilityTree { var trailingTruncation: TraversalTruncation? { nil } }

struct SyntheticAccessibilityNode {
    let loaded: LoadedAccessibilityNode
    static func node(id: String?, role: String? = "AXGroup", subrole: String? = nil,
                     title: String? = nil, description: String? = nil, label: String? = nil,
                     value: String? = nil, enabled: Bool? = true, focused: Bool? = false,
                     focusable: Bool? = nil, selected: Bool? = false,
                     bounds: AccessibilityBounds = .init(x: 0, y: 0, width: 10, height: 10),
                     children: [Self] = []) -> Self {
        Self(loaded: .init(id: id, role: role, subrole: subrole, title: title,
            description: description, label: label, value: value, enabled: enabled,
            focusable: focusable, focused: focused, selected: selected, bounds: bounds,
            children: children.map(\.loaded),
            truncated: nil))
    }
}
struct SyntheticAccessibilityTree: AccessibilityTree {
    let windows: [AccessibilityWindowSnapshot]; let roots: [LoadedAccessibilityNode]
    init(windowBounds: AccessibilityBounds = .init(x: 0, y: 0, width: 100, height: 100),
         roots: [SyntheticAccessibilityNode]) {
        windows = [.init(key: "w0", title: "Fixture", bounds: windowBounds)]
        self.roots = roots.map(\.loaded)
    }
}

enum AccessibilitySnapshotBuilder {
    static func capture(tree: AccessibilityTree, maximumDepth: Int, maximumNodeCount: Int,
                        deadline: Date, now: () -> Date = Date.init) throws -> AccessibilitySnapshot {
        var snapshot = AccessibilitySnapshot(capturedAt: timestamp(), windows: tree.windows,
                                             nodes: [], notes: [])
        for (windowIndex, root) in tree.roots.enumerated() {
            try append(root, windowIndex: windowIndex, segment: Self.segment(root.id, fallback: 0),
                parentKey: nil, depth: 0, windowBounds: tree.windows[windowIndex].bounds,
                maximumDepth: maximumDepth, maximumNodeCount: maximumNodeCount,
                deadline: deadline, now: now, snapshot: &snapshot)
        }
        if tree.trailingTruncation == .nodeCount {
            throw AccessibilityCaptureError.limitReached(snapshot,
                "maximumNodeCount \(maximumNodeCount) reached")
        }
        return snapshot
    }
    private static func append(_ node: LoadedAccessibilityNode, windowIndex: Int, segment: String,
        parentKey: String?, depth: Int, windowBounds: AccessibilityBounds, maximumDepth: Int,
        maximumNodeCount: Int, deadline: Date, now: () -> Date,
        snapshot: inout AccessibilitySnapshot) throws {
        if now() >= deadline { throw AccessibilityCaptureError.timeout(snapshot) }
        if snapshot.nodes.count >= maximumNodeCount {
            throw AccessibilityCaptureError.limitReached(snapshot,
                "maximumNodeCount \(maximumNodeCount) reached")
        }
        let key = parentKey.map { "\($0)/\(segment)" } ?? "w\(windowIndex)/\(segment)"
        snapshot.nodes.append(map(node, key: key, parentKey: parentKey, windowBounds: windowBounds))
        if depth == maximumDepth, !node.children.isEmpty {
            throw AccessibilityCaptureError.limitReached(snapshot, "maximumDepth \(maximumDepth) reached")
        }
        let counts = Dictionary(grouping: node.children.compactMap(\.id), by: { $0 }).mapValues(\.count)
        for (index, child) in node.children.enumerated() {
            let unique = child.id.flatMap { counts[$0] == 1 ? $0 : nil }
            try append(child, windowIndex: windowIndex, segment: Self.segment(unique, fallback: index),
                parentKey: key, depth: depth + 1, windowBounds: windowBounds,
                maximumDepth: maximumDepth, maximumNodeCount: maximumNodeCount,
                deadline: deadline, now: now, snapshot: &snapshot)
        }
        if let truncated = node.truncated {
            let message = truncated == .depth ? "maximumDepth \(maximumDepth) reached" :
                "maximumNodeCount \(maximumNodeCount) reached"
            throw AccessibilityCaptureError.limitReached(snapshot, message)
        }
    }
    static func segment(_ id: String?, fallback: Int) -> String {
        guard let id, !id.isEmpty else { return String(fallback) }
        let escaped = id.replacingOccurrences(of: "~", with: "~0")
            .replacingOccurrences(of: "/", with: "~1")
        let isASCIIInteger = !id.isEmpty && id.utf8.allSatisfy { $0 >= 48 && $0 <= 57 }
        return isASCIIInteger ? "~2\(escaped)" : escaped
    }
    private static func map(_ node: LoadedAccessibilityNode, key: String, parentKey: String?,
                            windowBounds: AccessibilityBounds) -> AccessibilityNodeSnapshot {
        var states: [String] = []
        if let enabled = node.enabled { states.append(enabled ? "enabled" : "disabled") }
        if node.focusable == true { states.append("focusable") }
        if node.focused == true { states.append("focused") }; if node.selected == true { states.append("selected") }
        if node.role == "AXCheckBox" || node.role == "AXRadioButton" {
            if let checkedState = checkedState(node.value) { states.append(checkedState) }
        }
        let roles = ["AXButton":"button", "AXCheckBox":"checkbox", "AXRadioButton":"radio-button",
            "AXTextField":"text-field", "AXTextArea":"text-field", "AXStaticText":"text",
            "AXWindow":"window", "AXDialog":"dialog", "AXLink":"link", "AXImage":"image",
            "AXMenuItem":"menu-item", "AXSlider":"slider", "AXGroup":"group"]
        let role = node.role.map { roles[$0] ?? String($0.dropFirst($0.hasPrefix("AX") ? 2 : 0)).lowercased() }
        let name = [node.title, node.description, node.label].compactMap { $0 }.first { !$0.isEmpty }
        return .init(key: key, parentKey: parentKey, id: node.id,
            platformRole: node.subrole ?? node.role, role: role, name: name, states: states,
            value: node.value, bounds: node.bounds, onScreen: node.bounds.intersects(windowBounds))
    }
    private static func checkedState(_ value: String?) -> String? {
        switch value?.lowercased() {
        case "0", "false": return "unchecked"
        case "1", "true": return "checked"
        case "2", "mixed": return "mixed"
        default: return nil
        }
    }
    private static func timestamp() -> String {
        let formatter = ISO8601DateFormatter(); formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: Date())
    }
}

struct AccessibilityWindowIdentity: Equatable {
    let title: String?; let bounds: AccessibilityBounds?
}
enum AccessibilityWindowMatcher {
    static func uniqueMatch(_ candidates: [AccessibilityWindowIdentity], title: String,
                            bounds: AccessibilityBounds? = nil) throws -> Int {
        let matches = candidates.indices.filter { index in
            candidates[index].title == title && (bounds == nil || candidates[index].bounds.map {
                approximatelyEqual($0, bounds!) } == true)
        }
        guard matches.count == 1 else {
            let reason = matches.isEmpty ? "was not found" : "is ambiguous (\(matches.count) matches)"
            throw AccessibilityCaptureError.targetNotFound("window \(title.debugDescription) \(reason)")
        }
        return matches[0]
    }
    private static func approximatelyEqual(_ lhs: AccessibilityBounds, _ rhs: AccessibilityBounds) -> Bool {
        abs(lhs.x-rhs.x) <= 2 && abs(lhs.y-rhs.y) <= 2 && abs(lhs.width-rhs.width) <= 2 && abs(lhs.height-rhs.height) <= 2
    }
}

struct AccessibilityNativeWindowIdentity: Equatable {
    let title: String?
    let bounds: AccessibilityBounds?
    let processID: pid_t
}
enum AccessibilityNativeWindowLookup {
    static func uniqueMatch(_ candidates: [AccessibilityNativeWindowIdentity], title: String,
                            processID: pid_t?) throws -> Int {
        let matches = candidates.indices.filter {
            candidates[$0].title == title &&
                (processID == nil || candidates[$0].processID == processID)
        }
        guard matches.count == 1 else {
            let reason = matches.isEmpty ? "was not found" : "is ambiguous (\(matches.count) matches)"
            throw AccessibilityCaptureError.targetNotFound("window \(title.debugDescription) \(reason)")
        }
        return matches[0]
    }
}

private final class AXAccessibilityTree: AccessibilityTree {
    let windows: [AccessibilityWindowSnapshot]; let roots: [LoadedAccessibilityNode]
    let trailingTruncation: TraversalTruncation?
    init(target: AccessibilityTarget, maximumDepth: Int, maximumNodeCount: Int, deadline: Date) throws {
        let selection = try Self.selectWindows(target: target, deadline: deadline)
        var snapshots: [AccessibilityWindowSnapshot] = []; var roots: [LoadedAccessibilityNode] = []
        var remaining = maximumNodeCount
        for (index, element) in selection.enumerated() where remaining > 0 {
            let loaded = try Self.load(element, depth: 0, maximumDepth: maximumDepth,
                                       remainingNodes: &remaining, deadline: deadline)
            snapshots.append(.init(key: "w\(index)", title: loaded.title, bounds: loaded.bounds)); roots.append(loaded)
        }
        guard !roots.isEmpty else { throw AccessibilityCaptureError.noAccessibilityTree("target has no accessibility windows") }
        windows = snapshots; self.roots = roots
        trailingTruncation = roots.count < selection.count ? .nodeCount : nil
    }
    private static func selectWindows(target: AccessibilityTarget, deadline: Date) throws -> [AXUIElement] {
        let pid: pid_t; var requestedTitle: String?; var requestedBounds: AccessibilityBounds?
        switch target {
        case let .processID(value):
            guard NSRunningApplication(processIdentifier: value) != nil else {
                throw AccessibilityCaptureError.targetNotFound("process \(value) was not found") }
            pid = value
        case let .windowID(value):
            guard let id = UInt32(value), let info = windowInfo(id: id),
                  let owner = info[kCGWindowOwnerPID as String] as? NSNumber else {
                throw AccessibilityCaptureError.targetNotFound("window \(value) was not found") }
            guard let title = info[kCGWindowName as String] as? String, !title.isEmpty,
                  let bounds = cgWindowBounds(info) else {
                throw AccessibilityCaptureError.targetNotFound("window \(value) identity could not be verified") }
            pid = pid_t(owner.int32Value); requestedTitle = title; requestedBounds = bounds
        case let .windowTitle(title, processID):
            let info = CGWindowListCopyWindowInfo(.optionOnScreenOnly, kCGNullWindowID)
                as? [[String: Any]] ?? []
            let identities = info.compactMap { candidate -> AccessibilityNativeWindowIdentity? in
                guard let owner = candidate[kCGWindowOwnerPID as String] as? NSNumber else {
                    return nil
                }
                return .init(title: candidate[kCGWindowName as String] as? String,
                             bounds: cgWindowBounds(candidate),
                             processID: pid_t(owner.int32Value))
            }
            let index = try AccessibilityNativeWindowLookup.uniqueMatch(
                identities, title: title, processID: processID)
            pid = identities[index].processID
            requestedTitle = title
            requestedBounds = identities[index].bounds
        }
        let app = AXUIElementCreateApplication(pid); try setTimeout(app, deadline)
        var raw: CFTypeRef?
        guard AXUIElementCopyAttributeValue(app, kAXWindowsAttribute as CFString, &raw) == .success,
              let axWindows = raw as? [AXUIElement] else {
            throw AccessibilityCaptureError.noAccessibilityTree("target has no accessibility windows") }
        guard let title = requestedTitle else { return axWindows }
        var identities: [AccessibilityWindowIdentity] = []
        for window in axWindows {
            try setTimeout(window, deadline); var titleValue: CFTypeRef?; var position: CFTypeRef?; var size: CFTypeRef?
            _ = AXUIElementCopyAttributeValue(window, kAXTitleAttribute as CFString, &titleValue)
            _ = AXUIElementCopyAttributeValue(window, kAXPositionAttribute as CFString, &position)
            _ = AXUIElementCopyAttributeValue(window, kAXSizeAttribute as CFString, &size)
            identities.append(.init(title: titleValue as? String, bounds: bounds(position, size)))
        }
        let index = try AccessibilityWindowMatcher.uniqueMatch(identities, title: title, bounds: requestedBounds)
        return [axWindows[index]]
    }
    private static func load(_ element: AXUIElement, depth: Int, maximumDepth: Int,
                             remainingNodes: inout Int, deadline: Date) throws -> LoadedAccessibilityNode {
        try setTimeout(element, deadline); var copied: CFArray?
        let error = AXUIElementCopyMultipleAttributeValues(element, attributes as CFArray, [], &copied)
        guard error == .success, let values = copied as? [Any], values.count == attributes.count else {
            if Date() >= deadline { throw AccessibilityCaptureError.timeout(nil) }
            throw AccessibilityCaptureError.noAccessibilityTree("AX attribute read failed (\(error.rawValue))")
        }
        let rawChildren = values[12] as? [AXUIElement] ?? []; remainingNodes -= 1
        var children: [LoadedAccessibilityNode] = []; var truncated: TraversalTruncation?
        if depth == maximumDepth, !rawChildren.isEmpty { truncated = .depth }
        else { for child in rawChildren { guard remainingNodes > 0 else { truncated = .nodeCount; break }
            children.append(try load(child, depth: depth+1, maximumDepth: maximumDepth,
                                     remainingNodes: &remainingNodes, deadline: deadline)) } }
        return .init(id: values[11] as? String, role: values[0] as? String, subrole: values[1] as? String,
            title: values[2] as? String, description: values[3] as? String, label: values[4] as? String,
            value: display(values[5]), enabled: (values[6] as? NSNumber)?.boolValue,
            focusable: values[7] is NSNumber, focused: (values[7] as? NSNumber)?.boolValue,
            selected: (values[8] as? NSNumber)?.boolValue,
            bounds: bounds(values[9], values[10]) ?? .init(x: 0,y: 0,width: 0,height: 0), children: children,
            truncated: truncated)
    }
    private static func display(_ value: Any) -> String? { (value as? String) ?? (value as? NSNumber)?.stringValue }
    private static func bounds(_ position: Any?, _ sizeValue: Any?) -> AccessibilityBounds? {
        guard let position, let sizeValue, CFGetTypeID(position as CFTypeRef) == AXValueGetTypeID(),
              CFGetTypeID(sizeValue as CFTypeRef) == AXValueGetTypeID() else { return nil }
        var point = CGPoint.zero; var size = CGSize.zero
        guard AXValueGetValue(position as! AXValue, .cgPoint, &point),
              AXValueGetValue(sizeValue as! AXValue, .cgSize, &size) else { return nil }
        return .init(x: Int(point.x.rounded()), y: Int(point.y.rounded()),
                     width: Int(size.width.rounded()), height: Int(size.height.rounded()))
    }
    private static func windowInfo(id: CGWindowID) -> [String: Any]? {
        (CGWindowListCopyWindowInfo(.optionIncludingWindow, id) as? [[String: Any]])?.first
    }
    private static func cgWindowBounds(_ info: [String: Any]) -> AccessibilityBounds? {
        guard let dictionary = info[kCGWindowBounds as String] as? [String: Any],
              let rect = CGRect(dictionaryRepresentation: dictionary as CFDictionary) else { return nil }
        return .init(x: Int(rect.origin.x.rounded()), y: Int(rect.origin.y.rounded()),
                     width: Int(rect.width.rounded()), height: Int(rect.height.rounded()))
    }
    private static func setTimeout(_ element: AXUIElement, _ deadline: Date) throws {
        let remaining = deadline.timeIntervalSinceNow
        guard remaining > 0 else { throw AccessibilityCaptureError.timeout(nil) }
        AXUIElementSetMessagingTimeout(element, Float(remaining))
    }
}

public enum AccessibilitySnapshotCommand {
    public static func run(stdin: FileHandle = .standardInput, stdout: FileHandle = .standardOutput,
                           stderr: FileHandle = .standardError) -> Int32 {
        run(stdin: stdin, stdout: stdout, stderr: stderr, trusted: AXIsProcessTrusted)
    }
    static func run(stdin: FileHandle, stdout: FileHandle, stderr: FileHandle,
                    trusted: () -> Bool) -> Int32 {
        do {
            let request = try AccessibilitySnapshotRequest.decode(stdin.readDataToEndOfFile())
            guard trusted() else {
                write(ErrorEnvelope(error: .init(code: "permission-missing",
                    message: "Accessibility is not granted for Spectre Capture Helper. Open \(accessibilitySettingsPath) (\(accessibilityDeepLink)), enable the signed helper, then retry. Spectre did not request permission.",
                    partialSnapshot: nil)), to: stdout); return 0
            }
            let deadline = Date().addingTimeInterval(Double(request.timeoutMs)/1000)
            let tree = try AXAccessibilityTree(target: request.target, maximumDepth: request.maximumDepth,
                                                maximumNodeCount: request.maximumNodeCount, deadline: deadline)
            let snapshot = try AccessibilitySnapshotBuilder.capture(tree: tree, maximumDepth: request.maximumDepth,
                maximumNodeCount: request.maximumNodeCount, deadline: deadline)
            write(SuccessEnvelope(snapshot: snapshot), to: stdout); return 0
        } catch let error as AccessibilityCaptureError {
            let payload: AccessibilityErrorPayload
            switch error {
            case let .targetNotFound(message): payload = .init(code:"target-not-found",message:message,partialSnapshot:nil)
            case let .noAccessibilityTree(message): payload = .init(code:"no-accessibility-tree",message:message,partialSnapshot:nil)
            case let .limitReached(partial,message): payload = .init(code:"limit-reached",message:message,partialSnapshot:partial)
            case let .timeout(partial): payload = .init(code:"timeout",message:"accessibility snapshot timed out",partialSnapshot:partial)
            }
            write(ErrorEnvelope(error: payload), to: stdout); return 0
        } catch {
            stderr.write(Data("spectre-screencapture: invalid accessibility request: \(error)\n".utf8)); return 2
        }
    }
    private static func write<T: Encodable>(_ value: T, to output: FileHandle) {
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(value) else { return }
        output.write(data); output.write(Data("\n".utf8))
    }
}
private struct SuccessEnvelope: Encodable { let ok = true; let snapshot: AccessibilitySnapshot }
private struct ErrorEnvelope: Encodable { let ok = false; let error: AccessibilityErrorPayload }
private struct AccessibilityErrorPayload: Codable {
    let code: String; let message: String; let partialSnapshot: AccessibilitySnapshot?

    enum CodingKeys: String, CodingKey { case code, message, partialSnapshot }
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(code, forKey: .code)
        try container.encode(message, forKey: .message)
        try container.encode(partialSnapshot, forKey: .partialSnapshot)
    }
}
