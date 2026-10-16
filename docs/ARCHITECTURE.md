# Architecture

## Module Structure

```text
spectre
├── core/                    — shared automation model and desktop automation primitives
├── input-coordinator/       — published local protocol, identity, client, and fencing tokens
├── input-coordinator-server/— published external FIFO coordinator process and recovery state
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
testing                 ─┴──> recording   (failure-video #206 uses AutoRecorder)
                               ↑
recording-macos    ─┐
recording-linux     ├──> recording (runtime helper artifacts)
recording-windows  ─┘
```

The modules follow these dependency rules:

- `core` defines reusable automation concepts.
- `core` implementation-depends only on `input-coordinator`; CLI, testing, and the attaching-side
  agent bring `input-coordinator-server`. The injected agent relocates only client/protocol classes
  and must never contain a server main or launcher.
- `server` transports `core` operations. It must not implement automation a second time.
- `sample-desktop` provides a test harness. Production automation belongs in the library modules.
- `testing` should use public APIs and reusable fixtures. Access private internals only when a
  test cannot verify the behaviour through a public path.
- The **shared contract-test corpus** in `:testing` checks transport parity across
  in-process, HTTP, and agent clients. It uses `AutomatorContractCorpus` and
  `CapabilityMatrix`. See the [capability matrix](guide/capability-matrix.md) and epic #197.
- `recording` isolates OS and native library behaviour from core query and input APIs.
  Platform helper artefacts carry runtime binaries. The API jar does not.

## Spike Constraints

The implementation must account for these Compose Desktop constraints:

1. Read semantics through the public `ComposeWindow.semanticsOwners` and
   `ComposePanel.semanticsOwners` properties.
2. Scope public node identifiers to their `SemanticsOwner`, or use compound identifiers.
   `SemanticsNode.id` is unique only within one owner.
3. Search for popup roots inside each window. Compose can keep popup layers on the same
   canvas.
4. Convert Compose semantics coordinates before passing them to Robot or AWT screen APIs.
   HiDPI scaling affects that conversion.
5. Check for a native `windowHandle` before selecting window recording. An embedded
   Swing-hosted Compose surface may not expose one.

## Planned Responsibility Split

### `core`

Expected long-term responsibilities:

- window/surface tracking
- semantics tree reading and normalisation
- owner/root-aware node identity
- selector/query API
- coordinate mapping between Compose, AWT, and Robot
- Robot-backed interaction primitives
- wait/synchronisation semantics

### `server`

Expected long-term responsibilities:

- request/response DTOs
- serialisation
- embedded HTTP server
- remote client

Keep transport-specific fields out of the core data model.

### Desktop input coordinator

A local process serialises `desktop-interaction` for each normalised OS user and desktop
key. Only its owner can access it. The coordinator manages FIFO acquisition, bounded
queues, heartbeats, epochs, fencing, restart quarantine, compare-and-revoke, and
audited unsafe takeover. Core input guards use a driver-bound JUnit lease first, then
a coroutine-scoped lease. They acquire an operation lease if neither exists. Queries,
waits, screenshots, and recording do not acquire that automatic lease. The agent runs
lease waiters on a bounded input worker to protect the general query pool.


### `cli`

The `cli` module provides the `spectre` client and the per-user session daemon contract.
It also owns the MCP entry point. Both frontends exchange versioned CBOR handshake messages
with the daemon before they send session commands.

The CLI and daemon protocol stays outside `agent-runtime`. The daemon can use `jdk.attach`
and agent transport. The runtime jar must remain small enough for arbitrary target JVMs.

### `recording`

Expected long-term responsibilities:

- screen/region capture orchestration
- platform recorder selection and helper extraction
- recorder lifecycle and output plumbing

Keep native capture interfaces small. Test pure logic separately from OS integration.
The published `spectre-recording` jar is API/common-only; it must not contain generated
`native/...` helper resources.

### `recording-macos`, `recording-linux`, and `recording-windows`

Expected long-term responsibilities:

- publish runtime-only helper resources for the recording API
- keep native binary payloads out of `spectre-recording` and its sources jar
- mirror the runtime resource paths the extractors probe:
  `native/macos/SpectreCaptureHelper.app`,
  `native/linux/<arch>/spectre-wayland-helper`, and
  `native/windows/<arch>/` multi-file Windows Graphics Capture helper tree
  (exe + companions; framework-dependent)

Current backends:

- `FfmpegRecorder` provides deprecated, explicit region capture through a system
  `ffmpeg` binary. `FfmpegBackend.detect()` chooses `avfoundation` on macOS,
  `gdigrab` on Windows, and `x11grab` on Linux Xorg. It rejects Linux Wayland;
  use the portal-backed Linux helper there.
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
  `spectre-recording-linux` helper and GStreamer `ximagesrc`. Window mode resolves
  an XID from the title and fails if that window is missing.
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
- `portal.WaylandPortalRecorder` captures Linux Wayland through `xdg-desktop-portal`.
  Region and monitor capture share a long-lived `spectre-wayland-helper --session`
  process with real OS input. That process uses RemoteDesktop, ScreenCast, host
  `Registry.Register`, and a Spectre-owned `restore_token` under
  `$XDG_STATE_HOME/spectre`. Window-source capture uses a separate one-shot ScreenCast
  helper. The `:recording-linux` artefact packages the helper. It passes the
  PipeWire FD to `gst-launch-1.0`.
- `AutoRecorder` selects a backend for each `startWindow(...)` or `startRegion(...)`
  call. It uses the Wayland portal on Wayland, macOS SCK on macOS, Windows Graphics
  Capture on Windows, and the Linux helper on Xorg/Xvfb. Each route selects a
  window or region backend for the requested mode.
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

Move reusable production logic out of the sample app and into its owning module.

### `sample-intellij-plugin`

The sample IntelliJ plugin verifies in-process automation of Jewel tool windows.
It is **never published**. The `runIde` checks cover popup discovery and `ComposePanel`
semantics. Production logic belongs in the library modules.

Run via `./gradlew :sample-intellij-plugin:runIde`, then `Tools → Run Spectre Against the
Sample Tool Window`. The action drives the in-process `ComposeAutomator` against the Jewel
tool window and dumps the discovered semantics tree to `idea.log`.

`./gradlew :sample-intellij-plugin:uiTest` runs the automated counterpart through
intellij-ide-starter (`#42`). It starts IntelliJ Ultimate in a child process and
installs the locally built plugin zip. The Driver API fires `RunSpectreAction`.
The test checks that `idea.log` contains every tagged Compose node from
`SpectreSampleToolWindowContent`. It uses the manual smoke assertions. This opt-in
test is outside `:check`. CI runs it through `.github/workflows/ide-uitest.yml`
when plugin, core, or recording sources change.

## Architectural Invariants

These should remain true as the codebase grows:

1. `core` stays usable in-process without requiring the server.
2. Selector/query logic belongs in `core`; the sample app and transport layer use that logic.
3. Transport modules serialise public/core-facing models. A strong compatibility reason is required
   to introduce a parallel model.
4. Platform-specific integrations should be isolated behind small interfaces at module boundaries.
5. Research-only shortcuts are acceptable in the sample app. Move reusable behaviour
   into the proper module before it is treated as part of the product surface.
