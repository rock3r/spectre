# Traps and gotchas

<!-- aliases: gotcha, pitfall, caveat, sharp edge, known issue, common mistake -->

These are the mistakes that most often cost agents and developers time with Spectre.
Each trap has its own heading, so `spectre guide --search "<symptom>"` can find it.
The [quick reference](#quick-reference) table at the end lists one row per symptom.

## CLI and attach

### Attach needs no build changes in the target

<!-- aliases: zero dependency, inject, classpath, gradle dependency, list jvms, pid, attach workflow -->

`spectre attach <pid>` loads Spectre into a running JVM. The target needs no Gradle,
classpath, or source change. When the target has no `spectre-core`, Spectre injects the
`inject-runtime.jar` payload from the agent runtime and loads core from it. This works for
Compose Desktop, Swing-hosted Compose, and IntelliJ or Jewel hosts.

- The target must run on **JDK 21+** under the same OS user as the CLI.
- Start the target with `-XX:+EnableDynamicAgentLoading`; otherwise the JVM prints a
  warning on attach, and a future JDK will refuse it.
- Inject is an experimental inspection path. Add `spectre-core` to the target for
  frequent attach loops, such as CI.

The CLI workflow is:

```shell
spectre ps --json                               # find the target pid
SESSION=$(spectre attach <pid> --json | jq -r .sessionId)
spectre windows "$SESSION"
spectre tree "$SESSION" --json
spectre find "$SESSION" <test-tag> --json       # node keys come from tree/find
spectre click "$SESSION" <node-key>
spectre type "$SESSION" "text"
spectre press-key "$SESSION" Enter              # or Ctrl+S, or an AWT code
spectre capture "$SESSION" --json
spectre record start "$SESSION" --window 0 --output ./run.mp4
spectre record stop "$SESSION"
spectre detach "$SESSION"
```

See [Agent attach](agent.md#injection-without-preinstalled-core) and [CLI](cli.md).

### Session IDs and node keys do not persist

<!-- aliases: session not found, node not found, stale key, current session, nodeNotFound -->

The CLI has no current session. Store the ID that `attach` prints and pass it to every
command. Node keys belong to one session and one tree snapshot. After navigation,
recomposition, Compose Hot Reload, or a new attach, run `tree` or `find` again and use the
new keys.

### A daemon restart drops every session

<!-- aliases: session not found after attach, daemon killed, agent runner, process group, pgid, setsid, did not bind UDS path, re-attach -->

The first CLI command starts a per-user daemon that holds the sessions. On Linux and
macOS the daemon starts in a session of its own, through `setsid` or `perl`, so tool
runners and CI steps that kill a finished command's process group leave it running. A
host without `/usr/bin/setsid`, `/bin/setsid`, or `/usr/bin/perl` starts it in the
caller's process group instead.

When the daemon stops anyway (`spectre daemon kill`, a crash, or a killed process tree),
its sessions go with it. The next command starts a fresh daemon and fails with
`session not found`. Run `spectre attach <pid>` again: the agent in the target releases
the session the old daemon left behind and binds the new one. A target first attached
by an older Spectre release keeps the old agent, which ignores the second attach and
fails with `Agent runtime did not bind UDS path … within 5000 ms`. Restart that target.

### `spectre type` types into the focused node

<!-- aliases: typing, keyboard, text field, textfield, input, no focused node, type text -->

`spectre type <session-id> <text>` sends key events to the Compose node that has focus.
It does not pick a field for you. Focus the field first:

```shell
KEY=$(spectre find "$SESSION" name-field --json | jq -r '.nodes[0].key')
spectre click "$SESSION" "$KEY"
spectre type "$SESSION" "Ada Lovelace"
```

`click` takes a node key from `tree`, `find`, `find-text`, or `wait-for-node`, not a test
tag. Without a focused node, `type` fails with
`Refusing typeText because no focused Spectre node was found`.

### Keyboard input needs OS keyboard focus

<!-- aliases: focus gate, focus, foreground, refusing typeText, refusing pressKey, keyboard focus, press-key, UIElement -->

On the attach path, `type` and `press-key` refuse to send keys when the target JVM does
not own OS keyboard focus. The error reads
`Refusing typeText because the target JVM does not currently own OS keyboard focus`
(or `Refusing pressKey …`). A Spectre click can focus a Compose node while the terminal
or IDE that runs the CLI stays the foreground app.

Bring the target window to the front before typing, for example by clicking its title
bar. The CLI has no focus command; the agent API offers `AttachedAutomator.focusWindow`.
A macOS app started with `-Dapple.awt.UIElement=true` never becomes the foreground app,
so keyboard commands over attach cannot reach it.

### `screenshot` needs `--fullscreen`; use `capture` for one window

<!-- aliases: screenshot, png, window capture, capture, image, fails closed, still -->

On the attach path, `spectre screenshot` without `--fullscreen` fails on purpose: Spectre
cannot prove that a window-sized crop shows only the target. Choose the command by what
you need:

| Need | Command |
| --- | --- |
| One window, with its semantics tree | `spectre capture <session-id> [--window <index>] [--out-dir <dir>] [--json]` |
| The whole desktop | `spectre screenshot <session-id> --fullscreen [--output <file.png>]` |
| Video of one window | `spectre record start <session-id> --window <index>` |

`capture` writes `screenshot.png` and `capture.json` and prints their paths. See
[CLI — Capture and recording](cli.md#capture-and-recording) and [Atomic capture](capture.md).

### `capture` can include overlapping windows

<!-- aliases: capture shows other windows, privacy, robot crop, region fallback, occluded, wrong pixels, capture fallback -->

`capture` uses a window-scoped native still when the target JVM has `spectre-recording`
on its classpath. Without it, which is always the case for an injected attach, it crops
the screen at the window bounds. Any window on top of the target then appears in the PNG.
Keep the target visible and in front while capturing, or add `spectre-recording` to the
target.

### MCP needs a clean standard output

<!-- aliases: mcp, stdio, banner, json-rpc, claude code -->

`spectre mcp` speaks MCP over standard input and output. Do not wrap it in a script
that prints anything to standard output. See [CLI — MCP](cli.md#mcp).

## UI state and geometry

### Queries do not wait

<!-- aliases: null, empty, not found, flaky, race, auto wait, find returns nothing -->

`find`, `find-text`, `tree`, and the `findBy…` functions read the UI once. After an
action, wait for the expected state with `wait-for-node`, `wait-until-gone`,
`wait-for-visual-idle`, or their library equivalents. See
[Synchronization](synchronization.md).

### Off-screen children of an eager scrolling column

<!-- aliases: scroll, verticalScroll, offscreen, 0x0, 1x1, empty bounds, viewport, click misses, click lands on edge, LazyColumn -->

A `LazyColumn` leaves off-screen items out of the semantics tree. An eager
`Column(Modifier.verticalScroll(…))` keeps them, but clips their bounds to the scroll
viewport:

- `boundsInWindow` is `0×0`.
- `boundsOnScreen` (`bounds` in `spectre tree --json`) is `1×1`, at the edge of the
  scroll container.

A click on such a node lands on the edge of the viewport. It does not scroll to the node.
Check the size first, scroll, then read the node again:

```kotlin
import dev.sebastiano.spectre.core.ComposeAutomator

suspend fun clickAfterScrolling(automator: ComposeAutomator, tag: String, listTag: String) {
    val list = automator.findOneByTestTag(listTag) ?: error("No node tagged $listTag")
    repeat(20) {
        val target = automator.findOneByTestTag(tag) ?: error("No node tagged $tag")
        if (target.boundsInWindow.width > 0f && target.boundsInWindow.height > 0f) {
            automator.click(target)
            return
        }
        automator.scrollWheel(list, wheelClicks = 3)
        automator.waitForVisualIdle()
    }
    error("$tag stayed outside the scroll viewport")
}
```

From the CLI, run `spectre scroll-wheel <session-id> <container-key> <wheel-clicks>`, then
`tree` again until the node's `bounds` are larger than `1×1`.

### Tests start before the window is ready

<!-- aliases: startup, window not showing, layout, blank capture, first frame, window size zero -->

A test harness that opens a window and immediately queries or captures can see an empty
tree or a zero-sized window. Wait until the window is showing **and** laid out before the
first query or capture: `window.isShowing && window.width > 0 && window.height > 0`. Then
use `waitForNode` for the first node the test needs.

### Window teardown is asynchronous

<!-- aliases: exitApplication, teardown, AfterEach, previous test window, leaked window, sequential tests, same fork, isDisplayable -->

`ApplicationScope.exitApplication()` returns before the window is disposed. When several
Compose Desktop or Jewel tests run one after another in the same JVM fork, the previous
test's window can stay displayable for a few frames into the next test, and Spectre can
track it. Wait for `!window.isDisplayable` in `@AfterEach` or `finally` before the test
returns:

```kotlin
import java.awt.Window
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

suspend fun awaitWindowReady(window: Window, timeout: Duration = 5.seconds) {
    withTimeout(timeout) {
        while (!(window.isShowing && window.width > 0 && window.height > 0)) delay(16)
    }
}

suspend fun awaitWindowDisposed(window: Window, timeout: Duration = 5.seconds) {
    withTimeout(timeout) {
        while (window.isDisplayable) delay(16)
    }
}
```

### Wait helpers reject the EDT

<!-- aliases: deadlock, event dispatch thread, edt, swing thread, IllegalStateException -->

All five waits (`waitForNode`, `waitUntilGone`, `waitUntil`, `waitForIdle`, and
`waitForVisualIdle`) throw when called on the AWT event dispatch thread. Move the call
with `withContext(Dispatchers.Default) { … }`. See [Synchronization](synchronization.md).

### `runTest` skips delays

<!-- aliases: runTest, longClick instant, swipe instant, virtual time, kotlinx-coroutines-test -->

`runTest` from `kotlinx-coroutines-test` skips `delay()`, so long clicks, swipes, and
paste polling finish instantly and miss. Use `runSpectreTest { … }`, and declare
expression-body tests as `fun mySpec(): Unit = runSpectreTest { … }`.

## CI and headless runs

### `xvfb-run` hangs after the tests finish

<!-- aliases: hang, stuck, never exits, xvfb, linux ci, headless, gradle daemon, no-daemon, file descriptor, pipe -->

On headless Linux, `xvfb-run -a ./gradlew …` can block after the test task finishes.
When the build starts a new Gradle daemon, the daemon inherits the standard output and
error pipes that `xvfb-run` opened. `xvfb-run` waits for those pipes to close, and the
daemon keeps them open. Pass `--no-daemon` (or redirect the descriptors) when you wrap
Gradle in `xvfb-run`:

```shell
xvfb-run -a ./gradlew --no-daemon test
```

See [Running on CI](ci.md).

### `xvfb-run` proves X11 only

<!-- aliases: wayland, pipewire, portal, x11 -->

Tests under Xvfb use the X11 paths. Validate Wayland portal capture and input on a real
Wayland session. See [Recording](recording.md).

### macOS screenshots are black

<!-- aliases: black image, locked screen, tcc, screen recording permission -->

A locked macOS console returns black pixels even with Screen Recording permission.
Unlock the session and retry. See [Troubleshooting](troubleshooting.md).

## Quick reference

| Symptom | Cause | Fix |
| --- | --- | --- |
| `xvfb-run` never exits after the tests pass | A new Gradle daemon holds the `xvfb-run` pipes open | Add `--no-daemon` to `./gradlew` |
| `session not found` after a successful attach | The daemon stopped and took its sessions with it | Run `spectre attach <pid>` again |
| Re-attach fails with `did not bind UDS path` | An agent from an older Spectre release ignores a second attach | Restart the target JVM |
| `Refusing typeText because no focused Spectre node was found` | `type` needs a focused node | `spectre click` the field first |
| `Refusing typeText`/`pressKey` because the JVM lacks OS keyboard focus | Keyboard input over attach needs the foreground window | Bring the target window to the front |
| `screenshot` fails without `--fullscreen` | Window screenshots fail closed on attach | Use `spectre capture` for one window |
| `capture` PNG shows another app's window | No `spectre-recording` in the target; the capture crops the screen | Keep the target in front, or add `spectre-recording` |
| Click lands on the edge of a scrolling column | Eager `verticalScroll` children have `0×0` window bounds off-screen | Scroll first, then read the node again |
| `find` or `tree` misses a node that appears later | Queries read once | Use `wait-for-node` or `waitForNode` |
| A previous test's window shows up in the next test | `exitApplication()` is asynchronous | Wait for `!window.isDisplayable` in teardown |
| Old node keys fail with `nodeNotFound` | Keys belong to one session and one tree | Run `tree` or `find` again |
| A wait deadlocks or throws on the EDT | Waits reject the event dispatch thread | Use `withContext(Dispatchers.Default)` |
