---
name: spectre
description: >
  Use when writing, debugging, or reviewing UI automation that drives a real on-screen Compose Desktop or
  IntelliJ/Jewel window with Spectre (`dev.sebastiano.spectre.*`, `ComposeAutomator`, `RobotDriver`, `AgentAttach`,
  `findByTestTag`, `waitForNode`, or `spectre`). Not for off-screen `runComposeUiTest`/`createComposeRule`.
---

# Spectre

Spectre drives a live on-screen Compose Desktop or Swing/IntelliJ/Jewel window through its semantics tree plus synthetic
AWT input (`RobotDriver.synthetic()`, the default) or real OS input (`RobotDriver()`). Never mix `runComposeUiTest`,
`createComposeRule`, or `onNodeWithTag` into a Spectre test; those belong to Compose's separate off-screen framework on
a virtual clock. Even `RobotDriver.headless()` requires a real AWT toolkit (`-Djava.awt.headless=false`, e.g., under
`xvfb-run`; pass `--no-daemon` when `xvfb-run` wraps `./gradlew` so a newly spawned Gradle daemon does not hold Xvfb
open).

## Source of truth

The `spectre` CLI bundles offline docs matching its installed version:

```shell
spectre guide                          # workflow overview and topic list
spectre guide traps                    # known traps and fixes
spectre guide --search "<symptom>"     # ranked sections (-s "<words>" --json for JSON)
```

Published guides mirror `docs/guide/` in the Spectre repository, starting at
<https://spectre.sebastiano.dev/guide/installation/>. Check the installation guide before giving Maven coordinates if a
tagged release is not on Maven Central yet (consumers may wire a composite build or local clone).

