# Recording

Screen recording for Spectre scenarios. v1 region capture (ffmpeg) and v2 window-targeted
capture (ScreenCaptureKit) live side by side — pick by what you need.

## Public surface

### Region capture (v1)
- `Recorder` — interface; produces a `RecordingHandle` for an in-progress capture.
- `FfmpegRecorder` — v1 default. Shells out to a system `ffmpeg` and captures the requested
  region via the macOS avfoundation device. Resolves the binary via PATH (`resolveFfmpegPath()`)
  by default; pass `ffmpegPath` to override (testing, non-PATH installs).

### Window-targeted capture (v2, #18)
- `screencapturekit.ScreenCaptureKitRecorder` — forks a bundled Swift helper that drives
  `SCStream` + `AVAssetWriter` against a single window identified by `(pid, title-substring)`.
  Captures only that window's pixels even when partially occluded, and survives the host
  window moving across the screen. macOS only. Implements `WindowRecorder`.
- `screencapturekit.WindowRecorder` — interface for window-targeted backends. SCK is the only
  implementation today; future Windows / Linux backends would slot in here.

  Decision rationale + alternatives considered are written up in the bridge strategy doc kept
  in `.plans/`.

### Auto-routing (v2)
- `AutoRecorder` — high-level wrapper that takes both a `WindowRecorder` and a `Recorder`
  plus a `(window?, region, output, options)` per-call signature. Picks SCK when a window
  is supplied on macOS, falls back to ffmpeg region capture otherwise. If the SCK helper
  isn't bundled in the jar (e.g. built on Linux running on macOS) it degrades to ffmpeg
  with a stderr warning. Operational SCK failures (TCC denied, window not found) propagate
  unmodified — only `HelperNotBundledException` triggers the fallback.

### Shared
- `RecordingHandle` — `AutoCloseable`. Stop sends `q` on stdin (the documented clean-shutdown
  signal for both ffmpeg and the ScreenCaptureKit helper), waits up to 5 s for a graceful exit,
  then SIGTERM / SIGKILL fallback.
- `RecordingOptions` — frame rate, cursor capture, codec.
- `MacOsRecordingPermissions.diagnose()` — best-effort startup diagnostic for Screen Recording
  and Accessibility permissions. Full native detection (`CGPreflightScreenCaptureAccess`,
  `AXIsProcessTrusted`) is still deferred — the v2 helper detects TCC denial by exit code,
  which is the practical signal callers need.

## Capability matrix

| Capability | Status |
|---|---|
| macOS region capture (avfoundation) | ✅ `FfmpegRecorder` |
| Embedded `ComposePanel` (`windowHandle == 0L`) | ✅ region capture (window-targeted not applicable) |
| ScreenCaptureKit window-targeted capture | ✅ `ScreenCaptureKitRecorder` |
| Windows `gdigrab` recording | ⏸ v3 (#22) |
| Linux `x11grab` / pipewire recording | ⏸ v4 (#27) |
| Audio | ⏸ deferred — add when asked |
| Notarization of the SCK helper | ✅ release workflow signs and notarizes the universal helper |

## ScreenCaptureKit helper build

`recording/native/macos/` is a SwiftPM project that produces the `spectre-screencapture`
helper binary. The `assembleScreenCaptureKitHelper` Gradle task runs `swift build -c release`
and stages the binary into `src/main/resources/native/macos/`, so the JAR carries it
transparently. The Swift `swift build` step only runs on macOS hosts — non-macOS hosts
produce a structurally-correct jar that just doesn't contain the helper file (consumers see
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
4. Stages the universal binary into `src/main/resources/native/macos/spectre-screencapture`.

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

Spectre's recording module ships two native helpers — the macOS ScreenCaptureKit helper
(Swift, requires `swiftc` + macOS frameworks) and the Linux Wayland helper (Rust, requires
`cargo` + `libdbus-1-dev`). Neither toolchain works cross-host, so a single build host
can only produce a subset of helpers. The recording jar's layout is host-agnostic — the
non-producible directories are simply empty or absent depending on Gradle's jar-packing
behavior — and the recorder gates on resource presence at runtime, throwing
`HelperNotBundledException` with a clear message when the helper its current backend
needs is missing.

| Host                | Default `./gradlew :recording:build`                                 | `-PuniversalHelper`               | `-PallLinuxArches`                                      |
| ------------------- | -------------------------------------------------------------------- | --------------------------------- | ------------------------------------------------------- |
| macOS (arm64)       | host-arch SCK (`native/macos/spectre-screencapture` thin arm64)      | universal SCK (arm64 + x86_64)    | no-op (host can't build Wayland helper)                 |
| macOS (x86_64)      | host-arch SCK (thin x86_64)                                          | universal SCK (arm64 + x86_64)    | no-op (same)                                            |
| Linux x86_64        | host-arch Wayland (`native/linux/x86_64/spectre-wayland-helper`)     | no-op (host can't build SCK)      | x86_64 + aarch64 Wayland helpers                        |
| Linux aarch64       | host-arch Wayland (`native/linux/aarch64/spectre-wayland-helper`)    | no-op (same)                      | x86_64 + aarch64 Wayland helpers                        |
| Windows             | (no helpers produced)                                                | no-op                             | no-op                                                   |

### Project-property reference

- `-PuniversalHelper` — on macOS hosts, build the SCK helper as a universal arm64+x86_64
  binary via `lipo -create`. Off by default to keep contributor builds fast; on for
  distribution.
- `-PnotarizeScreenCaptureKitHelper` — implies `-PuniversalHelper`. Codesigns the
  universal helper, archives it, and submits it to Apple notarization via
  `xcrun notarytool`. Only used by the release workflow; needs Developer ID + notary
  credentials in env vars.
- `-PallLinuxArches` — on Linux hosts, cross-compile both `x86_64` and `aarch64` Wayland
  helpers from a single host. Requires the Rust `rustup target add` for the foreign
  arch and the matching cross-linker (`gcc-aarch64-linux-gnu` / `gcc-x86_64-linux-gnu`)
  plus the foreign-arch `libdbus-1-dev` sysroot. The CI workflow at
  `.github/workflows/ci.yml` installs all of these on the Linux runner.

### Verifying bundled helpers

`./gradlew :recording:check` runs `verifyBundledRecordingHelpers`, which inspects the
recording jar's staged resources and asserts the helpers that *should* be there given
the current host + project-property combination actually are, and that each one matches
its expected arch (lipo for the macOS helper, JVM-side ELF header parse for the Linux
helper). The task is verification-only — it never triggers a universal or cross-arch
build that wasn't already requested.

When `-PallLinuxArches` is set the task expects both `x86_64` and `aarch64` Wayland
helpers. CI invokes it as

    ./gradlew :recording:assembleWaylandHelperAllArches :recording:verifyBundledRecordingHelpers -PallLinuxArches

to lock in both-arch coverage explicitly.

### Publishing intent (not yet implemented — gated on #84)

The eventual publish flow merges artifacts from two release runners:

- A macOS runner builds the SCK helper with `-PuniversalHelper -PnotarizeScreenCaptureKitHelper`
  so `native/macos/spectre-screencapture` carries a notarized universal binary.
- A Linux runner builds the Wayland helpers with `-PallLinuxArches` so
  `native/linux/<arch>/spectre-wayland-helper` carries both supported architectures.

The merged jar then has the full helper set regardless of which platform a downstream
consumer runs Spectre on. Until #84's publish pipeline lands, building locally produces
a host-shaped subset of helpers — useful for development, not yet a distribution
artifact.
