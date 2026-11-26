import ApplicationServices
import Foundation

func fail(_ message: String) -> Never { fputs("\(message)\n", stderr); exit(1) }
let args = CommandLine.arguments
if args.count != 3 { fail("usage: macos-reader <pid> <golden.json>") }
guard let pid = pid_t(args[1]) else { fail("invalid pid") }
guard let golden = try? JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: args[2]))) as? [String: Any] else { fail("invalid golden JSON") }
if !AXIsProcessTrusted() { fail("accessibility-disabled: grant Accessibility to the invoking terminal/helper") }
let app = AXUIElementCreateApplication(pid)
func copy(_ element: AXUIElement, _ attribute: CFString) -> CFTypeRef? {
  var value: CFTypeRef?
  return AXUIElementCopyAttributeValue(element, attribute, &value) == .success ? value : nil
}
func element(_ value: CFTypeRef?) -> AXUIElement? {
  guard let value, CFGetTypeID(value) == AXUIElementGetTypeID() else { return nil }
  return unsafeBitCast(value, to: AXUIElement.self)
}
func children(_ item: AXUIElement) -> [AXUIElement] {
  guard let values = copy(item, kAXChildrenAttribute as CFString) as? [CFTypeRef] else { return [] }
  return values.compactMap(element)
}
func walk(_ item: AXUIElement) -> [AXUIElement] { [item] + children(item).flatMap(walk) }
func string(_ item: AXUIElement, _ attribute: CFString) -> String? { copy(item, attribute) as? String }
func identifier(_ item: AXUIElement) -> String? { string(item, kAXIdentifierAttribute as CFString) }
func describe(_ items: [AXUIElement]) -> String {
  items.map { "\(identifier($0) ?? "<no-id>"):\(string($0, kAXRoleAttribute as CFString) ?? "<no-role>")" }.joined(separator: ", ")
}
func matches(_ id: String, in all: [AXUIElement], count: Int = 1) -> [AXUIElement] {
  let found = all.filter { identifier($0) == id }
  if found.count != count { fail("expected \(count) node(s) with id \(id), got \(found.count); saw [\(describe(all))]") }
  return found
}
func one(_ id: String, in all: [AXUIElement]) -> AXUIElement {
  guard let item = matches(id, in: all).first else { fail("internal missing match for \(id)") }
  return item
}
func goldenString(_ key: String) -> String {
  guard let value = golden[key] as? String else { fail("golden key \(key) is not a string") }
  return value
}
func expect(_ actual: String?, _ expected: String, _ context: String, all: [AXUIElement]) {
  if actual != expected { fail("\(context) mismatch: expected \(expected), got \(actual ?? "nil"); saw [\(describe(all))]") }
}
let deadline = Date().addingTimeInterval(15)
let required = ["primary-button", "newsletter", "account-name", "nested-group", "nested-label", "disabled-button", "offscreen-button", "popover-trigger"]
var all: [AXUIElement] = []
var ready = false
repeat {
  all = walk(app)
  ready = required.allSatisfy { id in all.contains { identifier($0) == id } } && all.filter { identifier($0) == "repeated" }.count == 3
  if ready { break }
  Thread.sleep(forTimeInterval: 0.1)
} while Date() < deadline
if !ready { fail("fixture children did not register before timeout; saw [\(describe(all))]") }
let windows = children(app).filter { string($0, kAXRoleAttribute as CFString) == goldenString("windowRole") }
if windows.isEmpty { fail("window role mismatch; saw [\(describe(children(app)))]") }
let button = one("primary-button", in: all)
let checkbox = one("newsletter", in: all)
let text = one("account-name", in: all)
let repeated = matches("repeated", in: all, count: 3)
let nested = one("nested-group", in: all)
let nestedLabel = one("nested-label", in: all)
let disabled = one("disabled-button", in: all)
let trigger = one("popover-trigger", in: all)
expect(string(button, kAXRoleAttribute as CFString), goldenString("buttonRole"), "button role", all: all)
expect(string(button, kAXTitleAttribute as CFString), "Save changes", "button label", all: all)
expect(string(checkbox, kAXRoleAttribute as CFString), goldenString("checkboxRole"), "checkbox role", all: all)
expect(String(describing: copy(checkbox, kAXValueAttribute as CFString) ?? "nil" as CFTypeRef), goldenString("checkedState"), "checkbox state", all: all)
expect(string(text, kAXRoleAttribute as CFString), goldenString("textRole"), "text role", all: all)
expect(string(text, kAXTitleAttribute as CFString), "Account name", "text label", all: all)
expect(string(text, kAXValueAttribute as CFString), goldenString("textValue"), "text value", all: all)
if copy(disabled, kAXEnabledAttribute as CFString) as? Bool != false || goldenString("disabledState") != "disabled" { fail("disabled state mismatch") }
let repeatedParents = repeated.compactMap { element(copy($0, kAXParentAttribute as CFString)) }
if repeatedParents.count != 3 { fail("duplicate parent relationships missing; repeated nodes: [\(describe(repeated))]") }
var distinctParents: [AXUIElement] = []
for parent in repeatedParents where !distinctParents.contains(where: { CFEqual($0, parent) }) { distinctParents.append(parent) }
if distinctParents.count != 2 { fail("duplicate parent relationships mismatch; parents [\(describe(repeatedParents))]") }
expect(string(nested, kAXRoleAttribute as CFString), "AXGroup", "nested group role", all: all)
guard let nestedParent = element(copy(nestedLabel, kAXParentAttribute as CFString)), CFEqual(nestedParent, nested) else {
  fail("nested relationship mismatch; label parent [\(describe(element(copy(nestedLabel, kAXParentAttribute as CFString)).map { [$0] } ?? []))]")
}
if AXUIElementPerformAction(trigger, kAXPressAction as CFString) != .success { fail("popover press failed") }
let popoverDeadline = Date().addingTimeInterval(5)
var popover: AXUIElement?
repeat {
  all = walk(app)
  popover = all.first { identifier($0) == "details-popover" }
  if popover != nil { break }
  Thread.sleep(forTimeInterval: 0.1)
} while Date() < popoverDeadline
guard let popover else { fail("popover did not register before timeout; saw [\(describe(all))]") }
expect(string(popover, kAXSubroleAttribute as CFString), goldenString("popoverRole"), "popover subrole", all: all)
expect(string(popover, kAXTitleAttribute as CFString), "Details", "popover label", all: all)
print("accesskit-fixture-macos: pass")
