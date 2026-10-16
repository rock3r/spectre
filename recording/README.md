# Recording

The `recording` module provides region video, window video, and native window screenshots.
macOS uses ScreenCaptureKit, Windows uses Windows Graphics Capture, and Linux uses a helper
with GStreamer on Xorg/Xvfb or xdg-desktop-portal on Wayland.

The [recording guide](../docs/guide/recording.md) lists user-facing capabilities.
This README describes implementation and packaging.

## Public surface

### Region capture
- `Recorder` — interface; produces a `RecordingHandle` for an in-progress capture.
- `FfmpegRecorder` — deprecated legacy explicit ffmpeg-backed region capture. Shells out to a system `ffmpeg` and
  captures the requested region via `avfoundation` on macOS, legacy explicit `gdigrab` on
  Windows, and legacy explicit `x11grab` on Linux Xorg. On Linux Wayland, use
  `WaylandPortalRecorder`; `FfmpegRecorder` throws. The ffmpeg backend resolves the
  binary via `PATH` (`resolveFfmpegPath()`) by default; pass `ffmpegPath` to override (testing,
  non-`PATH` installs).
- `LinuxX11Recorder` — Linux Xorg/Xvfb region and named-window capture through the bundled
  Linux helper and GStreamer `ximagesrc`. Window mode resolves a real XID from the title
  (`WM_NAME` / `_NET_WM_NAME`) and fails if that window is missing. It never records
  the root/desktop on a missing title. `AutoRecorder` uses this path on Xorg/Xvfb,
  where it does not require `ffmpeg`. The helper currently accepts only
  `RecordingOptions.codec = "libx264"` or `"x264enc"` for recording.
- `screencapturekit.ScreenCaptureKitRecorder` — macOS ScreenCaptureKit region and
  window-targeted capture through the bundled Swift helper. Region mode records a fixed
  rectangle from the selected display; window mode follows the target window and captures
  only its backing store.

### Window-targeted capture
- `screencapturekit.ScreenCaptureKitRecorder` — forks a bundled Swift helper that drives
  `SCStream` + `AVAssetWriter` against either a fixed display region or a single window
  identified by `(pid, title-substring)`. Window capture records only that window's pixels
  even when partially occluded, and survives the host window moving across the screen. macOS
  only. Implements `Recorder` and `WindowRecorder`.
- `screencapturekit.WindowRecorder` — interface for window-targeted backends. SCK is the
  macOS implementation; `windows.WindowsGraphicsCaptureRecorder` covers Windows, and
  `LinuxX11Recorder`/`WaylandPortalWindowRecorder` cover Linux Xorg/Xvfb and Wayland.
- `windows.WindowsGraphicsCaptureRecorder` — WGC + `MediaTranscoder` MP4 capture on Windows.
  Window mode follows the target window and captures its surface.
  Region and fullscreen modes record a fixed monitor rectangle.
  Both modes use the `spectre-recording-windows` runtime helper.
- `FfmpegWindowRecorder` — deprecated legacy explicit `gdigrab title=` capture on Windows. Follows the
  window across moves and resizes; requires a non-blank exact title and `ffmpeg` on `PATH`.
- `portal.WaylandPortalWindowRecorder` — Linux Wayland window-targeted capture via
  `xdg-desktop-portal`'s `SourceType.WINDOW`. It captures only the selected window's pixels.
  The crop stays fixed from `start()`. Moving or resizing the window can misalign output.
  It requires `xprop` and a compositor publishing
  `_GTK_FRAME_EXTENTS` (validated on GNOME/Mutter for GTK/XWayland windows; non-GTK toolkits
  and other compositors may not publish it).

### Auto-routing
- `AutoRecorder` selects from platform recorders and, when configured, Linux portal
  recorders. On Wayland it selects a window or region portal recorder. It fails
  if the required portal recorder is absent. macOS uses SCK for window and
  region capture. Windows uses Windows Graphics Capture for both. Linux Xorg/Xvfb
  uses the Linux helper. On macOS and Windows, a missing native helper or an
  unsupported option such as a custom `RecordingOptions.codec` causes an error.
  Permission errors, missing windows, and helper crashes also surface after
  discovery. `AutoRecorder` keeps the requested capture mode.
