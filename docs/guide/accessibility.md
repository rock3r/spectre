# Accessibility tree

Spectre can read the native accessibility tree that the operating system gives to a screen
reader. This source does not attach an agent and is therefore the right view for Rust, C++,
AccessKit, and other non-JVM applications. It is also useful beside a Compose semantics tree: the
two snapshots show what the app intended and what the OS actually exposed.

```shell
spectre a11y tree --pid 12345
spectre a11y tree --window "Spectre AccessKit Fixture"
```

The MCP `accessibility_tree` tool accepts the same `pid` or `window` target. Both public surfaces
return a schema-version-2 native snapshot with wire value `source: "os-accessibility"`. For an attached session, `spectre capture <session> --source accessibility` and MCP `capture` with
`source: "accessibility"` write the native tree beside the screenshot; `compose` is the default.

Schema version 2 is a discriminated contract, not permission to treat every document as the same
shape. The direct `a11y tree` / `accessibility_tree` result is not a `capture.json` artifact. It
has `windows`, native nodes with `name` and `bounds`, and `notes`. Inspect `source` and the command
result before selecting fields; do not infer
the shape from `schemaVersion` alone.

## What is read

The snapshot normalises native roles, names, values, states, and bounds while retaining the
platform role and platform identifier. It is a read-only tree: finding
or clicking accessibility nodes by selector is not part of this release.

Node `key` values prefer a path made from platform identifiers. Identifiers are not necessarily
unique, so Spectre disambiguates repeated siblings and falls back to an index path where no
identifier exists. Either path describes only **one snapshot**. Re-read the tree and resolve the
node again after navigation, scrolling, or any UI update; never persist a key as application data.

## Limits and errors

Each read is bounded by maximum depth, node count, and timeout. If a depth or node-count limit is
reached, the native protocol returns the typed `limit-reached` error with a coherent pre-order
`partialSnapshot`; callers must not mistake it for a complete tree. A timeout may also carry a
partial snapshot. The other typed target errors — `permission-missing`, `accessibility-disabled`,
`target-not-found`, and `no-accessibility-tree` — do not promise one. Invalid helper output and
helper crashes are integration failures rather than empty trees.

Every non-root node has a `parentKey` that names another node in the same snapshot. A platform
window wrapper can be the root, so do not assume the AccessKit `fixture-window` node itself has
`parentKey: null`; follow its parent chain. Optional values are explicit JSON `null`, and `jq` consumers should
use null-aware tests rather than assuming that a missing text value is an empty string:

```shell
spectre a11y tree --pid 12345 | jq '.nodes[] | select(.id == "account-name") | {key, parentKey, value}'
```

## Platform permissions and coordinates

| OS | Permission and behaviour | Bounds |
| --- | --- | --- |
| macOS | The signed **Spectre Capture Helper.app** needs Accessibility permission in System Settings. A denial is reported as an error, not an empty tree; the helper never opens the prompt implicitly. | Global screen points. |
| Windows | UI Automation normally needs no prompt. The framework-dependent helper requires the .NET 8 Desktop Runtime and a same-architecture public Windows App Runtime 1.8 installation; the CBS system package alone is insufficient. Integrity boundaries still apply: a normal process cannot inspect an elevated application. | Global screen pixels. |
| Linux | Spectre temporarily sets the session `org.a11y.Status.IsEnabled` value while it reads, then restores the previous value. The CLI prints a one-line notice when it enables the session; MCP includes the same notice in its result. This is necessary because AccessKit applications register with AT-SPI only while session accessibility is enabled. | Screen coordinates on X11. On GNOME Wayland, AccessKit bounds are **window-relative** and the output labels them as such. They must not be used as screen coordinates for input or crops. |

Do not hide or discard the Linux enablement notice: it explains a session-wide accessibility state
change and whether restoration succeeded.

## Known gaps

- JVM applications on Windows and Linux do not reliably publish their full Compose semantics
  through the native accessibility bridge. An attached Compose tree can therefore be richer than
  the OS tree. This command reports what the OS exposes; it does not synthesize missing nodes.
- AccessKit text fields on Linux currently expose no readable value through AT-SPI. A field can be
  present as raw role `79` (`entry`) with name, bounds, and states while `value` remains `null`.
- Do not infer whether an AccessKit button is enabled on Linux from the AT-SPI Action interface or
  native state words. The fixture's disabled button and its enabled, offscreen peer both omit the
  Action interface and expose the same state words. Spectre preserves both nodes without inventing
  a disabled state that this provider does not expose.
- GNOME Wayland does not provide a global window origin through this accessibility path. Obtain a
  trusted window origin separately before converting window-relative bounds.
- Platform visibility flags are not trusted for scrolled content. Spectre derives `onScreen` from
  the node and window bounds.

## Verified native-reader baselines

Release validation uses exact, real-reader results rather than a synthetic JSON substitute. Use
the released AccessKit fixture under `fixtures/accesskit/` from the **same checkout** as the
packaged CLI; do not substitute a fixture pinned to an older revision. Standalone packaged-CLI
proofs measured 20 nodes by PID and 20–21 nodes by title on macOS, 21 nodes/two windows by PID and
20 nodes/one window by title on Windows, and 14 nodes by both PID and title on Linux. A two-node
budget on every platform must fail with the typed `limit-reached` result and a two-node partial
snapshot. These checks use the fixture's
official platform roles: AX `AXWindow`/`AXButton`/`AXCheckBox`/`AXTextField`, UIA
`Window`/`Button`/`CheckBox`/`Edit`, and AT-SPI raw role ids/names `23`/`frame`,
`43`/`button`, `7`/`check box`, and `79`/`entry`. AccessKit's native D-Bus `GetRoleName` is
unimplemented, but `GetRole` was independently verified in a native Wayland session with
`DISPLAY` unset. Spectre validates these raw roles and normalised
`window`/`button`/`checkbox`/`entry` roles; it does not relabel `entry` or infer a Linux disabled state.

Attached capture cross-checks use a real PNG and the packaged stdio MCP path. The current sample
measured 30 AX nodes versus 21 Compose nodes on macOS; on Windows it measured eight native UIA
nodes (window chrome only) versus 21 Compose nodes. Earlier diagnostic probes found 35 nodes in
macOS Calculator, 11 buttons in the original sample, and 313 nodes in Windows Character Map.
Those applications are diagnostic references, not substitutes or fallbacks for the deterministic
fixture. Linux cloud validation covers the real packaged AT-SPI helper and CLI path; the separate
MCP smoke cell supplies the public MCP transport evidence. Native GNOME 46 evidence confirmed 14
AccessKit nodes, a direct GTK Zenity probe exposed 16 nodes, and an Xvfb Java ATK-wrapper sample
exposed 31 OS nodes for a 22-node Compose tree. These measurements establish provider integration,
not semantic parity.

There is no unsafe fallback: permission denial, missing native helpers, incomplete limit typing, a
changed node count, or a broken parent chain fails the release cell rather than producing an empty
or synthetic pass.

## Capability status

Native macOS, Windows, and Linux readers are tracked as **Not yet CI-executed** in the
[capability matrix](capability-matrix.md). The per-OS AccessKit release-smoke scenarios are hard
pre-tag checks, but they are not CI evidence. Keep that state until an independent workflow has
actually executed each native cell.
