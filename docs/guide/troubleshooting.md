# Troubleshooting

Common surprises and their fixes, ordered roughly by how often they bite.

## "I called a wait helper from the EDT"

```
java.lang.IllegalStateException: waitForIdle must not be called from the AWT event
dispatch thread; wrap the call with withContext(Dispatchers.Default) or similar.
```

All three wait helpers — `waitForNode`, `waitForIdle`, and `waitForVisualIdle` — refuse
to run on the AWT event dispatch thread. They snapshot semantics via
`invokeAndWait`/`readOnEdt`. Running them on the EDT would either deadlock or skip the
bounded worker that enforces the timeout, so the helpers raise
`IllegalStateException` instead. The exact wait name in the message tells you which
call to wrap.

JUnit test methods don't run on the EDT, so a plain `runBlocking { … }` body is fine
there — no `withContext` needed. The error appears when the call originates from a
coroutine on `Dispatchers.Main` or any Swing-backed dispatcher, e.g.:

```kotlin
// inside a Dispatchers.Main coroutine — wrong:
automator.waitForVisualIdle() // throws IllegalStateException

// fix: hop off the EDT first
withContext(Dispatchers.Default) {
    automator.waitForVisualIdle()
}
```

See [Synchronization](synchronization.md#the-edt-rule).

## "My selector returns null or an empty list"

In order of likelihood:

1. **The UI hasn't rendered yet.** Spectre doesn't auto-wait. Use
   [`waitForNode(...)`](synchronization.md#waitfornode) before the first read.
2. **The node is composed but offscreen.** It still appears in the semantics tree, but
   `boundsOnScreen` may be empty. Scroll to it first.
3. **You're reading after an interaction without waiting.** Add
   `waitForIdle()`/`waitForVisualIdle()` after the click or type.
4. **The selector doesn't match.** Run `println(automator.printTree())` and check the
   actual test tags/text/role. Localised text is the usual culprit.
5. **The tree is empty.** If `printTree()` returns `""`, the composition probably
   crashed before any node registered. Check the test JVM's stderr for exceptions from
   the EDT or composition thread; they do not always propagate to the test method.

## "Tests fight over OS focus when run in parallel"

Real `RobotDriver` dispatches OS-level input. Two parallel test JVMs racing for the
same screen will collide.

Use synthetic input:

```kotlin
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver

val automator = ComposeAutomator.inProcess(
    robotDriver = RobotDriver.synthetic(rootWindow = composeWindow),
)
```

Synthetic input posts AWT events directly into the target window's event queue — no
global focus, no cursor motion. The trade-off is that some interactions (system-level
shortcuts, real OS drag-and-drop) won't behave the same way. See
[Driving input](interactions.md#real-vs-synthetic-input).

## "`typeText` or `pasteText` didn't reach my field"

`typeText` dispatches key press/release pairs and avoids the clipboard. It supports
ASCII letters, digits, space, newline, and common US-keyboard punctuation. Use
`pasteText` for arbitrary Unicode or large text: it writes to the system clipboard,
dispatches the platform paste shortcut (<kbd>Cmd</kbd>+<kbd>V</kbd> on macOS,
<kbd>Ctrl</kbd>+<kbd>V</kbd> elsewhere), waits for the paste to land, and restores the
previous clipboard contents. A few failure modes follow from those contracts:

- **Nothing has focus.** `typeText` types into whatever the focused component is. With
  the real `RobotDriver()` that means the OS/AWT focus owner. With
  `RobotDriver.synthetic(rootWindow)`, Spectre can also route key events through the
  key-listening Compose Desktop host when AWT has no focus owner (for example,
  `apple.awt.UIElement=true` helper JVMs), but Compose still needs an internally
  focused text field. If your test never clicked into the field — or the click landed
  on something else, e.g., a parent that absorbed it — the input either no-ops or lands
  in the wrong place. Use `clearAndTypeText(node, …)` (which clicks first) or precede
  the call with an explicit `automator.click(field)`.
- **The field doesn't accept paste.** Some Compose components (and any read-only
  text field) ignore the system paste shortcut. Verify the field accepts pasted
  input outside the test before assuming Spectre is at fault.
- **macOS `apple.awt.UIElement=true`.** UI-element/helper mode is supported for
  `RobotDriver.synthetic(rootWindow = ...)` per-character `typeText`: Spectre bypasses
  the missing AWT focus owner and targets the key-listening Compose host under the
  root window. It can still break clipboard-backed `pasteText`: the field may be
  focused and the paste shortcut may be delivered, but Compose reads stale or empty
  clipboard contents. Disable `apple.awt.UIElement=true` for the JVM hosting the test
  window when you need `pasteText`, or use `typeText` for supported ASCII input.
- **macOS pasteboard race.** macOS's `NSPasteboard` writes are asynchronous, so
  Spectre polls the clipboard until it reads back the requested text before
  dispatching <kbd>Cmd</kbd>+<kbd>V</kbd>. If your environment has a clipboard
  manager or another process actively rewriting the clipboard, the poll can time
  out and the paste lands stale. Disable clipboard managers in the test
  environment.
- **`RobotDriver.headless()` throws on `typeText` and `pasteText`.** It throws on every
  input, clipboard, and screenshot call by design (see [Driving input](interactions.md#real-vs-synthetic-input)),
  so text entry against a headless driver surfaces an `UnsupportedOperationException`
  at the call site rather than silently dropping. Use `RobotDriver.synthetic(rootWindow)`
  or the default `RobotDriver()` for any real-input scenario.
- **Compose's paste action runs on its own dispatcher.** After the keystroke,
  Spectre pumps the EDT and sleeps briefly so the paste handler can read the
  clipboard before the previous contents are restored. If you stack many
  `typeText` calls back-to-back in a tight loop and observe truncated text, give
  the field a `waitForIdle()` between calls.

Use `pressKey(...)` for individual key events (modifier shortcuts, navigation keys,
`<kbd>Tab</kbd>`, `<kbd>Esc</kbd>`) — those go through the AWT key map, not the
clipboard, so none of the paste-specific caveats apply.

## "The JVM is headless"

Spectre input and screenshots need AWT. If the JVM is launched with
`-Djava.awt.headless=true`, real `RobotDriver()` cannot drive the window, and synthetic
input still needs a real AWT window hierarchy to dispatch into. Move live Spectre tests
to a non-headless test task (`systemProperty("java.awt.headless", "false")`). On Linux CI
under `xvfb`, also set `systemProperty("skiko.renderApi", "SOFTWARE_COMPAT")` on the
same `Test` task so Skiko does not try to create a GPU-backed OpenGL context. Use
`RobotDriver.headless()` only for read-only semantics-tree checks. See
[Running on CI](ci.md) for a complete workflow example.

## "The test hangs behind a Swing Error dialog"

A modal Swing dialog with text such as `Error: No Component provided` usually means the
UI library threw an uncaught composition exception and JBR surfaced it as a blocking
`JOptionPane`. Check stderr for the real exception and make sure required
`CompositionLocal`s are provided. For Jewel standalone windows, use Jewel's window
wrapper when your UI reads Jewel locals such as `LocalComponent`.

## "Captured screenshot pixels look slightly off"

**Rule of thumb: when you're validating colours from a `screenshot()`, always think
about the node's interaction state first.** If the node is currently focused,
hovered, or pressed, the captured pixels include whatever indication overlay the
component's theme draws on top — a translucent state layer for press / hover, a focus
halo, a ripple, etc. The overlay alpha-blends with the underlying colour, so the
bytes that come out of `screenshot()` are the blended result, not the raw painted
colour. The effect is platform-independent (same on macOS, Windows, and Linux) and
easy to mistake for a colour-space or render-pipeline bug.

The fix is to **assert against the right expected colour for the state you're
actually capturing**, not to reach for tricks that bypass the indication. If your
node is focused at capture time, your expected value is *raw colour + focus
overlay*; if it's pressed, *raw colour + press overlay*; if it's idle, the raw
colour. The indication is part of the rendered output, not noise to suppress.

In practice that means either:

- **Pin the node to a known interaction state before capture** and compute the
  expected colour for that state. If you want the raw colour, make sure the node
  isn't focused / hovered / pressed when `screenshot()` runs. If you specifically
  want to verify the focused appearance, focus the node first and compare against
  the blended expected value.
- **Compute the blended expected value at assertion time** if reproducing the
  exact theme overlay yourself. Compose Foundation's default state-layer alphas
  are part of the public theme contract; for ad-hoc baselines, capture the
  expected pixels once from a known-good run and store those as the baseline
  rather than hard-coding RGB literals derived from the unblended source colour.

## "Linux Wayland recording — `UnsupportedOperationException` from `x11grab`"

`FfmpegBackend.LinuxX11Grab` deliberately throws on Wayland sessions rather than
silently capturing black frames (Wayland's security model blocks framebuffer reads by
clients other than the compositor, so `x11grab` through XWayland would otherwise return
uniform black). The error message points you at the fix:

> ffmpeg's x11grab silently captures black frames on Wayland sessions even with
> XWayland in the loop … Use Wayland-native capture instead: construct
> `dev.sebastiano.spectre.recording.AutoRecorder` (which routes Wayland sessions
> through xdg-desktop-portal + PipeWire automatically), or instantiate
> `WaylandPortalRecorder` directly.

If you're driving `FfmpegRecorder` directly on Linux, switch to `AutoRecorder` — it
detects the session type and routes through the portal-based recorder when the
`spectre-recording-linux` helper artifact is on the runtime classpath. If you'd rather
force an Xorg session, verify with:

```shell
echo "$XDG_SESSION_TYPE"               # should be "x11"
echo "$WAYLAND_DISPLAY"                # should be empty
ls "$XDG_RUNTIME_DIR" | grep wayland   # should be empty
```

(or pick "Ubuntu on Xorg" at the GDM login screen, or run under `Xvfb`).

See [Recording limitations](../RECORDING-LIMITATIONS.md) for the full Wayland story.

## "macOS recording errors out or produces no file"

- **Screen Recording permission.** macOS gates screen capture behind an explicit
  grant. Open System Settings → Privacy & Security → Screen Recording and toggle on
  the responsible parent process — see the next bullet for what that actually means.
- **TCC attaches to the launching app, not to `java`.** macOS attributes
  Screen Recording (and Accessibility) to whichever binary opened the JVM
  process: IntelliJ IDEA when you run tests from the IDE, Terminal.app or iTerm
  when you `./gradlew test` from a shell, a third-party launcher otherwise. If
  the wrong app is granted, capture still fails. Check the entry that's actually
  ticked in the Screen Recording list; it should match the icon you launched.
- **TCC doesn't refresh live.** After granting, fully quit and relaunch the
  parent app — not just the JVM child. macOS only picks up the new entitlement on
  process start.
- **The SCK helper artifact is missing.** If `spectre-recording-macos` is not on the
  runtime classpath, the Swift helper is not present and `AutoRecorder.startWindow(...)`
  throws instead of silently switching capture modes. Add the helper artifact as
  `runtimeOnly(...)` or `testRuntimeOnly(...)`. Use `startRegion(...)` explicitly if region
  capture is an acceptable fallback for your test.
- **Operational SCK errors propagate.** Permission denied, target window not found,
  helper crashed during init — these all throw `IllegalStateException` rather than
  silently falling back, so you see the real cause.

## "macOS RobotDriver throws `IllegalStateException` about TCC"

The default `RobotDriver()` lazily probes the two macOS TCC entries `java.awt.Robot`
needs and throws on first use if either is denied:

- **Accessibility** — required for mouse and keyboard delivery. Without it,
  `Robot.mouseMove`, `Robot.mousePress`, `Robot.keyPress`, etc. return without the
  OS delivering anything. The probe runs on the first `click(...)`, `typeText(...)`,
  `pressKey(...)`, etc. and asks `System Events` (via `osascript`) for the active
  process. A "not allowed assistive access" denial throws with an actionable
  message naming the wrapping app to grant; an inconclusive probe (no `osascript`,
  AppleEvents/Automation refusal, etc.) prints a one-shot stderr warning and
  proceeds.
- **Screen Recording** — required for `Robot.createScreenCapture` to return real
  pixels. Without it the call silently returns an all-black image. The probe runs
  on the first `screenshot(...)` call: it captures a small region near the screen
  origin (which on macOS overlaps the menu bar) and treats an all-black result as
  denial. False positives are vanishingly rare in practice; if your screen really
  is fully black at the origin, switch to `RobotDriver.headless()` for tests
  or grant Screen Recording to silence the probe.

Fix: grant System Settings → Privacy & Security → Accessibility (or → Screen &
System Audio Recording) to whichever app launched the JVM — IntelliJ, Terminal,
iTerm2, Claude.app, etc. macOS attributes Robot operations to the wrapping app
that opened the JVM, **not** the JVM binary itself. Fully quit and relaunch the
wrapping app afterwards so macOS picks up the new entitlement, and `./gradlew
--stop` the Gradle daemon if you launched from a shell.

!!! note "macOS 26+ Screen Recording"
    Starting with macOS 26, toggling Screen Recording in System Settings grants
    picker-based access only. The first direct `Robot.createScreenCapture` call
    (i.e. the first `automator.screenshot(...)`) pops a second system dialog —
    "allow *App* to bypass the system private window picker" — that you must
    accept. This dialog appears once per app per boot; subsequent calls are
    silent. CI runners that require fully-headless captures should use a notarised
    wrapper with the appropriate entitlement pre-granted.

The two probes are independent: a consumer who only takes screenshots is not
punished for missing Accessibility, and vice versa. Probe results are cached
after the first call, so subsequent operations have no extra cost.

If you don't need real OS input or capture (in CI, in tests, etc.) use
`RobotDriver.headless()` — it bypasses the AWT Robot entirely and skips the TCC
probe.

For a passive, opt-in startup rollup of both entries (handy for harnesses that
want to log a banner), `MacOsRecordingPermissions.diagnose()` in `:recording`
returns a human-readable diagnostic without throwing.

## "JBR vs Temurin: which JDK should I use?"

- **Locally**: JBR 21 is the dev-loop default. JBR 25 also gets exercised via the
  IDE-hosted UI test (it's bundled with IntelliJ 2026.1).
- **On CI**: Temurin 21. `actions/setup-java` does support the `jetbrains`
  distribution if you'd rather mirror the local toolchain — the choice here is
  pragmatic, not a constraint.
- **For consumers**: any JDK 21+ works for the non-IDE modules.

The IntelliJ plugin module needs JBR specifically for its sandbox JDK; the Gradle build
configures that automatically.

## Still stuck?

- Read [Architecture](../ARCHITECTURE.md) for the module-level invariants.
- Check the [open issues](https://github.com/rock3r/spectre/issues) — the platform
  caveats are usually tracked.
- Drop a question or repro on the [issue tracker](https://github.com/rock3r/spectre/issues/new).