- `AutoScreenshotter` — high-level still-image router. Uses ScreenCaptureKit window capture
  on macOS, Windows Graphics Capture on Windows, and the Linux helper on Xorg/Xvfb and
  Wayland. Wayland still screenshots require the compositor portal dialog to be accepted.
  The explicit `FfmpegRegionScreenshotter` and `FfmpegWindowScreenshotter` classes are
  deprecated legacy escape hatches.
- `./gradlew :recording:runWindowScreenshotSmoke` — manual cross-platform smoke for
  `AutoScreenshotter`. It writes a PNG on macOS, Windows, Linux Xorg/Xvfb, and Linux
  Wayland. For Wayland window-source video, run `./gradlew :recording:runWaylandPortalWindowSmoke`.
- `./gradlew :recording:runLinuxX11RecordingSmoke` — Linux Xorg/Xvfb helper smoke (CI uses
  `xvfb-run`). Records a region and a named window whose title includes `·`, asserts the
  window-mode MP4 matches the window dimensions, and checks that a
  missing title fails closed.

### Shared
- `RecordingHandle` — `AutoCloseable`. Stop uses the backend's clean-shutdown path (`q` for
  ffmpeg/SCK-style helpers, JSON `Stop` for the Linux helper), waits for a graceful exit, then
  escalates to process termination when the backend supports that lifecycle.
- `RecordingOptions` — frame rate, cursor capture, codec, and display index. For macOS SCK
  region capture, `screenIndex` is primary display first, then by display frame `minX` and
  `minY`; explicit legacy `FfmpegRecorder` callers still get avfoundation device ordering.
  Custom codecs are legacy ffmpeg-backend-specific today; the SCK/WGC/native-helper paths
  reject unsupported codec strings until encoder pipelines are represented structurally.
- `MacOsRecordingPermissions.diagnose()` — startup guidance for Screen Recording and
  Accessibility permissions. The bundled helper uses `CGPreflightScreenCaptureAccess`
  for Screen Recording checks. `spectre permissions check` reports that result;
  `spectre permissions request` opens a permission guide for a human.

## Capability matrix

| Capability | Status |
|---|---|
| macOS region capture (ScreenCaptureKit) | ✅ `ScreenCaptureKitRecorder` |
| macOS legacy region capture (`avfoundation`) | ✅ deprecated `FfmpegRecorder` |
| macOS window-targeted capture (ScreenCaptureKit) | ✅ `ScreenCaptureKitRecorder` |
| Embedded `ComposePanel` (`windowHandle == 0L`) | ✅ region capture; window-targeted capture is unavailable |
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

`recording/native/macos/` is a SwiftPM project that builds `spectre-screencapture`.
`assembleScreenCaptureKitHelper` runs `swift build -c release` and stages the binary under
`build/generated/screenCaptureHelper/native/macos/`. `:recording-macos` packages those
resources into its JAR. Swift builds run only on macOS.
Other hosts can produce a JAR without the helper. `HelperBinaryExtractor` then reports
"binary not found" if a consumer tries to use ScreenCaptureKit.

**Distribution requires a macOS-built helper.** The release workflow builds, signs,
and notarises the universal helper on `macos-latest`. The Linux publish job packages
that prebuilt helper in `spectre-recording-macos`.

For local work, `:recording:assembleScreenCaptureKitHelper` builds only for the host
architecture. Enable the universal `arm64+x86_64` binary with a project property:

```bash
# Default: host-arch helper, fast.
./gradlew :recording-macos:jar

# Distribution: universal helper bundled in the jar instead.
./gradlew :recording-macos:jar -PuniversalHelper
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
   `native/macos/SpectreCaptureHelper.app`.

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
./gradlew :recording-macos:jar -PuniversalHelper -PnotarizeScreenCaptureKitHelper
```

Create the keychain profile once with:

```bash
xcrun notarytool store-credentials <notary-profile> \
  --apple-id "developer@example.com" \
  --team-id "TEAMID1234"
```

Enter the app-specific password at the `notarytool` prompt.
Command arguments can appear in macOS process listings, so do not pass the password there.

For local consistency with Compose Desktop apps, the same task also accepts these user Gradle
properties from `~/.gradle/gradle.properties`:

```properties
compose.desktop.mac.signing.identity=Developer ID Application: Example Developer (TEAMID)
compose.desktop.mac.notarization.keychainProfile=<notary-profile>
```

CI uses an App Store Connect API key. Set `APPLE_NOTARY_API_KEY_PATH`,
`APPLE_NOTARY_API_KEY_ID`, and `APPLE_NOTARY_API_ISSUER`.
The release workflow writes the key file from a base64 secret before running Gradle.

The Gradle pipeline:

1. Builds the universal helper with the `arm64` + `x86_64` SwiftPM/lipo path described above.
2. Signs the Mach-O with `codesign --options runtime --timestamp --force`.
3. Archives the signed helper with `ditto -c -k --keepParent` because `notarytool submit`
   expects an archive for this shape of software.
4. Submits the archive with `xcrun notarytool submit --keychain-profile ... --wait --timeout
   30m`.
5. Runs `codesign --verify --strict --verbose=4` against the signed helper before staging it
   into the jar resources.

