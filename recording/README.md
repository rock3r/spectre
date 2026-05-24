# Recording

Screen recording and native still-window screenshots for Spectre scenarios. Region capture
(Windows Graphics Capture on Windows, ffmpeg on macOS, the Linux helper/GStreamer on
Xorg/Xvfb, xdg-desktop-portal on Linux Wayland), window-targeted video capture, and still
screenshot routing live side by side — pick by what you need.

The user-facing recorder capability matrix lives in
[`docs/guide/recording.md`](../docs/guide/recording.md). This README is the in-tree
implementer's view of the same module.

## Public surface

### Region capture
- `Recorder` — interface; produces a `RecordingHandle` for an in-progress capture.
- `FfmpegRecorder` — deprecated legacy explicit ffmpeg-backed region capture. Shells out to a system `ffmpeg` and
  captures the requested region via `avfoundation` on macOS, legacy explicit `gdigrab` on
  Windows, and legacy explicit `x11grab` on Linux Xorg. Throws on Linux Wayland — use
  `WaylandPortalRecorder` instead. Resolves the
  binary via `PATH` (`resolveFfmpegPath()`) by default; pass `ffmpegPath` to override (testing,
  non-`PATH` installs).
- `LinuxX11Recorder` — Linux Xorg/Xvfb region and named-window capture through the bundled
  Linux helper and GStreamer `ximagesrc`. This is the default Linux Xorg/Xvfb route used by
  `AutoRecorder`; `ffmpeg` is no longer required for that path. The helper currently accepts
  only `RecordingOptions.codec = "libx264"` or `"x264enc"` for recording.

### Window-targeted capture
- `screencapturekit.ScreenCaptureKitRecorder` — forks a bundled Swift helper that drives
  `SCStream` + `AVAssetWriter` against a single window identified by `(pid, title-substring)`.
  Captures only that window's pixels even when partially occluded, and survives the host
  window moving across the screen. macOS only. Implements `WindowRecorder`.
- `screencapturekit.WindowRecorder` — interface for window-targeted backends. SCK is the
  macOS implementation; `windows.WindowsGraphicsCaptureRecorder` covers Windows, and
  `LinuxX11Recorder`/`WaylandPortalWindowRecorder` cover Linux Xorg/Xvfb and Wayland.
- `windows.WindowsGraphicsCaptureRecorder` — WGC + `MediaTranscoder` MP4 capture on Windows.
  Window mode follows the window across moves and captures the target window surface
  instead of a screen rectangle. Region/fullscreen mode records a fixed monitor rectangle.
  Both modes use the `spectre-recording-windows` runtime helper.
- `FfmpegWindowRecorder` — deprecated legacy explicit `gdigrab title=` capture on Windows. Follows the
  window across moves and resizes; requires a non-blank exact title and `ffmpeg` on `PATH`.
- `portal.WaylandPortalWindowRecorder` — Linux Wayland window-targeted capture via
  `xdg-desktop-portal`'s `SourceType.WINDOW`. Captures only the picked window's pixels (no
  leakage from occluding apps), but Spectre's crop is fixed at `start()` time — if the user
  moves or resizes the window during the recording, the crop no longer aligns with the
  window's pixels and the output is misaligned. Requires `xprop` and a compositor publishing
  `_GTK_FRAME_EXTENTS` (validated on GNOME/Mutter for GTK/XWayland windows; non-GTK toolkits
  and other compositors may not publish it).

  Decision rationale + alternatives considered are written up in the bridge strategy doc kept
  in `.plans/`.

### Auto-routing
- `AutoRecorder` — high-level wrapper that takes a `WindowRecorder`, a `Recorder`, and the
  Linux portal recorders (when wired). Routing is Wayland-first (window-targeted portal,
  region portal, or loud failure if no portal recorder is wired), then macOS SCK for
  window capture, Windows Graphics Capture for Windows window/region capture, Linux helper
  capture for Linux Xorg/Xvfb window/region capture, and ffmpeg region capture for macOS.
  If the Windows WGC helper
  artifact is absent, `startRegion(...)` falls back to the legacy ffmpeg `gdigrab`
  region path for compatibility. It also uses ffmpeg for Windows region calls with custom
  `RecordingOptions.codec` or `screenIndex`, because the WGC backend cannot honour those
  ffmpeg-specific knobs. Operational native-helper failures after the helper is found
  (permissions denied, window not found, helper crash) propagate with actionable messages
  instead of silently changing capture semantics.
