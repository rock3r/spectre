# Architecture

## Module Structure

```text
spectre
├── core/                    — shared automation model and desktop automation primitives
├── server/                  — optional transport layer for cross-JVM access
├── cli/                     — agent-facing CLI / daemon / MCP entrypoint
├── recording/               — screenshot / recording API and common JVM implementation
├── recording-macos/         — runtime-only macOS ScreenCaptureKit helper artifact
├── recording-linux/         — runtime-only Linux capture helper artifact
├── recording-windows/       — runtime-only Windows Graphics Capture helper artifact
├── testing/                 — test fixtures and JUnit-facing helpers built on top of public APIs
├── sample-desktop/          — small manual-test harness app for spike validation
└── sample-intellij-plugin/  — sample IntelliJ plugin embedding Spectre in a Jewel tool window
```

## Dependency Direction

```text
sample-desktop          ─┐
sample-intellij-plugin   ├──> core
server                   │
testing                 ─┘

recording                (isolated desktop/native integration API)
recording-macos    ─┐
recording-linux     ├──> recording (runtime helper artifacts)
recording-windows  ─┘
```

Guidelines:

- `core` owns the reusable automation concepts.
- `server` should be transport-only glue over `core`, not a second implementation.
- `sample-desktop` is a harness, not the source of truth for automation logic.
- `testing` should exercise public seams and reusable fixtures, not reach through private internals
  without a strong reason.
- Transport parity (in-process / HTTP / agent) is enforced by a **shared contract-test corpus** in
  `:testing` (`AutomatorContractCorpus` + `CapabilityMatrix`), not by a single shared automator
  interface — see [capability matrix](guide/capability-matrix.md) and epic #197.
- `recording` should isolate OS- and native-library-specific behaviour from the core query/input
  APIs. Runtime helper binaries live in platform helper artifacts, not the API jar.

## Spike Constraints

These constraints are already known from the current research and should shape implementation:

1. `ComposeWindow.semanticsOwners` and `ComposePanel.semanticsOwners` are public and should be the
   primary read path.
2. `SemanticsNode.id` is only unique within a single `SemanticsOwner`. Public identifiers must be
   owner-scoped or compound.
3. Popup discovery cannot assume separate windows. Extra semantics roots may appear within the same
   window, especially when Compose layers stay on the same canvas.
4. HiDPI/AWT conversion is subtle. Compose semantics coordinates must be converted carefully before
   feeding Robot/AWT screen APIs.
5. Embedded Swing-hosted Compose surfaces may not expose a usable native `windowHandle`, so
   window-targeted recording cannot be assumed everywhere.

## Planned Responsibility Split

### `core`

Expected long-term responsibilities:

- window/surface tracking
- semantics tree reading and normalization
- owner/root-aware node identity
- selector/query API
- coordinate mapping between Compose, AWT, and Robot
- Robot-backed interaction primitives
- wait/synchronization semantics

### `server`

Expected long-term responsibilities:

- request/response DTOs
- serialisation
- embedded HTTP server
- remote client

Keep server concerns out of the core data model unless they are genuinely transport-independent.


### `cli`

The `cli` module is the agent-facing entrypoint track for issue #173. It owns the thin
`spectre` client surface, the long-lived per-user session daemon contract, and the future
`mcp` facade. The first checked-in surface is the client↔daemon handshake protocol: both
frontends must speak the same versioned CBOR messages before issuing session commands.

The CLI/daemon protocol intentionally lives outside `agent-runtime`: the daemon may use
`jdk.attach` and the existing agent transport, while the runtime jar must stay thin enough
to load into arbitrary target JVMs.

### `recording`

Expected long-term responsibilities:

- screen/region capture orchestration
- platform recorder selection and helper extraction
- recorder lifecycle and output plumbing

Keep native capture boundaries narrow and test the pure pieces separately from OS integration.
The published `spectre-recording` jar is API/common-only; it must not contain generated
`native/...` helper resources.

### `recording-macos`, `recording-linux`, and `recording-windows`

Expected long-term responsibilities:

- publish runtime-only helper resources for the recording API
- keep native binary payloads out of `spectre-recording` and its sources jar
- mirror the runtime resource paths the extractors probe:
  `native/macos/spectre-screencapture`,
  `native/linux/<arch>/spectre-wayland-helper`, and
  `native/windows/<arch>/spectre-window-capture.exe`

Current backends:

- `FfmpegRecorder` — deprecated legacy explicit region capture via a system `ffmpeg`
  binary, with the input device picked per OS by `FfmpegBackend.detect()`: `avfoundation`
  on macOS, `gdigrab` on Windows, and legacy explicit `x11grab` on Linux Xorg. (Linux
  Wayland is rejected here; use the portal-backed Linux helper path instead.)