## Minimal JUnit 5 test and core invariants

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
    fun mySpec(): Unit = runSpectreTest {
        launchCounterApp() // your harness opens the real Compose window

        val automator = automatorExt.automator
        automator.waitForNode(tag = "CounterValue")
        automator.waitForVisualIdle()
        check(automator.findOneByTestTag("CounterValue")?.text == "Count: 0")

        val increment = automator.findOneByTestTag("Increment") ?: error("Missing Increment")
        automator.click(increment)
        automator.waitForVisualIdle()
        check(automator.findOneByTestTag("CounterValue")?.text == "Count: 1")
    }
}
```

- **Spectre owns the automator, not the window.** Wait for `window.isShowing` and non-zero size before the first query.
  `exitApplication()` is asynchronous; wait for `!window.isDisplayable` in teardown.
- **Always use `runSpectreTest` and `: Unit`.** `kotlinx.coroutines.test.runTest` skips `delay()`, collapsing
  `longClick` holds, `swipe` pacing, and `pasteText` clipboard polling to zero. Expression-body `@Test` methods need an
  explicit `: Unit` return type because JUnit 5.14+ rejects non-void test methods at discovery.
- **JUnit 4 and parallel JUnit 5.** JUnit 4 uses `@get:Rule val automatorRule = ComposeAutomatorRule()`. When JUnit 5
  `@Test` methods run concurrently in one class, inject `(automator: ComposeAutomator)` via
  `@ExtendWith(ComposeAutomatorExtension::class)` (`automatorExt.automator` is sequential-only).

## Choosing and wiring `RobotDriver`

`ComposeAutomator.inProcess()` defaults to synthetic AWT input:

- **`RobotDriver.synthetic()` / `RobotDriver.synthetic(rootWindow = hostWindow)`** (`RobotDriver.Companion`, no separate
  import): dispatches AWT events directly into the live `java.awt.Window` hierarchy without global focus contention —
  safe for parallel test JVMs and IDE-hosted Compose. For keys, Spectre targets the AWT focus owner or falls back to the
  key-listening Compose descendant, so `typeText` works in macOS helper JVMs with `apple.awt.UIElement=true` where
  `Window.focusOwner` is `null`. Does not trigger OS shortcuts (`Cmd+Tab`, system menus) or move the OS cursor
  (`MouseInfo`).
- **`RobotDriver()`**: explicit opt-in for real OS pointer/focus (`MouseInfo` hover via `moveTo`, OS shortcuts).
  Uncoordinated across processes by default; pass `RobotDriver(InputLeasePolicy.Required)` for experimental cross-JVM
  coordination (`references/input-coordination.md`). On Linux Wayland, always use no-arg `RobotDriver()` (routes through
  `spectre-wayland-helper`); `RobotDriver(robot)` or raw `java.awt.Robot()` goes through Xwayland and needs separate
  consent.
- **`RobotDriver.headless()`**: semantics-only inspection; throws `UnsupportedOperationException` on input, clipboard,
  or screenshots.

`ComposeAutomatorExtension` and `ComposeAutomatorRule` have **no** `robotDriver =` parameter. Every factory constructor
puts `factory: AutomatorFactory = () -> ComposeAutomator` last, so both trailing-lambda and `factory = { ... }` compile:

```kotlin
@JvmField
@RegisterExtension
val automatorExt = ComposeAutomatorExtension {
    ComposeAutomator.inProcess(robotDriver = RobotDriver.synthetic(rootWindow = hostWindow))
}
```

## API cheat sheet

### Selectors and tree inspection (non-waiting single reads)

Selectors read the current tree once and never auto-wait:

1. `findByTestTag(tag)` / `findOneByTestTag(tag)` / `hasTag(tag)` — prefer `Modifier.testTag("...")`.
2. `findByText(text, exact = true)` / `findOneByText(...)` / `hasText(...)` (or `TextQuery.exact`/`substring`).
3. `findByContentDescription(...)`, `findByRole(Role.Button)`.
4. `tree()` / `tree(windowIndex)` / `printTree()` / `allNodes()` / `refreshWindows()` / `surfaceIds()`.

`tree()` refreshes windows automatically; `findBy*`, `hasTag`, `hasText`, and `allNodes()` inspect the last snapshot
unless `refreshWindows()` is called. When a selector unexpectedly returns `null`, log `println(automator.printTree())`;
if `printTree()` returns an empty string, composition failed or no window registered yet (check stderr for EDT errors).

`AutomatorNode` exposes `key`, `surfaceId`, `testTag`, `text`/`texts`, `editableText`,
`contentDescription`/`contentDescriptions`, `role`, `isFocused`, `isDisabled`, `isSelected`, `isClickable`,
`toggleableState`, `progressBarRangeInfo`, `parent`, and `children`. Each `AutomatorWindow` in `tree()` exposes
`window`, `composePanel`, `hostComponent`, `isPopup`, and `surfaceId`.

### Coordinate spaces and scrollable containers

- **`boundsInWindow`**: density-scaled surface pixels (e.g., 48 px for `24.dp` at 2× Retina), relative to the Compose
  surface. Compare against `Dp.roundToPx()`/`LocalDensity`, never raw `dp`, and never multiply by display scale.
- **`boundsOnScreen` / `centerOnScreen`**: AWT logical screen pixels (`boundsInWindow / scale + surfaceScreenOrigin`).
  Use for `RobotDriver`, `screenshot(region)`, `screenshotAtDeviceScale(region)`, and `AutoRecorder.startRegion`.
- **`boundsInAwtWindow`**: logical units relative to the hosting `Window` (including title-bar insets).
- **Off-screen `Modifier.verticalScroll(...)` children** report empty `boundsInWindow` (`in-window bounds are empty` on
  pointer actions); scroll the container with `scrollWheel` first, then re-query the child.

### Input (`suspend` except `focusWindow` and `performSemanticsClick`)

- **Pointer:** `click(node)`, `doubleClick(node)`, `longClick(node, holdFor = 500.milliseconds)`, `moveTo(node)` /
  `moveTo(x, y)`, `moveBy(dx, dy)` (throws without a prior Spectre pointer position), `swipe(...)`,
  `scrollWheel(node, wheelClicks)`. Spectre has no secondary-button (right-click) API; use `longClick` where supported
  or dispatch an AWT `MouseEvent`.
- **Keyboard and text:** `typeText("ascii")` sends per-character key events without touching the clipboard (preferred
  for supported ASCII and safe under macOS `apple.awt.UIElement=true`). `pasteText("unicode")` uses the system clipboard
  (polling async macOS pasteboard writes); disable CI clipboard managers and avoid `apple.awt.UIElement=true` on JVMs
  that require `pasteText`. `clearAndTypeText(node, "new")` selects all, deletes, and calls `typeText`.
  `pressKey(KeyEvent.VK_ENTER, modifiers = 0)` and `pressEnter()` send raw keys.
- **Focus and semantics:** `focusWindow(node)` raises/focuses the host window (use before real-input `typeText` or
  `pressKey` over attach when unfocused; synthetic input needs no OS focus). `performSemanticsClick(node)` invokes
  Compose `OnClick` directly for click-only headless flows; it cannot type or scroll.

### Five waits (all `suspend`, all forbid the AWT EDT)

Every wait marshals onto AWT via `invokeAndWait` and throws `IllegalStateException` if called on the EDT. On Swing or
IntelliJ EDT dispatchers, wrap waits in `withContext(Dispatchers.Default) { ... }` or `executeOnPooledThread`.

- `waitForNode(tag = "...", text = ..., timeout = 5.seconds)` — wait until a matching node appears.
- `waitUntilGone(tag = "...", text = ..., timeout = 5.seconds)` — refreshes windows each poll so a closed popup `Window`
  counts as gone.
- `waitUntil(description = "...", timeout = 5.seconds) { ... }` — predicate with `AutomatorTree` receiver (`windows()`,
  `allNodes()`, `roots()`); in-process Spectre tree state only.
- `waitForIdle()` — semantics fingerprint + `AutomatorIdlingResource`s (`registerIdlingResource` /
  `unregisterIdlingResource` in `try/finally`).
- `waitForVisualIdle(stableFrames = 3)` — consecutive pixel-stable frames using required native
  window capture; use before screenshots or after animations. Missing helpers fail immediately.
  Use `allowScreenRegionFallback = true` only when occlusion-sensitive desktop crops are acceptable.
  Uses native window capture when `:recording` and a runnable helper exist, falls back to `Robot` region capture when
  `:recording` is absent or the helper cannot launch, and fails fast on missing Wayland grants or unsupported Wayland
  `JWindow` popups (`#640`).