- `AutoScreenshotter` — high-level still-image router. Uses ScreenCaptureKit window capture
  on macOS, Windows Graphics Capture on Windows, and the Linux helper on Xorg/Xvfb and
  Wayland. Wayland still screenshots require the compositor portal dialog to be accepted.
  The explicit `FfmpegRegionScreenshotter` and `FfmpegWindowScreenshotter` classes are
  deprecated legacy escape hatches.
- `./gradlew :recording:runWindowScreenshotSmoke` — manual cross-platform smoke for
  `AutoScreenshotter`. It writes a PNG on macOS, Windows, Linux Xorg/Xvfb, and Linux
  Wayland. For Wayland window-source video, run `./gradlew :recording:runWaylandPortalWindowSmoke`.

### Shared
- `RecordingHandle` — `AutoCloseable`. Stop uses the backend's clean-shutdown path (`q` for
  ffmpeg/SCK-style helpers, JSON `Stop` for the Linux helper), waits for a graceful exit, then
  escalates to process termination when the backend supports that lifecycle.
- `RecordingOptions` — frame rate, cursor capture, codec.
  Custom codecs are ffmpeg-backend-specific today; the Linux helper/GStreamer path rejects
  non-x264 codec strings until encoder pipelines are represented structurally.
- `MacOsRecordingPermissions.diagnose()` — best-effort startup diagnostic for Screen Recording
  and Accessibility permissions. Full native detection (`CGPreflightScreenCaptureAccess`,
  `AXIsProcessTrusted`) is a future improvement — the SCK helper detects TCC denial by exit
  code, which is the practical signal callers need.

## Capability matrix

| Capability | Status |
|---|---|
| macOS region capture (`avfoundation`) | ✅ deprecated `FfmpegRecorder` |
| macOS window-targeted capture (ScreenCaptureKit) | ✅ `ScreenCaptureKitRecorder` |
| Embedded `ComposePanel` (`windowHandle == 0L`) | ✅ region capture (window-targeted not applicable) |
| Windows region/fullscreen capture (Windows Graphics Capture) | ✅ `WindowsGraphicsCaptureRecorder` |
| Windows legacy region capture (`gdigrab`) | ✅ deprecated `FfmpegRecorder` |
| Windows window-targeted capture (Windows Graphics Capture) | ✅ `WindowsGraphicsCaptureRecorder` |
| Windows legacy window-targeted capture (`gdigrab title=`) | ✅ deprecated `FfmpegWindowRecorder` |
| Linux Xorg/Xvfb region/window capture (`ximagesrc`) | ✅ `LinuxX11Recorder` |
| Linux Xorg legacy explicit region capture (`x11grab`) | ✅ deprecated `FfmpegRecorder` |
| Linux still screenshots (`ximagesrc` or portal/PipeWire) | ✅ `LinuxNativeScreenshotter` |
| Linux Wayland region capture (xdg-desktop-portal) | ✅ `WaylandPortalRecorder` |
| Linux Wayland window-targeted capture (xdg-desktop-portal `SourceType.WINDOW`) | ✅ `WaylandPortalWindowRecorder` (fixed crop at `start()` — moves and resizes during recording break alignment) |
| Audio | ⏸ deferred — add when asked |
| Notarization of the SCK helper | ✅ release workflow signs and notarizes the universal helper |

## ScreenCaptureKit helper build

`recording/native/macos/` is a SwiftPM project that produces the `spectre-screencapture`
helper binary. The `assembleScreenCaptureKitHelper` Gradle task runs `swift build -c release`
and stages the binary under `build/generated/screenCaptureHelper/native/macos/`, then
wires that generated directory into the JAR resources transparently. The Swift `swift
build` step only runs on macOS hosts — non-macOS hosts produce a structurally-correct jar
that just doesn't contain the helper file (consumers see
`HelperBinaryExtractor`'s "binary not found" message at runtime if they try to use SCK).

