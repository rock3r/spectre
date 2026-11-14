# JUnit integration

The `:testing` module ships `ComposeAutomatorExtension` (JUnit 5) and `ComposeAutomatorRule` (JUnit 4). Each builds a
fresh `ComposeAutomator` before every test and closes it afterward. For multi-JVM real-input isolation, read
[input-coordination.md](input-coordination.md).

## JUnit 5 — `ComposeAutomatorExtension` and parallel methods

For sequential methods in a class, register a `@JvmField` extension field:

```kotlin
import dev.sebastiano.spectre.testing.ComposeAutomatorExtension
import dev.sebastiano.spectre.testing.runSpectreTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class DialogTest {
    @JvmField
    @RegisterExtension
    val automatorExt = ComposeAutomatorExtension()

    @Test
    fun `opens settings dialog`(): Unit = runSpectreTest {
        launchHarness()
        val automator = automatorExt.automator
        // ...
    }
}
```

`ComposeAutomatorExtension` also implements `ParameterResolver`, storing each invocation's automator in JUnit's
`ExtensionContext.Store`. **When `@Test` methods run in parallel within one class, always inject the parameter**
(`automatorExt.automator` returns only the most recently created instance and races under concurrent methods):

```kotlin
@ExtendWith(ComposeAutomatorExtension::class)
class ParallelDialogTest {
    @Test
    fun example(automator: ComposeAutomator): Unit = runSpectreTest {
        // ...
    }
}
```

## JUnit 4 — `ComposeAutomatorRule`

Annotate with `@get:Rule` so JUnit 4 sees Kotlin's generated getter:

```kotlin
import dev.sebastiano.spectre.testing.ComposeAutomatorRule
import dev.sebastiano.spectre.testing.runSpectreTest
import org.junit.Rule
import org.junit.Test

class DialogTest {
    @get:Rule
    val automatorRule = ComposeAutomatorRule()

    @Test
    fun `opens settings dialog`(): Unit = runSpectreTest {
        launchHarness()
        val automator = automatorRule.automator
        // ...
    }
}
```

## Custom `RobotDriver` factory

Neither wrapper accepts a `robotDriver =` parameter; both take `factory: AutomatorFactory = () -> ComposeAutomator` as
the **last** parameter on every factory constructor, so both trailing-lambda and named `factory = { ... }` compile
(omitting `factory` defaults to `ComposeAutomator.inProcess()`, which uses `RobotDriver.synthetic()`):

```kotlin
@JvmField
@RegisterExtension
val automatorExt = ComposeAutomatorExtension {
    ComposeAutomator.inProcess(robotDriver = RobotDriver.synthetic(rootWindow = TestHarness.window))
}
// Named form: ComposeAutomatorExtension(factory = { ComposeAutomator.inProcess(...) })
// JUnit 4: ComposeAutomatorRule { ComposeAutomator.inProcess(...) }
```

## Experimental whole-test input isolation

When parallel JVMs use real OS `RobotDriver` input and setup, failure capture, or teardown can shift focus, pass
`InputIsolationConfig.perTest()` (`spectre-testing` already includes `spectre-input-coordinator-server` at runtime):

```kotlin
@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

@JvmField
@RegisterExtension
val automatorExt = ComposeAutomatorExtension(inputIsolation = InputIsolationConfig.perTest())
```

Do not wrap `LaunchAndAttachExtension`/`LaunchAndAttachRule` in a test-JVM `perTest()` lease: attached input is already
coordinated in the target JVM, and a second outer lease self-deadlocks.

## Failure artefacts and failure video

- **Still failure artefacts (`FailureArtifactsConfig`):** enabled by default (`FailureArtifactsConfig(enabled = false)`
  opts out). On test failure, Spectre writes atomic window captures (`screenshot.png` + `capture.json`) per tracked
  window before teardown under `build/reports/spectre/<class>/<method>/` (requires `:recording` + OS helper for PNGs).
- **Failure video (`FailureVideoConfig`):** off by default (`FailureVideoPolicy.Off` | `OnFailureKeep` | `Always`),
  writing `build/reports/spectre/<class>/<method>/failure-video.mp4` for in-process JUnit tests:

```kotlin
ComposeAutomatorExtension(
    failureVideo = FailureVideoConfig(policy = FailureVideoPolicy.OnFailureKeep),
)
```

## Screenshot golds (`assertMatchesGold`)

Call `waitForVisualIdle()` before comparing against committed PNGs (`spectre-recording` + OS helper required for
`screenshot(windowIndex = ...)`):

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
        tolerance = ScreenshotTolerance(),
    )
}
```

- **Pass JUnit 5 `TestInfo` inside `runSpectreTest`:** `runSpectreTest` switches to a worker dispatcher, whereas the
  name-only `assertMatchesGold(name, image)` overload infers identity from the JUnit thread (`ScreenshotGoldJunit5`
  isolates `TestInfo` so JUnit 4 callers need no Jupiter dependency).
- **Tolerance and paths:** strict by default (`maxChannelDelta = 0`, `maxDifferingPixels = 0`, equal dimensions). Golds
  live at `src/test/resources/spectre-golds/<class>/<method>/<name>/[<invocation>/]<os>/scale-<sx>x<sy>/gold.png`
  (`macos` | `windows` | `linux-x11` | `linux-wayland`). Parameterised/repeated tests should pass `invocationKey`.
- **Update mode (current OS + scale only):** set `SPECTRE_UPDATE_SCREENSHOT_GOLDS=true` (with `--rerun-tasks` if only
  the env var changed), or forward `-Pspectre.updateScreenshotGolds=true` to
  `-Ddev.sebastiano.spectre.testing.updateScreenshotGolds=true` on your `Test` task (property wins when both are set).
  Details: [JUnit — Screenshot golds](https://spectre.sebastiano.dev/guide/junit/).

## Launch-and-attach (separate UI JVM)

For a separate target JVM (`java -jar`, `installDist`), `LaunchAndAttachExtension` (JUnit 5) and `LaunchAndAttachRule`
(JUnit 4) launch the process, attach (injecting core if not preinstalled), and tear down the process tree after each
test. Keep `ComposeAutomatorExtension`/`Rule` innermost if combining with in-process failure artefacts:

```kotlin
@JvmField
@RegisterExtension
val launchExt = LaunchAndAttachExtension(
    LaunchSpec(command = listOf("${System.getProperty("java.home")}/bin/java", "-jar", "app/build/libs/app.jar")),
)
```

## Lifecycle checklist

- Launch your Compose window in `@BeforeEach`/`@Before` or at the start of the test, wait for `window.isShowing` and
  non-zero size, and wait for `!window.isDisplayable` after async `exitApplication()`.
- Never cache `ComposeAutomator` across tests, never replace `runSpectreTest` with `runTest`, and pass `--no-daemon`
  when wrapping `./gradlew` in `xvfb-run` (`-Djava.awt.headless=false`).
