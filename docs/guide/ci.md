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
