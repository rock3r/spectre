# Troubleshooting

Common surprises and their fixes, ordered roughly by how often they bite.

## "I called a wait helper from the EDT"

```
java.lang.IllegalStateException: waitForIdle must not be called from the AWT event
dispatch thread; wrap the call with withContext(Dispatchers.Default) or similar.
```

The wait helpers drain the EDT and snapshot semantics via `invokeAndWait`. Running them
on the EDT would either deadlock or skip the bounded worker that enforces their
timeout. Wrap your wait calls:

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

## "My selector returns null / an empty list"

In order of likelihood:

1. **The UI hasn't rendered yet.** Spectre doesn't auto-wait. Use
   [`waitForNode(...)`](synchronization.md#waitfornode) before the first read.
2. **The node is composed but offscreen.** It still appears in the semantics tree, but
   `boundsOnScreen` may be empty. Scroll to it first.
3. **You're reading after an interaction without waiting.** Add
   `waitForIdle()` / `waitForVisualIdle()` after the click / type.
4. **The selector doesn't match.** Run `println(automator.printTree())` and check the
   actual test tags / text / role. Localised text is the usual culprit.

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

## "Recording produces black frames on Linux"

You're on Wayland and the recorder fell back to `x11grab` through XWayland. `x11grab`
succeeds without erroring under XWayland but produces uniform-black frames — Wayland's
security model blocks framebuffer reads by clients other than the compositor.

`AutoRecorder` handles Wayland sessions explicitly via the portal-based recorder; if
you're driving `FfmpegRecorder` directly, switch to `AutoRecorder`. If you've forced
the X11 path, verify the session type:

```shell
echo "$XDG_SESSION_TYPE"          # should be "x11"
echo "$WAYLAND_DISPLAY"           # should be empty
ls "$XDG_RUNTIME_DIR" | grep wayland   # should be empty
```

See [Recording limitations](../RECORDING-LIMITATIONS.md) for the full Wayland story.

## "macOS recording errors out / produces no file"

- **Screen Recording permission.** macOS gates screen capture behind an explicit grant
  per binary. Grant it to the JVM that runs your tests (System Settings → Privacy &
  Security → Screen Recording). After granting, restart the JVM; macOS doesn't pick up
  the new permission for already-running processes.
- **The SCK helper isn't bundled.** If you built `recording` on a non-macOS host and
  shipped the jar to macOS, the bundled Swift helper isn't present and `AutoRecorder`
  will fall back to `ffmpeg` region capture with a warning on stderr. To fix, build on
  macOS, or run `:recording:assembleScreenCaptureKitHelper` (optionally with
  `-PuniversalHelper`).
- **Operational SCK errors propagate.** Permission denied, target window not found,
  helper crashed during init — these all throw `IllegalStateException` rather than
  silently falling back, so you see the real cause.

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
- Linux validation runs on a single Ubuntu 22.04 VM exercising one Xorg session and one
  GNOME / mutter Wayland session. Other distros, compositors (KDE / Plasma, sway,
  wlroots), and window managers aren't covered.

If something is broken on a configuration not covered, [open an issue](https://github.com/rock3r/spectre/issues/new)
with the distro / compositor / session combo. Reports and PRs widening the matrix are
explicitly welcome.

## Still stuck?

- Read [Architecture](../ARCHITECTURE.md) for the module-level invariants.
- Check the [open issues](https://github.com/rock3r/spectre/issues) — the platform
  caveats are usually tracked.
- Drop a question or repro on the [issue tracker](https://github.com/rock3r/spectre/issues/new).
