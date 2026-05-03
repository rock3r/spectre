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

## Custom `AutomatorFactory`

Both wrappers default to `ComposeAutomator.inProcess()`. Pass your own factory when you
need a stub for headless CI or unit-style isolation:

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
    val automatorExt = ComposeAutomatorExtension(headlessFactory)
}
```

The `testing` module's own test sources include an internal `newHeadlessAutomator()`
helper that follows this pattern. It isn't part of the public API, but it's worth
borrowing as a reference recipe.

## JUnit dependency model

Both `junit:junit` (JUnit 4) and `org.junit.jupiter:junit-jupiter-api` (JUnit 5) are
declared `compileOnly` on the `testing` module. **Consumers pick whichever JUnit they
already use** and pull in the matching test dependency themselves. The module never
forces both onto the test classpath.

If you see a `NoClassDefFoundError` for a JUnit class when the rule or extension runs,
add the corresponding `testImplementation` dependency to your project — see
[Installation](installation.md#consume-as-a-composite-build).
