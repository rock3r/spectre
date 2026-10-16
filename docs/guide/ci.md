# Running on CI

## Parallel real-input jobs

Desktop input coordination is experimental and opt-in. Callers need
`ExperimentalSpectreInputCoordinationApi`. See
[desktop input coordination](input-coordination.md). Validate real input on a graphical
desktop for each supported platform before release.

JUnit's in-memory locks do not coordinate separate Gradle forks or CI jobs.
Use `InputIsolationConfig.perTest()` for in-process JUnit tests that need real input.
Install `spectre-input-coordinator-server`; `spectre-testing`, CLI, and the attaching agent
already include it. Synthetic and headless tests can run in parallel unless they share
the system clipboard.

The coordinator serializes per normalized OS user/desktop identity and uses a short owner-only
local socket. X11 spellings such as `:0`, `:0.0`, and `unix/:0` converge; Wayland socket paths are
canonicalized where possible. Over-serialization is intentional when a platform cannot safely
distinguish sessions. Do not treat Xvfb evidence as Wayland real-input evidence: Robot input is not
claimed on Wayland.

Live Compose Desktop tests need a graphical test JVM.
GitHub Actions validation runs on Ubuntu 24.04 under `xvfb`, covering scripted RPC tests
and real-backend Compose Desktop tests.

## Test JVM flags

Set these properties on the Gradle `Test` task that starts the Spectre JVM:

```kotlin
tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "false")
    if (System.getProperty("os.name").lowercase().contains("linux")) {
        systemProperty("skiko.renderApi", "SOFTWARE_COMPAT")
    }
}
```

Spectre needs AWT, so set `-Djava.awt.headless=false`.
`xvfb` supplies a Linux display server. It cannot change a JVM that already runs headless
and ignores `DISPLAY`.

