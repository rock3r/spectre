# Troubleshooting

Common surprises and their fixes, ordered roughly by how often they bite.

## "I called a wait helper from the EDT"

```
java.lang.IllegalStateException: waitForIdle must not be called from the AWT event
dispatch thread; wrap the call with withContext(Dispatchers.Default) or similar.
```

`waitForIdle` and `waitForVisualIdle` drain the EDT and snapshot semantics via
`invokeAndWait`. Running them on the EDT would either deadlock or skip the bounded
worker that enforces their timeout. (`waitForNode` polls through `readOnEdt` and is
exempt.) Wrap your wait calls:

```kotlin
runBlocking {
    withContext(Dispatchers.Default) {
        automator.waitForVisualIdle()
    }
}
```

JUnit test methods don't run on the EDT, but coroutines launched via
`Dispatchers.Main` or any Swing-backed dispatcher do. See
[Synchronization](synchronization.md#the-edt-rule).

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

## "Tests fight over OS focus when run in parallel"

Real `RobotDriver` dispatches OS-level input. Two parallel test JVMs racing for the
same screen will collide.

Use synthetic input:

```kotlin
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.core.synthetic

val automator = ComposeAutomator.inProcess(
    robotDriver = RobotDriver.synthetic(rootWindow = composeWindow),
)
```

Synthetic input posts AWT events directly into the target window's event queue — no
global focus, no cursor motion. The trade-off is that some interactions (system-level
shortcuts, real OS drag-and-drop) won't behave the same way. See
[Driving input](interactions.md#real-vs-synthetic-input).

## "`typeText` didn't reach my field"

`typeText` writes to the system clipboard, dispatches the platform paste shortcut
(<kbd>Cmd</kbd>+<kbd>V</kbd> on macOS, <kbd>Ctrl</kbd>+<kbd>V</kbd> elsewhere), waits
for the paste to land, and restores the previous clipboard contents. A few failure
modes follow from that:

- **Nothing has focus.** `typeText` types into whatever the focused component is. If
  your test never clicked into the field — or the click landed on something else,
  e.g. a parent that absorbed it — the paste either no-ops or lands in the wrong
  place. Use `clearAndTypeText(node, …)` (which clicks first) or precede the call
  with an explicit `automator.click(field)`.
- **The field doesn't accept paste.** Some Compose components (and any read-only
  text field) ignore the system paste shortcut. Verify the field accepts pasted
  input outside the test before assuming Spectre is at fault.
- **macOS pasteboard race.** macOS's `NSPasteboard` writes are asynchronous, so
  Spectre polls the clipboard until it reads back the requested text before
  dispatching <kbd>Cmd</kbd>+<kbd>V</kbd>. If your environment has a clipboard
  manager or another process actively rewriting the clipboard, the poll can time
  out and the paste lands stale. Disable clipboard managers in the test
  environment.
- **Headless adapters skip the readback.** `RobotDriver.headless()`'s clipboard
  adapter doesn't support read-back, so it doesn't wait and doesn't drain the EDT
  after dispatch. That's fine for headless tests that don't really care about
  paste, but means real-input scenarios should not be exercised against a headless
  driver.
- **Compose's paste action runs on its own dispatcher.** After the keystroke,
  Spectre pumps the EDT and sleeps briefly so the paste handler can read the
  clipboard before the previous contents are restored. If you stack many
  `typeText` calls back-to-back in a tight loop and observe truncated text, give
  the field a `waitForIdle()` between calls.

Use `pressKey(...)` for individual key events (modifier shortcuts, navigation keys,
`<kbd>Tab</kbd>`, `<kbd>Esc</kbd>`) — those go through the AWT key map, not the
clipboard, so none of the above applies.

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
detects the session type and routes through the portal-based recorder when the bundled
helper is wired up. If you'd rather force an Xorg session, verify with:

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
- **The SCK helper isn't bundled.** If you built `recording` on a non-macOS host and
  shipped the jar to macOS, the bundled Swift helper isn't present and `AutoRecorder`
  will fall back to `ffmpeg` region capture with a warning on stderr. To fix, build on
  macOS, or run `:recording:assembleScreenCaptureKitHelper` (optionally with
  `-PuniversalHelper`).
- **Operational SCK errors propagate.** Permission denied, target window not found,
  helper crashed during init — these all throw `IllegalStateException` rather than
  silently falling back, so you see the real cause.

## "macOS clicks and key presses silently no-op"

`java.awt.Robot`'s mouse and keyboard methods need the **Accessibility** TCC entry
on macOS, separately from Screen Recording. Without it, `automator.click(...)`,
`typeText(...)`, `pressKey(...)`, and friends return without throwing, but the OS
never delivers the events — the test silently misses every interaction.

Same parent-process attribution rules as Screen Recording apply: grant System
Settings → Privacy & Security → Accessibility to whichever app launched the JVM
(IntelliJ, Terminal, etc.), then fully quit and relaunch that app.

`MacOsRecordingPermissions.diagnose()` returns a human-readable rollup of both
entries (Screen Recording + Accessibility) you can dump at startup if your harness
wants to surface the missing-permission case explicitly rather than letting tests
fail mysteriously.

## "Gradle behaves oddly inside a worktree"

Spectre is a multi-module Gradle build with several quirks around worktrees,
configuration cache, and the IntelliJ plugin sandbox. The repo has a dedicated
write-up: [Worktree + Gradle pitfalls](../WORKTREE-GRADLE-PITFALLS.md).

## "JBR vs Temurin: which JDK should I use?"

- **Locally**: JBR 21 is the dev-loop default. JBR 25 also gets exercised via the
  IDE-hosted UI test (it's bundled with IntelliJ 2026.1).
- **On CI**: Temurin 21, because GitHub `setup-java`'s JBR 21 entry is missing.
- **For consumers**: any JDK 21+ works for the non-IDE modules.

The IntelliJ plugin module needs JBR specifically for its sandbox JDK; the Gradle build
configures that automatically.

## "Linux: `:sample-desktop:validationTestPopupLayers` skipped on Wayland"

The `OnWindow` popup layer hits a JBR/skiko native crash on Wayland that's tracked
separately as [issue #56](https://github.com/rock3r/spectre/issues/56). The validation
test `assumeFalse`-skips it on Wayland; `OnSameCanvas` and `OnComponent` continue to
exercise on both Xorg and Wayland.

## "My platform isn't covered"

The README is upfront about the validation footprint:

- macOS, Windows, and Linux are exercised in CI.
- Linux validation: Xorg on Ubuntu 22.04, Wayland (GNOME/mutter) on Ubuntu 22.04 and
  24.04. Other distros, compositors (KDE/Plasma, sway, wlroots), and window managers
  aren't covered.

If something is broken on a configuration not covered, [open an issue](https://github.com/rock3r/spectre/issues/new)
with the distro/compositor/session combo. Reports and PRs widening the matrix are
explicitly welcome.

## Still stuck?

- Read [Architecture](../ARCHITECTURE.md) for the module-level invariants.
- Check the [open issues](https://github.com/rock3r/spectre/issues) — the platform
  caveats are usually tracked.
- Drop a question or repro on the [issue tracker](https://github.com/rock3r/spectre/issues/new).
