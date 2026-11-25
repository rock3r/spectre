import ApplicationServices
import Foundation

func fail(_ message: String) -> Never { fputs("\(message)\n", stderr); exit(1) }
let args = CommandLine.arguments
if args.count != 3 { fail("usage: macos-reader <pid> <golden.json>") }
let pid = pid_t(args[1]) ?? 0
let golden = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: args[2]))) as! [String: Any]
if !AXIsProcessTrusted() { fail("accessibility-disabled: grant Accessibility to the invoking terminal/helper") }
let app = AXUIElementCreateApplication(pid)
func copy(_ element: AXUIElement, _ attribute: CFString) -> AnyObject? {
  var value: CFTypeRef?; return AXUIElementCopyAttributeValue(element, attribute, &value) == .success ? value : nil
}
func children(_ element: AXUIElement) -> [AXUIElement] { copy(element, kAXChildrenAttribute as CFString) as? [AXUIElement] ?? [] }
func walk(_ element: AXUIElement) -> [AXUIElement] { [element] + children(element).flatMap(walk) }
let deadline = Date().addingTimeInterval(15)
var all: [AXUIElement] = []
repeat { all = walk(app); if all.count < 2 { Thread.sleep(forTimeInterval: 0.1) } } while all.count < 2 && Date() < deadline
func string(_ element: AXUIElement, _ attribute: CFString) -> String? { copy(element, attribute) as? String }
func byId(_ id: String) -> [AXUIElement] { all.filter { string($0, kAXIdentifierAttribute as CFString) == id } }
if String(describing: copy(byId("newsletter")[0], kAXValueAttribute as CFString)!) != golden["checkedState"] as? String { fail("checkbox state mismatch") }
if byId("repeated").count != 3 { fail("duplicate ids were collapsed") }
if string(byId("primary-button")[0], kAXRoleAttribute as CFString) != golden["buttonRole"] as? String { fail("button role mismatch") }
if copy(byId("disabled-button")[0], kAXEnabledAttribute as CFString) as? Bool != false { fail("disabled button is enabled") }
if string(byId("account-name")[0], kAXValueAttribute as CFString) != golden["textValue"] as? String { fail("text value mismatch") }
if AXUIElementPerformAction(byId("popover-trigger")[0], kAXPressAction as CFString) != .success { fail("popover press failed") }
Thread.sleep(forTimeInterval: 0.5); all = walk(app)
if byId("details-popover").isEmpty { fail("popover did not open") }
if string(byId("details-popover")[0], kAXRoleAttribute as CFString) != golden["popoverRole"] as? String { fail("popover role mismatch") }
print("accesskit-fixture-macos: pass")
