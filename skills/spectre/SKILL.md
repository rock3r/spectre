---
name: spectre
description: Use when writing, debugging, or reviewing tests that drive a real, on-screen Compose Desktop window with Spectre — the JVM/Kotlin library for automating live Compose Desktop UIs (and IntelliJ/Jewel-hosted Compose) via the semantics tree plus java.awt.Robot or synthetic AWT input. Trigger on mentions of `ComposeAutomator`, `RobotDriver`, `AutomatorNode`, `findByTestTag`, `waitForNode`, `waitForIdle`, "automate a Compose window", "live/real-window Compose Desktop UI test", screenshotting or recording a Compose Desktop window, or any task involving `dev.sebastiano.spectre.*` imports. Also use when the user is writing JUnit 4/5 tests against Compose Desktop **and** the test opens a real top-level window — but NOT when the user wants the off-screen `runComposeUiTest` / `createComposeRule` / `onNodeWithTag` framework, which is a different tool.
---

# Spectre

Spectre drives **live** Compose Desktop UIs from JUnit tests. It is *not* the
Compose Multiplatform test framework (`runComposeUiTest` / `onNodeWithTag`) —
that one runs an off-screen Compose tree on a virtual clock. Spectre opens a real
window, reads its semantics tree, and feeds it real OS input via
`java.awt.Robot` (or synthetic AWT events).

Pick Spectre when the test needs to exercise the actual window, popups,
IntelliJ/Jewel-hosted Compose, or to record a real video of the UI.

## The 30-second mental model

A test owns three things, in this order:

1. **A running Compose window.** You launch your app or test harness yourself
   (e.g. `application { Window(...) { … } }`); Spectre does *not* host it for
   you.
2. **A `ComposeAutomator`.** Built once per test via
   `ComposeAutomator.inProcess()` (usually through the JUnit extension/rule),
   it discovers Compose surfaces and reads their semantics.
3. **Suspending input + synchronization calls** against that automator. All
   input methods (`click`, `typeText`, etc.) and waits are `suspend` functions
   — wrap the test body in `runBlocking { ... }`.

There is no `compose-test` style auto-wait. Every `findBy…` call is a single
read against current state. **You wait explicitly**, then you query.

