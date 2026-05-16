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
    `runBlocking { ... }` body; some assertions, including `assertNotNull`, return the
    asserted value. Prefer `fun mySpec(): Unit = runBlocking { ... }` for Spectre tests.

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
    val automatorExt = ComposeAutomatorExtension(headlessFactory)
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