- `windows.WindowsGraphicsCaptureRecorder` — Windows-only MP4 capture via the shared
  .NET Windows Graphics Capture helper packaged by `:recording-windows` for x64 and
  arm64. Window mode follows movement automatically and ignores occluders; region mode
  records a fixed monitor rectangle and is also used for fullscreen recording.
- `FfmpegWindowRecorder` — deprecated legacy explicit Windows-only window-targeted capture via
  `gdigrab title=`. Window movement is followed automatically; occlusion doesn't matter.
- `windows.WindowsWindowScreenshotter` — Windows-only still window screenshots via a
  shared framework-dependent .NET Windows Graphics Capture helper packaged by
  `:recording-windows` for x64 and arm64.
- `LinuxX11Recorder` — Linux Xorg/Xvfb region and named-window capture via the
  `spectre-recording-linux` helper and GStreamer `ximagesrc`.
- `LinuxNativeScreenshotter` — Linux still screenshots via the same helper: GStreamer
  `ximagesrc` on Xorg/Xvfb, and one-frame portal/PipeWire capture on Wayland.
- `FfmpegRegionScreenshotter` — deprecated legacy explicit Linux X11 still screenshot
  fallback via one-frame `x11grab` region capture; the target must be visible and frontmost
  because this is not true window capture.
- `screencapturekit.ScreenCaptureKitRecorder` — macOS-only window-targeted and region
  capture via a Swift helper (`recording/native/macos/`). The helper is built by Gradle on
  macOS, staged under `recording/build/generated/screenCaptureHelper/...`, and packaged by
  `:recording-macos`.
- `screencapturekit.ScreenCaptureKitScreenshotter` — macOS-only still window screenshots
  through the same Swift helper in `--mode screenshot`.
- `portal.WaylandPortalRecorder` — Linux Wayland capture via `xdg-desktop-portal`'s
  ScreenCast interface, driven by a Rust helper
  (`recording/native/linux/spectre-wayland-helper`) packaged by `:recording-linux`.
  The helper hands the PipeWire FD to `gst-launch-1.0`.
- `AutoRecorder` — high-level router that picks per call from `startWindow(...)` /
  `startRegion(...)` + OS detection: Wayland portal first, then macOS SCK for window and
  region capture, Windows Graphics Capture for window and region capture, and Linux helper
  capture for Linux Xorg/Xvfb window and region capture.
- `AutoScreenshotter` — high-level still screenshot router: macOS SCK window source,
  Windows Graphics Capture, and Linux helper still capture on Xorg/Xvfb and Wayland.

### `testing`

Expected long-term responsibilities:

- JUnit rules/extensions
- reusable fixtures for validation of public APIs
- focused contract-test helpers for transport, selectors, and geometry

### `sample-desktop`

Expected long-term responsibilities:

- tiny exploratory app for manual spike validation
- reproducible surfaces for popup, focus, scrolling, and coordinate tests

Do not let the sample app become a dumping ground for production logic.

### `sample-intellij-plugin`

A minimal IntelliJ plugin used to validate that Spectre's in-process automator works against
IDE-hosted Compose surfaces (Jewel-on-IntelliJ tool windows). The plugin is **never
published** — it exists only for `runIde` validation against #13's checklist (popup
discovery and `ComposePanel` semantics in the IDE-hosted case). Same constraint as
`sample-desktop`: do not move production logic here.

Run via `./gradlew :sample-intellij-plugin:runIde`, then `Tools → Run Spectre Against the
Sample Tool Window`. The action drives the in-process `ComposeAutomator` against the Jewel
tool window and dumps the discovered semantics tree to `idea.log`.

The non-interactive counterpart is `./gradlew :sample-intellij-plugin:uiTest`
(intellij-ide-starter, `#42`). It boots a real IntelliJ Ultimate IDE in a child process,
installs the locally-built plugin zip, fires `RunSpectreAction` through the Driver API, and
asserts every tagged Compose node from `SpectreSampleToolWindowContent` appears in
`idea.log`. Same assertions as the manual smoke, no human in the loop. Opt-in (not wired into
`:check`); CI runs it in `.github/workflows/ide-uitest.yml` when plugin/core/recording
sources change.

## Architectural Invariants

These should remain true as the codebase grows:

1. `core` stays usable in-process without requiring the server.
2. Selector/query logic lives in `core`, not in the sample app or transport layer.
3. Transport modules serialise public/core-facing models instead of inventing parallel ones unless
   there is a strong compatibility reason.
4. Platform-specific integrations should be isolated behind small interfaces at module boundaries.
5. Research-only shortcuts are acceptable in the sample app, but reusable behaviour must be moved
   into the proper module before it is treated as part of the product surface.
