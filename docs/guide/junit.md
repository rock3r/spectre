# JUnit integration

The `testing` module creates and closes a `ComposeAutomator` for each JUnit test.

## Whole-test input isolation

!!! warning "Experimental and opt-in"
    JUnit input isolation requires
    `@OptIn(ExperimentalSpectreInputCoordinationApi::class)`. The constructors and configuration
    may change in any release. See [Experimental desktop input coordination](input-coordination.md)
    for runtime dependencies, recovery, and platform status.

In-process JUnit wrappers can hold one input lease through factory setup, the test,
failure capture, video finalisation, and teardown:

```kotlin
@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

import dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi
import dev.sebastiano.spectre.testing.InputIsolationConfig

@JvmField
@RegisterExtension
val automatorExt =
    ComposeAutomatorExtension(
        inputIsolation = InputIsolationConfig.perTest(),
    )
```

The same `inputIsolation` constructor is available on `ComposeAutomatorRule` for JUnit 4. Modes:

- `PerTest` acquires before the default/custom factory and releases unconditionally after teardown.
- `Auto` acquires for real input or shared clipboard capabilities. With a custom factory it must
  create the automator before it can inspect capabilities, so factory-time focus is not covered;
  use `PerTest` when setup itself needs isolation.
- `PerInteraction` relies on core operation/scoped leases. The input-isolation-only constructor
  creates a default driver with `InputLeasePolicy.Required`; custom factories must select
  `InputLeasePolicy.Auto` or `Required` themselves. Legacy no-argument wrappers remain
  uncoordinated. The wrappers close their default coordination-enabled driver after test evidence
  and teardown complete.
- `Off` declares that coordination is external or intentionally disabled.

During a synchronous custom factory, `PerTest` exposes its lease on the invoking thread.
`RobotDriver(InputLeasePolicy.Required)` reuses that lease during setup.
Factory work must finish before the factory returns. Spectre then binds the lease to
the returned automator.

JUnit 5 stores the lease per invocation, so parameter-injected automators remain parallel-safe.
JUnit 4 wraps the complete `Statement.evaluate()` lifecycle. Owner diagnostics contain class and
method identity, never parameter values. `LaunchAndAttachExtension` and `LaunchAndAttachRule` do
not expose `PerTest`: input runs in the target JVM and currently uses target-side operation leases;
holding a separate test-JVM lease would self-deadlock.

## JUnit 5: `ComposeAutomatorExtension`

Register one extension field on the test class with
`@JvmField` and `@RegisterExtension`:

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

The extension also implements `ParameterResolver`, allowing `@ExtendWith` to supply
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
    fine for sequential runs. Parallel execution creates a race.

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

`@get:Rule` puts the annotation on Kotlin's generated getter.
JUnit 4 inspects that getter. Without `get:`, JUnit may not find the rule.

## Launch-and-attach harness

