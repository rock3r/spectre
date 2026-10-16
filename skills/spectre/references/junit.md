# JUnit integration

The `:testing` module provides JUnit 4 and JUnit 5 wrappers.
Each wrapper creates a `ComposeAutomator` before a test and closes it afterward.

For real-input suites running across multiple processes, read
[input-coordination.md](input-coordination.md). `InputIsolationConfig` is experimental and
requires `@OptIn(ExperimentalSpectreInputCoordinationApi::class)`.

## JUnit 5 — `ComposeAutomatorExtension`

Put `@RegisterExtension` on a `@JvmField` so JUnit 5 can find the field:

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
    fun `opens settings dialog`() = runSpectreTest {
        launchHarness()
        val automator = automatorExt.automator
        // ...
    }
}
```

`ComposeAutomatorExtension` also implements `ParameterResolver`, so the
automator can be injected as a test parameter:

```kotlin
@Test
fun example(automator: ComposeAutomator) = runSpectreTest {
    // ...
}
```

Both forms work. A field can be convenient when the class also registers other helpers.

## JUnit 4 — `ComposeAutomatorRule`

```kotlin
import dev.sebastiano.spectre.testing.ComposeAutomatorRule
import dev.sebastiano.spectre.testing.runSpectreTest
import org.junit.Rule
import org.junit.Test

class DialogTest {
    @get:Rule
    val automatorRule = ComposeAutomatorRule()

    @Test
    fun `opens settings dialog`() = runSpectreTest {
        launchHarness()
        val automator = automatorRule.automator
        // ...
    }
}
```

Kotlin needs `@get:Rule` because JUnit 4 inspects the generated getter.

## Custom `RobotDriver` per test

Both wrappers accept an `AutomatorFactory = () -> ComposeAutomator`.
Their constructors have no `robotDriver =` parameter.
Use a trailing lambda to create an automator with the required driver:

```kotlin
@JvmField
@RegisterExtension
val automatorExt = ComposeAutomatorExtension {
    ComposeAutomator.inProcess(robotDriver = RobotDriver.headless())
}
```

Use the same shape to pin the synthetic driver to a known window
(the no-arg `inProcess()` default is already synthetic):

```kotlin
@JvmField
@RegisterExtension
val automatorExt = ComposeAutomatorExtension {
    ComposeAutomator.inProcess(
        robotDriver = RobotDriver.synthetic(rootWindow = TestHarness.window),
    )
}
```

The JUnit 4 rule is identical: `ComposeAutomatorRule { ComposeAutomator.inProcess(...) }`.

## Experimental whole-test input isolation

Use `InputIsolationConfig.perTest()` when parallel JVMs use real OS input and setup,
failure capture, or teardown can change focus:

```kotlin
@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

@JvmField
@RegisterExtension
val automatorExt =
    ComposeAutomatorExtension(
        inputIsolation = InputIsolationConfig.perTest(),
    )
```

Add `spectre-input-coordinator-server` at runtime for a core-only setup; `spectre-testing`
already wires it. JUnit 5 owns the lease per invocation, while JUnit 4 wraps the complete
`Statement.evaluate()` lifecycle. Do not add a test-JVM whole-test lease around
`LaunchAndAttachExtension`/Rule: input is coordinated in the target JVM and a second lease would
self-deadlock.

## Failure video (#206)

Whole-test recording is optional via `FailureVideoConfig` (default
`FailureVideoPolicy.Off`). Policies: `Off` | `OnFailureKeep` (delete on pass,
keep on fail) | `Always`. Output: `build/reports/spectre/<class>/<method>/failure-video.mp4`
next to stills. Video artefacts are independent of still failure artefacts. This applies to in-process JUnit only;
see the user guide (`docs/guide/junit.md#failure-video`) and
`docs/RECORDING-LIMITATIONS.md` for overhead.

```kotlin
ComposeAutomatorExtension(
    failureVideo = FailureVideoConfig(policy = FailureVideoPolicy.OnFailureKeep),
)
```

## Screenshot golds

