---
name: spectre
description: Use when writing, debugging, or reviewing tests that drive a real on-screen Compose Desktop window with Spectre through semantics plus real/synthetic input, cooperative desktop leases, HTTP, or agent attach. Trigger on `ComposeAutomator`, `RobotDriver`, `AutomatorNode`, `InputLeasePolicy`, `InputIsolationConfig`, `spectre input-lock`, `AgentAttach`, `AttachedAutomator`, `findByTestTag`, `waitForNode`, `waitForIdle`, parallel JVM focus contention, cross-JVM Compose automation, screenshots/recording, IntelliJ/Jewel-hosted Compose, or `dev.sebastiano.spectre.*` imports. Also use for JUnit 4/5 tests that open a real top-level Compose window. Do not use for the off-screen `runComposeUiTest` / `createComposeRule` / `onNodeWithTag` framework.
---

# Spectre

Spectre drives a running Compose Desktop UI from a JUnit test.
It reads the semantics tree of a real window and sends synthetic AWT events through
`RobotDriver.synthetic()` by default. Tests can opt into `java.awt.Robot` for real OS input.
`runComposeUiTest` and `onNodeWithTag` belong to the separate off-screen Compose test framework.

Use Spectre to test application windows, popups, IntelliJ or Jewel hosts, and video capture.

## User guide source of truth

Check the published guides when answering setup questions:

- Installation and coordinates: <https://spectre.sebastiano.dev/guide/installation/>
- Experimental desktop input coordination: <https://spectre.sebastiano.dev/guide/input-coordination/>
- Agent attach: <https://spectre.sebastiano.dev/guide/agent/>
- Cross-JVM HTTP transport: <https://spectre.sebastiano.dev/guide/cross-jvm/>
- Compose Hot Reload awareness (CLI/MCP only): <https://spectre.sebastiano.dev/guide/hot-reload/>

In the Spectre repository, the same pages live under `docs/guide/`.

## Compose Hot Reload MCP and Spectre

When both MCP servers are configured:

> Use Compose Hot Reload MCP to check recomposition during a live reload session.
> Use Spectre to inspect or control the UI.

Repeatable tests run without Hot Reload. `waitForReloadSettled` exists on the interactive
CLI, daemon, and MCP paths; `:testing` and JUnit do not expose it.

## Test setup

A test needs three components:

1. **A running Compose window.** The test harness launches the app
   (e.g. `application { Window(...) { … } }`); Spectre does *not* host it.
2. **A `ComposeAutomator`.** Built once per test via
   `ComposeAutomator.inProcess()` (usually through the JUnit extension/rule),
   it discovers Compose surfaces and reads their semantics.
3. **Suspending input + synchronization calls** against that automator. All
   input methods (`click`, `typeText`, etc.) and waits are `suspend` functions.
   Wrap the test body in `runSpectreTest { ... }` (from `dev.sebastiano.spectre.testing`).

Every `findBy…` call reads the current state once.
Wait explicitly when the required state may appear later.

## Minimal end-to-end example

