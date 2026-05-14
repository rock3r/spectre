# Security notes

Spectre is a JVM-first library for **automating live Compose Desktop UIs in trusted local /
test environments**. It drives real OS input, captures screenshots, and records screen content
by design. This page documents the trust boundaries Spectre relies on, the security-sensitive
capabilities it exposes, and the risks that are explicitly accepted for the pre-1.0 release.

Spectre is **pre-1.0**. The HTTP transport in the `server` module is **experimental** and is
expected to change.

## Reporting a vulnerability

Please email security reports to **spectre@sebastiano.dev**. Do not open a public GitHub
issue for security reports.

GitHub's private vulnerability reporting flow may also be used once it is enabled on this
repository.

## Trust boundaries

Spectre assumes the following trust model. Capabilities outside these boundaries are
out of scope.

1. **Test code is trusted.** A malicious test process can already run arbitrary JVM code.
   Spectre does not try to constrain what its callers can do; it only tries not to expose
   capabilities to unintended callers.
2. **The local OS is trusted.** Real `RobotDriver`, screenshots, and recording act with the
   privileges of the JVM process and the OS permissions granted to it (macOS TCC, Wayland
   portal, X server access, etc.).
3. **The HTTP transport assumes a trusted-local peer.** Routes registered by
   `installSpectreRoutes` expose click, keystroke, and screenshot capture to anyone who can
   reach the bound port. Bind to `127.0.0.1`. Anything network-reachable broadens the threat
   model beyond what this release covers.
4. **Bundled native helpers are trusted artifacts.** Spectre extracts and executes Swift
   (`spectre-screencapture`) and Rust (`spectre-wayland-helper`) helpers from the published jar
   resources. The extraction path is process-private; the helpers are launched with `argv`
   lists (never shell strings). Developer-only override env vars exist for local iteration
   and are explicitly documented as such — see the
   [`SPECTRE_WAYLAND_HELPER` note](#developer-only-override-env-vars) below.
5. **External binaries (ffmpeg, xprop, osascript) come from the host PATH.** Spectre does not
   pin versions and treats them as prerequisites of the host environment.

## Capabilities and their exposure

| Capability | Surface | Default exposure |
| --- | --- | --- |
| Move mouse / press keys | `RobotDriver.click`, `swipe`, `pressKey`, `scrollWheel` | In-process; trusted-local HTTP via `/spectre/click` |
| Modify clipboard | `RobotDriver.pasteText` (save / set / paste / restore) | In-process |
| Capture pixels | `RobotDriver.screenshot(region)` — **captures any rectangle of the virtual desktop**, not just the app under test | In-process; trusted-local HTTP via `/spectre/screenshot` |
| Record video | `AutoRecorder`, `FfmpegRecorder`, `ScreenCaptureKitRecorder`, `WaylandPortalRecorder` | In-process only |
| Execute a helper binary | `HelperBinaryExtractor` (SCK), `WaylandHelperBinaryExtractor` | Local file system, JVM process |
| Expose any of the above over HTTP | `installSpectreRoutes` mounts the windows / nodes / click / typeText / screenshot routes | **Unauthenticated, plaintext** — host application chooses bind address |

The HTTP exposure column is the most important one to internalise: there are **no auth
tokens, no TLS, and no origin checks** on any route. The transport is intentionally a
testing affordance for the same machine, not a remote-control protocol.

## What R5 changed

The pre-publishing security review (R5) made the following changes; everything else listed
under [Accepted risks](#accepted-risks-deferred-follow-ups) was deferred for a future,
separately reviewed pass.

- **Trust-boundary documentation** added on the user-facing cross-JVM guide, on
  `installSpectreRoutes`, on `ComposeAutomator.http(...)`, on `RobotDriver.screenshot`, and on
  the `SPECTRE_WAYLAND_HELPER` developer override.
- **Pinned loopback bind in the cross-JVM example.** The published worked example now passes
  `host = "127.0.0.1"` explicitly to `embeddedServer(...)`. Earlier guidance omitted the host
  argument; the explicit pin is engine-independent and removes any reliance on whichever
  default a given Ktor engine happens to apply.
- **Stopped echoing attacker-controlled content in HTTP error bodies.**
    - The server's decode-error mapping (`receiveOrRespond400`) responds with just the
      curated request type name (`Could not decode ClickRequest`) instead of interpolating
      the underlying `BadRequestException` message.
    - The `/click` malformed-key 400 response no longer interpolates the caller-supplied
      key string into the body.
    - The `/click` no-matching-node 404 response no longer echoes the caller-supplied
      `nodeKey`.
    - The HTTP client (`HttpComposeAutomator.click` / `typeText`) no longer interpolates
      `response.bodyAsText()` into the thrown `IllegalStateException` — peer body content
      cannot reflect into logs / test output through these exception messages.
- **New tests** in `HttpNegativeContractTest` pin all four no-echo behaviours so a future
  regression is caught at `./gradlew check`.

## Accepted risks / deferred follow-ups

These risks are accepted for the pre-1.0 release and tracked for later. Each entry names
the right venue: items requiring a security-design pass go to #96 (HTTP transport
expansion); items that are hygiene fixes get their own issues.

- **HTTP transport authentication / authorization.** No tokens, no headers, no principal
  checks. Tracked under #96.
- **TLS support on the HTTP client.** `HttpComposeAutomator` speaks plaintext only.
  Tracked under #96.
- **CORS / Origin policy on the HTTP routes.** No protection against a local browser
  reaching the loopback server. Tracked under #96.
- **Narrower screenshot API.** `RobotDriver.screenshot(region)` captures any rectangle on
  the display, including unrelated windows. A per-window / per-node API for remote callers
  is tracked under #96.
- **Recording output-path validation.** Spectre passes the caller-supplied output path
  through to ffmpeg / the helpers without rejecting `/dev/`, `/proc/`, symlinks, or
  not-yet-existing parents. Standalone follow-up issue, separate from #96.
- **`pasteText` clipboard-restore robustness.** A failure during the post-paste restore is
  swallowed via `runCatching` (clipboard may be left holding the typed text). Standalone
  follow-up issue.
- **Helper-extraction cleanup.** Extracted helper binaries are not `deleteOnExit`-registered.
  This is hygiene, not security (the extraction path is process-private and writable only by
  the user), but a tidy-up follow-up is worth tracking.
- **Supply-chain audit of Gradle plugins and external binaries** (ffmpeg, GStreamer,
  xdg-desktop-portal, OS APIs). Out of scope per the masterplan.

## Developer-only override env vars

The `SPECTRE_WAYLAND_HELPER` environment variable lets a developer point the recorder at a
locally-built helper binary without rebundling. It is **honored unconditionally** — there is
no signature check, hash check, or path constraint. Never set it in an environment that
ingests untrusted input. The bundled helper is the only supported configuration for non-dev
use.

## Out of scope for this review

The R5 review explicitly did not cover:

- Making the HTTP transport safe for arbitrary untrusted networks.
- Designing authentication / authorization for a production remote-control service.
- Sandboxing untrusted test code.
- Full supply-chain audit of third-party dependencies.
- True Wayland window-targeted / window-following capture beyond what the portal helper
  exposes.

If a follow-up review uncovers an issue in any of the above areas, please use the reporting
flow at the top of this page.
