# JUnit integration

The `testing` module provides drop-in wrappers that own a per-test `ComposeAutomator`.

## JUnit 5: `ComposeAutomatorExtension`

The safest pattern is `@RegisterExtension` on a `@JvmField` — one extension instance per
test class, owned by the test class:

```kotlin
import dev.sebastiano.spectre.testing.ComposeAutomatorExtension
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class MyTest {

    @JvmField
    @RegisterExtension
    val automatorExt = ComposeAutomatorExtension()

    @Test
    fun something() {
        val node = automatorExt.automator.findOneByTestTag("Send")
        // ...
    }
}
```

The extension also implements `ParameterResolver`, so you can use `@ExtendWith` and take
the automator as a parameter:

```kotlin
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.testing.ComposeAutomatorExtension
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(ComposeAutomatorExtension::class)
class MyTest {

    @Test
    fun something(automator: ComposeAutomator) {
        val node = automator.findOneByTestTag("Send")
        // ...
    }
}
```

!!! tip "Parallel execution"
    The parameter-injection form is **the** parallel-safe form: each test resolves its
    own automator from the per-invocation `ExtensionContext.Store`. The
    `automatorExt.automator` accessor returns the most recently created instance and is
    fine for sequential runs but races under parallel execution.

!!! warning "Expression-body tests should declare `: Unit`"
    JUnit 5.14 and newer reject `@Test` methods whose JVM return type is not `void`.
    Kotlin expression-body tests infer the return type from the last expression in the
    `runSpectreTest { ... }` body; some assertions, including `assertNotNull`, return the
    asserted value. Prefer `fun mySpec(): Unit = runSpectreTest { ... }` for Spectre tests.

## JUnit 4: `ComposeAutomatorRule`

```kotlin
import dev.sebastiano.spectre.testing.ComposeAutomatorRule
import org.junit.Rule
import org.junit.Test

class MyTest {

    @get:Rule
    val automatorRule = ComposeAutomatorRule()

    @Test
    fun something() {
        val node = automatorRule.automator.findOneByTestTag("Send")
        // ...
    }
}
```

`@get:Rule` (note the `get:` prefix) targets the annotation at the property's generated
getter, which is what JUnit 4 reflects on. Without the `get:` prefix Kotlin would put
the annotation on the property itself and JUnit wouldn't see it.

## Launch-and-attach harness