## Minimal end-to-end example

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
    fun `clicking increment bumps the counter`(): Unit = runBlocking {
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

JUnit 4 users substitute `ComposeAutomatorRule` for `ComposeAutomatorExtension`
and `@get:Rule` for `@RegisterExtension`.

## Choosing a `RobotDriver`

`ComposeAutomator.inProcess()` accepts a `RobotDriver`. Default is real input
on the host OS. Three variants:

- **`RobotDriver()`** — real `java.awt.Robot` input on the host. Highest
  fidelity, but contends for global focus. Use for the default
  single-process test run.
- **`RobotDriver.synthetic(rootWindow = someTopLevelWindow)`** — dispatches
  AWT events directly into the given `java.awt.Window` hierarchy. No global
  focus contention, so safe for **parallel test JVMs** and for IDE-hosted
  Compose where stealing the IDE focus would be hostile. Does **not** see OS
  shortcuts (Cmd+Tab, system menus).
- **`RobotDriver.headless()`** — refuses to send any input. For tests that
  only read the semantics tree (e.g. asserting a screen layout) without
  driving it.

```kotlin
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
val automator = ComposeAutomator.inProcess(
    robotDriver = RobotDriver.synthetic(rootWindow = ideFrame),
)
```

### Wiring a custom driver through the JUnit extension/rule

`ComposeAutomatorExtension` and `ComposeAutomatorRule` do **not** take a
`robotDriver = …` named argument. Their primary constructor takes a single
`AutomatorFactory = () -> ComposeAutomator`. Use the trailing-lambda form to
build the automator with the driver you want:

```kotlin
@JvmField
@RegisterExtension
val automatorExt = ComposeAutomatorExtension {
    ComposeAutomator.inProcess(robotDriver = RobotDriver.headless())
}
```

Same shape for the JUnit 4 rule — `ComposeAutomatorRule { ComposeAutomator.inProcess(...) }`.

## Finding nodes

Selectors all live on `ComposeAutomator` and return `AutomatorNode` (or a list
thereof). They do **not** wait — see the synchronization section.

Use, in order of preference:

1. **`findByTestTag(tag)` / `findOneByTestTag(tag)`** — relies on
   `Modifier.testTag("…")` on the composable. The default. Most reliable.
2. **`findByText(text, exact = true)` / `findOneByText(...)`** — match
   semantics `Text`. Brittle to i18n; OK for affordances written in test
   harness code.
3. **`findByContentDescription(...)`** — accessibility descriptions.
4. **`findByRole(Role.Button)`** — semantics roles.
5. **`allNodes()`** / **`tree()`** / **`printTree()`** — for debugging.
   `printTree()` returns a human-readable dump; log it when a selector returns
   `null`.

`AutomatorNode` exposes `testTag`, `text`/`texts`, `contentDescription(s)`,
`role`, `isFocused`, `isDisabled`, `isSelected`, `editableText`, plus
coordinates: `boundsInWindow` (dp), `boundsOnScreen` (screen pixels,
post-HiDPI), `centerOnScreen`. Tree navigation via `children`/`parent`.

## Driving input

All `suspend` on `ComposeAutomator`:

- `click(node)`, `doubleClick(node)`, `longClick(node, holdFor = 500.milliseconds)`
- `swipe(from, to, steps, duration)` or `swipe(startX, startY, endX, endY, …)`
- `scrollWheel(node, wheelClicks)`
- `typeText("hello")` — types supported ASCII text via key events without using
  the clipboard. Use `pasteText` for large or Unicode strings.
- `pasteText("hello")` — pastes via the system clipboard. On macOS the clipboard
  write is async; Spectre polls until the clipboard reads back the requested text.
  Disable clipboard managers in CI, and do not use `apple.awt.UIElement=true` for
  JVMs that need clipboard-backed paste.
- `clearAndTypeText(node, "new")` — Ctrl/Cmd+A, Backspace, then `typeText`.
- `pressKey(KeyEvent.VK_ENTER, modifiers = 0)`, `pressEnter()`.
- `performSemanticsClick(node)` — bypasses the OS entirely and invokes
  the Compose `OnClick` semantics action. **Last resort** for click-only flows
  or strictly headless contexts: it only clicks (no typing, no key events,
  no scrolling), so it can't fully replace OS input for most tests. For
  parallel-JVM focus contention, prefer `RobotDriver.synthetic(rootWindow)`
  — especially as soon as the test also types text.
- `focusWindow(node)` — raises and focuses the window hosting `node`.

## Synchronization — the part everyone gets wrong

This is the #1 source of flakes. Internalize three rules:

### Rule 1: queries don't wait

`findByTestTag("Submit")` returns whatever is in the semantics tree *right
now*. If the screen hasn't rendered yet, it returns null. Always wait before
querying state that depends on a prior action.

### Rule 2: pick the right wait

- **`waitForNode(tag = "...", timeout = 5.seconds)`** — wait until a node with
  the given tag (or text) exists. Throws on timeout. Use this when a *new*
  node must appear.
- **`waitForIdle()`** — wait until the semantics fingerprint stabilizes and
  all registered `AutomatorIdlingResource`s are idle. Use this when you've
  triggered work that updates semantics but no specific node appears.
- **`waitForVisualIdle(stableFrames = 3)`** — wait until the on-screen pixels
  are stable for N consecutive frames. Heavier than `waitForIdle`. Use it
  before screenshotting, or when work is animation-bound rather than
  semantics-bound.

A typical pattern after an interaction is `waitForVisualIdle()`. After
triggering a screen *change* (e.g. opening a dialog), `waitForNode(tag = …)`
is more precise.

### Rule 3: never call any wait on the EDT

All three of `waitForNode`, `waitForIdle`, and `waitForVisualIdle` actively
reject being called from the AWT event dispatch thread — they need to
`invokeAndWait` onto the EDT to read state, so running them *on* the EDT would
deadlock. If your dispatcher is Swing-backed (the IntelliJ EDT dispatcher, a
custom `Swing` dispatcher, etc.), wrap the wait in
`withContext(Dispatchers.Default) { … }` (or any non-EDT dispatcher) so the
wait suspends off-thread. The user docs you may have read elsewhere have an
older carve-out for `waitForNode` — that exception is gone in current code.

### Rule 4: use `runBlocking`, not `runTest`

`kotlinx-coroutines-test`'s `runTest` skips `delay()`. That collapses
`longClick` hold durations, `swipe` pacing, and the macOS clipboard-settle
poll inside `pasteText` to zero, breaking them all. Use `runBlocking { ... }`
in the test body.

### Rule 4b: force expression-body tests to return `Unit`

Write `@Test fun mySpec(): Unit = runBlocking { ... }`. JUnit 5.14+ rejects
non-void test methods during discovery, and Kotlin infers an expression-body
function's return type from the last expression in the `runBlocking` body. Some
assertions return the asserted value, not `Unit`.

### Custom idling resources

Background work that doesn't tick the semantics tree (custom animations,
network calls) is invisible to `waitForIdle`. Register an
`AutomatorIdlingResource` so the wait knows about it:

