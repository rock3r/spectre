# Driving input

The interaction layer of `ComposeAutomator` sits on top of `RobotDriver` and dispatches
mouse, keyboard, and clipboard input to whatever surface the target node lives in.

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

// click-clear-type in one go (uses clipboard paste under the hood for speed)
automator.clearAndTypeText(input, "replacement text")

// raw key events
automator.pressKey(KeyEvent.VK_TAB)
automator.pressKey(KeyEvent.VK_S, modifiers = InputEvent.CTRL_DOWN_MASK) // Ctrl+S

// shorthand
automator.pressEnter()
```

`pressKey`'s `modifiers` parameter takes an AWT modifier mask (`InputEvent.CTRL_DOWN_MASK`,
`InputEvent.SHIFT_DOWN_MASK`, …) — not a `KeyEvent` constant. The driver translates the
mask into the right modifier-key presses around the main `keyCode`.

`typeText` always works through the system clipboard: it stashes the previous clipboard
contents, writes the requested text, dispatches the platform paste shortcut
(<kbd>Cmd</kbd>+<kbd>V</kbd> on macOS, <kbd>Ctrl</kbd>+<kbd>V</kbd> elsewhere), waits for
the paste handler to drain, then restores the previous clipboard contents. It does not
fall back to per-key dispatch, so `typeText` works for any text the system clipboard can
carry — non-ASCII included. See [Troubleshooting](troubleshooting.md) for the
platform-specific quirks.

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
  `ComposeAutomator.inProcess()` wires up by default.
- **`RobotDriver(robot)`** — same as the no-arg form but reuses an existing
  `java.awt.Robot` you've already constructed (e.g., one targeted at a non-default
  `GraphicsDevice`).
- **`RobotDriver.synthetic(rootWindow)`** — synthetic AWT events posted straight into the
  target window's event queue. No real cursor motion, no global focus, doesn't fight with
  other processes. `synthetic` is a companion extension function in the
  `dev.sebastiano.spectre.core` package, so it needs an explicit import.
- **`RobotDriver.headless()`** — a no-op adapter for tests and headless CI. Mouse/key
  calls are silently dropped, screenshots return a 1×1 empty image, and clipboard access
  is a no-op. Note that this only stubs out input/screenshot side effects; it does not
  fake out the live `WindowTracker` and `SemanticsReader`. See
  [The automator](automator.md#what-an-automator-owns) for the full picture.

Pass a non-default driver via the `inProcess` factory:

```kotlin
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.core.synthetic

val automator = ComposeAutomator.inProcess(
    robotDriver = RobotDriver.synthetic(rootWindow = composeWindow),
)
```

Synthetic input is the right choice when you're running tests in parallel JVMs, or when
the test machine also runs unrelated UI work. Stick to real input for end-to-end smokes
where the realism of the input matters (e.g., validating that a system shortcut reaches
the app).