```kotlin
import dev.sebastiano.spectre.testing.ComposeAutomatorExtension
import dev.sebastiano.spectre.testing.runSpectreTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class CounterTest {
    @JvmField
    @RegisterExtension
    val automatorExt = ComposeAutomatorExtension()

    @Test
    fun `clicking increment bumps the counter`(): Unit = runSpectreTest {
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

JUnit 4 uses `ComposeAutomatorRule` and `@get:Rule` in place of the JUnit 5 extension.

## Choosing a `RobotDriver`

`ComposeAutomator.inProcess()` uses synthetic AWT input by default.
Choose a driver for the interactions the test needs:

- **`RobotDriver.synthetic()` / `RobotDriver.synthetic(rootWindow = someTopLevelWindow)`**
  dispatches AWT events into a live `java.awt.Window`. It is the default and
  supports parallel test JVMs without global focus contention. It also works
  for IDE-hosted Compose. For keys, Spectre uses the AWT focus owner when
  available. Otherwise, it finds a key-listening descendant under the last
  pointer target or Compose host. This lets a Compose Desktop `TextField`
  receive input in macOS helper JVMs with `apple.awt.UIElement=true`, where
  AWT may provide no `Window.focusOwner`. Synthetic input does not trigger
  OS shortcuts such as Cmd+Tab or system menus.
- **`RobotDriver()`** sends real `java.awt.Robot` input on the host. It uses
  global focus and supports OS shortcuts and focus handoffs. The no-argument
  form remains uncoordinated while input coordination is experimental. If
  several processes need real input, use `RobotDriver(InputLeasePolicy.Required)`
  and read `references/input-coordination.md`.
- **`RobotDriver.headless()`** — refuses to send any input. Use it for tests that
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
build the automator with the required driver:

```kotlin
@JvmField
@RegisterExtension
val automatorExt = ComposeAutomatorExtension {
    ComposeAutomator.inProcess(robotDriver = RobotDriver.headless())
}
```

The JUnit 4 rule uses the same shape: `ComposeAutomatorRule { ComposeAutomator.inProcess(...) }`.

## Finding nodes

`ComposeAutomator` selectors return one `AutomatorNode` or a list of nodes.
They read once. Use the synchronization helpers when a node may appear later.

Choose a selector by the semantics the application provides:

1. **`findByTestTag(tag)` / `findOneByTestTag(tag)`** — relies on
   `Modifier.testTag("…")` on the composable. The default. Most reliable.
   `hasTag(tag)` is the boolean form of the same snapshot read.
2. **`findByText(text, exact = true)` / `findOneByText(...)`** — match
   semantics `Text`. Brittle to i18n; OK for affordances written in test
   harness code. `hasText(text)` is the boolean form of the same snapshot
   read.
3. **`findByContentDescription(...)`** — accessibility descriptions.
4. **`findByRole(Role.Button)`** — semantics roles.
5. **`allNodes()`** / **`tree()`** / **`printTree()`** — for debugging.
   `printTree()` returns a human-readable dump; log it when a selector returns
   `null`.

`AutomatorNode` exposes `testTag`, `text`/`texts`, `contentDescription(s)`,
`role`, `isFocused`, `isDisabled`, `isSelected`, `editableText`, plus
coordinates: `boundsInWindow` (Compose pixels, already density-scaled),
`boundsOnScreen` (screen pixels, post-HiDPI), `centerOnScreen`. Tree
navigation via `children`/`parent`. Compare `boundsInWindow` against
`Dp.roundToPx()` / `LocalDensity`-scaled values. For `boundsOnScreen`, only
compare sizes and coordinate deltas against dp figures; absolute `x`/`y` also
include the Compose surface origin, so subtract that (or prefer
`boundsInWindow` for window-relative position asserts).

## Driving input

All `suspend` on `ComposeAutomator`:

- `click(node)`, `doubleClick(node)`, `longClick(node, holdFor = 500.milliseconds)`
- `moveTo(node)`, `moveTo(x, y)`, `moveBy(deltaX, deltaY)` move the pointer
  without pressing a button. `moveBy` starts from the last Spectre pointer
  position and throws if none exists.
- `swipe(from, to, steps, duration)` or `swipe(startX, startY, endX, endY, …)`
- `scrollWheel(node, wheelClicks)`
- `typeText("hello")` — types supported ASCII text via key events without using
  the clipboard. Use `pasteText` for large or Unicode strings.
- `pasteText("hello")` — pastes via the system clipboard. On macOS the clipboard
  write is async; Spectre polls until the clipboard reads back the requested text.
  Disable clipboard managers in CI, and do not use `apple.awt.UIElement=true` for
  JVMs that need clipboard-backed paste. `typeText` is the preferred UIElement-safe
  path for supported ASCII because it uses per-character key events and does not
  touch the clipboard.
- `clearAndTypeText(node, "new")` — Ctrl/Cmd+A, Backspace, then `typeText`.
- `pressKey(KeyEvent.VK_ENTER, modifiers = 0)`, `pressEnter()`.
- `performSemanticsClick(node)` invokes Compose `OnClick` semantics directly.
  Use it for click-only or strictly headless flows. It does not type, send
  keys, or scroll. For parallel JVM tests that also type text, use
  `RobotDriver.synthetic(rootWindow)`.
- `focusWindow(node)` — raises and focuses the window hosting `node`. Over attach,
  use `AttachedAutomator.focusWindow(nodeKey)` (#364) before `pressKey` / `typeText`
  when the target app may not own OS keyboard focus.

## Synchronization

Wait for expected UI state before querying or interacting with it.

### Queries read once

`findByTestTag("Submit")` / `hasTag("Submit")` read whatever is in the semantics
tree *right now*. If the screen hasn't rendered yet, they return empty / `false`.
Always wait before querying state that depends on a prior action.

### Choose a wait

- **`waitForNode(tag = "...", timeout = 5.seconds)`** — wait until a node with
  the given tag (or text) exists. Throws on timeout. Use this when a *new*
  node must appear.
- **`waitUntilGone(tag = "...", timeout = 5.seconds)`** — wait until *no* node
  with the given tag (or text) exists in any tracked window. Refreshes windows
  before every poll, so a popup that closes its own `Window` counts as gone.
  Throws `IllegalStateException` naming the selector on timeout. Use this
  after dismissing a popup, menu, or dialog.
- **`waitUntil(description = "...") { ... }`** — wait until a predicate on the
  `AutomatorTree` snapshot holds. The lambda's receiver is the tree, so phrase
  the condition with `windows()` / `allNodes()` / `roots()`; every poll re-reads
  it (refreshing windows first). Throws `IllegalStateException` naming the
  `description` on timeout, so describe the expected state. Use
  this for barriers no tag/text selector expresses: a node count, a comparison,
  or a combination. It is scoped to Spectre-observable state. Wait for a service
  flag, a file, or an HTTP response with the tool that owns it.
- **`waitForIdle()`** — wait until the semantics fingerprint stabilizes and
  all registered `AutomatorIdlingResource`s are idle. Use this after
  work that updates semantics without producing a specific new node.
- **`waitForVisualIdle(stableFrames = 3)`** — wait until tracked Compose
  surface pixels are stable for N consecutive frames. With `spectre-recording`
  present, samples use the same window-scoped native still path as
  `screenshot(windowIndex)`; it does not use a screen-region crop. It is heavier than
  `waitForIdle`. Use it before screenshotting or for animations that do not
  change semantics.

A typical pattern after an interaction is `waitForVisualIdle()`. After
triggering a screen *change* (e.g. opening a dialog), `waitForNode(tag = …)`
is more precise.

### Keep waits off the EDT

All five waits reject calls from the AWT event dispatch thread: `waitForNode`,
`waitUntilGone`, `waitUntil`, `waitForIdle`, and `waitForVisualIdle`.
They read state through `invokeAndWait`; an EDT caller could deadlock.
On a Swing-backed dispatcher, including IntelliJ's EDT dispatcher, move the
wait with `withContext(Dispatchers.Default) { … }` or another non-EDT dispatcher.
`waitForNode` follows the same rule.

### Use `runSpectreTest`

`kotlinx-coroutines-test`'s `runTest` skips `delay()`. That collapses
`longClick` hold durations, `swipe` pacing, and the macOS clipboard-settle
poll inside `pasteText` to zero, breaking them all. Use `runSpectreTest { ... }`
in the test body.

### Return `Unit` from expression-body tests

Write `@Test fun mySpec(): Unit = runSpectreTest { ... }`. JUnit 5.14+ rejects
non-void test methods during discovery, and Kotlin infers an expression-body
function's return type from the last expression in the `runSpectreTest` body. Some
assertions return the asserted value. JUnit therefore needs the explicit `Unit` return type.

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

`automator.screenshot()` returns a `BufferedImage` (or a multi-window scene; see below):

```kotlin
import dev.sebastiano.spectre.core.WindowScreenshotResult

