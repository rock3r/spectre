# Driving input

The interaction layer of `ComposeAutomator` sits on top of `RobotDriver` and dispatches
mouse, keyboard, and clipboard input to whatever surface the target node lives in.

All interaction methods (`click`, `doubleClick`, `longClick`, `swipe`, `scrollWheel`,
`typeText`, `clearAndTypeText`, `pressKey`, `pressEnter`) are `suspend` — call them
from a coroutine. Real `java.awt.Robot` work runs inline when the caller is already
off the AWT event dispatch thread, and hops to `Dispatchers.IO` only when needed to
keep EDT callers from blocking the UI. Internal sleeps use `delay` rather than
`Thread.sleep`, so a cancelled coroutine cancels mid-`longClick` / mid-`swipe` rather
than parking the worker thread until the hold completes.

`screenshot` stays sync — it's a single framebuffer read, no blocking I/O to bury
behind a coroutine boundary.

The snippets below are written as if they sit inside a suspend block (e.g. a JUnit
test wrapped in `runBlocking { … }`).

## Mouse: clicks and drags

```kotlin
val send = automator.findOneByTestTag("Send") ?: error("button missing")
automator.click(send)
automator.doubleClick(send)
automator.longClick(send, holdFor = 600.milliseconds)
```

All click helpers resolve the node's `centerOnScreen` and dispatch through `RobotDriver`,
which compensates for HiDPI/display scaling.

## Mouse: swipes and scrolling

```kotlin
val list = automator.findOneByTestTag("MessageList") ?: error("list missing")
val first = automator.findOneByText("First message") ?: error("first row missing")
val last  = automator.findOneByText("Last message")  ?: error("last row missing")

// node-to-node drag
automator.swipe(from = first, to = last)

// raw coordinates (HiDPI-corrected)
automator.swipe(
    startX = 100, startY = 400,
    endX = 100, endY = 100,
    steps = 16,
    duration = 200.milliseconds,
)

// mouse-wheel scrolling — drives Modifier.scrollable / LazyColumn on desktop
automator.scrollWheel(list, wheelClicks = 5)   // scroll down
automator.scrollWheel(list, wheelClicks = -5)  // scroll up
```

`scrollWheel` is the right helper for desktop scrollable containers — they respond to
wheel events rather than touch-style drags.

## Keyboard: typing and key events

```kotlin
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

val input = automator.findOneByTestTag("MessageInput") ?: error("input missing")

// click-then-type
automator.click(input)
automator.typeText("Hello, Spectre!")

// click-clear-type in one go (uses key events, not the clipboard)
automator.clearAndTypeText(input, "replacement text")

// clipboard paste for large or Unicode text
automator.pasteText("こんにちは, Spectre!")

// raw key events
automator.pressKey(KeyEvent.VK_TAB)
automator.pressKey(KeyEvent.VK_S, modifiers = InputEvent.CTRL_DOWN_MASK) // Ctrl+S

// shorthand
automator.pressEnter()
```

`pressKey`'s `modifiers` parameter takes an AWT modifier mask (`InputEvent.CTRL_DOWN_MASK`,
`InputEvent.SHIFT_DOWN_MASK`, …) — not a `KeyEvent` constant. The driver translates the
mask into the right modifier-key presses around the main `keyCode`.

`typeText` dispatches key press/release pairs and does not touch the clipboard. It is
intentionally conservative: ASCII letters, digits, space, newline, and common
US-keyboard punctuation. Use `pasteText` for large strings or arbitrary Unicode; it
stashes the previous clipboard contents, writes the requested text, dispatches the
platform paste shortcut (<kbd>Cmd</kbd>+<kbd>V</kbd> on macOS,
<kbd>Ctrl</kbd>+<kbd>V</kbd> elsewhere), waits for the paste handler to drain, then
restores the previous clipboard contents. See [Troubleshooting](troubleshooting.md) for
macOS clipboard and `apple.awt.UIElement=true` caveats.

## Screenshots

```kotlin
import java.awt.Rectangle
import java.io.File
import javax.imageio.ImageIO

// whole virtual screen
val full = automator.screenshot()
ImageIO.write(full, "png", File("screenshot.png"))

// a single window's Compose surface
val mainWindow = automator.screenshot(windowIndex = 0)
ImageIO.write(mainWindow, "png", File("main.png"))

// a single node
val send = automator.findOneByTestTag("Send") ?: error("button missing")
val sendShot = automator.screenshot(send)

// arbitrary screen region
val region = automator.screenshot(Rectangle(0, 0, 800, 600))
```

Returns a `BufferedImage` you can save, hash, or compare against a baseline.