**Distribution must be built on macOS.** A jar published from a Linux CI runner won't carry
the helper, so any macOS consumer of that jar would fail with "helper binary not found" the
first time `ScreenCaptureKitRecorder.start()` runs. The release workflow builds on
`macos-latest`, signs and notarizes the universal helper, then packages the recording jar.

For local dev the default `:recording:assembleScreenCaptureKitHelper` builds host-arch only
— faster iteration. The universal `arm64+x86_64` binary is opt-in via a project property:

```bash
# Default: host-arch helper, fast.
./gradlew :recording:jar

# Distribution: universal helper bundled in the jar instead.
./gradlew :recording:jar -PuniversalHelper
```

The `-PuniversalHelper` flag swaps `processResources`'s dependency from
`assembleScreenCaptureKitHelper` to `assembleScreenCaptureKitHelperUniversal` at
configuration time. Only ever one staging task is in the graph, so there's no race over
which binary ends up bundled. The universal task pipeline:

1. Builds the helper twice via `swift build --triple <arch>-apple-macosx13.0` (once per
   arch).
2. `lipo -create`s them into a fat binary at
   `recording/build/generated/screenCaptureHelperUniversal/SpectreScreenCapture`.
3. Verifies the result via `lipo -verify_arch arm64 x86_64` — exits non-zero (fails the
   build) if any expected arch isn't present, so a thin binary can never sneak through.
4. Stages the universal binary into generated resources at
   `native/macos/spectre-screencapture`.

**Both paths only need the macOS Command Line Tools.** The `--triple` + `lipo` recipe
deliberately avoids `swift build --arch arm64 --arch x86_64` (which delegates to `xcbuild`
and requires a full Xcode install). `lipo` ships with CLT.

Universal builds roughly double the helper build time (two `swift build` invocations + the
`lipo` step), which is why the default stays single-arch. The release workflow sets
`-PuniversalHelper` so distribution jars always contain the fat binary.

## ScreenCaptureKit helper notarization

The distribution path is opt-in so local development does not require Apple credentials:

```bash
APPLE_DEVELOPER_IDENTITY="Developer ID Application: Example, Inc. (TEAMID1234)" \
APPLE_NOTARY_KEYCHAIN_PROFILE="<notary-profile>" \
./gradlew :recording:jar -PuniversalHelper -PnotarizeScreenCaptureKitHelper
```

Create the keychain profile once with:

```bash
xcrun notarytool store-credentials <notary-profile> \
  --apple-id "developer@example.com" \
  --team-id "TEAMID1234"
```

Let `notarytool` prompt for the app-specific password. Do not pass the password on the
command line: macOS process listings can expose command arguments while notarization is
running.

For local consistency with Compose Desktop apps, the same task also accepts these user Gradle
properties from `~/.gradle/gradle.properties`:

```properties
compose.desktop.mac.signing.identity=Developer ID Application: Example Developer (TEAMID)
compose.desktop.mac.notarization.keychainProfile=<notary-profile>
```

CI should use App Store Connect API key auth instead of an app-specific password. Set
`APPLE_NOTARY_API_KEY_PATH`, `APPLE_NOTARY_API_KEY_ID`, and `APPLE_NOTARY_API_ISSUER`; the
release workflow writes the key file from a base64 secret before invoking Gradle.

The Gradle pipeline:

1. Builds the universal helper with the `arm64` + `x86_64` SwiftPM/lipo path described above.
2. Signs the Mach-O with `codesign --options runtime --timestamp --force`.
3. Archives the signed helper with `ditto -c -k --keepParent` because `notarytool submit`
   expects an archive for this shape of software.
4. Submits the archive with `xcrun notarytool submit --keychain-profile ... --wait --timeout
   30m`.
5. Runs `codesign --verify --strict --verbose=4` against the signed helper before staging it
   into the jar resources.

Apple's notary service creates tickets for standalone binaries inside the submitted archive,
but Apple's stapler does not currently attach tickets directly to bare command-line
executables. `spctl --assess --type execute` also reports a bare helper as valid code that is
not an app. That is why Spectre notarizes and verifies the helper signature but does not run
`xcrun stapler staple` or use `spctl` as the Gradle gate for `SpectreScreenCapture`.
Gatekeeper can fetch the ticket online on first run; the jar-bundled helper is still
Developer ID signed and notarized.

