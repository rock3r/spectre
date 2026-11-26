# Native accessibility helper protocol

Spectre 0.10 calls the native capture helper once per accessibility snapshot. The daemon owns the
process, deadline, and limits; a helper must batch platform attribute reads rather than spawning
one request per node. A roughly 1,500-node snapshot is expected to complete well under one second.

## Invocation and framing

The command is `accessibility-snapshot`:

```text
<helper executable> accessibility-snapshot
```

The daemon writes exactly one UTF-8 JSON request to standard input, closes standard input, reads
exactly one UTF-8 JSON response from standard output, and then requires a zero exit status. Logs
belong on standard error. Unknown JSON keys must be ignored for forward compatibility.

```json
{
  "target": { "type": "process-id", "value": 4123 },
  "maximumDepth": 64,
  "maximumNodeCount": 10000,
  "timeoutMs": 750,
  "allowAccessibilityEnable": false
}
```

`target.type` is `process-id` (numeric OS PID), `window-id` (a string preserving an OS window handle
or identifier without numeric truncation), or `window-title` (an exact, non-empty title). A title
target may carry an optional owner constraint:

```json
{ "target": { "type": "window-title", "value": "Preferences", "processId": 4123 } }
```

The helper must resolve every candidate's actual owner before applying `processId` and before
deciding whether the title is ambiguous. It must not trust a caller-supplied PID as the owner of an
otherwise matching native window. No match, a wrong owner, or multiple matches after owner
filtering returns `target-not-found` with an explanatory message; the helper never chooses the
first match. On Linux, a `window-id` is the AT-SPI pair
`<unique-bus-name>|<object-path>` (for example,
`:1.42|/org/a11y/atspi/accessible/7`), not a well-known bus name alone.

All three limits are positive. Depth counts the window root as zero. The helper must stop work by
`timeoutMs`; the daemon also kills a helper that outlives the deadline plus a small process-exit
allowance. `allowAccessibilityEnable` defaults to `false`. A Linux helper may temporarily set
`org.a11y.Status.IsEnabled` only when it is `true`, must report the change in `notes`, and must
restore the original value on every return path, including structured errors and timeouts. With the
default, a disabled session returns `accessibility-disabled` without changing session state.

## Successful response

```json
{
  "ok": true,
  "snapshot": {
    "schemaVersion": 2,
    "source": "os-accessibility",
    "capturedAt": "2026-11-25T12:34:56.789Z",
    "windows": [{
      "key": "w0", "title": "Preferences",
      "bounds": { "x": 10, "y": 20, "width": 900, "height": 700 },
      "boundsSpace": "screen"
    }],
    "nodes": [{
      "key": "w0/settings/0", "parentKey": null, "id": "settings",
      "platformRole": "AXButton", "role": "button", "name": "Save",
      "states": ["focusable"], "value": null,
      "bounds": { "x": 30, "y": 40, "width": 80, "height": 32 },
      "onScreen": true
    }],
    "notes": []
  }
}
```

`boundsSpace` is `screen`, except an API that only supplies window-relative coordinates uses
`window` (notably AT-SPI on GNOME Wayland). A reader must never label window coordinates as screen
coordinates. `onScreen` is computed by intersecting node bounds with its window bounds; platform
visibility flags are not copied. `notes` reports non-error side effects, including enabling the
Linux accessibility bus.

Keys are snapshot-local. Begin with `w<window-index>`, then append the platform `id` at each step.
When an id is absent **or duplicated among siblings**, append that child's zero-based index instead.
To prevent the numeric fallback from colliding with a literal numeric sibling id, prefix an all-decimal id segment with `~2` (id `0` is `~20`, while fallback index 0 remains `0`). Escape `~` and `/` in ids as JSON Pointer does (`~0` and `~1`). Stable identity across snapshots is
not promised. `parentKey` is null only at a window tree root. `id` preserves `AXIdentifier`,
`AutomationId`, or `accessible_id`; it is null when absent. Normalised `role` and `states` use
lowercase ASCII vocabulary; `platformRole` preserves the OS spelling. Optional values are JSON
null, not omitted. A failed optional read stays null; readers do not invent zero bounds, an empty
value, or a disabled state. When bounds are null, `onScreen` is null too. Node order is window order
followed by pre-order traversal.

