import XCTest
@testable import SpectreScreenCaptureCore

final class AccessibilitySnapshotTests: XCTestCase {
    func testParsesEverySupportedTarget() throws {
        XCTAssertEqual(try request(#"{"type":"process-id","value":42}"#).target, .processID(42))
        XCTAssertEqual(try request(#"{"type":"window-id","value":"99"}"#).target, .windowID("99"))
        XCTAssertEqual(try request(#"{"type":"window-title","value":"Preferences"}"#).target,
                       .windowTitle("Preferences", processID: nil))
        XCTAssertEqual(try request(#"{"type":"window-title","value":"Preferences","processId":44}"#).target,
                       .windowTitle("Preferences", processID: 44))
    }

    func testRejectsEmptyTitleAndWrongScalarTypes() {
        for target in [#"{"type":"window-title","value":""}"#,
                       #"{"type":"window-title","value":7}"#,
                       #"{"type":"window-id","value":7}"#] {
            XCTAssertThrowsError(try request(target))
        }
    }

    func testRejectsInvalidWindowTitleProcessIDs() {
        for processID in ["0", "-1", "2147483648"] {
            XCTAssertThrowsError(try request(
                #"{"type":"window-title","value":"Preferences","processId":\#(processID)}"#))
        }
        XCTAssertThrowsError(try request(
            #"{"type":"window-title","value":"Preferences","processId":"44"}"#))
    }

    func testSameTitleAndSameFrameIsAmbiguous() {
        let frame = AccessibilityBounds(x: 10, y: 20, width: 300, height: 200)
        let candidates = [AccessibilityWindowIdentity(title: "Same", bounds: frame),
                          AccessibilityWindowIdentity(title: "Same", bounds: frame)]
        XCTAssertThrowsError(try AccessibilityWindowMatcher.uniqueMatch(candidates,
                                                                          title: "Same", bounds: frame)) {
            guard case let AccessibilityCaptureError.targetNotFound(message) = $0 else {
                return XCTFail("unexpected error: \($0)")
            }
            XCTAssertTrue(message.contains("ambiguous"), message)
        }
    }

    func testUniqueTitleSelectsExactlyOneWindow() throws {
        let candidates = [AccessibilityWindowIdentity(title: "One", bounds: nil),
                          AccessibilityWindowIdentity(title: "Wanted", bounds: nil),
                          AccessibilityWindowIdentity(title: "Three", bounds: nil)]
        XCTAssertEqual(try AccessibilityWindowMatcher.uniqueMatch(candidates, title: "Wanted"), 1)
    }

    func testRealLookupScopesDuplicateTitlesToRequestedProcess() throws {
        let candidates = [
            nativeWindow(title: "Wanted", processID: 11),
            nativeWindow(title: "Wanted", processID: 44),
        ]

        XCTAssertEqual(try AccessibilityNativeWindowLookup.uniqueMatch(
            candidates, title: "Wanted", processID: 44), 1)
    }

    func testRealLookupRejectsDuplicateTitlesWithinRequestedProcess() {
        let candidates = [
            nativeWindow(title: "Wanted", processID: 44),
            nativeWindow(title: "Wanted", processID: 44),
            nativeWindow(title: "Wanted", processID: 55),
        ]

        XCTAssertThrowsError(try AccessibilityNativeWindowLookup.uniqueMatch(
            candidates, title: "Wanted", processID: 44)) {
            guard case let AccessibilityCaptureError.targetNotFound(message) = $0 else {
                return XCTFail("unexpected error: \($0)")
            }
            XCTAssertTrue(message.contains("ambiguous (2 matches)"), message)
        }
    }

    func testRealLookupDoesNotSelectWindowOwnedByDifferentProcess() {
        let candidates = [nativeWindow(title: "Wanted", processID: 55)]

        XCTAssertThrowsError(try AccessibilityNativeWindowLookup.uniqueMatch(
            candidates, title: "Wanted", processID: 44)) {
            guard case let AccessibilityCaptureError.targetNotFound(message) = $0 else {
                return XCTFail("unexpected error: \($0)")
            }
            XCTAssertTrue(message.contains("was not found"), message)
        }
    }

    func testNumericPlatformIDsCannotCollideWithFallbackIndices() {
        XCTAssertEqual(AccessibilitySnapshotBuilder.segment("0", fallback: 99), "~20")
        XCTAssertEqual(AccessibilitySnapshotBuilder.segment("123", fallback: 0), "~2123")
        XCTAssertEqual(AccessibilitySnapshotBuilder.segment("１２３", fallback: 0), "１２３")
        XCTAssertEqual(AccessibilitySnapshotBuilder.segment(nil, fallback: 0), "0")
        XCTAssertEqual(AccessibilitySnapshotBuilder.segment("a/b~c", fallback: 0), "a~1b~0c")
    }

    func testMissingPermissionIsStructuredAndDoesNotPrompt() throws {
        let json = #"{"target":{"type":"process-id","value":42},"maximumDepth":2,"maximumNodeCount":2,"timeoutMs":10}"#
        let input = Pipe(); input.fileHandleForWriting.write(Data(json.utf8)); try input.fileHandleForWriting.close()
        let output = Pipe(); var checks = 0
        let status = AccessibilitySnapshotCommand.run(stdin: input.fileHandleForReading,
            stdout: output.fileHandleForWriting, stderr: .nullDevice, trusted: { checks += 1; return false })
        try output.fileHandleForWriting.close()
        let response = String(decoding: output.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
        XCTAssertEqual(status, 0); XCTAssertEqual(checks, 1)
        XCTAssertTrue(response.contains(#""code":"permission-missing""#), response)
        XCTAssertTrue(response.contains("did not request permission"), response)
    }

    func testDuplicateIdentifiersAndLimitProducePreorderPartialTree() throws {
        let tree = SyntheticAccessibilityTree(roots: [.node(id: "root", children: [
            .node(id: "same"), .node(id: "same"), .node(id: "last")])])
        XCTAssertThrowsError(try AccessibilitySnapshotBuilder.capture(tree: tree, maximumDepth: 8,
            maximumNodeCount: 3, deadline: .distantFuture)) {
            guard case let AccessibilityCaptureError.limitReached(partial, _) = $0 else {
                return XCTFail("unexpected error: \($0)")
            }
            XCTAssertEqual(partial.nodes.map(\.key), ["w0/root", "w0/root/0", "w0/root/1"])
        }
    }

    private func nativeWindow(title: String, processID: pid_t) -> AccessibilityNativeWindowIdentity {
        .init(title: title,
              bounds: .init(x: 0, y: 0, width: 100, height: 100),
              processID: processID)
    }

    func testNullableProtocolFieldsArePresentAsJSONNull() throws {
        let tree = SyntheticAccessibilityTree(roots: [.node(id: nil, role: nil, enabled: nil,
                                                             focused: nil, selected: nil)])
        let snapshot = try AccessibilitySnapshotBuilder.capture(tree: tree, maximumDepth: 8,
            maximumNodeCount: 10, deadline: .distantFuture)
        let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: JSONEncoder().encode(snapshot))
            as? [String: Any])
        let untitled = AccessibilityWindowSnapshot(key: "w0", title: nil,
            bounds: .init(x: 0, y: 0, width: 100, height: 100))
        let window = try XCTUnwrap(try JSONSerialization.jsonObject(with: JSONEncoder().encode(untitled))
            as? [String: Any])
        XCTAssertTrue(window.keys.contains("title")); XCTAssertTrue(window["title"] is NSNull)
        let node = try XCTUnwrap((object["nodes"] as? [[String: Any]])?.first)
        for key in ["parentKey", "id", "platformRole", "role", "name", "value"] {
            XCTAssertTrue(node.keys.contains(key), "missing key \(key)")
            XCTAssertTrue(node[key] is NSNull, "expected null for \(key), got \(String(describing: node[key]))")
        }
    }

    func testStructuredErrorContainsNullPartialSnapshot() throws {
        let json = #"{"target":{"type":"process-id","value":42},"maximumDepth":2,"maximumNodeCount":2,"timeoutMs":10}"#
        let input = Pipe(); input.fileHandleForWriting.write(Data(json.utf8)); try input.fileHandleForWriting.close()
        let output = Pipe()
        _ = AccessibilitySnapshotCommand.run(stdin: input.fileHandleForReading,
            stdout: output.fileHandleForWriting, stderr: .nullDevice, trusted: { false })
        try output.fileHandleForWriting.close()
        let response = try XCTUnwrap(try JSONSerialization.jsonObject(
            with: output.fileHandleForReading.readDataToEndOfFile()) as? [String: Any])
        let error = try XCTUnwrap(response["error"] as? [String: Any])
        XCTAssertTrue(error.keys.contains("partialSnapshot"))
        XCTAssertTrue(error["partialSnapshot"] is NSNull)
    }

    func testDecodedAccessKitFixtureRegression() throws {
        let tree = SyntheticAccessibilityTree(windowBounds: .init(x: 0, y: 0, width: 100, height: 100), roots: [
            .node(id: nil, title: nil, description: nil, label: nil, children: [
                .node(id: "newsletter", role: "AXCheckBox", value: "1", focused: false,
                      focusable: true),
                .node(id: "terms", role: "AXCheckBox", value: "0"),
                .node(id: "choice", role: "AXRadioButton", value: "2"),
                .node(id: "disabled", role: "AXButton", enabled: false, focused: nil),
                .node(id: "repeated"), .node(id: "repeated"),
                .node(id: "offscreen", bounds: .init(x: 200, y: 200, width: 10, height: 10)),
            ]),
        ])
        let encoded = try JSONEncoder().encode(AccessibilitySnapshotBuilder.capture(tree: tree,
            maximumDepth: 8, maximumNodeCount: 10, deadline: .distantFuture))
        let decoded = try JSONDecoder().decode(AccessibilitySnapshot.self, from: encoded)
        XCTAssertEqual(decoded.windows.first?.title, "Fixture")
        let root = try XCTUnwrap(decoded.nodes.first)
        XCTAssertNil(root.name); XCTAssertNil(root.id)
        let checkbox = try XCTUnwrap(decoded.nodes.first { $0.id == "newsletter" })
        XCTAssertEqual(checkbox.role, "checkbox"); XCTAssertTrue(checkbox.states.contains("checked"))
        XCTAssertTrue(checkbox.states.contains("focusable"))
        XCTAssertTrue(try XCTUnwrap(decoded.nodes.first { $0.id == "terms" }).states.contains("unchecked"))
        XCTAssertTrue(try XCTUnwrap(decoded.nodes.first { $0.id == "choice" }).states.contains("mixed"))
        let disabled = try XCTUnwrap(decoded.nodes.first { $0.id == "disabled" })
        XCTAssertTrue(disabled.states.contains("disabled")); XCTAssertFalse(disabled.states.contains("focusable"))
        XCTAssertEqual(decoded.nodes.filter { $0.id == "repeated" }.count, 2)
        XCTAssertEqual(Set(decoded.nodes.map(\.key)).count, decoded.nodes.count)
        XCTAssertEqual(decoded.nodes.first { $0.id == "offscreen" }?.onScreen, false)
    }

    func testNativeTraversalStopsBeforeSiblingAfterDepthTruncation() throws {
        struct Element { let id: String; let children: [String] }
        let elements = [
            "root": Element(id: "root", children: ["limited", "slow"]),
            "limited": Element(id: "limited", children: ["unread"]),
            "slow": Element(id: "slow", children: []),
        ]
        var reads: [String] = []
        var remaining = 10

        let result = try AccessibilityNativeTraversal.load("root", depth: 0, maximumDepth: 1,
                                                           remainingNodes: &remaining) { key in
            reads.append(key)
            let element = try XCTUnwrap(elements[key])
            return nativeNode(id: element.id, children: element.children)
        }

        XCTAssertEqual(reads, ["root", "limited"])
        XCTAssertTrue(result.didTruncate)
        XCTAssertEqual(result.node.children.map(\.id), ["limited"])
        XCTAssertEqual(result.node.children.first?.truncated, .depth)
        let tree = SyntheticAccessibilityTree(loadedRoots: [result.node])
        XCTAssertThrowsError(try AccessibilitySnapshotBuilder.capture(tree: tree, maximumDepth: 1,
            maximumNodeCount: 10, deadline: .distantFuture)) {
            guard case let AccessibilityCaptureError.limitReached(partial, _) = $0 else {
                return XCTFail("unexpected error: \($0)")
            }
            XCTAssertEqual(partial.nodes.map(\.key), ["w0/root", "w0/root/limited"])
        }
    }

    func testNativeTraversalStopsRemainingWindowsAtFirstTruncation() throws {
        var reads: [String] = []
        let result = try AccessibilityNativeTraversal.loadRoots(["first", "slow-window"],
            maximumDepth: 0, maximumNodeCount: 10) { key in
                reads.append(key)
                return self.nativeNode(id: key, children: key == "first" ? ["unread"] : [])
            }

        XCTAssertEqual(reads, ["first"])
        XCTAssertEqual(result.roots.map(\.id), ["first"])
        XCTAssertEqual(result.trailingTruncation, .depth)
    }

    func testEveryPotentiallyBlockingReadGetsFreshRemainingDeadline() throws {
        let deadline = Date(timeIntervalSinceReferenceDate: 10)
        var times = [Date(timeIntervalSinceReferenceDate: 9.0),
                     Date(timeIntervalSinceReferenceDate: 9.4),
                     Date(timeIntervalSinceReferenceDate: 9.9)]
        var applied: [TimeInterval] = []
        let budget = AccessibilityReadBudget(deadline: deadline, now: { times.removeFirst() })

        for _ in 0..<3 {
            try budget.beforeRead { applied.append($0) }
        }

        XCTAssertEqual(applied.count, 3)
        XCTAssertEqual(applied[0], 1.0, accuracy: 0.001)
        XCTAssertEqual(applied[1], 0.6, accuracy: 0.001)
        XCTAssertEqual(applied[2], 0.1, accuracy: 0.001)
    }

    func testExpiredBudgetDoesNotStartAnotherRepeatedAttributeRead() throws {
        let deadline = Date(timeIntervalSinceReferenceDate: 10)
        var times = [Date(timeIntervalSinceReferenceDate: 9.9), deadline]
        var calls = 0
        let budget = AccessibilityReadBudget(deadline: deadline, now: { times.removeFirst() })
        try budget.beforeRead { _ in calls += 1 }

        XCTAssertThrowsError(try budget.beforeRead { _ in calls += 1 }) {
            guard case AccessibilityCaptureError.timeout = $0 else {
                return XCTFail("unexpected error: \($0)")
            }
        }
        XCTAssertEqual(calls, 1)
    }

    private func nativeNode(id: String, children: [String]) -> NativeAccessibilityNode<String> {
        .init(id: id, role: "AXGroup", subrole: nil, title: id, description: nil, label: nil,
              value: nil, enabled: true, focusable: false, focused: false, selected: false,
              bounds: .init(x: 0, y: 0, width: 10, height: 10), children: children)
    }

    private func request(_ target: String) throws -> AccessibilitySnapshotRequest {
        try AccessibilitySnapshotRequest.decode(Data("{\"target\":\(target),\"maximumDepth\":2,\"maximumNodeCount\":3,\"timeoutMs\":10}".utf8))
    }
}