### GitHub release secrets

The tag workflow in
[`../.github/workflows/release.yml`](https://github.com/rock3r/spectre/blob/main/.github/workflows/release.yml)
expects these repository secrets:

| Secret | Purpose |
|---|---|
| `APPLE_DEVELOPER_ID_P12` | Base64-encoded Developer ID Application `.p12`. |
| `APPLE_DEVELOPER_ID_P12_PASSWORD` | Password for that `.p12` export. |
| `APPLE_SIGNING_KEYCHAIN_PASSWORD` | Temporary CI keychain password. Generate a long random value. |
| `APPLE_DEVELOPER_IDENTITY` | Exact `codesign` identity, for example `Developer ID Application: Example, Inc. (TEAMID1234)`. |
| `APPLE_NOTARY_API_KEY` | Base64-encoded App Store Connect API `.p8` key. |
| `APPLE_NOTARY_API_KEY_ID` | App Store Connect API key ID. |
| `APPLE_NOTARY_API_ISSUER` | App Store Connect API issuer UUID. |

To rotate the certificate:

1. Create or renew a Developer ID Application certificate in the Apple Developer portal.
2. Export it from Keychain Access as a password-protected `.p12`.
3. Base64-encode it with `base64 -i DeveloperID.p12 | pbcopy`.
4. Update `APPLE_DEVELOPER_ID_P12`, `APPLE_DEVELOPER_ID_P12_PASSWORD`, and
   `APPLE_DEVELOPER_IDENTITY` in the repository secrets.
5. Rotate the App Store Connect API key secrets if needed.
6. Push a test tag and verify the `Release` workflow reaches the `codesign --verify` step.

### Manual end-to-end smoke
After granting the host JVM Screen Recording permission, point a `ScreenCaptureKitRecorder`
at any titled window owned by the same JVM:

```kotlin
import dev.sebastiano.spectre.recording.screencapturekit.ScreenCaptureKitRecorder
import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow

val recorder = ScreenCaptureKitRecorder()
val handle = recorder.start(
    window = composeWindow.asTitledWindow(), // public adapter for any java.awt.Frame
    output = Paths.get("/tmp/spectre-sck.mov"),
)
// drive the UI...
handle.stop() // restores the original title, finalises the .mov
```

`asTitledWindow()` is a `Frame.() -> TitledWindow` extension that works against
`ComposeWindow`, `JFrame`, `JDialog.parent`, or any other `java.awt.Frame`. If you have a
non-Frame window-like object, implement `TitledWindow` directly — it's a single mutable
`title: String?` property.

The bundled `:recording:runScreenCaptureKitSmoke` Gradle task does this against a tiny Swing
JFrame and dumps a Robot screenshot alongside for cross-checking.

### Implementation note: retain `SCStreamOutput` strongly

`SCStream.addStreamOutput` reads as if it strongly retains the output for the stream's
lifetime, but in practice (macOS 26 / SCK as of this writing) the only delivered frame in
repeated runs was the very first sample buffer if the output was held by a local variable
that went out of scope after `startCapture`. The helper now retains its `FrameOutput` (and
`SCStreamDelegate`) on the recorder instance — this is the difference between "one frame for
the whole recording" and "frames at the configured rate". Keep it that way.

## Permissions

The JVM running Spectre needs two macOS permissions for recording to work:
- **Screen Recording** — for ffmpeg's avfoundation capture, for `ScreenCaptureKitRecorder`'s
  helper subprocess, and for AWT `Robot` screenshots.
- **Accessibility** — for AWT `Robot` mouse / keyboard control.

Grant them under **System Settings → Privacy & Security**, targeting whichever process is
hosting Spectre (your IDE, your test runner, or a standalone `java`). Restart the JVM after
granting — macOS does not refresh TCC entitlements live. `MacOsRecordingPermissions.diagnose()`
returns a human-readable summary you can dump at startup to surface this to developers.

The SCK helper is a child process, so it inherits the host JVM's TCC grant — no separate
permission grant needed for it.

## Build matrix: which helpers each host can produce

Spectre's recording module ships three native helpers — the macOS ScreenCaptureKit helper
(Swift, requires `swiftc` + macOS frameworks), the Linux capture helper (Rust, requires
`cargo` + `libdbus-1-dev`), and the Windows Graphics Capture helper (.NET 8 SDK). The
macOS and Linux toolchains do not work cross-host, while the Windows helper is built on
Windows for both x64 and arm64. The recording jar's layout is host-agnostic — the
non-producible directories are simply empty or absent depending on Gradle's jar-packing
behavior — and the recorder gates on resource presence at runtime, throwing
`HelperNotBundledException` with a clear message when the helper its current backend
needs is missing.

| Host                | Default `./gradlew :recording:build`                                 | `-PuniversalHelper`               | `-PallLinuxArches`                                      |
| ------------------- | -------------------------------------------------------------------- | --------------------------------- | ------------------------------------------------------- |
| macOS (arm64)       | host-arch SCK (`native/macos/spectre-screencapture` thin arm64)      | universal SCK (arm64 + x86_64)    | no-op (host can't build Linux helper)                   |
| macOS (x86_64)      | host-arch SCK (thin x86_64)                                          | universal SCK (arm64 + x86_64)    | no-op (same)                                            |
| Linux x86_64        | host-arch Linux helper (`native/linux/x86_64/spectre-wayland-helper`) | no-op (host can't build SCK)      | x86_64 + aarch64 Linux helpers                          |
| Linux aarch64       | host-arch Linux helper (`native/linux/aarch64/spectre-wayland-helper`) | no-op (same)                      | x86_64 + aarch64 Linux helpers                          |
| Windows             | x64 + arm64 WGC helper (`native/windows/<arch>/spectre-window-capture.exe`) | no-op                             | no-op                                                   |

### Project-property reference

- `-PuniversalHelper` — on macOS hosts, build the SCK helper as a universal arm64+x86_64
  binary via `lipo -create`. Off by default to keep contributor builds fast; on for
  distribution.
- `-PnotarizeScreenCaptureKitHelper` — implies `-PuniversalHelper`. Codesigns the
  universal helper, archives it, and submits it to Apple notarization via
  `xcrun notarytool`. Only used by the release workflow; needs Developer ID + notary
  credentials in env vars.
- `-PallLinuxArches` — on Linux hosts, cross-compile both `x86_64` and `aarch64` Linux
  helpers from a single host. Requires the Rust `rustup target add` for the foreign
  arch and the matching cross-linker (`gcc-aarch64-linux-gnu` / `gcc-x86_64-linux-gnu`)
  plus the foreign-arch `libdbus-1-dev` sysroot. The CI workflow at
  `.github/workflows/ci.yml` installs all of these on the Linux runner.

### Verifying bundled helpers

`./gradlew :recording-macos:check` and `./gradlew :recording-linux:check` verify the
platform helper jars, not the API-only `:recording` jar. `./gradlew :recording-windows:check`
does the same for the Windows helper artifact. Each helper module packages the
generated resources staged by `:recording`'s native build tasks:

- `:recording-macos` expects `native/macos/spectre-screencapture` when a mac helper can
  be produced or provided.
- `:recording-linux` expects `native/linux/<arch>/spectre-wayland-helper` on Linux.
- `:recording-windows` expects both `native/windows/x64/spectre-window-capture.exe` and
  `native/windows/arm64/spectre-window-capture.exe` on Windows.

When `-PallLinuxArches` is set the task expects both `x86_64` and `aarch64` Linux
helpers. CI invokes it as

    ./gradlew :recording-linux:verifyRecordingLinuxHelpers -PallLinuxArches

to lock in both-arch coverage explicitly.

### Release packaging

The tag-driven release workflow builds and notarizes the macOS helper on a macOS runner,
builds both Linux helper architectures on a Linux runner, then publishes three recording
artifacts:

- `spectre-recording` — API/common JVM implementation only.
- `spectre-recording-macos` — signed and notarized universal ScreenCaptureKit helper.
- `spectre-recording-linux` — x86_64 and aarch64 Linux helpers.
- `spectre-recording-windows` — x64 and arm64 Windows Graphics Capture helpers.

Local builds still produce a host-shaped subset of helpers unless you opt into the
distribution-oriented flags above.
