# Running on CI

Spectre can run live Compose Desktop tests on CI as long as the test JVM is a real desktop JVM.
GitHub Actions Linux runners are validated on Ubuntu 24.04 under `xvfb` for both scripted-RPC
tests and real-backend Compose Desktop tests.

## Test JVM flags

Set these on the Gradle `Test` task that hosts Spectre, not only on the runner process:

```kotlin
tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "false")
    if (System.getProperty("os.name").lowercase().contains("linux")) {
        systemProperty("skiko.renderApi", "SOFTWARE_COMPAT")
    }
}
```

`-Djava.awt.headless=false` is required because Spectre needs the AWT toolkit. `xvfb` gives
Linux a display server, but it cannot help if the JVM has already decided to run headless and
ignore `DISPLAY`.

`-Dskiko.renderApi=SOFTWARE_COMPAT` is required on GPU-less Linux runners. Without it, Skiko's
default OpenGL path can throw `RenderException: Cannot create Linux GL context`. The semantics
tree may still compose, so selectors such as `findByTestTag` can pass, but typed input can
silently no-op because the Skia surface is not fully wired. Software rendering is the reliable
path when the runner has no GPU.

## macOS helper JVMs

On macOS, add `-Dapple.awt.UIElement=true` when you want the Spectre test JVM to stay out of the
Dock:

```kotlin
tasks.withType<Test>().configureEach {
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        systemProperty("apple.awt.UIElement", "true")
    }
}
```

This is safe with `RobotDriver.synthetic(rootWindow = ...)` and per-character `typeText(...)`.
Synthetic typing posts key events into the window hierarchy and does not require the app to be
the foreground Dock application. Clipboard-backed `pasteText(...)` still needs
`apple.awt.UIElement=false`, because that path goes through macOS clipboard services.

## macOS `sandbox-exec` runners

If your local agent, test harness, or CI wrapper launches Gradle inside a macOS
`sandbox-exec` profile, the sandbox must allow the desktop services used by AWT,
Swing, Compose Desktop, and `java.awt.Robot`. This is separate from JVM headless
mode: `-Djava.awt.headless=false` is still required, but it does not grant access
to the WindowServer, Core Animation, input, or screen-capture services.

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
| `com.apple.iohideventsystem`       | Real `Robot` input paths. Synthetic Spectre input does not need OS-level input delivery, but real `RobotDriver()` does.                                                   |
| `com.apple.tccd.system`            | TCC checks, including screen-capture and accessibility-style checks.                                                                                                      |
| `com.apple.replayd`                | `Robot.createScreenCapture(...)`, ScreenCaptureKit, and ReplayKit capture plumbing. Without it, a capture can hang after the `Robot` is created.                          |
| `com.apple.logd.events`            | Log/event integration observed on the Robot/capture path. Treat `com.apple.diagnosticd` as noisy rather than a baseline requirement unless your harness proves otherwise. |

Think of the requirements in layers:

- **Window creation** needs HiServices, WindowServer/window-manager, Text Services,
  Carbon named data, and Dock integration.
- **Actual painting** needs `com.apple.CARenderServer`. Java visibility state is not
  proof that macOS has painted the window.
- **Real `Robot` input and screen capture** need the input/TCC/capture services,
  especially `com.apple.iohideventsystem`, `com.apple.tccd.system`, and
  `com.apple.replayd`.
- **Synthetic Spectre input** avoids OS-level mouse/keyboard delivery, but the JVM
  still needs enough AWT/Compose Desktop access to create and paint the target
  window. Screenshots and `waitForVisualIdle()` still go through capture paths.

macOS Screen Recording permission is also required for capture. Grant it to the
app that launched the JVM — Terminal.app, iTerm2, IntelliJ IDEA, or your runner app —
then fully quit and restart that app. TCC can be granted correctly and capture can
still hang if the sandbox profile does not allow `com.apple.replayd`.

When validating sandbox profile changes, avoid stale Gradle daemons:

```shell
./gradlew --no-daemon spectreTest --tests '*SpectreSmokeTest'
```

A normal `./gradlew spectreTest ...` may reuse a daemon launched under an older
sandbox profile. Use `./gradlew --stop` only deliberately: if your desktop app or
runner itself was launched via Gradle, stopping all Gradle daemons can kill that
parent process too.

For painting/capture debugging, a useful probe is a small always-on-top Swing frame
filled with a known colour, placed at a known location, followed by
`Robot.createScreenCapture(Rectangle(x, y, 1, 1))`. If `Frame.isVisible()` and
`Frame.isShowing()` are `true` but the sampled pixel is the desktop background,
look at `com.apple.CARenderServer` and `com.apple.windowmanager.server`. If the
capture hangs after creating the `Robot`, look at Screen Recording permission and
`com.apple.replayd`.

The macOS `log` tool cannot run inside `sandbox-exec`:

```text
log: Cannot run while sandboxed
```

If your runner enforces `sandbox-exec`, provide a narrow diagnostic lane outside the
sandbox for read-only commands such as `log show ...`, `/usr/bin/log show ...`,
`log stream ...`, and `/usr/bin/log stream ...`. Keep it read-only: reject shell
metacharacters and mutating subcommands such as `log collect` and `log erase`. If
your policy logger supports reason codes, label this escape clearly, e.g.,
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

Per-PR CI stays on a single Temurin 21 JDK for speed. Compatibility across **JBR 21**,
**JBR 25**, and **Temurin LTS** (toolchain major) on macOS, Linux, and Windows is covered by
the scheduled [runtime-matrix](https://github.com/rock3r/spectre/blob/main/.github/workflows/runtime-matrix.yml)
workflow (epic #215 / issue #216):

| Dimension | Values |
| --- | --- |
| Runtime | JBR 21, JBR 25, Temurin LTS |
| OS | macOS, Linux (`xvfb`), Windows |
| Suites | Contract corpus (all OSes); agent attach same-runtime on Linux/macOS + mixed vanilla↔JBR on Linux; Linux X11 recording smoke. Agent suites are `@EnabledOnOs(LINUX, MAC)` only — Windows cells still run the non-agent corpus. |

Pins live in [`.github/jbr-pins.env`](https://github.com/rock3r/spectre/blob/main/.github/jbr-pins.env)
(JBRSDK / `jdk` package, not `jbr_jcef`). Bump procedure is in that file’s header comments.
`actions/setup-java` caches downloads per runner OS/arch via the composite action
`.github/actions/setup-matrix-jdk`.

The matrix is **release-gated**: `release.yml` calls the same workflow via `workflow_call`
before helper builds and publish. A red cell blocks the release; scheduled failures open
issues labelled `runtime-matrix` rather than vanishing into Actions noise.

Mixed-runtime agent attach sets `SPECTRE_FIXTURE_JAVA_HOME` (or
`-Pspectre.agent.fixtureJavaHome=…`) so the fixture JVM can differ from the attacher; the
`:agent:test` task forwards it as
`-Ddev.sebastiano.spectre.agent.fixtureJavaHome=…` into the forked test JVM.

## Recording tests

Keep recording tests tagged separately from normal UI tests. Linux CI can validate Xorg / `xvfb`
region recording and non-recording Spectre flows, but it cannot validate macOS
ScreenCaptureKit capture. The base `spectre-recording` artifact also does not carry the macOS
ScreenCaptureKit helper; macOS recording tests need the `spectre-recording-macos` runtime
artifact or a locally built helper artifact on the test classpath.

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

Install `xvfb` on Linux, then run the Gradle task through `xvfb-run`. Keep the JVM flags in
Gradle so local runs and CI use the same test process configuration.

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
