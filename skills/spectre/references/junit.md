# JUnit integration

Spectre ships JUnit 4 and JUnit 5 wrappers in the `:testing` module. They own
the `ComposeAutomator` lifecycle — building it before each test, tearing
down after. You don't construct `ComposeAutomator.inProcess()` yourself when
using them.

## JUnit 5 — `ComposeAutomatorExtension`

Use `@RegisterExtension` on a `@JvmField` (required for JUnit 5 to see the
field at runtime):

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

Either form works; the field form is friendlier when you have multiple
helpers.

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

Note `@get:Rule` (not `@Rule`) for Kotlin — applies the annotation to the
generated getter, which is what JUnit 4 looks for.

## Custom `RobotDriver` per test

Both wrappers take a single positional argument: an
`AutomatorFactory = () -> ComposeAutomator`. There is **no** `robotDriver =`
named parameter on the extension or rule constructor. Use the trailing
lambda to build the automator with whichever driver you want:

```kotlin
@JvmField
@RegisterExtension
val automatorExt = ComposeAutomatorExtension {
    ComposeAutomator.inProcess(robotDriver = RobotDriver.headless())
}
```

Same shape for parallel-safe synthetic input:

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

## Failure video (#206)

Optional whole-test recording via `FailureVideoConfig` (default
`FailureVideoPolicy.Off`). Policies: `Off` | `OnFailureKeep` (delete on pass,
keep on fail) | `Always`. Output: `build/reports/spectre/<class>/<method>/failure-video.mp4`
next to stills. Independent of still failure artifacts. In-process JUnit only —
see the user guide (`docs/guide/junit.md#failure-video`) and
`docs/RECORDING-LIMITATIONS.md` for overhead.

```kotlin
ComposeAutomatorExtension(
    failureVideo = FailureVideoConfig(policy = FailureVideoPolicy.OnFailureKeep),
)
```

## Launch-and-attach (separate UI JVM)

When the UI under test is a **separate JVM** (prod-like `java -jar`,
`installDist`, or `./gradlew :app:run` with warnings), use
`LaunchAndAttachExtension` (JUnit 5) or `LaunchAndAttachRule` (JUnit 4)
instead of only `ComposeAutomatorExtension` / `ComposeAutomatorRule`. They
call the shared agent launch core, attach, and tear the process tree down
after each test. The launched app does **not** have to preinstall
`spectre-core` — attach injects it when Compose is present — but
preinstalled core is still the preferred target shape.

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

Keep a `ComposeAutomatorExtension` / `Rule` innermost if you also want
in-process failure artifacts. Full recipe: user guide
[JUnit — Launch-and-attach](https://spectre.sebastiano.dev/guide/junit/).

## Lifecycle notes

- The extension/rule does **not** open a Compose window for you. Launch your
  app or harness in `@BeforeEach`/`@Before`, or at the top of each test.
- Each test gets a fresh `ComposeAutomator`. Don't cache one across tests.
- The test body must still be wrapped in `runSpectreTest { ... }` because input
  and wait calls are `suspend`. `runTest` will break timing — use `runSpectreTest` instead; see the main
  SKILL.md.