### Screenshots and atomic capture

Single-target `screenshot(...)` calls return a `BufferedImage` (write PNGs via `ImageIO.write(image, "png", file)`), and
`capture(windowIndex)` returns an `AtomicCapture` (image + semantics snapshot); call `waitForVisualIdle()` before
capturing animated UI.

| Call | Resolution | Backend and failure behaviour |
|---|---|---|
| `screenshot()`, `screenshot(region)` | 1 px per logical screen unit (`boundsOnScreen` maps 1:1) | `Robot` capture; no `:recording` needed |
| `screenshotAtDeviceScale(region)` | Device pixels (2× Retina, 1× Xvfb) | `Robot` multi-resolution capture; derive scale as `image.width / bounds.width` for sub-dp pixel assertions |
| `screenshot(node)`, `screenshot(windowIndex)`, `capture(windowIndex)` | Device pixels | Native window backend (`:recording` + OS helper); **fails closed** — never falls back to a desktop crop |
| `screenshotWindows(listOf(0, 1))` | Device pixels on union canvas | Painter's order (owner before popup); returns `CompositeScreenshot` (`composite` is `null` if any window fails) |

On macOS, screenshots require Screen Recording TCC plus an unlocked console (`IOConsoleLocked`; unlock before debugging
black captures as TCC).

### CLI attach workflow and advanced entry points

For an application that Spectre must not or cannot JVM-attach to, read the native accessibility
tree that a screen reader receives:

```shell
spectre a11y tree --pid <pid>
spectre a11y tree --window "<window title>"
```