When the UI under test is a **separate JVM** (prod-like `java -jar`, installDist, or even
`./gradlew :app:run` with warnings), use `LaunchAndAttachExtension` (JUnit 5) or
`LaunchAndAttachRule` (JUnit 4) from `:testing`. They call the shared agent launch core
before each test and tear the process tree down after — the same lifecycle window
`ComposeAutomatorExtension` / `ComposeAutomatorRule` use. Failure-artifact capture is
wired into those automator wrappers (see [Failure artifacts](#failure-artifacts)); keep
the automator rule/extension innermost if you also use launch-and-attach so capture still
sees open windows.

```kotlin
import dev.sebastiano.spectre.agent.launch.LaunchSpec
import dev.sebastiano.spectre.testing.LaunchAndAttachExtension
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class LaunchedAppTest {

    @JvmField
    @RegisterExtension
    val launchExt =
        LaunchAndAttachExtension(
            LaunchSpec(
                command =
                    listOf(
                        // java.home/bin/java (or java.exe on Windows)
                        "${System.getProperty("java.home")}/bin/java",
                        "-jar",
                        "app/build/libs/app.jar",
                    )
            )
        )

    @Test
    fun exercise() {
        val windows = launchExt.automator.windows()
        // …
    }
}
```

The extension implements `ParameterResolver`, so when a class registers **one**
`LaunchAndAttachExtension`, parallel-safe tests can take `LaunchedSession` or
`AttachedAutomator` as method parameters (resolved from the per-invocation store):

```kotlin
import dev.sebastiano.spectre.agent.AttachedAutomator
import dev.sebastiano.spectre.agent.launch.LaunchedSession

@Test
fun exercise(session: LaunchedSession) {
    session.automator.windows()
}

@Test
fun alsoFine(automator: AttachedAutomator) {
    automator.windows()
}
```

The `launchExt.automator` / `launchExt.launched` accessors are instance-specific and
thread-local-backed, so they stay correct under parallel execution and when a class
registers **two** launch extensions (app + helper). With two or more registrations,
parameter injection is disabled to avoid competing resolvers — use the property
accessors instead.

Because the extension needs a `LaunchSpec`, register it with `@RegisterExtension` (not
`@ExtendWith`).

Prefer prod-like commands. For Gradle-ish launches, set `appJvmNameFilter` (main-class
substring) so discovery can find the daemon-spawned app JVM without attaching an unrelated
process. Direct `java` launches inject `-XX:+EnableDynamicAgentLoading` automatically.

The full API lives in `dev.sebastiano.spectre.agent.launch` (`LaunchAndAttach`,
`LaunchSpec`, stage exceptions). See [Agent attach](agent.md) and
[Troubleshooting](troubleshooting.md#launch-and-attach-harness).

## Launching a Compose window from a test

`application { Window(...) { ... } }` blocks until the app exits, so do not call it
inline from `@BeforeAll`. Start the Compose application loop on a daemon thread, disable
`exitProcessOnExit`, capture `exitApplication` for cleanup, and capture the
`ComposeWindow` from inside the `Window` content scope:

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.application
import androidx.compose.ui.window.Window
import androidx.compose.ui.awt.ComposeWindow
import java.util.concurrent.atomic.AtomicReference

internal class SpectreTestWindow(
    private val title: String,
    private val content: @Composable () -> Unit,
) {
    @Volatile private var exitFn: (() -> Unit)? = null
    private val windowRef = AtomicReference<ComposeWindow?>()

    fun start() {
        Thread({
            application(exitProcessOnExit = false) {
                exitFn = ::exitApplication
                Window(onCloseRequest = ::exitApplication, title = title) {
                    windowRef.compareAndSet(null, window)
                    content()
                }
            }
        }, "$title-window").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        exitFn?.invoke()
    }

    fun awaitWindow(timeoutMs: Long = 30_000): ComposeWindow {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            windowRef.get()?.let { return it }
            Thread.sleep(50)
        }
        error("ComposeWindow for '$title' was not captured within ${timeoutMs}ms")
    }
}
```

Use the returned `ComposeWindow` when constructing
`RobotDriver.synthetic(rootWindow = window)` or when adapting the window for recording.

## Test JVM requirements

Spectre tests that drive a real Compose window need a non-headless JVM. If your default
`Test` task sets `java.awt.headless=true`, move Spectre tests to a separate task and
force that task to run with `java.awt.headless=false`. On GPU-less Linux CI, also force
Skiko software rendering:

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

Use `RobotDriver.headless()` only for read-only semantics-tree tests. It throws on
input, clipboard, and screenshot calls by design. See [Running on CI](ci.md) for the
full Linux `xvfb` and test-JVM flag recipe.

On macOS, a dedicated Spectre test task may also set
`systemProperty("apple.awt.UIElement", "true")` to keep helper JVMs out of the Dock and
avoid foreground-app fights. Pair that with `RobotDriver.synthetic(rootWindow = window)`
for typing-driven Compose Desktop tests: Spectre can deliver key events through
Compose's AWT key listener even when macOS never grants the window an AWT focus owner.
Do not rely on UI-element mode for clipboard-backed `pasteText`; that path still goes
through macOS clipboard services outside the synthetic key-event path. Run recording tests
as a separate, foreground-capable task while establishing Screen Recording TCC grants.

## Failure artifacts

When a Spectre-driven test **fails**, `ComposeAutomatorExtension` and `ComposeAutomatorRule`
capture an [atomic capture](capture.md) (PNG + `capture.json`) for every window the automator
knows about. Capture runs **after** the failure and **before** the wrapper tears down the
automator, so windows are still open.

Default is **on**. Opt out when constructing the wrapper:

```kotlin
import dev.sebastiano.spectre.testing.ComposeAutomatorExtension
import dev.sebastiano.spectre.testing.FailureArtifactsConfig
import org.junit.jupiter.api.extension.RegisterExtension

@JvmField
@RegisterExtension
val automatorExt =
    ComposeAutomatorExtension(
        failureArtifacts = FailureArtifactsConfig(enabled = false),
    )
```

```kotlin
import dev.sebastiano.spectre.testing.ComposeAutomatorRule
import dev.sebastiano.spectre.testing.FailureArtifactsConfig
import org.junit.Rule

@get:Rule
val automatorRule =
    ComposeAutomatorRule(
        failureArtifacts = FailureArtifactsConfig(enabled = false),
    )
```

### Layout

Artifacts land under Gradle’s reports tree (cleaned by `clean`), not under the CLI/agent
`$TMPDIR` capture root:

```text
build/reports/spectre/<test-class>/<test-method>[/<invocation>][/attempt-N]/run-*/window-<i>/
  capture.json
  screenshot.png
```

- **`<test-class>` / `<test-method>`** — sanitized FQCN and method name.
- **`<invocation>`** — distinguishes parallel or repeated runs of the same method (JUnit 5
  unique id by default; JUnit 4 synthesizes one).
- **`attempt-N`** — only when you set `FailureArtifactsConfig.attemptIndex` to a value greater
  than 1 (1-based). Use this with retry runners so attempt 2 does not overwrite attempt 1.
- **`run-*` / `window-<i>`** — one isolation tree per capture attempt; window index matches the
  automator’s known windows. Same on-disk shape as a manual atomic capture, so the shipped
  **`spectre-capture`** skill’s `jq` recipes work unchanged.

Passing tests write nothing. Aborted tests (JUnit 5 assumptions / JUnit 4 `Assume`) also write
nothing — they are skips, not failures.

On JUnit 5, each written window directory is published as a report entry under the key
`spectre.failureArtifact` (path string). JUnit 4 has no report-entry API; inspect disk under
`build/reports/spectre/` (or your custom `reportsRoot`).

### Caveats

- Capture happens **after** the exception. Animations may advance a few frames past the failing
  assertion; treat the PNG as “state at capture time,” not a perfect freeze of the assert line.
- Capture is **best-effort**. A secondary capture error must never replace the original test
  failure; if capture cannot run, you still see the real failure in the test report.
- With multiple JUnit rules, keep `ComposeAutomatorRule` **innermost** (last `.around(...)` in
  a `RuleChain`) so outer rules do not close UI or process state before capture runs.

Point CI at the reports tree with a single upload glob — see [Running on CI](ci.md#failure-artifacts).

## Failure video

You cannot record a failure retroactively, so video-of-a-failure means recording the **whole
test** and deciding at the end whether to keep the file. Configure this with
`FailureVideoConfig` next to stills config on `ComposeAutomatorExtension` /
`ComposeAutomatorRule`.

Default is **`FailureVideoPolicy.Off`** — no recorder overhead on green CI. Opt in per suite:

| Policy | Behaviour |
| --- | --- |
| `Off` | Default. No recording starts. |
| `OnFailureKeep` | Record the whole test; **delete** the finalized file on pass; **keep** on fail. |
| `Always` | Keep the finalized video on pass and fail. |

```kotlin
import dev.sebastiano.spectre.testing.ComposeAutomatorExtension
import dev.sebastiano.spectre.testing.FailureVideoConfig
import dev.sebastiano.spectre.testing.FailureVideoPolicy
import org.junit.jupiter.api.extension.RegisterExtension

@JvmField
@RegisterExtension
val automatorExt =
    ComposeAutomatorExtension(
        failureVideo =
            FailureVideoConfig(policy = FailureVideoPolicy.OnFailureKeep),
    )
```

```kotlin
import dev.sebastiano.spectre.testing.ComposeAutomatorRule
import dev.sebastiano.spectre.testing.FailureVideoConfig
import dev.sebastiano.spectre.testing.FailureVideoPolicy
import org.junit.Rule

@get:Rule
val automatorRule =
    ComposeAutomatorRule(
        failureVideo =
            FailureVideoConfig(policy = FailureVideoPolicy.OnFailureKeep),
    )
```

### Layout

Videos land under the **same reports tree** as stills (same class/method/invocation/`attempt-N`
nesting), as a sibling file:

```text
build/reports/spectre/<test-class>/<test-method>[/<invocation>][/attempt-N]/
  failure-video.mp4          ← when the policy keeps the file
  run-*/window-<i>/…         ← stills (#205), independent of video policy
```

Stills stay default-on and are independent of the video policy. Aborted tests (JUnit assumptions)
never keep a failure video — same skip semantics as stills. On JUnit 5, a kept video is published
as a report entry under `spectre.failureVideo`.

### Overhead (honest)

Recording every test is real cost. Prefer `Off` on CI unless you need video for a flaky suite.

- **CPU / helper process** — a platform recorder runs for the full test duration (ScreenCaptureKit
  helper on macOS, Windows Graphics Capture helper on Windows, GStreamer / portal paths on Linux).
  See [Recording limitations](../RECORDING-LIMITATIONS.md) for per-OS backend behaviour and
  permissions.
- **Disk under `OnFailureKeep`** — the file is written for **every** invocation (including green
  tests) and only deleted after the recorder stops and finalizes. Parallel suites and long tests
  can spike disk during the run even when nothing is left on pass.
- **Permissions** — macOS Screen Recording TCC, Windows helper packaging, and Wayland portal
  consent still apply; if the backend cannot start, video is skipped best-effort (the test
  outcome is never replaced by a recorder error).
- **Scope** — this policy is for **in-process** JUnit wrappers only. Agent/attach recording stays
  on the CLI/daemon path (`spectre record`), not this config.

## Custom `AutomatorFactory`

Both wrappers default to `ComposeAutomator.inProcess()`. Pass your own factory when you
need a different driver for headless CI or unit-style isolation. `RobotDriver.headless()`
throws on input, clipboard, and screenshot calls (see
[Driving input](interactions.md#real-vs-synthetic-input)), so the example below is
appropriate for tests that only exercise semantics-tree queries or rule/extension
lifecycle — anything that needs real input should use `RobotDriver.synthetic(rootWindow)`
or the default `RobotDriver()` instead:

```kotlin
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.testing.AutomatorFactory
import dev.sebastiano.spectre.testing.ComposeAutomatorExtension
import org.junit.jupiter.api.extension.RegisterExtension

private val headlessFactory: AutomatorFactory = {
    ComposeAutomator.inProcess(robotDriver = RobotDriver.headless())
}

class HeadlessTest {

    @JvmField
    @RegisterExtension
    val automatorExt = ComposeAutomatorExtension(factory = headlessFactory)
}
```


## JUnit dependency model

Both `junit:junit` (JUnit 4) and `org.junit.jupiter:junit-jupiter-api` (JUnit 5) are
declared `compileOnly` on the `testing` module. **Consumers pick whichever JUnit they
already use** and pull in the matching test dependency themselves. The module never
forces both onto the test classpath.

If you see a `NoClassDefFoundError` for a JUnit class when the rule or extension runs,
add the corresponding `testImplementation` dependency to your project — see
[Installation](installation.md#consume-the-current-checkout).