On Linux, `platformRole` uses the provider's `GetRoleName` result when available and the AT-SPI
role name for a known numeric ID otherwise. The reader reports `disabled` when a nonempty state set
lacks both `enabled` and `sensitive`. Some AccessKit buttons expose both flags even when the
application disables them. Their application-level disability cannot be determined from those
flags or from the absence of an Action interface.

## Errors and partial results

```json
{
  "ok": false,
  "error": {
    "code": "limit-reached",
    "message": "maximumNodeCount 10000 reached",
    "partialSnapshot": { "schemaVersion": 2, "source": "os-accessibility", "capturedAt": "2026-11-25T12:34:56.789Z", "windows": [], "nodes": [], "notes": [] }
  }
}
```

Error codes are `permission-missing`, `accessibility-disabled`, `target-not-found`,
`no-accessibility-tree`, `limit-reached`, `timeout`, and `restore-failed`. The last code makes a
failure to restore a Linux accessibility-bus change visible rather than relegating it to a log.
`limit-reached` must include the valid pre-order partial snapshot at the exact limit; `timeout` may
include one. Partial snapshots obey the same unique-key, parent-before-child, window-reference, and
coordinate-space invariants as complete snapshots. Other errors omit or set `partialSnapshot` to
null. Callers must preserve an unrecognised future error code, including its raw code, as a
structured failure rather than treating it as success. A structured error is still a valid
protocol response and the helper exits zero. Invalid
requests, corrupt output, and helper crashes use a non-zero exit and are daemon/helper integration
failures rather than target errors.

Platform readers must batch attribute reads and enforce one deadline over selection and traversal.
They check depth, node count, and time before expanding another node rather than first materialising
an unbounded descendant tree. Concurrent reads must be bounded and joined in source order so the
result remains deterministic.

## Platform reader invariants

### macOS AX

The reader checks `AXIsProcessTrusted()` without prompting. If it is false, it returns
`permission-missing` with the System Settings Accessibility link. It runs inside the signed
**Spectre Capture Helper.app**, so TCC grants belong to that exact signed app identity, not Terminal
or a copied Swift executable. It sets `AXUIElementSetMessagingTimeout`, uses
`AXUIElementCopyMultipleAttributeValues` for batch reads, and maps `AXIdentifier` to `id`.

AX bounds are screen coordinates. `onScreen` is nevertheless computed geometrically because AX can
return scrolled nodes far outside the window. A popover exposed as an `AXDialog` remains in its
owning window tree.

### Windows UI Automation

The reader walks UIA control view and uses a cache request containing every property and supported
pattern it reads. It reads every top-level element owned by a process because a popover can be a
second top-level UIA element, and records the owning window for each subtree. `AutomationId` maps to
`id` but is not assumed unique; the common key fallback still applies. UIA bounding rectangles are
screen coordinates.

Java Access Bridge is outside this reader. Until measured otherwise, a Compose/JVM window may be a
single opaque UIA element; this is not evidence that the native UIA reader failed.

### Linux AT-SPI

The reader connects through `org.a11y.Bus`, finds the application by PID, and prefers
`org.a11y.atspi.Cache.GetItems`; when unavailable, it uses bounded concurrent calls rather than a
serial property farm. `accessible_id` maps to `id`. AccessKit 0.25 text fields can omit both AT-SPI
`Text` and `Value`; their `value` stays null rather than being inferred elsewhere. `onScreen` is a
geometric intersection because AT-SPI `showing` can remain true for scrolled-out content.

X11 and Xvfb extents use `boundsSpace: screen`. GNOME Wayland extents are window-relative (the
window reports `(0, 0)`), so they use `boundsSpace: window` and are never relabelled as screen
coordinates. Compose/JVM registration depends on a separately installed Java ATK wrapper; the
helper does not install or activate it.

## Live verification

