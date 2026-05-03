# Getting started

This page walks through writing a single test against a running Compose Desktop window.
It assumes you've already followed [Installation](installation.md).

## The shape of a Spectre test

A typical Spectre test does four things, in order:

1. **Launch the UI under test.** This is your responsibility — `application { Window { ... } }`
   for a standalone Compose Desktop app, or a custom harness for IDE-hosted Compose.
2. **Wait until the UI has settled.** Spectre's queries don't auto-wait, so there must be a
   point where the window is visible and the first frame is composed before you query it.
3. **Find nodes via the semantics tree** and drive them with mouse, keyboard, and typing.
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
    import dev.sebastiano.spectre.testing.ComposeAutomatorExtension
    import kotlinx.coroutines.runBlocking
    import org.junit.jupiter.api.Test
    import org.junit.jupiter.api.extension.RegisterExtension

    class CounterTest {

        @JvmField
        @RegisterExtension
        val automatorExt = ComposeAutomatorExtension()

        @Test
        fun `clicking increment bumps the counter`() = runBlocking {
            launchCounterApp() // your harness — opens the Compose window

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
    ```

=== "JUnit 4"

    ```kotlin
    import dev.sebastiano.spectre.testing.ComposeAutomatorRule
    import kotlinx.coroutines.runBlocking
    import org.junit.Rule
    import org.junit.Test

    class CounterTest {

        @get:Rule
        val automatorRule = ComposeAutomatorRule()

        @Test
        fun clickingIncrementBumpsTheCounter() = runBlocking {
            launchCounterApp()

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
    ```

## What just happened

- `ComposeAutomatorExtension` and `ComposeAutomatorRule` each build a fresh
  `ComposeAutomator` for each test. The default factory is `ComposeAutomator.inProcess()`, which uses
  `RobotDriver` for real OS-level input.
- `waitForNode(tag = "CounterValue")` polls the semantics tree until the node exists.
  It's how you bridge the gap between "the test started" and "the UI is on screen".
- `waitForVisualIdle()` waits until the on-screen pixels stop changing for a few frames in
  a row — useful between an interaction and a read-back.
- `findOneByTestTag(...)` does a single semantics-tree read — no waiting. If the result
  isn't what you expect, your UI probably wasn't idle yet.
- `automator.click(node)` is `suspend` — it resolves the node's centre on screen,
  marshals the blocking AWT work onto `Dispatchers.IO` internally, and dispatches a
  real mouse click via `java.awt.Robot`.

All interaction methods (`click`, `doubleClick`, `swipe`, `typeText`, …) and all wait
helpers (`waitForNode`, `waitForIdle`, `waitForVisualIdle`) are `suspend`, so the test
body runs inside `runBlocking { … }`. JUnit test methods don't run on the AWT event
dispatch thread, so no extra `withContext` is needed here. If you ever call wait helpers
from a coroutine on `Dispatchers.Main` (Swing EDT), wrap them in
`withContext(Dispatchers.Default)` — they reject EDT callers at runtime. See
[Synchronization](synchronization.md).

!!! warning "Use `runBlocking`, not `runTest`"
    `runTest` from `kotlinx-coroutines-test` controls time via a virtual scheduler and
    skips `delay()` calls, advancing the clock instantly. Spectre uses `delay` internally
    for timing-sensitive operations — `longClick` hold durations, `swipe` step pacing, and
    clipboard-settle polling in `typeText` — so running under `runTest` silently collapses
    those pauses to zero. The result is that `longClick` doesn't actually hold, `swipe`
    jumps to the end position instantly, and clipboard operations may race.

    Stick with `runBlocking` for Spectre tests. A future `runSpectreTest` helper may
    provide `runTest`-style structured concurrency while preserving real time for
    Spectre's internal delays.

## Where to go next

- **[The automator](automator.md)** for the mental model — surfaces, the semantics tree,
  and the deliberate lack of auto-wait.
- **[Finding nodes](selectors.md)** if `testTag` isn't enough.
- **[Synchronization](synchronization.md)** for the full wait-helper toolkit.
- **[Troubleshooting](troubleshooting.md)** if your test isn't finding what you expect.
