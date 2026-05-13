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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class DialogTest {
    @JvmField
    @RegisterExtension
    val automatorExt = ComposeAutomatorExtension()

    @Test
    fun `opens settings dialog`() = runBlocking {
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
fun example(automator: ComposeAutomator) = runBlocking {
    // ...
}
```

Either form works; the field form is friendlier when you have multiple
helpers.

## JUnit 4 — `ComposeAutomatorRule`

```kotlin
import dev.sebastiano.spectre.testing.ComposeAutomatorRule
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class DialogTest {
    @get:Rule
    val automatorRule = ComposeAutomatorRule()

    @Test
    fun `opens settings dialog`() = runBlocking {
        launchHarness()
        val automator = automatorRule.automator
        // ...
    }
}
```

Note `@get:Rule` (not `@Rule`) for Kotlin — applies the annotation to the
generated getter, which is what JUnit 4 looks for.

## Custom `RobotDriver` per test

Both wrappers accept a constructor parameter (or builder) to override the
default driver. Use this for parallel JVMs or headless reads:

```kotlin
@JvmField
@RegisterExtension
val automatorExt = ComposeAutomatorExtension(robotDriver = RobotDriver.headless())
```

## Lifecycle notes

- The extension/rule does **not** open a Compose window for you. Launch your
  app or harness in `@BeforeEach`/`@Before`, or at the top of each test.
- Each test gets a fresh `ComposeAutomator`. Don't cache one across tests.
- The test body must still be wrapped in `runBlocking { ... }` because input
  and wait calls are `suspend`. `runTest` will break timing — see the main
  SKILL.md.