Apple's notary service creates tickets for standalone binaries in the submitted archive.
Stapler cannot attach those tickets directly to bare command-line executables.
`spctl --assess --type execute` reports the helper as valid code that is not an app.
Spectre therefore verifies the signature without using `xcrun stapler staple` or
`spctl` as the Gradle gate for `SpectreScreenCapture`.
Gatekeeper can fetch the ticket online on first run. The JAR contains the signed,
notarised helper.

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
    output = Paths.get("/tmp/spectre-sck.mp4"),
)
// drive the UI...
handle.stop() // restores the original title, finalises the .mp4
```

`asTitledWindow()` is a `Frame.() -> TitledWindow` extension that works against
`ComposeWindow`, `JFrame`, `JDialog.parent`, or any other `java.awt.Frame`. For a
non-Frame window-like object, implement `TitledWindow` directly. It has a single mutable
`title: String?` property.

The `:recording:runScreenCaptureKitSmoke` task records a small Swing `JFrame` and writes
a Robot screenshot for comparison.

### Implementation note: retain `SCStreamOutput` strongly

On macOS 26, repeated tests delivered only the first frame when `FrameOutput` was held
in a local variable that went out of scope after `startCapture`.
The helper now retains `FrameOutput` and `SCStreamDelegate` on the recorder instance.
Keep these strong references so `SCStream` continues delivering frames.

## Permissions

macOS permissions depend on the capture or input path:

- **Screen Recording for Spectre Capture Helper** — the bundled
  `SpectreCaptureHelper.app` owns ScreenCaptureKit capture permission. The Settings row
  must say **Spectre Capture Helper**. The helper does not inherit the host JVM's grant.
- **Accessibility for the input host** — real AWT `Robot` mouse and keyboard control needs
  this permission for the app hosting the JVM. Synthetic input does not need it.
- Legacy ffmpeg capture and AWT `Robot` screenshots use the host's Screen Recording
  permission. The bundled helper's grant does not apply to them.

Run `spectre permissions check` to inspect the helper's grant.
With a person present, run `spectre permissions request` to open the guide.
In **System Settings → Privacy & Security → Screen Recording**, add and enable the helper.
Wait for **Done ✓**. Capture commands report missing permission without opening the guide.

macOS may ask for approval again after an update or when a prior grant lapses. Run the
request command again if this happens. See [troubleshooting](../docs/guide/troubleshooting.md).

## Build matrix: which helpers each host can produce

The recording module ships three native helpers. The macOS ScreenCaptureKit helper
needs Swift, `swiftc`, and macOS frameworks. The Linux Rust helper needs `cargo`
and `libdbus-1-dev`. The Windows Graphics Capture helper needs .NET 8 SDK and
builds on Windows for x64 and arm64. The macOS and Linux toolchains require their
respective hosts. Each helper jar uses a host-independent layout. Gradle leaves
unavailable directories empty or absent. At runtime, the recorder checks for the
required resource and throws `HelperNotBundledException` if it is missing.

| Host                | Default platform-helper build                                        | `-PuniversalHelper`               | `-PallLinuxArches`                                      |
| ------------------- | -------------------------------------------------------------------- | --------------------------------- | ------------------------------------------------------- |
| macOS (arm64)       | host-arch SCK (`native/macos/SpectreCaptureHelper.app` thin arm64)      | universal SCK (arm64 + x86_64)    | no-op (host can't build Linux helper)                   |
| macOS (x86_64)      | host-arch SCK (thin x86_64)                                          | universal SCK (arm64 + x86_64)    | no-op (same)                                            |
| Linux x86_64        | host-arch Linux helper (`native/linux/x86_64/spectre-wayland-helper`) | no-op (host can't build SCK)      | x86_64 + aarch64 Linux helpers                          |
| Linux aarch64       | host-arch Linux helper (`native/linux/aarch64/spectre-wayland-helper`) | no-op (same)                      | x86_64 + aarch64 Linux helpers                          |
| Windows             | x64 + arm64 WGC helper (`native/windows/<arch>/spectre-window-capture.exe`) | no-op                             | no-op                                                   |

### Project-property reference

- `-PuniversalHelper` — on macOS hosts, build the SCK helper as a universal arm64+x86_64
  binary via `lipo -create`. The option is off by default to keep contributor builds fast;
  enable it for distribution.
- `-PnotarizeScreenCaptureKitHelper` — implies `-PuniversalHelper`. Codesigns the
  universal helper, archives it, and submits it to Apple notarization via
  `xcrun notarytool`. The release workflow uses this option and requires Developer ID + notary
  credentials in environment variables.
- `-PallLinuxArches` — on Linux hosts, cross-compile both `x86_64` and `aarch64` Linux
  helpers from a single host. Requires the Rust `rustup target add` for the foreign
  arch and the matching cross-linker (`gcc-aarch64-linux-gnu` / `gcc-x86_64-linux-gnu`)
  plus the foreign-arch `libdbus-1-dev` sysroot. The CI workflow at
  `.github/workflows/ci.yml` installs all of these on the Linux runner.

### Verifying bundled helpers

`./gradlew :recording-macos:check` and `./gradlew :recording-linux:check` verify the
platform helper jars. The API-only `:recording` jar has a separate check. `./gradlew :recording-windows:check`
does the same for the Windows helper artefact. Each helper module packages the
generated resources staged by `:recording`'s native build tasks:

- `:recording-macos` expects `native/macos/SpectreCaptureHelper.app` when a mac helper can
  be produced or provided.
- `:recording-linux` expects `native/linux/<arch>/spectre-wayland-helper` on Linux.
- `:recording-windows` expects a complete multi-file Windows Graphics Capture helper for
  both `x64` and `arm64` under `native/windows/<arch>/` (exe + managed assembly + deps/
  runtimeconfig + WASDK/Win2D companions; see
  `WindowsGraphicsCaptureHelperPackagingContract`) when helpers are expected.

When `-PallLinuxArches` is set the task expects both `x86_64` and `aarch64` Linux
helpers. CI invokes it as

    ./gradlew :recording-linux:verifyRecordingLinuxHelpers -PallLinuxArches

to lock in both-arch coverage explicitly.

### Release packaging

The tag-driven release workflow builds and notarizes the macOS helper on a macOS runner,
builds both Linux helper architectures on a Linux runner, then publishes three recording
artefacts:

- `spectre-recording` — API/common JVM implementation only.
- `spectre-recording-macos` — signed and notarized universal ScreenCaptureKit helper.
- `spectre-recording-linux` — x86_64 and aarch64 Linux helpers.
- `spectre-recording-windows` — x64 and arm64 Windows Graphics Capture helpers.

Local builds produce helpers for the host unless the distribution flags above are enabled.