```kotlin
automator.registerIdlingResource(myResource)
try {
    // ...
    automator.waitForIdle()
} finally {
    automator.unregisterIdlingResource(myResource)
}
```

## Screenshots

`automator.screenshot()` returns a `BufferedImage`. Three forms:

```kotlin
automator.screenshot()                  // full desktop
automator.screenshot(region = Rectangle(x, y, w, h))
automator.screenshot(node)              // node bounds
automator.screenshot(windowIndex = 0)   // a tracked window
```

Always `waitForVisualIdle()` immediately before screenshotting — otherwise
you may capture a mid-animation frame.

## Recording, JUnit, IntelliJ-hosted Compose

These each have their own reference. Read the file *only when the task
touches that area*; they are not needed for the common case.

- **Video recording** → `references/recording.md` — `AutoRecorder`, platform
  capture backends (ScreenCaptureKit / gdigrab / ffmpeg / Wayland portal),
  region vs window targeting, frame-drop and HiDPI traps.
- **JUnit 4 vs JUnit 5 integration** → `references/junit.md` —
  `ComposeAutomatorExtension`, `ComposeAutomatorRule`, parameter resolution,
  lifecycle.
- **IntelliJ/Jewel-hosted Compose** → `references/intellij.md` — running
  the automator from an `AnAction` against the IDE frame, the
  pooled-thread requirement, and the `synthetic` driver. If the work also
  involves Jewel popups, `ComposePanel` embedding, or `SwingBridgeTheme`,
  the repo-local `jewel-swing-interop` skill applies as well.

## Common pitfalls (memorise these)

| Symptom | Cause | Fix |
|---|---|---|
| `findOneBy…` returns `null` right after an interaction | Selectors don't wait | Add `waitForNode(...)` or `waitForVisualIdle()` first |
| Test deadlocks or throws inside any `waitFor…` | Called from the AWT EDT | Wrap in `withContext(Dispatchers.Default) { … }` — applies to all three waits, including `waitForNode` |
| `longClick`/`swipe`/`typeText` complete instantly and miss | Test body uses `runTest` | Switch to `runBlocking` |
| Two parallel test JVMs steal focus from each other | Both use real `RobotDriver()` | Use `RobotDriver.synthetic(rootWindow)` |
| Cmd+Tab or OS shortcuts don't work under synthetic driver | Synthetic events bypass HID | Use real `RobotDriver()` for those tests |
| Screenshot is blurry / mid-animation | Captured before frame stabilised | `waitForVisualIdle()` first |
| `pasteText` silently does not land on macOS with `apple.awt.UIElement=true` | UI-element/helper mode breaks clipboard-backed paste, even with synthetic input | Disable `apple.awt.UIElement=true` for the JVM hosting the test window, or use `typeText` for supported ASCII |
| `pasteText` times out on macOS in CI | Clipboard manager rewriting `NSPasteboard` | Disable clipboard utilities in CI |
| Recording misses popups that escape the host window | Popups live in their own AWT window outside both the region rectangle *and* a window-targeted capture | Choose an explicit region (or full-desktop crop) wide enough to include where the popup opens, or document the limitation — neither region nor window targeting follows cross-window popups |
| Window-targeted Wayland recording throws `IllegalStateException` | `xprop` missing or non-GNOME compositor | Use `AutoRecorder.startRegion(...)` with an explicit rectangle |
| Coordinates derived from `boundsInWindow` land off-target on HiDPI | `boundsInWindow` is dp; screen is pixels | Use `boundsOnScreen`/`centerOnScreen`, or apply density |

## What Spectre is NOT (don't pretend it is)

- It is **not** published to Maven Central yet. Consumers wire it as a
  composite build or local clone. Do not suggest `implementation("dev.sebastiano.spectre:…")`
  unless the user confirms artifacts exist.
- It is **not** `compose-test` / `runComposeUiTest` / `onNodeWithTag`. Don't
  mix those APIs into a Spectre test.
- It does **not** capture audio. Recording is video-only.
- The cross-JVM **HTTP server is experimental** and security-caveated — do
  not recommend it for general use without flagging that.
- It does **not** auto-wait on queries. Don't write tests that assume it
  does.

## When unsure, dump the tree

The single most useful debugging primitive:

```kotlin
println(automator.printTree())
```

Run it right before a failing selector. The output names every node Spectre
can see, with tags/texts/bounds. 90% of "selector returned null" mysteries
resolve here. If it returns an empty string, the composition probably crashed
before any node registered; check test stderr for EDT/composition exceptions.