Tests compare committed PNG golds only when they call `assertMatchesGold`.
Failure artefacts and failure video remain separate diagnostics.
Use `automator.screenshot(windowIndex = …)` when the test runtime includes
`spectre-recording` and its OS helper. Call `waitForVisualIdle()` before capture.

```kotlin
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.testing.ScreenshotTolerance
import dev.sebastiano.spectre.testing.assertMatchesGold
import dev.sebastiano.spectre.testing.runSpectreTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo

@Test
fun homeMatchesGold(testInfo: TestInfo, automator: ComposeAutomator): Unit =
    runSpectreTest {
        automator.waitForVisualIdle()
        assertMatchesGold(
            testInfo = testInfo,
            name = "main-window",
            image = automator.screenshot(windowIndex = 0),
            tolerance = ScreenshotTolerance(), // strict: channel delta 0, no differing pixels
        )
    }
```

- Pass JUnit 5 `TestInfo` when the body runs inside `runSpectreTest` (worker
  dispatcher). That overload is on the `ScreenshotGoldJunit5` facade so the
  name-only `assertMatchesGold(name, image)` has no `TestInfo` descriptor
  (JUnit 4-only Java callers can resolve without `junit-jupiter-api`).
- Name-only overload infers the test from the calling thread; use it only from
  JUnit methods that call it directly.
- Defaults are strict (equal dimensions required; no auto-scale / SSIM). Loosen
  `maxChannelDelta` and/or `maxDifferingPixels` / `maxDifferingPixelFraction`
  when font AA or chrome noise is expected.
- Gold layout:
  `src/test/resources/spectre-golds/<class>/<method>/<name>/[<invocation>/]<os>/scale-<sx>x<sy>/gold.png`
  (`macos` | `windows` | `linux-x11` | `linux-wayland`). Linux keys follow the
  same session detection as window capture.
- Parameterized / repeated / `@ParameterizedClass` hosts often need an explicit
  `invocationKey` so invocations do not share a gold; see the user guide.
- Update mode (rewrite **current** OS + scale gold only):
  `SPECTRE_UPDATE_SCREENSHOT_GOLDS=true` (read by the test JVM; needs no
  Gradle forwarding) or `-Pspectre.updateScreenshotGolds=true`. Spectre's own
  `:testing` task forwards `-P` as
  `-Ddev.sebastiano.spectre.testing.updateScreenshotGolds=true`; **consumers
  using `-P` on their own `Test` task must forward that system property the same
  way** (or prefer the env var). When both are set the property wins. Force a
  rerun when only the environment variable supplies update mode (`--rerun-tasks`).

For scale-key rules, inherited-class identity, and the CI upload glob, see the user
guide [JUnit — Screenshot golds](https://spectre.sebastiano.dev/guide/junit/).

## Launch-and-attach (separate UI JVM)

When the UI under test is a **separate JVM** (prod-like `java -jar`,
`installDist`, or `./gradlew :app:run` with warnings), use
`LaunchAndAttachExtension` (JUnit 5) or `LaunchAndAttachRule` (JUnit 4). They
call the shared agent launch core, attach, and tear the process tree down
after each test. The launched app does **not** have to preinstall
`spectre-core`; attach injects it when Compose is present. Preinstalled core
remains the preferred target setup.

```kotlin
@JvmField
@RegisterExtension
val launchExt =
    LaunchAndAttachExtension(
        LaunchSpec(
            command =
                listOf(
                    "${System.getProperty("java.home")}/bin/java",
                    "-jar",
                    "app/build/libs/app.jar",
                )
        )
    )
```

Keep a `ComposeAutomatorExtension` / `Rule` innermost when using
in-process failure artefacts. Full recipe: user guide
[JUnit — Launch-and-attach](https://spectre.sebastiano.dev/guide/junit/).

## Lifecycle notes

- The extension/rule does **not** open a Compose window. Launch the
  app or harness in `@BeforeEach`/`@Before`, or at the top of each test.
- Each test gets a fresh `ComposeAutomator`. Don't cache one across tests.
- The test body must still be wrapped in `runSpectreTest { ... }` because input
  and wait calls are `suspend`. `runTest` breaks timing; use `runSpectreTest`. See the main
  SKILL.md.
