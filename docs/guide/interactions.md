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
which compensates for HiDPI / display scaling.

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
val input = automator.findOneByTestTag("MessageInput") ?: error("input missing")

// click-then-type
automator.click(input)
automator.typeText("Hello, Spectre!")

// click-clear-type in one go (uses clipboard paste under the hood for speed)
automator.clearAndTypeText(input, "replacement text")

// raw key events
import java.awt.event.KeyEvent
automator.pressKey(KeyEvent.VK_TAB)
automator.pressKey(KeyEvent.VK_S, modifiers = KeyEvent.CTRL_DOWN_MASK) // Ctrl+S

// shorthand
automator.pressEnter()
```

`typeText` falls back through several strategies depending on the platform — it can hit
the system clipboard for non-ASCII content, and uses the AWT key map for plain keys. See
[Troubleshooting](troubleshooting.md) for the platform-specific quirks.

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

Returns a `BufferedImage` you can save, hash, or compare against a baseline. For test
output that records continuous video rather than per-step images, see
[Recording](recording.md).

## Real vs. synthetic input

The `RobotDriver` factory governs how input is actually dispatched:

- **`RobotDriver()`** — real OS-level input via `java.awt.Robot`. The mouse cursor moves,
  the keyboard focus is system-wide, and other applications see the input too. This is
  the default and what end users experience.
- **`RobotDriver.synthetic(window)`** — synthetic AWT events posted straight into the
  target window's event queue. No real cursor motion, no global focus, doesn't fight
  with other processes.

Pass a non-default driver via the `inProcess` factory:

```kotlin
val automator = ComposeAutomator.inProcess(
    robotDriver = RobotDriver.synthetic(window = composeWindow),
)
```

Synthetic input is the right choice when you're running tests in parallel JVMs, or when
the test machine also runs unrelated UI work. Stick to real input for end-to-end smokes
where the realism of the input matters (e.g. validating that a system shortcut reaches
the app).

## Beyond the high-level helpers

Anything not exposed via `ComposeAutomator` interactions can be done directly against
the wrapped `RobotDriver`. The driver exposes lower-level helpers for raw mouse moves,
modifier-only key state, swipe interpolation parameters, and clipboard control. See the
[`RobotDriver` source](https://github.com/rock3r/spectre/blob/main/core/src/main/kotlin/dev/sebastiano/spectre/core/RobotDriver.kt)
for the current surface.
