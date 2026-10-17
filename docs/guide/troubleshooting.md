# Troubleshooting

Find a symptom below, then check its cause and suggested action.

## Launch-and-attach harness

### "Launched process exited with code N before attach (PROCESS_ALIVE)"

The process exited before attach. The exception includes its exit code, output file
paths, and a stderr excerpt. Check app startup for a missing main class, classpath
error, or headless AWT setting.

During a **Gradle-ish** launch, the `./gradlew` client may exit before Spectre finds
an app JVM. Wrapper downloads, the environment, or build scripts can cause this.
Spectre reports the same stage and may state that the client exited first.
Read captured stderr. Check `--app-name` after confirming that the client stays alive.

### "No attachable JVM … (JVM_ATTACHABLE)" on a Gradle launch

`./gradlew :app:run` spawns the app JVM from the **Gradle daemon**. The `gradlew`
client remains a separate process. This stage means the **client is still running** and no matching app JVM was
found in time. Pass `LaunchSpec.appJvmNameFilter` / `spectre launch --app-name
<MainClass>` so discovery can match the app among daemon children. Never kill the
Gradle daemon to "fix" teardown. The harness only tears down the discovered app JVM.

Starting a Gradle daemon or compiling the app for the first time can delay JVM discovery.
For Gradle launches with default stage timeouts, Spectre allows **120s** for
`JVM_ATTACHABLE`. Set `LaunchStageTimeouts.jvmAttachableMs` for another limit.
If discovery still times out, check captured output for compile errors.

The **Gradle-ish** warning lists daemon, sandbox, and JEP 451 limits. For controlled
builds, use a production-style launch such as `java -jar` or installDist.

### "Agent bootstrap failed (AGENT_BOOTSTRAP)"

