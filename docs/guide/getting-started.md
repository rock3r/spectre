# Getting started

This page walks through writing a single test against a running Compose Desktop window.
It assumes you've already followed [Installation](installation.md).

## The shape of a Spectre test

A typical Spectre test does four things, in order:

1. **Launch the UI under test.** This is your responsibility — `application { Window { ... } }`
   for a standalone Compose Desktop app, or a custom harness for IDE-hosted Compose.
2. **Wait until the UI has settled.** Spectre's queries don't auto-wait, so there must be a
   point where the window is visible and the first frame is composed before you query it.
3. **Find nodes via the semantics tree** and drive them with mouse / keyboard / typing.
4. **Assert on the resulting state** by reading the semantics tree again.

## Tag your UI

Spectre finds nodes by Compose semantics. The most reliable selector is `Modifier.testTag`:

```kotlin
@Composable
fun CounterScreen() {
    var count by remember { mutableStateOf(0) }
    Column(modifier = Modifier.padding(24.dp)) {
        Text(
            text = "Count: $count",
            modifier = Modifier.testTag("CounterValue"),
        )
        Button(
            onClick = { count++ },
            modifier = Modifier.testTag("Increment"),
        ) {
            Text("Increment")
        }
    }
}
```

If you can't change the UI to add tags, you can still find nodes by visible text, content
description, or role — see [Finding nodes](selectors.md).

## Write the test

=== "JUnit 5"

    ```kotlin
    import dev.sebastiano.spectre.core.ComposeAutomator
    import dev.sebastiano.spectre.testing.ComposeAutomatorExtension
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.runBlocking
    import kotlinx.coroutines.withContext
    import org.junit.jupiter.api.Test
    import org.junit.jupiter.api.extension.RegisterExtension

    class CounterTest {

        @JvmField
        @RegisterExtension
        val automatorExt = ComposeAutomatorExtension()

        @Test
        fun `clicking increment bumps the counter`() = runBlocking {
            launchCounterApp() // your harness — opens the Compose window

            withContext(Dispatchers.Default) {
                val automator = automatorExt.automator

                automator.waitForNode(tag = "CounterValue")
                automator.waitForVisualIdle()

                val initial = automator.findOneByTestTag("CounterValue")
                check(initial?.text == "Count: 0")

                val increment = automator.findOneByTestTag("Increment")
                    ?: error("Could not find Increment button")
                automator.click(increment)

                automator.waitForVisualIdle()

                val updated = automator.findOneByTestTag("CounterValue")
                check(updated?.text == "Count: 1")
            }
        }
    }
    ```

=== "JUnit 4"

    ```kotlin
    import dev.sebastiano.spectre.core.ComposeAutomator
    import dev.sebastiano.spectre.testing.ComposeAutomatorRule
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.runBlocking
    import kotlinx.coroutines.withContext
    import org.junit.Rule
    import org.junit.Test

    class CounterTest {

        @get:Rule
        val automatorRule = ComposeAutomatorRule()

        @Test
        fun clickingIncrementBumpsTheCounter() = runBlocking {
            launchCounterApp()

            withContext(Dispatchers.Default) {
                val automator = automatorRule.automator

                automator.waitForNode(tag = "CounterValue")
                automator.waitForVisualIdle()

                val initial = automator.findOneByTestTag("CounterValue")
                check(initial?.text == "Count: 0")

                val increment = automator.findOneByTestTag("Increment")
                    ?: error("Could not find Increment button")
                automator.click(increment)

                automator.waitForVisualIdle()

                val updated = automator.findOneByTestTag("CounterValue")
                check(updated?.text == "Count: 1")
            }
        }
    }
    ```

## What just happened

- `ComposeAutomatorExtension` / `ComposeAutomatorRule` builds a fresh `ComposeAutomator`
  for each test. The default factory is `ComposeAutomator.inProcess()`, which uses
  `RobotDriver` for real OS-level input.
- `waitForNode(tag = "CounterValue")` polls the semantics tree until the node exists.
  It's how you bridge the gap between "the test started" and "the UI is on screen".
- `waitForVisualIdle()` waits until the on-screen pixels stop changing for a few frames in
  a row — useful between an interaction and a read-back.
- `findOneByTestTag(...)` does a single semantics-tree read — no waiting. If the result
  isn't what you expect, your UI probably wasn't idle yet.
- `automator.click(node)` resolves the node's centre on screen and dispatches a real mouse
  click via `java.awt.Robot`.

The `withContext(Dispatchers.Default)` wrapper keeps the wait helpers off the AWT event
dispatch thread. Calling them from the EDT is rejected at runtime — see
[Synchronization](synchronization.md).

## Where to go next

- **[The automator](automator.md)** for the mental model — surfaces, the semantics tree,
  and the deliberate lack of auto-wait.
- **[Finding nodes](selectors.md)** if `testTag` isn't enough.
- **[Synchronization](synchronization.md)** for the full wait-helper toolkit.
- **[Troubleshooting](troubleshooting.md)** if your test isn't finding what you expect.
