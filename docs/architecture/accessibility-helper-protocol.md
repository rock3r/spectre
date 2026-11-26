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
  "timeoutMs": 750
}
```

`target.type` is either `process-id` (numeric OS PID) or `window-id` (a string preserving an OS
window handle or identifier without numeric truncation). All three limits are positive. Depth
counts the window root as zero. The helper must stop work by `timeoutMs`; the daemon also kills a
helper that outlives the deadline plus a small process-exit allowance.

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
null, not omitted. Node order is window order followed by pre-order traversal.

On Linux, `platformRole` uses the provider’s `GetRoleName` result when available and the
AT-SPI role name for a known numeric ID otherwise. The reader reports `disabled` when a
nonempty state set lacks both `enabled` and `sensitive`. Some AccessKit buttons expose both
flags even when the application disables them. Their application-level disability cannot be
determined from those flags or from the absence of an Action interface.

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
`no-accessibility-tree`, `limit-reached`, and `timeout`. `limit-reached` must include the valid
pre-order partial snapshot; `timeout` may include one. Other errors omit or set `partialSnapshot`
to null. A structured error is still a valid protocol response and the helper exits zero. Invalid
requests, corrupt output, and helper crashes use a non-zero exit and are daemon/helper integration
failures rather than target errors.

Platform readers must batch attribute reads. macOS runs inside the signed **Spectre Capture
Helper.app**, so Accessibility and Screen Recording grants belong to that app. Windows uses the
packaged C# helper and UI Automation. Linux uses the packaged Rust helper and AT-SPI; if it enables
the bus it records that in `notes`, and if policy forbids doing so returns
`accessibility-disabled`.