`xvfb-run` tests the Linux **X11** capture path (helper `ximagesrc`, JUnit
failure screenshots, agent screenshots, visual idle under a virtual framebuffer). It does
**not** prove Wayland portal consent, PipeWire one-frame capture, or restore-token behaviour.
When the host also has residual Wayland sockets or inherited Wayland env vars, Spectre still
routes to X11 for an Xvfb `DISPLAY` (#397). For portal coverage, run on a real Wayland
session separately. If detection disagrees with the actual session, set
`SPECTRE_CAPTURE_BACKEND=x11` (or `wayland`) explicitly.

GPU-less Linux runners need `-Dskiko.renderApi=SOFTWARE_COMPAT`.
Otherwise Skiko may throw `RenderException: Cannot create Linux GL context`.
The semantics tree can still appear, while typed input does nothing because the Skia
surface did not initialise fully.

## macOS helper JVMs

On macOS, `-Dapple.awt.UIElement=true` keeps the Spectre test JVM out of the Dock:

```kotlin
tasks.withType<Test>().configureEach {
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        systemProperty("apple.awt.UIElement", "true")
    }
}
```

`RobotDriver.synthetic(rootWindow = ...)` and per-character `typeText(...)` work in this mode.
Synthetic typing sends events into the window hierarchy without foreground Dock focus.
Clipboard-backed `pasteText(...)` uses macOS clipboard services and still needs
`apple.awt.UIElement=false`.

## macOS `sandbox-exec` runners

If a macOS runner uses `sandbox-exec`, its profile must allow the services used by AWT,
Swing, Compose Desktop, and `java.awt.Robot`. Set `-Djava.awt.headless=false` as well.
That JVM flag does not grant access to WindowServer, Core Animation, input, or capture services.

A working profile needs Mach lookup access to these services:

| Service                            | Why Spectre/Compose Desktop needs it                                                                                                                                      |
|------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `com.apple.hiservices-xpcservice`  | AWT/Compose Desktop startup. Missing access commonly logs `Connection Invalid error for service com.apple.hiservices-xpcservice`.                                         |
| `com.apple.windowserver.active`    | WindowServer integration for desktop windows.                                                                                                                             |
| `com.apple.windowmanager.server`   | Window-manager transactions. Without it, AppKit can log `WMClientWindowManager: Invalid connection` and `SLSTransaction commit with no context`.                          |
| `com.apple.CARenderServer`         | Actual Core Animation painting. Without it, `Frame.isVisible()`/`Frame.isShowing()` can both be `true` while no window appears on screen.                                 |
| `com.apple.tsm.uiserver`           | Text Services Manager and input plumbing.                                                                                                                                 |
| `com.apple.carboncore.csnameddata` | Carbon named data lookup used by the desktop stack.                                                                                                                       |
| `com.apple.dock.server`            | Dock/window integration.                                                                                                                                                  |
| `com.apple.dock.fullscreen`        | Full-screen/Dock integration used by AppKit.                                                                                                                              |
| `com.apple.iohideventsystem`       | Real `RobotDriver()` input requires OS-level input delivery. Synthetic Spectre input avoids that service.                                                                |
| `com.apple.tccd.system`            | TCC checks, including screen-capture and accessibility-style checks.                                                                                                      |
| `com.apple.replayd`                | `Robot.createScreenCapture(...)`, ScreenCaptureKit, and ReplayKit capture plumbing. Without it, a capture can hang after the `Robot` is created.                          |
| `com.apple.logd.events`            | Log/event integration observed on the Robot/capture path. `com.apple.diagnosticd` is noisy; require it only when the harness provides evidence.                         |

The services support these operations:

- **Window creation** needs HiServices, WindowServer/window-manager, Text Services,
  Carbon named data, and Dock integration.
- **Actual painting** needs `com.apple.CARenderServer`. Java visibility state is not
  proof that macOS has painted the window.
- **Real `Robot` input and screen capture** need the input/TCC/capture services,
  especially `com.apple.iohideventsystem`, `com.apple.tccd.system`, and
  `com.apple.replayd`.
- **Synthetic Spectre input** avoids OS-level mouse/keyboard delivery. The JVM
  still needs enough AWT/Compose Desktop access to create and paint the target
  window. Screenshots and `waitForVisualIdle()` still go through capture paths.

macOS Screen Recording permission is also required for capture. Grant it to the
app that launched the JVM (Terminal.app, iTerm2, IntelliJ IDEA, or the runner app),
then fully quit and restart that app. TCC can be granted correctly and capture can
still hang if the sandbox profile does not allow `com.apple.replayd`.

Use a new Gradle process when checking sandbox profile changes:

```shell
./gradlew --no-daemon spectreTest --tests '*SpectreSmokeTest'
```

A normal `./gradlew spectreTest ...` may reuse a daemon launched under an older
sandbox profile. Use `./gradlew --stop` only deliberately: if the desktop app or
runner itself was launched via Gradle, stopping all Gradle daemons can kill that
parent process too.

To check painting, show a small always-on-top Swing frame with a known colour.
Sample one pixel with `Robot.createScreenCapture(Rectangle(x, y, 1, 1))`.
If `Frame.isVisible()` and `Frame.isShowing()` are true while the pixel shows the desktop, inspect
`com.apple.CARenderServer` and `com.apple.windowmanager.server`.
If capture hangs after Robot creation, check Screen Recording permission and
`com.apple.replayd`.

The macOS `log` tool cannot run inside `sandbox-exec`:

```text
log: Cannot run while sandboxed
```

If the runner enforces `sandbox-exec`, provide a narrow diagnostic lane outside the
sandbox for read-only commands such as `log show ...`, `/usr/bin/log show ...`,
`log stream ...`, and `/usr/bin/log stream ...`. Keep it read-only: reject shell
metacharacters and mutating subcommands such as `log collect` and `log erase`. If
the policy logger supports reason codes, label this escape clearly, e.g.,
`macos-log-diagnostic-lane`.

Useful diagnostics:

```shell
/usr/bin/log show --last 3m --style compact --predicate 'process == "java" OR eventMessage CONTAINS "Sandbox:" OR eventMessage CONTAINS "ClientCallsAuxiliary" OR eventMessage CONTAINS "deny"'
/usr/bin/log show --last 2m --style compact --predicate 'processID == <PID> OR eventMessage CONTAINS "<PID>"'
```

Useful patterns to search for include `Sandbox: java(...) deny(1) mach-lookup`,
`Service "com.apple.CARenderServer" failed bootstrap look up`,
`WMClientWindowManager: Invalid connection`, `ScreenCaptureKit`, `ReplayKit`,
`com.apple.replayd`, and `kTCCServiceScreenCapture`.

## Runtime matrix (JBR / Temurin)

Pull request CI uses one Temurin 21 JDK. Compatibility across **JBR 21**,
**JBR 25**, and **Temurin LTS** (toolchain major) on macOS, Linux, and Windows is covered by
the scheduled [runtime-matrix](https://github.com/rock3r/spectre/blob/main/.github/workflows/runtime-matrix.yml)
workflow (epic #215 / issue #216):

| Dimension | Values |
| --- | --- |
| Runtime | JBR 21, JBR 25, Temurin LTS |
| OS | macOS, Linux (`xvfb`), Windows |
| Suites | Contract corpus (all OSes); agent attach same-runtime on Linux/macOS + mixed vanilla↔JBR on Linux; Linux X11 recording smoke. Full attach UI e2e is hosted on Linux/macOS; Windows cells run non-UI transport/ACL agent tests (UI attach e2e is opt-in on physical desktops via `-Pspectre.agent.attachE2e.allowWindows=true`). |

Pins live in [`.github/jbr-pins.env`](https://github.com/rock3r/spectre/blob/main/.github/jbr-pins.env)
(JBRSDK / `jdk` package). The `jbr_jcef` package is excluded. Bump procedure is in that file’s header comments.
`actions/setup-java` caches downloads per runner OS/arch via the composite action
`.github/actions/setup-matrix-jdk`.

`release.yml` calls the runtime matrix through `workflow_call` before building helpers
and publishing. A failed cell blocks the release.
Scheduled failures open issues labelled `runtime-matrix`.

`SPECTRE_MATRIX_JAVA_HOME` sets the JDK for `Test` and `JavaExec` workers in the root
`build.gradle.kts`. Modules still compile with `jvmToolchain(21)`.
Without this variable, a JBR 25 cell could run workers on toolchain JDK 21.

Mixed-runtime attach sets `SPECTRE_FIXTURE_JAVA_HOME` or
`-Pspectre.agent.fixtureJavaHome=…` for the fixture JVM.
The `:agent:test` task passes it into the forked test JVM as
`-Ddev.sebastiano.spectre.agent.fixtureJavaHome=…`.

## Recording tests

Tag recording tests separately from ordinary UI tests.
Linux CI can check Xorg or Xvfb region recording and other Spectre flows.
It cannot check macOS ScreenCaptureKit. The base `spectre-recording` artefact lacks the
macOS helper. macOS tests need `spectre-recording-macos` or a locally built helper
on the test runtime classpath.

For example:

```kotlin
tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        excludeTags("recording")
    }
}

val macRecordingTest by tasks.registering(Test::class) {
    useJUnitPlatform {
        includeTags("recording")
    }
    onlyIf { System.getProperty("os.name").lowercase().contains("mac") }
    systemProperty("java.awt.headless", "false")
}
```

## GitHub Actions example

Install `xvfb` on Linux and run the test task through `xvfb-run`.
Keep JVM flags in Gradle so local and CI runs use the same test configuration.

```yaml
jobs:
  spectre-ui-tests:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - name: Install xvfb
        run: sudo apt-get update && sudo apt-get install -y xvfb
      - name: Run Spectre UI tests
        run: xvfb-run --auto-servernum ./gradlew spectreTest
      - name: Upload Spectre failure artifacts
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: spectre-failure-artifacts
          path: "**/build/reports/spectre/**"
          if-no-files-found: ignore
      - name: Upload Spectre screenshot gold failures
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: spectre-screenshot-golds
          path: "**/build/reports/spectre-screenshots/**"
          if-no-files-found: ignore
```

```kotlin
val spectreTest by tasks.registering(Test::class) {
    description = "Runs live Compose Desktop UI tests with Spectre."
    group = "verification"
    useJUnitPlatform()
    systemProperty("java.awt.headless", "false")
    if (System.getProperty("os.name").lowercase().contains("linux")) {
        systemProperty("skiko.renderApi", "SOFTWARE_COMPAT")
    }
}
```

## Failure artifacts

On a JUnit failure, the extension or rule writes atomic captures under
`build/reports/spectre/`. See [JUnit integration](junit.md#failure-artifacts).
Upload that directory on CI failure to inspect `capture.json` and `screenshot.png`:

```yaml
- name: Upload Spectre failure artifacts
  if: failure()
  uses: actions/upload-artifact@v4
  with:
    name: spectre-failure-artifacts
    path: "**/build/reports/spectre/**"
    if-no-files-found: ignore
```

`if-no-files-found: ignore` allows jobs with no capture files.
For multi-module builds, use `**/build/reports/spectre/**` to include every module.

## Screenshot gold failures

Opt-in [screenshot golds](junit.md#screenshot-golds) write mismatch PNGs under
`build/reports/spectre-screenshots/`. Upload them separately.
The `**/build/reports/spectre/**` glob does not include this directory:

```yaml
- name: Upload Spectre screenshot gold failures
  if: failure()
  uses: actions/upload-artifact@v4
  with:
    name: spectre-screenshot-golds
    path: "**/build/reports/spectre-screenshots/**"
    if-no-files-found: ignore
```

Gold rewrite is off by default. `SPECTRE_UPDATE_SCREENSHOT_GOLDS=true` is read by the
test JVM with no Gradle forwarding. `-Pspectre.updateScreenshotGolds=true` only reaches
forked workers if the `Test` task forwards it as
`-Ddev.sebastiano.spectre.testing.updateScreenshotGolds=true` (Spectre's `:testing` tests
already do). The Gradle/system property wins over the environment variable,
including an explicit `false`. Consuming builds that set only the environment
variable must also run the `Test` task. Use `--rerun-tasks`,
`outputs.upToDateWhen { false }`, or `outputs.cacheIf { false }`. Spectre's
repository update helper does not apply to downstream `Test` tasks. An
`UP-TO-DATE` or `FROM-CACHE` skip starts no JVM.

## Failure video uploads

[Failure video](junit.md#failure-video) writes `failure-video.mp4` under
`build/reports/spectre/` when the policy keeps it.
The test runtime still needs the matching `spectre-recording-<os>` helper.
Select the upload condition for the configured policy:

### `FailureVideoPolicy.Off` (default)

No recorder starts and no video files are written. Upload **stills only on failure** (same as
[Failure artifacts](#failure-artifacts)):

```yaml
- name: Upload Spectre failure stills
  if: failure()
  uses: actions/upload-artifact@v4
  with:
    name: spectre-failure-artifacts
    path: "**/build/reports/spectre/**"
    if-no-files-found: ignore
```

### `FailureVideoPolicy.OnFailureKeep`

Video is kept **only when the test failed** (deleted on pass). Upload stills and
any kept videos share the same reports tree:

```yaml
- name: Upload Spectre failure stills and videos
  if: failure()
  uses: actions/upload-artifact@v4
  with:
    name: spectre-failure-artifacts
    path: "**/build/reports/spectre/**"
    if-no-files-found: ignore
```

### `FailureVideoPolicy.Always`

Videos are kept on **pass and fail**; assumption/abort skips produce no kept video. Use `if: always()` so
green jobs still publish videos; stills remain failure-only on disk, so the same glob is safe:

```yaml
- name: Upload Spectre reports (including Always policy videos)
  if: always()
  uses: actions/upload-artifact@v4
  with:
    name: spectre-reports
    path: "**/build/reports/spectre/**"
    if-no-files-found: ignore
```

With `FailureVideoPolicy.Always`, use `if: always()` for upload.
An `if: failure()` step skips videos from passing jobs, which are lost when the runner exits.

| Policy | When files remain on disk | Typical GHA `if:` |
| --- | --- | --- |
| `Off` | Stills on fail only | `failure()` |
| `OnFailureKeep` | Stills + video on fail | `failure()` |
| `Always` | Stills on fail; video on pass **and** fail | `always()` for video-bearing uploads |