Spectre found the target JVM. The agent failed to bind its UDS in time.
Bootstrap may have failed to find or inject `ComposeAutomator`, or the target
may have no Compose host.
On Linux and other POSIX hosts, a first `VirtualMachine.attach` can fail with
`Connection refused` when a leftover `.java_pid` socket is still on disk (pid
reuse after a force-kill). Launch-and-attach removes that orphaned endpoint and
retries inside the `AGENT_BOOTSTRAP` budget so the target can start a real
attach listener.
Add `spectre-core` to a controlled app build.
For a Compose-only target, current `spectre-agent-runtime` jars inject
`META-INF/spectre/inject-runtime.jar` automatically. Spectre adds
`-XX:+EnableDynamicAgentLoading` to direct launches. Set that flag in the build
for Gradle launches.
See [Agent attach](agent.md#injection-without-preinstalled-core).

### "Unix domain path too long" / `UdsPathTooLongException`

The socket path the agent binds must fit the operating system's `sockaddr_un.sun_path`
field: 102 usable bytes on macOS, 106 on Linux and Windows. Longer paths are refused by
the kernel.

Spectre chooses `/tmp` on Linux and macOS. On Windows it uses `%TEMP%` if the socket
path fits, then `%LOCALAPPDATA%\Temp` if needed. A build harness may set
`java.io.tmpdir` to a directory whose path exceeds the socket limit.

Custom `AttachOptions.udsPath` values can still exceed the limit. Attach now
rejects that up front with `UdsPathTooLongException`, which names the path, its size in
bytes, and the limit. Pick a shorter directory, closer to the filesystem root.

### "Attached but no window (FIRST_WINDOW)"

Attach succeeded. `windows()` stayed empty until timeout. The app may still be
showing a splash screen, or the Compose tree never registered a window. Increase
`LaunchStageTimeouts.firstWindowMs` or fix the UI startup path.

## "I called a wait helper from the EDT"

```
java.lang.IllegalStateException: waitForIdle must not be called from the AWT event
dispatch thread; wrap the call with withContext(Dispatchers.Default) or similar.
```

All five waits reject calls from the AWT event dispatch thread: `waitForNode`,
`waitUntilGone`, `waitUntil`, `waitForIdle`, and `waitForVisualIdle`.
They read semantics through `invokeAndWait` or `readOnEdt`.
An EDT call could deadlock or bypass the timeout worker, so the helper throws
`IllegalStateException` and names the offending wait.

JUnit test methods don't run on the EDT, so a `runSpectreTest { … }` body is fine (or plain `runBlocking` as a fallback)
there, with no `withContext` needed. The error appears when the call originates from a
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

Check these causes in order:

1. **The UI hasn't rendered yet.** Spectre doesn't auto-wait. Use
   [`waitForNode(...)`](synchronization.md#waitfornode) before the first read.
2. **The node is composed and offscreen.** It still appears in the semantics tree;
   `boundsOnScreen` may be empty. Scroll to it first.
3. **A read follows an interaction without waiting.** Add
   `waitForIdle()`/`waitForVisualIdle()` after the click or type.
4. **The selector doesn't match.** Run `println(automator.printTree())` and check the
   actual test tags/text/role. Localised text is the usual culprit.
5. **The tree is empty.** If `printTree()` returns `""`, the composition probably
   crashed before any node registered. Check the test JVM's stderr for exceptions from
   the EDT or composition thread; they do not always propagate to the test method.

## "Tests fight over OS focus when run in parallel"

Real `RobotDriver` sends OS input. Parallel test JVMs can interfere when they share
one desktop.

For participating Spectre processes, enable cooperative coordination with
`RobotDriver(InputLeasePolicy.Required)`, wrap a sequence in `withExclusiveInput`, or configure
the in-process JUnit wrapper with `InputIsolationConfig.perTest()`. Inspect a suspected wedge with
`spectre input-lock status --json`. Revoke only the exact observed lease ID:

```text
spectre input-lock revoke --lease <id> --reason "test JVM is unresponsive"
```

Normal revoke fences new work and waits for owner cleanup. If the owner cannot reach a checkpoint,
`--force` advances after a bounded grace period and reports `unsafeTakeover=true`. That is an
audited escape hatch. It provides no proof that an already-running native call stopped. A stale lease ID can
never revoke a newer holder.

If the coordinator process does not become ready, check that
`spectre-input-coordinator-server` and its dependencies are present in `java.class.path`. This is
especially relevant for thin `java -jar` launchers and module-path-only applications. Run the
command returned by `CoordinatorProcessLauncher.command()` directly to retain the
child JVM's standard error; automatic launches intentionally discard child output. See
["Every input verb fails and the coordinator will not start"](#coordinator-unreachable) when the
coordinator cannot be repaired and an alternative input path is needed.

Use synthetic input:

```kotlin
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver

val automator = ComposeAutomator.inProcess(
    robotDriver = RobotDriver.synthetic(rootWindow = composeWindow),
)
```

Synthetic input posts AWT events into the target window without changing global focus
or moving the cursor. System shortcuts and OS drag-and-drop need real input. See
[Driving input](interactions.md#real-vs-synthetic-input).

## "Every input verb fails and the coordinator will not start" {#coordinator-unreachable}

Symptom: semantics reads, `windows()`, `findByTestTag` and screenshots all keep working, while
verbs that touch **shared OS state for the current driver** fail with a message about the input
coordinator. On a real-OS `Required` driver that is `focusWindow`, `click`, `typeText`, and
`pasteText` together. On the default synthetic `Required` driver (attach / in-process) only
clipboard-backed `pasteText` and explicit exclusive scopes need a live coordinator. Synthetic
`focusWindow` / `click` / `typeText` do not. That split is the signature: leased shared-desktop
work fails, everything else does not.

The failure reports the coordinator state:

```text
… Spectre could not reach the desktop input coordinator (COORDINATOR_IO), and this driver runs
with InputLeasePolicy.Required, which fails rather than continuing uncoordinated. Measured: no
coordinator answered. Not measured: whether it is wedged, absent, or merely slower than its
startup budget. …
```

Use the error code to choose a repair:

| Code | What it means | First thing to try |
| --- | --- | --- |
| `COORDINATOR_PROVIDER_MISSING` | the coordinator artefact is not on the classpath | add `spectre-input-coordinator-server` to the **attacher's** runtime classpath |
| `COORDINATOR_IO` | a coordinator was launched; clients could not reach it | run `CoordinatorProcessLauncher.command()` by hand and read the child's stderr |
| `ACQUIRE_TIMEOUT` | the coordinator is fine; another owner holds the desktop | `spectre input-lock status --json`, then revoke the exact lease ID |

Do **not** answer `ACQUIRE_TIMEOUT` by turning coordination off: that failure means the lease is
doing its job. See "Tests fight over OS focus when run in parallel" above.

If the coordinator is genuinely unusable, such as a host with a broken socket path,
the attach path has one deliberate opt-out. Set it on the **attaching** JVM:

```text
-Ddev.sebastiano.spectre.agent.inputCoordination=disabled
```

Run `spectre daemon kill` before changing this mode. The daemon reads it at
startup and passes it to every injected target. A running daemon keeps its earlier
mode. Spectre rejects a mismatch during the handshake and reports the kill command.

Run `spectre daemon kill` again when finished. Removing the property does not
change a running daemon in disabled mode. Spectre refuses commands until the daemon
restarts with the default mode.

`spectre` forwards the property to a daemon it starts. For a separately started daemon,
pass it through `JAVA_TOOL_OPTIONS`. Only the exact word `disabled` opts out.
The attacher and target announce that mode on stderr. Other processes can then
drive the same desktop. Read
[When the coordinator cannot be reached](input-coordination.md#coordinator-unreachable) before
using it.

## "`typeText` or `pasteText` didn't reach my field"

`typeText` sends key press and release events without using the clipboard.
It supports ASCII letters, digits, space, newline, and common US keyboard punctuation.
For Unicode or large text, `pasteText` writes to the clipboard. It sends
<kbd>Cmd</kbd>+<kbd>V</kbd> on macOS or <kbd>Ctrl</kbd>+<kbd>V</kbd> elsewhere.
It waits for the paste, then restores the previous clipboard content.
Check these possible failures:

- **Nothing has focus.** `typeText` sends keys to the focused component.
  `RobotDriver()` uses the OS or AWT focus owner. `RobotDriver.synthetic(rootWindow)`
  can use a key-listening Compose Desktop host when AWT has no focus owner, as in
  `apple.awt.UIElement=true` helper JVMs. Compose still needs a focused text field.
  A missing click or a click absorbed by a parent can send input elsewhere.
  Use `clearAndTypeText(node, …)`, which clicks first, or call
  `automator.click(field)`. On Windows and X11, a node click that never reaches
  the target JVM throws. See
  [undelivered input](interactions.md#undelivered-input-fails-loudly-on-windows-and-x11).
- **The field doesn't accept paste.** Some Compose components (and any read-only
  text field) ignore the system paste shortcut. Verify the field accepts pasted
  input outside the test before assuming Spectre is at fault.
- **macOS `apple.awt.UIElement=true`.** With
  `RobotDriver.synthetic(rootWindow = ...)`, per-character `typeText` reaches
  the key-listening Compose host despite a missing AWT focus owner.
  Clipboard-backed `pasteText` can fail in this mode. Compose may read stale or
  empty clipboard content after the paste shortcut. For `pasteText`, disable
  `apple.awt.UIElement=true` on the test window JVM. Supported ASCII can use
  `typeText`.
- **macOS pasteboard race.** macOS's `NSPasteboard` writes are asynchronous, so
  Spectre polls the clipboard until it reads back the requested text before
  dispatching <kbd>Cmd</kbd>+<kbd>V</kbd>. If the environment has a clipboard
  manager or another process actively rewriting the clipboard, the poll can time
  out and the paste lands stale. Disable clipboard managers in the test
  environment.
- **`RobotDriver.headless()` throws on `typeText` and `pasteText`.** Every input,
  clipboard, and screenshot call throws `UnsupportedOperationException`.
  See [Driving input](interactions.md#real-vs-synthetic-input). For input, use
  `RobotDriver.synthetic(rootWindow)` or `RobotDriver()` for real OS actions.
- **Compose's paste action runs on its own dispatcher.** After the keystroke,
  Spectre pumps the EDT and sleeps briefly so the paste handler can read the
  clipboard before the previous contents are restored. If many
  `typeText` calls run back-to-back in a tight loop and text is truncated, call
  `waitForIdle()` between calls.

Use `pressKey(...)` for individual key events (modifier shortcuts, navigation keys,
`<kbd>Tab</kbd>`, `<kbd>Esc</kbd>`). They go through the AWT key map and bypass
the clipboard, so none of the paste-specific caveats apply.

## Compose Hot Reload {#compose-hot-reload}

### "`wait --reload-settled` says hotReloadUnavailable"

The attached session is **not** reload-aware: attach did not find HR orchestration properties on
the process, so no Hot Reload client was created. Common causes:

1. **The target is not running under Compose Hot Reload** — ordinary `run` / packaged launches
   are fine for Spectre; reload wait only activates when HR properties are discoverable.
2. **Port discovery found nothing** — Spectre reads `compose.reload.orchestration.port` and/or
   `compose.reload.pidFile` from the **target process’s JVM arguments**, then the pid file’s
   `orchestration.port` field. Confirm the app was started by an HR-aware run configuration that
   still exposes those properties.

If properties **are** present while the orchestration server is down or the port is stale, wait
returns **`timeout`** because the session remains reload-aware. `hotReloadUnavailable` applies
when the HR properties are absent.

See [Compose Hot Reload awareness](hot-reload.md).

### "`reloadFailed` vs `timeout`"

- **`reloadFailed`** — HR completed a `ReloadClassesResult` with `isSuccess = false` (for
  example a redefine error). Fix the reload in the app/IDE; Spectre is only reporting HR’s
  outcome.
- **`timeout`** — the settle chain (`Request` → `Result` → `UIRendered` → `Ping`/`Ack`) did not
  finish in time, **or** a reload-aware session never connected to orchestration (stale port,
  HR not listening yet). Retry with a larger `--timeout-ms`, confirm HR is healthy and still
  advertising a live port, then re-query the tree.

### "Clicks fail with nodeNotFound after a hot reload"

On reload-aware sessions, node keys from `tree` / `find` are generation-stamped and cleared
after a successful reload settle. Pre-reload keys (and guessed `g{n}:…` stamps before a fresh
tree) return `nodeNotFound`. Arm `spectre wait --reload-settled <session>` **before** the
reload, then `tree` / `find` again and use the new stamped keys. Do not reuse keys from an
older `capture.json` for clicks after settle. Capture writes raw keys for inspection.

### "Older Hot Reload builds"

Spectre tests against the Compose Hot Reload **1.2** line (pinned **1.2.0**, minimum
**1.2.0-alpha+211**). Older orchestration servers may omit message types Spectre needs; upgrade
HR. Settle wait requires an HR-aware run configuration.

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
wrapper when the UI reads Jewel locals such as `LocalComponent`.

## "Captured screenshot pixels look slightly off"

`screenshot(windowIndex)` and `screenshot(node)` use the platform window-capture backend when
`spectre-recording` is on the runtime classpath. They fail if that backend is unavailable or cannot
identify the requested window; they never substitute a screen-region crop. `capture()` uses the
same window-scoped still whenever recording is present (GitHub Actions hosted Windows is treated
as non-interactive for WGC). If the helper cannot actually run, such as Linux without
`gst-launch-1.0`, `capture()` fails. It does not substitute a Robot crop of whatever sits
under the window. Without recording (for example an inject payload that omits it) `capture()`
uses Robot region capture of the Compose surface. On Linux X11/Xvfb native stills read visible
framebuffer pixels; keep the target frontmost. Settle the UI
(`waitForIdle` / `waitForVisualIdle`) before `capture()` so the semantics snapshot and PNG stay a
usable pair across one-shot native still latency.

`waitForVisualIdle()` samples **window-scoped** pixels for tracked Compose surfaces when
`spectre-recording` is present **and the platform helper can actually run** (same native still
path as `screenshot(windowIndex)`). Class presence is not enough: on Linux the helper needs
`gst-launch-1.0` (with `ximagesrc`, `videoconvert`, and `pngenc` for X11/Xvfb stills). If
recording is on the classpath and GStreamer is missing, Spectre falls back to Robot region
capture of each surface rectangle so samples remain usable. Keep the
target frontmost and unobscured in that fallback mode (Linux X11 region and some embedded
cases still need visibility). For native window stills on Linux, install GStreamer and
include `spectre-recording` with the platform helper on the test runtime classpath.

Check the node's interaction state when comparing colours from `screenshot()`.
A focused, hovered, or pressed node can display a theme overlay, such as a
state layer, focus halo, or ripple. The overlay blends with the underlying
colour. The screenshot contains that blended result on macOS, Windows, and Linux.

Use the colour expected for the captured state. A focused node includes its
focus overlay. A pressed node includes its press overlay. An idle node shows
the raw colour.

Choose one of these approaches:

- **Pin the node to a known interaction state before capture** and compute the
  expected colour for that state. For the raw colour, ensure the node
  is unfocused, unhovered, and unpressed when `screenshot()` runs. To
  verify the focused appearance, focus the node first and compare against
  the blended expected value.
- **Compute the blended expected value at assertion time** if the test knows
  the theme overlay. Compose Foundation defines the default state-layer alphas.
  For an ad hoc baseline, save pixels from a known-good run. Raw source RGB
  values do not include the overlay.

## "Linux Wayland recording — `UnsupportedOperationException` from `x11grab`"

`FfmpegBackend.LinuxX11Grab` throws on Wayland. Wayland blocks framebuffer reads
outside the compositor, so `x11grab` through XWayland would capture black frames.
The error message gives the supported route:

> ffmpeg's x11grab silently captures black frames on Wayland sessions even with
> XWayland in the loop … Use Wayland-native capture instead: construct
> `dev.sebastiano.spectre.recording.AutoRecorder` (which routes Wayland sessions
> through xdg-desktop-portal + PipeWire automatically), or instantiate
> `WaylandPortalRecorder` directly.

When using `FfmpegRecorder` directly on Linux, select `AutoRecorder` for Wayland.
It detects the session and uses the portal when `spectre-recording-linux` is on
the runtime classpath. To use Xorg, check the session with:

```shell
echo "$XDG_SESSION_TYPE"               # should be "x11"
echo "$WAYLAND_DISPLAY"                # should be empty
ls "$XDG_RUNTIME_DIR" | grep wayland   # should be empty
```

(or pick "Ubuntu on Xorg" at the GDM login screen, or run under `Xvfb`).

See [Recording limitations](../RECORDING-LIMITATIONS.md) for Wayland capture requirements.

## "Linux Wayland keeps asking to share the screen / remote interaction"

A seated GNOME session should show **one** Share + Remember / Allow remote interaction
dialog for Spectre, then reuse the helper. If every JVM pops a new picker:

- Confirm `spectre-recording-linux` is on the runtime classpath and
  `spectre-wayland-helper` is the binary in use (`SPECTRE_WAYLAND_HELPER` only for local
  helper iteration).
- Check `$XDG_RUNTIME_DIR/spectre/wayland-session.sock` exists after the first grant. If it
  exists and every call fails immediately, delete the socket (dead helper) and retry.
- Do not point token storage at `~/.java/robot/` — Spectre stores
  `wayland-rd-restore-token-*` under `$XDG_STATE_HOME/spectre/` (or
  `SPECTRE_WAYLAND_RESTORE_TOKEN_DIR`).
- A rejected token is cleared and retried once. A second failure is fatal; delete the
  token file only when re-consent is intended.

SSH or headless sessions without a compositor seat cannot complete the dialog.
See [Linux Wayland consent](recording.md#linux-wayland-consent).

## "macOS `sandbox-exec` blocks my Compose Desktop/Spectre test"

A macOS process sandbox can block AWT, Swing, Compose Desktop, and `java.awt.Robot`
even when the JVM is not headless. If a Gradle test runs inside `sandbox-exec`,
the sandbox profile must allow the desktop Mach services documented in
[Running on CI](ci.md#macos-sandbox-exec-runners).

| Symptom                                                                      | Likely cause                                                 | What to check                                                                                                       |
|------------------------------------------------------------------------------|--------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------|
| `Connection Invalid error for service com.apple.hiservices-xpcservice`       | Missing HiServices lookup                                    | Allow `com.apple.hiservices-xpcservice`.                                                                            |
| `Frame.isVisible()`/`Frame.isShowing()` are `true`; no window appears        | Missing render/window-manager services                       | Allow `com.apple.CARenderServer` and `com.apple.windowmanager.server`; also verify `com.apple.windowserver.active`. |
| Pixel probes sample the desktop background where the test window should appear | The window exists in Java state; macOS is not painting it | Check `com.apple.CARenderServer` first, then window-manager logs.                                                   |
| `Robot.createScreenCapture(...)` hangs after the `Robot` is created          | Missing capture service or TCC grant                         | Grant Screen Recording to the launching app, restart that app, and allow `com.apple.replayd`.                       |
| `log: Cannot run while sandboxed`                                            | `/usr/bin/log` is itself blocked by `sandbox-exec`           | Use a narrow read-only macOS log diagnostic lane outside the sandbox.                                               |
| Spectre times out waiting for/capturing a window                             | Stale Gradle daemon or missing UI Mach services              | Re-run with `./gradlew --no-daemon spectreTest --tests '*SpectreSmokeTest'` and inspect sandbox denies.             |

Screen Recording belongs to the app that launched the JVM. Granting access to `java` itself
does not cover that app.
After granting permission to Terminal.app, iTerm2, IntelliJ IDEA, or another launcher,
fully quit and restart that app before retrying. TCC permission alone is not enough
inside a sandbox: `Robot` capture can still hang until `com.apple.replayd` is
allowed.

For missing-service diagnosis, inspect recent macOS logs from outside the sandbox:

```shell
/usr/bin/log show --last 3m --style compact --predicate 'process == "java" OR eventMessage CONTAINS "Sandbox:" OR eventMessage CONTAINS "ClientCallsAuxiliary" OR eventMessage CONTAINS "deny"'
/usr/bin/log show --last 2m --style compact --predicate 'processID == <PID> OR eventMessage CONTAINS "<PID>"'
```

Look for patterns such as `Sandbox: java(...) deny(1) mach-lookup`,
`Service "com.apple.CARenderServer" failed bootstrap look up`,
`WMClientWindowManager: Invalid connection`, `ScreenCaptureKit`, `ReplayKit`,
`com.apple.replayd`, and `kTCCServiceScreenCapture`.

## "macOS recording errors out or produces no file"

- **Screen Recording permission for Spectre Capture Helper.** Capture uses the
  bundled helper app (`SpectreCaptureHelper.app`, display name **Spectre Capture
  Helper**). With a human present, run:

  ```bash
  spectre permissions check
  spectre permissions request   # guided window if not granted
  ```

  The request command opens a small guide: Open Settings (Screen Recording deep
  link), drag the helper icon into the list if missing, wait until the UI shows
  **Done ✓**, then close. Capture/record paths never open this guide themselves;
  they fail fast with a structured error agents can relay.
- **Settings row should say Spectre Capture Helper.** A Terminal/IntelliJ/java row identifies
  the spawning app.
  If only the spawning app appears, upgrade Spectre and unset
  `SPECTRE_SCREENCAPTURE_HELPER` so the bundled app is used.
- **macOS may re-prompt after updates.** Run `spectre permissions request` again;
  the guide uses re-approval copy when opened after a prior grant lapsed.
- **The SCK helper artefact is missing.** If `spectre-recording-macos` is not on the
  runtime classpath, the Swift helper is not present and `AutoRecorder.startWindow(...)`
  throws and does not silently switch capture modes. Add the helper artefact as
  `runtimeOnly(...)` or `testRuntimeOnly(...)`. Use `startRegion(...)` explicitly if region
  capture is an acceptable fallback for the test.
- **Operational SCK errors propagate.** Permission denied, target window not found,
  helper crashed during init: these all throw `IllegalStateException` and
  preserve the cause without a silent fallback.

## "macOS RobotDriver throws `IllegalStateException` about TCC"

The real-OS `RobotDriver()` lazily probes the two macOS TCC entries `java.awt.Robot`
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
  pixels. Without it the call silently returns an all-black image. A locked
  screen can produce the same symptom, so the probe first checks whether macOS
  reports the console session as locked; if so, unlock the screen and retry. If
  the session is unlocked, the probe captures a small region near the screen
  origin (which on macOS overlaps the menu bar) and treats an all-black result as
  denial. False positives are rare; if the screen really is fully
  black at the origin, switch to `RobotDriver.headless()` for tests or grant
  Screen Recording to silence the probe.

Open System Settings → Privacy & Security. Grant Accessibility or Screen &
System Audio Recording to the app that launched the JVM. It may be IntelliJ,
Terminal, iTerm2, or Claude.app. macOS attributes Robot operations to that app. Fully
quit and relaunch it after granting access. If Gradle was launched from a
shell, also stop its daemon with `./gradlew --stop`.

!!! note "macOS 26+ Screen Recording"
    Starting with macOS 26, toggling Screen Recording in System Settings grants
    picker-based access only. The first direct `Robot.createScreenCapture` call
    (i.e. the first `automator.screenshot(...)`) pops a second system dialog —
    "allow *App* to bypass the system private window picker". Accept this dialog.
    It appears once per app per boot; subsequent calls are
    silent. CI runners that require fully-headless captures should use a notarised
    wrapper with the appropriate entitlement pre-granted.

The two probes are independent: a consumer who only takes screenshots is not
punished for missing Accessibility, and vice versa. Probe results are cached
after the first call, so subsequent operations have no extra cost.

When real OS input or capture is unnecessary (in CI, in tests, etc.), use
`RobotDriver.headless()`. It bypasses the AWT Robot entirely and skips the TCC
probe.

For a passive, opt-in startup rollup of both entries (handy for harnesses that
want to log a banner), `MacOsRecordingPermissions.diagnose()` in `:recording`
returns a human-readable diagnostic without throwing.

## Choosing JBR or Temurin

- **Locally**: JBR 21 is the dev-loop default. JBR 25 is exercised by the scheduled
  [runtime matrix](https://github.com/rock3r/spectre/blob/main/.github/workflows/runtime-matrix.yml)
  and by the IDE-hosted UI test (IntelliJ 2026.2 / platform 262 bundles JBR 25).
- **On CI (per-PR)**: Temurin 25 (the CLI requires JDK 25). The full JBR/Temurin × OS matrix is
  scheduled and **release-gated**, not per-PR.
- **For consumers**: any JDK **21+** works for non-IDE modules; the `spectre`
  CLI and its daemon require JDK **25+** (the packaged CLI bundles its own runtime). The codified supported
  set and what “supported” means operationally live in
  [Stability policy — JVM runtime support tiers](../STABILITY.md#jvm-runtime-support-tiers).

**Agent attach** needs a JDK with `jdk.attach` in the attacher process. The
target must allow dynamic agent loading. **IntelliJ-hosted Compose** uses the
IDE's bundled JBR. Keep a second skiko out of the plugin classloader. See the
[IntelliJ guide](intellij.md).

If an attached session can inspect semantics while real input reports that the coordinator provider
is missing, add `spectre-input-coordinator-server` to the attacher's runtime classpath and retry.
Read-only attach deliberately remains available when the experimental coordinator cannot start;
current-core real input uses `Required` and fails closed if coordination is unavailable. When the
coordinator cannot be repaired, see
["Every input verb fails and the coordinator will not start"](#coordinator-unreachable).

The sample IntelliJ plugin module configures its sandbox JDK via the IntelliJ Platform
Gradle plugin; the IDE's bundled JBR supplies that path.

## Further help

- Read [Architecture](../ARCHITECTURE.md) for the module-level invariants.
- Check the [open issues](https://github.com/rock3r/spectre/issues) — the platform
  caveats are usually tracked.
- Drop a question or repro on the [issue tracker](https://github.com/rock3r/spectre/issues/new).