Live results are evidence, not a claim that every reader is released. They must record the helper
build, target build, host/session, complete command, response, and node counts. Never replace a
missing real-host result with fixture or recorded JSON output.

### macOS

On a fresh physical macOS host, the signed helper read an AccessKit fixture as **20 nodes by PID**
and the measured **20–21 nodes by title**, with a two-node bounded partial snapshot. It
distinguished both same-title owners and rejected a wrong-owner title constraint. Attached capture
through the packaged stdio MCP path produced a real PNG and measured **30 AX nodes versus 21
Compose nodes** in the current sample. Earlier diagnostics read stock Calculator as **35 nodes**
and found **11 buttons** in the original sample. These results verify those hosts and builds only;
hosted-runner TCC availability remains unproven.

Build and sign the app, grant that app Accessibility access, and invoke its embedded executable:

```bash
./gradlew :recording:assembleScreenCaptureKitHelper
APP="$PWD/recording/build/generated/screenCaptureHelper/native/macos/SpectreCaptureHelper.app"
HELPER="$APP/Contents/MacOS/spectre-screencapture"
codesign --force --deep --sign - "$APP"
open 'x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility'
```

Use the released fixture from the same checkout, then send its PID through the signed helper:

```bash
cargo build --locked --manifest-path fixtures/accesskit/Cargo.toml
fixtures/accesskit/target/debug/spectre-accesskit-fixture &
FIXTURE_PID=$!
printf '{"target":{"type":"process-id","value":%s},"maximumDepth":64,"maximumNodeCount":10000,"timeoutMs":750}\n' "$FIXTURE_PID" \
  | "$HELPER" accessibility-snapshot | tee /tmp/spectre-accesskit-ax.json
jq -e '.ok and .snapshot.schemaVersion == 2 and (.snapshot.nodes | length > 0) and
  ([.snapshot.nodes[].key] | length == (unique | length))' /tmp/spectre-accesskit-ax.json
kill "$FIXTURE_PID"
```

If this returns `permission-missing`, confirm the TCC row names the same signed app at `$APP`; an
ad-hoc rebuild can require granting it again. Before granting access, also verify the structured
error and zero exit status without a prompt. Repeat with `maximumNodeCount: 2` and check the exact
partial count, then exercise `window-id`, duplicate `window-title`, correct `processId`, and wrong
`processId` selection.

### Windows

Existing physical-host UIA evidence reads the AccessKit fixture as **21 nodes** by PID/two windows
and **20 nodes** by title/one window, with a typed two-node partial result. A fresh Character Map
instance measured **313 nodes**. The attached current sample measured only **8 UIA nodes**
(window chrome) versus **21 Compose nodes**, which is a Java-bridge limit rather than a blanket UIA
reader claim.

From an unlocked interactive PowerShell session, run the repository harness against the released
fixture in the same checkout:

```powershell
$root = (git rev-parse --show-toplevel)
$fixture = Join-Path $root "fixtures/accesskit"
dotnet publish "$root/recording/native/windows/SpectreWindowCapture.csproj" `
  -c Release -r win-x64 --self-contained false -o "$env:TEMP/spectre-uia-helper"
cargo build --locked --release --manifest-path "$fixture/Cargo.toml"
& "$root/recording/native/windows/test-accesskit-fixture.ps1" `
  -Helper "$env:TEMP/spectre-uia-helper/SpectreWindowCapture.exe" `
  -Fixture "$fixture/target/release/spectre-accesskit-fixture.exe" `
  -Golden "$fixture/golden/windows.json"
```

The harness must finish with `spectre-accesskit-helper: pass`. If it hangs, first confirm the
session is interactive and unlocked, then confirm the harness drains redirected standard output
and standard error concurrently; a large snapshot can otherwise fill a pipe before process exit.

### Linux

A cloud run through the real packaged AT-SPI helper and public MCP read the AccessKit fixture as
**14 nodes by PID and title**, including a typed two-node partial result. This verifies that
AccessKit path only. A seated GTK/Wayland run and the Java ATK bridge remain under test; no claim
for either path should be inferred from the cloud result. On GNOME Wayland, any bounds that are
available remain window-relative as described above.