automator.screenshot()                  // full desktop
automator.screenshot(region = Rectangle(x, y, w, h))
automator.screenshot(node)              // node bounds
automator.screenshot(windowIndex = 0)   // a tracked window

// several physical windows (back→front painter order), e.g. owner then dialog/popup
val scene = automator.screenshotWindows(listOf(0, 1))
scene.windows.forEachIndexed { index, result ->
    if (result is WindowScreenshotResult.Success) {
        // result.image, bounds, density, capture order
    }
}
scene.composite?.let { /* union canvas of successful captures; null if any window failed */ }
```

`screenshotWindows` is for heavyweight popup renderers (Swing/Jewel dialogs, menus,
tooltips). Index list is painter's order; put the owner before its popups. The composite
uses the union of requested bounds so a popup that extends past the owner is not clipped.
If any requested window fails, that entry is `WindowScreenshotResult.Failure` and
`composite` is null; successful per-window images remain available.

Call `waitForVisualIdle()` before a screenshot when an animation may still change the pixels.

On macOS, screenshots need an unlocked console session in addition to Screen
Recording TCC. A locked screen can make `Robot.createScreenCapture` return black
pixels; current Spectre checks the macOS `IOConsoleLocked` flag first and tells
the caller to unlock/retry before pointing at TCC.

`screenshot(node)` and `screenshot(windowIndex)` are native window-scoped still
screenshots and require `:recording`; they fail clearly when no native backend is available.
They never substitute a framebuffer crop. Use `screenshot(region)` only when a
screen-region capture is intended. `AutoScreenshotter` remains the direct top-level-window
API. This is separate from video recording:

- macOS: `ScreenCaptureKitScreenshotter` via `spectre-recording-macos`.
- Windows: `WindowsWindowScreenshotter` takes stills, and
  `WindowsGraphicsCaptureRecorder` records window or region video. Both use the
  framework-dependent Windows Graphics Capture helper in `spectre-recording-windows`
  for x64 and arm64. Runtime needs Windows 10 version 1903 or newer, .NET 8
  Desktop Runtime, and Windows App Runtime 1.8. Source builds and CI also need
  .NET 8 SDK.
- Linux X11: Linux helper (`ximagesrc`) for stills; the target must be visible/frontmost.
- Linux Wayland: Linux portal helper for stills and window-targeted video. On a seated
  session, monitor capture and real OS input share **one** long-lived
  `spectre-wayland-helper` portal grant (later processes reuse the seat socket); see
  `references/recording.md`.

## Recording, JUnit, IntelliJ-hosted Compose

Open the relevant reference for recording, JUnit, input coordination, IntelliJ, or attach work:

- **Still window screenshots and video recording** → `references/recording.md` —
  `AutoScreenshotter`, `AutoRecorder`, platform helper artifacts
  (`spectre-recording-macos` / `-linux` / `-windows`), region vs window targeting,
  frame-drop and HiDPI traps.
- **JUnit 4 vs JUnit 5 integration** → `references/junit.md` —
  `ComposeAutomatorExtension`, `ComposeAutomatorRule`, parameter resolution,
  lifecycle, and opt-in **screenshot golds** (`assertMatchesGold`,
  `ScreenshotTolerance`, JUnit 5 `TestInfo` / `ScreenshotGoldJunit5`).
- **Experimental desktop input coordination** → `references/input-coordination.md` — real-input
  contention across JVMs, `InputLeasePolicy`, JUnit `InputIsolationConfig`, runtime dependency,
  exact revoke, and unsafe forced recovery.
- **IntelliJ/Jewel-hosted Compose** → `references/intellij.md` — running
  the automator from an `AnAction` against the IDE frame, the
  pooled-thread requirement, and the `synthetic` driver. If the work also
  involves Jewel popups, `ComposePanel` embedding, or `SwingBridgeTheme`,
  the repo-local `jewel-swing-interop` skill applies as well.
- **Java-agent attach** → `references/agent.md` — `AgentAttach`,
  `AttachedAutomator`, `AttachOptions`, the `spectre-agent` /
  `spectre-agent-runtime` split, runtime jar auto-discovery, custom
  attach paths, and **inject** when the target has Compose without
  preinstalled `spectre-core`.

## Common failures

| Symptom | Cause | Fix |
|---|---|---|
| `findOneBy…` returns `null` right after an interaction | Selectors don't wait | Add `waitForNode(...)` or `waitForVisualIdle()` first |
| Test deadlocks or throws inside any `waitFor…` / `waitUntil…` | Called from the AWT EDT | Wrap in `withContext(Dispatchers.Default) { … }` — applies to all five waits, including `waitForNode`, `waitUntilGone`, and `waitUntil` |
| `longClick`/`swipe`/`typeText` complete instantly and miss | Test body uses `runTest` | Switch to `runSpectreTest` |
| Two parallel test JVMs steal focus from each other | Both opted into real `RobotDriver()` | Stay on the default `RobotDriver.synthetic()` (or pin `rootWindow`) |
| Parallel JVMs must preserve real OS input behavior | Real focus/pointer/clipboard state is shared across processes | Opt in to experimental coordination, add `spectre-input-coordinator-server` at runtime, use `Required` and JUnit `InputIsolationConfig.perTest()` |
| Cmd+Tab or OS shortcuts don't work under synthetic driver | Synthetic events bypass HID | Use real `RobotDriver()` for those tests |
| Screenshot is blurry / mid-animation | Captured before frame stabilised | `waitForVisualIdle()` first |
| Windows WGC helper missing or fails to start | `spectre-recording-windows` is not on the runtime classpath, the helper was not built/staged locally, or .NET / Windows App Runtime is missing | Add `testRuntimeOnly("dev.sebastiano.spectre:spectre-recording-windows")`; install .NET 8 Desktop Runtime + Windows App Runtime 1.8 for runtime; for source builds install .NET 8 SDK and run `:recording-windows:verifyRecordingWindowsHelper` |
| `typeText` silently does not land on macOS with `apple.awt.UIElement=true` | Usually stale Spectre or wrong `rootWindow`; current synthetic input should target the Compose key-listening host even without AWT focus | Verify the test uses a Spectre build with the UIElement synthetic-key fallback, click/focus the field first, and pass the top-level host window to `RobotDriver.synthetic(rootWindow)` |
| `pasteText` silently does not land on macOS with `apple.awt.UIElement=true` | UI-element/helper mode breaks clipboard-backed paste, even with synthetic input | Disable `apple.awt.UIElement=true` for the JVM hosting the test window, or use `typeText` for supported ASCII |
| `pasteText` times out on macOS in CI | Clipboard manager rewriting `NSPasteboard` | Disable clipboard utilities in CI |
| Recording misses popups that escape the host window | Popups live in their own AWT window outside both the region rectangle *and* a window-targeted capture | Choose an explicit region (or full-desktop crop) wide enough to include where the popup opens, or document the limitation — neither region nor window targeting follows cross-window popups |
| Window-targeted Wayland recording throws `IllegalStateException` | `xprop` missing or non-GNOME compositor | Use `AutoRecorder.startRegion(...)` with an explicit rectangle |
| Coordinates derived from `boundsInWindow` land off-target on HiDPI | `boundsInWindow` is Compose pixels (already density-scaled) and window-relative; AWT/Robot and recording regions use screen pixels | Use `boundsOnScreen`/`centerOnScreen` for input and region targeting. If converting yourself, divide by the display scale **and** add the surface/panel screen origin — do not multiply |

## API boundaries

- If a tagged release is not available on Maven Central yet, consumers wire Spectre as a
  composite build or local clone. Check the installation guide before giving coordinates.
- Spectre tests use Spectre APIs. Keep `compose-test`, `runComposeUiTest`, and
  `onNodeWithTag` out of them.
- It does **not** capture audio. Recording is video-only.
- The cross-JVM **HTTP transport is experimental**. It uses bearer authentication
  and HTTPS by default. It offers windows, nodes, input, and screenshots, with
  no live idling-resource registration or `withTracing`. It grants privileged
  desktop automation. Explain these limits when recommending it. User guide:
  <https://spectre.sebastiano.dev/guide/cross-jvm/>.
- Desktop input coordination is experimental, cooperative, and opt-in. Do not call it an OS input
  grab, imply `RobotDriver()` coordinates by default, or suggest `--force` without its
  `unsafeTakeover=true` overlap warning.
- It does **not** auto-wait on queries. Don't write tests that assume it
  does.

## Inspect the tree

Print the tree before a failing selector:

```kotlin
println(automator.printTree())
```

The output lists visible nodes, tags, text, and bounds.
If it is empty, composition may have failed before registering nodes.
Check test stderr for EDT or composition exceptions.