For a **separate UI JVM**, use `LaunchAndAttachExtension` with JUnit 5 or
`LaunchAndAttachRule` with JUnit 4. They start the app before each test and stop
its process tree afterward. The app can run from `java -jar`, installDist, or Gradle
`run` with warnings. The app need not preinstall `spectre-core`; attach injects core
when Compose is present. Prefer preinstalled core for controlled app builds.
Keep an automator rule or extension innermost when combining wrappers, so
[failure capture](#failure-artifacts) runs while windows remain open.

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
parameter injection is disabled to avoid competing resolvers. Use the property
accessors instead.

Because the extension needs a `LaunchSpec`, register it with `@RegisterExtension`.
`@ExtendWith` cannot supply that spec.

Prefer prod-like commands. For Gradle-ish launches, set `appJvmNameFilter` (main-class
substring) so discovery can find the daemon-spawned app JVM without attaching an unrelated
process. Direct `java` launches inject `-XX:+EnableDynamicAgentLoading` automatically.

The full API lives in `dev.sebastiano.spectre.agent.launch` (`LaunchAndAttach`,
`LaunchSpec`, stage exceptions). See [Agent attach](agent.md) and
[Troubleshooting](troubleshooting.md#launch-and-attach-harness).

## Launching a Compose window from a test

`application { Window(...) { ... } }` blocks until the app exits.
Start it on a daemon thread. A direct call from `@BeforeAll` would block setup.
Set `exitProcessOnExit = false`, retain `exitApplication` for cleanup, and capture
`ComposeWindow` inside the `Window` content scope. `SampleAppFixture` uses this setup
so Windows `validationTest` workers continue after successful JUnit output:

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

Tests that drive a real Compose window need a non-headless JVM.
If the default `Test` task sets `java.awt.headless=true`, create a separate Spectre task
with `java.awt.headless=false`. GPU-less Linux runners also need Skiko software rendering:

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

## Screenshot golds

Tests can compare screenshots with committed PNG golds by calling `assertMatchesGold`.
Spectre does not run this check automatically. [Failure artifacts](#failure-artifacts)
and [failure video](#failure-video) provide separate diagnostics.

Prefer a window-scoped `automator.screenshot(windowIndex = …)` when `spectre-recording`
and the OS helper are on the test runtime classpath. Region and node stills can clip or
include occlusion; Linux X11 window capture is frontmost-window; embedded Swing/Jewel
panels may not have a native window handle. Settle the UI first
(`waitForVisualIdle()`).

```kotlin
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.testing.ScreenshotTolerance
import dev.sebastiano.spectre.testing.assertMatchesGold
import dev.sebastiano.spectre.testing.runSpectreTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo

@Test
fun homeMatchesGold(testInfo: TestInfo, automator: ComposeAutomator): Unit = runSpectreTest {
    automator.waitForVisualIdle()
    assertMatchesGold(
        testInfo = testInfo,
        name = "main-window",
        image = automator.screenshot(windowIndex = 0),
        tolerance = ScreenshotTolerance(), // strict: channel delta 0, no differing pixels
    )
}
```

Pass JUnit 5 `TestInfo` when calling `assertMatchesGold` inside `runSpectreTest`.
That body runs on a worker dispatcher. The `ScreenshotGoldJunit5` facade provides
the `TestInfo` overload. The name-only `ScreenshotGoldKt.assertMatchesGold(name, image)`
has no `TestInfo` descriptor, so JUnit 4 Java callers can load it without
`junit-jupiter-api`. The name-only overload infers identity from the calling thread.
Call it directly from a JUnit method. It recognises `@Test`, `@ParameterizedTest`,
`@RepeatedTest`, and annotations composed from `@Testable` or `@TestTemplate`.

Repeated and parameterised invocations that share a screenshot name need distinct golds.
This includes JUnit 5 `@ParameterizedTest`, `@RepeatedTest`, `@ParameterizedClass`,
`@ClassTemplate`, and JUnit 4 `@RunWith(Parameterized)`.
The `TestInfo` facade uses a display name only when its pattern contains a real
invocation index and the resolved name is unique, such as `[1] dark` or
`repetition 1 of 2`. Use `{index}` for `@ParameterizedTest` and
`{currentRepetition}` for `@RepeatedTest`.
The default `@ParameterizedTest` pattern includes `{index}`; the default repeated
test pattern includes `{currentRepetition}`. `{index}` stays literal in a repeated test.
Argument placeholders such as `{0}` or `{arguments}` can repeat values.
Spectre removes unstable `Any.toString()` hash text such as `Foo@4a12bc` from a
display name. It retains stable index or repetition tokens.
Constant names and argument-only patterns require an explicit `invocationKey`.
Examples include `@ParameterizedTest(name = "theme")`,
`@ParameterizedTest(name = "[1] theme")`, and `@ParameterizedTest(name = "[{0}] theme")`.
The name-only overload cannot identify invocations in parameterised class hosts,
so it fails without `invocationKey`. `TestInfo.displayName` also names only an ordinary
test method inside such a host. Its facade therefore needs `invocationKey` there.
A `@ParameterizedTest` or `@RepeatedTest` inside `@ParameterizedClass` still needs
that key because outer argument sets repeat method-level indices.
Identity resolution retains the `Class` from `TestInfo` or `StackWalker`.
It can reload a name through the context or child classloader so plugin classloaders
do not hide parameterised hosts.

The default `scaleKey` uses the captured window's display scale when its visible
AWT geometry matches the still. It considers the outer window, client area,
content pane, or a showing embedded `ComposePanel`.
It also uses that scale when all showing windows share one density.
On Linux X11, the PNG contains the client area, so decorated outer bounds are
not candidates. Cropped stills use window-capture edge rounding for fractional DPI.
Spectre predicts PNG size with `round(captureAwt × displayScale)`.
It then uses `imageWidth / captureAwtWidth` for crop matching to account for
nominal-scale rounding. This lets an 801-DP capture at 1.25× (1001 px) match a 202-DP panel.
Linux X11 native capture begins at the client origin; Spectre offsets regions
before rounding. It ignores hidden panels and reads geometry on the EDT.
Otherwise, `scaleKey` uses the primary screen transform.
Pass `scaleKey = ScreenshotGoldPaths.scaleKey(configuration)` when several densities
are visible and the window's `GraphicsConfiguration` is known.
An explicit `invocationKey` retains surrounding whitespace, keeping `"foo"` and
`" foo "` distinct. A whitespace-only key counts as absent.

Default comparison requires equal dimensions and identical pixels.
Spectre does not scale images or use SSIM or another perceptual matcher.
Set `maxChannelDelta` between 0 and 255, `maxDifferingPixels`, or
`maxDifferingPixelFraction` when expected font antialiasing or window chrome differs.

### Gold layout

Committed files:

```text
src/test/resources/spectre-golds/
  <test-class>/
    <test-method>/
      <name>/
        [<invocation>/]       # stable display / [index] / repetition, or invocationKey
        <os>/                 # macos | windows | linux-x11 | linux-wayland
          scale-<sx>x<sy>/    # captured window display; else default screen; or pass scaleKey
            gold.png
```

The method path comes from the inferred JUnit method or `TestInfo`.
Every identity includes a parameter list: `name()` for a no-argument method,
or `(fqcn,…)` for an overload. For example, `@Test render()`, a method named
`render(int)`, and `@Test render(value: Int)` use separate golds.
Repeated and parameterised runs add an `<invocation>` segment.
Stack frames name a method's declaring class. The running receiver can have a different class.
For inherited tests or non-final declaring classes, pass `TestInfo` or
`assertMatchesGold(getClass(), name, image)` to identify the running class.
Spectre matches method names containing `(` through the full generated identity.

The default root is `src/test/resources/spectre-golds/` in the main JUnit source set.
Linux path keys follow window-capture session detection: `SPECTRE_CAPTURE_BACKEND`,
pure-X11 or Xvfb `DISPLAY`, then `XDG_SESSION_TYPE` and `WAYLAND_DISPLAY`.
A Wayland desktop with XWayland `DISPLAY` still uses `linux-wayland`.
Its golds stay separate from Xvfb `SOFTWARE_COMPAT` captures.
Theme, Skiko render API, JDK, and font antialiasing add **no** path key.
Pin the runner or adjust tolerance; see [Running on CI](ci.md).

On mismatch, the assertion writes `actual.png` and a copy of the expected `gold.png`
under:

```text
build/reports/spectre-screenshots/<class>/<method>/<name>[/<invocation>]/
```

When dimensions match, Spectre writes `diff.png` with magenta differences on black.
A size mismatch removes any stale `diff.png` from a prior run.
Spectre sanitises class, method, name, and invocation path segments for separators and
reserved Windows device names. It limits each segment to 255 UTF-8 bytes.
Changed segments receive a short stable suffix. This keeps distinct names such as
`foo/bar` and `foo_bar`, `NUL` and `NUL_`, `Main` and `main`, `σ` and `ς`,
or `가` and `가` on separate paths.
A passing run, update write, missing gold, or unreadable gold removes prior mismatch
PNGs so CI does not upload stale failures. Upload current failures with:

```yaml
- name: Upload Spectre screenshot gold failures
  if: failure()
  uses: actions/upload-artifact@v4
  with:
    name: spectre-screenshot-golds
    path: "**/build/reports/spectre-screenshots/**"
    if-no-files-found: ignore
```

Keep that glob **separate** from `**/build/reports/spectre/**` (failure stills).

### Update mode

Update mode is off by default. It rewrites the gold for the **current** OS and scale;
other matrix cells remain unchanged:

| Knob | Effect |
| --- | --- |
| `SPECTRE_UPDATE_SCREENSHOT_GOLDS=true` | Environment; read by the test JVM |
| `-Pspectre.updateScreenshotGolds=true` | Gradle property; Spectre's `:testing` test task forwards it as `-Ddev.sebastiano.spectre.testing.updateScreenshotGolds=true` |

When both are set, the Gradle/system property **wins**, including an explicit `false`
that disables a true environment variable. Unset property falls back to the environment
variable. Consumers who use `-P` on their own `Test` task must forward it the same way
(or set the environment variable, which needs no forwarding).

The environment variable is read only after a test JVM starts. Spectre's own test
tasks already force a rerun when update mode is on; consuming builds do not. When using
`SPECTRE_UPDATE_SCREENSHOT_GOLDS=true` alone, force execution with
`./gradlew test --rerun-tasks` (or the equivalent `outputs.upToDateWhen { false }` /
`outputs.cacheIf { false }` on that `Test` task). Otherwise Gradle can skip the worker
and no golds are rewritten.

Update mode must run on the OS and scale that owns that gold file.

## Failure artifacts

When a test **fails**, `ComposeAutomatorExtension` and `ComposeAutomatorRule` save an
[atomic capture](capture.md) for each tracked window. Each capture contains a PNG and
`capture.json`. The wrapper captures after the failure and before closing the automator,
while the windows remain open.

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

Artifacts land under Gradle’s reports tree (cleaned by `clean`). The CLI/agent
`$TMPDIR` capture root is separate:

```text
build/reports/spectre/<test-class>/<test-method>[/<invocation>][/attempt-N]/run-*/window-<i>/
  capture.json
  screenshot.png
```

- **`<test-class>` / `<test-method>`** — sanitized FQCN and method name.
- **`<invocation>`** — distinguishes parallel or repeated runs of the same method (JUnit 5
  unique id by default; JUnit 4 synthesizes one).
- **`attempt-N`** — only when `FailureArtifactsConfig.attemptIndex` is greater
  than 1 (1-based). Use this with retry runners so attempt 2 does not overwrite attempt 1.
- **`run-*` / `window-<i>`** — one isolation tree per capture attempt; window index matches the
  automator’s known windows. The on-disk shape matches a manual atomic capture, so the shipped
  **`spectre-capture`** skill’s `jq` recipes work unchanged.

Passing tests write no failure artefacts. Tests aborted by JUnit 5 assumptions or
JUnit 4 `Assume` also write none.

On JUnit 5, each written window directory is published as a report entry under the key
`spectre.failureArtifact` (path string). JUnit 4 has no report-entry API; inspect disk under
`build/reports/spectre/` (or a custom `reportsRoot`).

### Caveats

- Capture happens **after** the exception. Animations may advance a few frames past the failing
  assertion; treat the PNG as “state at capture time,” not a perfect freeze of the assert line.
- Capture is **best-effort**. A secondary capture error must never replace the original test
  failure; if capture cannot run, the test report retains the original failure.
- With multiple JUnit rules, keep `ComposeAutomatorRule` **innermost** (last `.around(...)` in
  a `RuleChain`) so outer rules do not close UI or process state before capture runs.

Point CI at the reports tree with a single upload glob; see [Running on CI](ci.md#failure-artifacts).

## Failure video

Failure video records the **whole test** and decides whether to keep the file afterward.
Configure `FailureVideoConfig` beside still image settings on `ComposeAutomatorExtension`
or `ComposeAutomatorRule`.

The default is **`FailureVideoPolicy.Off`**, which starts no recorder.
Select a policy for each suite:

| Policy | Behaviour |
| --- | --- |
| `Off` | The default policy starts no recording. |
| `OnFailureKeep` | Record the whole test; **delete** the finalized file on pass; **keep** on fail. |
| `Always` | Keep the finalized video on pass and fail; assumption/abort skips produce no kept video. |

### Runtime dependency (required for video)

Failure video routes through `AutoRecorder` and therefore needs the **matching platform helper
on the test runtime classpath**. The same requirement applies to every recording call:

| Host OS | Runtime dependency |
| --- | --- |
| macOS | `dev.sebastiano.spectre:spectre-recording-macos` |
| Linux | `dev.sebastiano.spectre:spectre-recording-linux` (plus GStreamer / portal prerequisites) |
| Windows | `dev.sebastiano.spectre:spectre-recording-windows` (plus .NET / Windows App Runtime) |

```kotlin
// build.gradle.kts — test runtime only; pick the OS you actually run
dependencies {
    testImplementation("dev.sebastiano.spectre:spectre-testing:<version>")
    // Also pull the base recording API if not already on the classpath:
    testImplementation("dev.sebastiano.spectre:spectre-recording:<version>")
    // Platform helper (example: macOS CI runner)
    testRuntimeOnly("dev.sebastiano.spectre:spectre-recording-macos:<version>")
}
```

Without the OS helper, the recorder fails to start and video is **skipped best-effort** (the
test outcome is never replaced by a recorder error). Stills (`FailureArtifactsConfig`) do not
require these helpers for Robot-region fallbacks. Native window stills require them; see
[Recording](recording.md). Full per-OS packaging and permission notes:
[Recording limitations](../RECORDING-LIMITATIONS.md).

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
  run-*/window-<i>/…         ← stills, independent of video policy
```

Stills stay default-on and are independent of the video policy. Aborted tests (JUnit assumptions)
never keep a failure video. This matches still skip semantics. On JUnit 5, a kept video is published
as a report entry under `spectre.failureVideo`.

CI artifact upload examples for **every** policy (`Off`, `OnFailureKeep`, `Always` with
`if: always()`): [Running on CI — Failure video uploads](ci.md#failure-video-uploads).

### Recording overhead

Recording every test uses CPU, helper processes, and disk.
Use `Off` on CI unless a suite needs video diagnostics.

- **CPU / helper process** — a platform recorder runs for the full test duration (ScreenCaptureKit
  helper on macOS, Windows Graphics Capture helper on Windows, GStreamer / portal paths on Linux).
  See [Recording limitations](../RECORDING-LIMITATIONS.md) for per-OS backend behaviour and
  permissions.
- **Disk under `OnFailureKeep`** — the file is written for **every** invocation (including green
  tests) and only deleted after the recorder stops and finalizes. Parallel suites and long tests
  can spike disk during the run even when nothing is left on pass.
- **Permissions** — macOS Screen Recording TCC, Windows helper packaging, and Wayland
  portal consent still apply. If the backend cannot start, Spectre skips video
  on a best-effort basis. A recorder error does not replace the test outcome.
- **Scope** — this policy is for **in-process** JUnit wrappers only. Agent/attach recording stays
  on the CLI/daemon path (`spectre record`). This config applies to in-process JUnit wrappers.

## Custom `AutomatorFactory`

Both wrappers use `ComposeAutomator.inProcess()` by default.
Pass a factory to select another driver. `RobotDriver.headless()` throws for input,
clipboard, and screenshots. Use it for semantics queries or wrapper lifecycle tests.
For input, use the default synthetic driver or `RobotDriver.synthetic(rootWindow)`.
Pass `RobotDriver()` when a test needs real OS input.
See [Driving input](interactions.md#real-vs-synthetic-input).

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

If a rule or extension throws `NoClassDefFoundError` for a JUnit class,
add the corresponding `testImplementation` dependency to the project; see
[Installation](installation.md#consume-the-current-checkout).