The MCP `accessibility_tree` tool takes the same `pid` or `window` target. For an attached session, CLI `capture --source accessibility` and MCP `capture` with
`source: "accessibility"` store `accessibility.json` beside the screenshot; `compose` is the default. Accessibility keys belong to one
snapshot only: re-read and resolve by `id`, name, role, and bounds after the UI changes. On GNOME
Wayland, heed the output's `window-relative` bounds marker and never use those bounds directly as
screen input coordinates. Linux may report that Spectre temporarily enabled session
accessibility; preserve that notice in agent output. AccessKit text-field values can be `null` on
Linux even when the field is present. Its canonical AT-SPI roles are `frame`, `button`, `check
box`, and `entry` (normalised as `window`, `button`, `checkbox`, and `entry`); never rewrite
`entry` as raw `text`, infer a disabled state, or invent a value from another source. On Windows,
the framework-dependent helper requires the .NET 8 Desktop Runtime and a same-architecture public
Windows App Runtime 1.8 installation; the CBS system package alone is insufficient.

`spectre attach <pid>` attaches to a same-user JDK 21+ JVM (injecting core automatically when `spectre-core` is absent):

```shell
spectre ps --json
SESSION=$(spectre attach <pid> --json | jq -r .sessionId)
spectre tree "$SESSION" --json                      # or: spectre find "$SESSION" <tag> --json
spectre scroll-wheel "$SESSION" <node-key> 5        # scroll off-screen verticalScroll items first
spectre click "$SESSION" <node-key>                 # keys are per session + tree; re-query after UI changes
spectre type "$SESSION" "text"                      # click field first; focus-window if real OS input
spectre press-key "$SESSION" Enter
spectre capture "$SESSION" --json                   # window PNG + capture.json (screenshot needs --fullscreen)
spectre detach "$SESSION"                           # daemon restart invalidates sessions; re-attach if needed
```

When Compose Hot Reload MCP is also active, use Hot Reload MCP for recomposition status and Spectre for UI inspection or
input (`spectre wait --reload-settled` / MCP `wait_for_reload_settled` is interactive-only, not in `:testing`).

Other `ComposeAutomator` entry points: `withTracing(output, tracer) { ... }` (in-process),
`@ExperimentalSpectreApi monitorRecompositions()`, `@ExperimentalSpectreInputCoordinationApi withExclusiveInput { ... }`,
and experimental authenticated `ComposeAutomator.http(...)` (`DEFAULT_PORT = 9274`, HTTPS + bearer token by default, no
live idling resources or `withTracing`; flag its desktop-control trust boundary and see
<https://spectre.sebastiano.dev/guide/cross-jvm/>).

## References

Load only the reference matching the task:

- **Native window stills, `AutoScreenshotter`, `AutoRecorder`, OS helpers (`spectre-recording-{macos,linux,windows}`),
  Linux X11 GStreamer vs Wayland `spectre permissions request`, Windows WGC prerequisites, or popup/HiDPI capture
  traps** → `references/recording.md`
- **JUnit 4/5 wrappers, parallel `@ExtendWith` parameter injection, `FailureArtifactsConfig`, `FailureVideoConfig`,
  screenshot golds (`assertMatchesGold`, `TestInfo`), or `LaunchAndAttachExtension`/`Rule`** → `references/junit.md`
- **Parallel JVMs requiring real OS `RobotDriver` input, `InputLeasePolicy`, `InputIsolationConfig`,
  `withExclusiveInput`, or `spectre input-lock` recovery** → `references/input-coordination.md`
- **IntelliJ Platform/Jewel plugin `ComposePanel` or tool-window automation (`IDE` dependency baseline,
  `executeOnPooledThread`, `synthetic(rootWindow = ideFrame)`, popup layers)** → `references/intellij.md`
- **Programmatic `AgentAttach`, `AttachedAutomator`, `AttachOptions`, `spectre-agent`/`spectre-agent-runtime`
  dependencies, or zero-dependency `inject-runtime.jar` bootstrap** → `references/agent.md`
- **Querying, diffing, or pruning `capture.json` (`schemaVersion 2`) with `jq`** → sibling skill `spectre-capture`