!!! note "Captures are normalised to sRGB"
    The returned `BufferedImage` is always sRGB (`TYPE_INT_ARGB` with an sRGB
    `ColorModel`), regardless of the source display's colour profile. Capturing on a
    wide-gamut display (Display P3 on a modern Mac, Adobe RGB, etc.) goes through
    the OS's display pipeline and lands in the buffer as sRGB pixels. This keeps
    captures portable — a baseline collected on one machine compares meaningfully
    against a capture from another — but it means the captured pixel values are
    post-display-pipeline, not the raw `Color(...)` your Compose code passed.
    Plan for ±1–2 per-channel rounding noise from the gamma round-trip when you
    assert on colour, and use a tolerant comparator (see below).

!!! warning "Bitmap comparison needs tolerance"
    Don't compare screenshots byte-for-byte against a baseline. Identical-looking
    frames routinely differ at the pixel level because of:

    - Encoder/decoder round-trips (PNG re-saves can shift LSBs).
    - Text rendering: subpixel positioning, hinting, font fallback, font version.
    - Antialiasing on edges, gradients, and blurs.
    - OS- and GPU-driven differences in compositing, gamma, and colour profiles.
    - HiDPI scaling at non-integer factors.

    Always compare with a tolerance — perceptual diff (e.g., a small ΔE threshold),
    a per-channel allowance, or a structural metric like SSIM. Region-mask the
    parts of the UI that are inherently noisy (timestamps, cursors, animations).

    Spectre intentionally doesn't ship a screenshot comparison suite — it returns
    `BufferedImage` and lets you wire whatever comparator fits your stack. If
    there's demand, a built-in tolerant comparator could land later; open an
    issue describing the use case if you'd find it valuable.

For test output that records continuous video rather than per-step images, see
[Recording](recording.md).

## Real vs. synthetic input

The `RobotDriver` your automator wraps governs how input is actually dispatched. The
public surface:

- **`RobotDriver()`** — the default. Uses a fresh `java.awt.Robot` plus the system
  clipboard. Moves the real cursor, takes system-wide keyboard focus, and is visible to
  other applications. This is what end users experience and what
  `ComposeAutomator.inProcess()` wires up by default. On macOS, the first input or
  screenshot call lazily probes TCC permissions (Accessibility for input, Screen
  Recording for capture) and throws `IllegalStateException` with remediation guidance
  when either is denied — see [Troubleshooting](troubleshooting.md#macos-robotdriver-throws-illegalstateexception-about-tcc).
- **`RobotDriver(robot)`** — same as the no-arg form but reuses an existing
  `java.awt.Robot` you've already constructed (e.g., one targeted at a non-default
  `GraphicsDevice`).
- **`RobotDriver.synthetic(rootWindow)`** — synthetic AWT events posted straight into the
  target window's AWT hierarchy. No real cursor motion, no global focus, doesn't fight
  with other processes. Mouse and wheel events hit-test against `rootWindow`, its owned
  windows, and other visible top-level windows. Key events go to the current AWT focus
  owner when one exists; when AWT has no focus owner (for example, a macOS helper JVM
  launched with `apple.awt.UIElement=true`), Spectre falls back to the key-listening AWT
  descendant under the last pointer target or Compose host. That lets Compose Desktop's
  internal focus model route `typeText` into focused `TextField`s even when the host
  window is not the OS-foreground app.
  `screenshot()` under a synthetic driver also bypasses the OS framebuffer — it renders
  the target window via `Component.paint(Graphics)` into a `BufferedImage` instead of
  calling `Robot.createScreenCapture`. Results are consistent for regression tests, but
  will differ from what the user sees on wide-gamut displays, and skip TCC probing entirely.
- **`RobotDriver.headless()`** — for read-only flows in headless CI where real OS I/O is
  unavailable. Every input, clipboard, and screenshot call throws
  `UnsupportedOperationException` so an accidental `automator.click(...)` /
  `typeText(...)` / `screenshot(...)` surfaces at the call site instead of silently
  dropping. Semantics-tree reads still work — pair this with
  `ComposeAutomator.performSemanticsClick(node)` if you need to fire clicks without
  going through the OS. See
  [The automator](automator.md#what-an-automator-owns) for the full picture.

Pass a non-default driver via the `inProcess` factory:

```kotlin
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
val automator = ComposeAutomator.inProcess(
    robotDriver = RobotDriver.synthetic(rootWindow = composeWindow),
)
```

Synthetic input is the right choice when you're running tests in parallel JVMs, when the
test machine also runs unrelated UI work, or when a macOS test helper runs with
`apple.awt.UIElement=true` to avoid a Dock icon. That macOS mode is safe for
per-character `typeText` with `RobotDriver.synthetic(rootWindow = ...)`, but
clipboard-backed `pasteText` still requires a foreground-capable app
(`apple.awt.UIElement=false`). Stick to real input for end-to-end smokes where the realism
of the input matters (e.g., validating that a system shortcut reaches the app). See
[Running on CI](ci.md#macos-helper-jvms) for the macOS CI trade-off.
