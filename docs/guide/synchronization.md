# Synchronization

Spectre reads and actions do not wait for idle automatically.
Add an explicit wait when the next test step depends on a UI change.

## Interactive-only: Compose Hot Reload settle

JUnit and `:testing` have **no** Hot Reload wait. CLI and MCP attach expose
`wait --reload-settled` and `wait_for_reload_settled` for targets running under
Compose Hot Reload. These calls fail when Hot Reload is unavailable.
See [Compose Hot Reload awareness](hot-reload.md).
Automated tests can use `waitForNode`, `waitForIdle`, and `waitForVisualIdle`.

## The EDT rule

All five wait helpers (`waitForNode`, `waitUntilGone`, `waitUntil`, `waitForIdle`,
and `waitForVisualIdle`) refuse to run on the AWT event dispatch thread:

```
waitForNode must not be called from the AWT event dispatch thread;
wrap the call with withContext(Dispatchers.Default) or similar.
```

Their loops read semantics through `invokeAndWait` and `readOnEdt`.
An EDT caller could deadlock or bypass the worker that enforces timeouts.
All five helpers reject EDT callers.

JUnit runs test methods outside the EDT. A normal test can call waits directly:

```kotlin
@Test
fun mySpec(): Unit = runSpectreTest {
    launchApp()
    automator.waitForNode(tag = "Root")
    // ...your test body
}
```

Calling any wait helper from the EDT throws `IllegalStateException`, preventing a deadlock.
Move that call into `withContext(Dispatchers.Default)`
around the offending call; see
[Troubleshooting](troubleshooting.md#i-called-a-wait-helper-from-the-edt).

## JUnit expression-body return types

Write expression-body tests as `fun mySpec(): Unit = runSpectreTest { ... }`.
JUnit 5.14 and newer reject `@Test` methods whose JVM return type is not `void`.
Kotlin infers that return type from the body's last expression.
An assertion that returns a value can make a compiled test disappear at discovery.

## `waitForNode`

Wait for a matching node:

```kotlin
val node = automator.waitForNode(
    tag = "CounterValue",
    timeout = 5.seconds,
    pollInterval = 100.milliseconds,
)
```

`waitForNode` polls the semantics tree and returns a node that meets every provided
criterion. It throws on timeout. Pass `tag`, `text`, or both.
When both are set, one node must match the tag **and** its text or editable text.

Call it after launching the UI and after interactions that add expected content,
such as a dialog or list item.

## `waitUntilGone`

Wait until a matching node disappears:

```kotlin
automator.click(dismissButton)
automator.waitUntilGone(
    tag = "popup.body",
    timeout = 5.seconds,
    pollInterval = 100.milliseconds,
)
```

Polls until **no** node in any tracked window matches every non-null supplied criterion,
then returns. As with `waitForNode`, at least one of `tag` or `text` is required, and
passing both means "no single node carrying this tag **and** this text".

`waitUntilGone` calls `refreshWindows()` before every poll.
Compose Desktop menus, combo boxes, and speed search can use separate `Window` instances.
Closing one removes its nodes from the tracked windows.
The refresh lets the next poll observe that absence.

On timeout it throws an `IllegalStateException` that names the selector, the timeout, and
what was still on screen:

```
waitUntilGone timed out after 5000ms: 1 node(s) matching tag="popup.body" still present in tracked windows
```

Use it after dismissing a popup, menu, or dialog, before asserting on whatever the
dismissal revealed, or before the next click that would otherwise land on the closing
surface.

It is reachable over every transport `waitForNode` reaches: CLI
[`wait-until-gone`](cli.md#inspection-and-waits), MCP `wait_until_gone`, and agent attach
(`AttachedAutomator.waitUntilGone`). The timeout diagnostics above cross those boundaries
intact, as do the other waits', so a remote caller still learns which selector stayed on
screen and how many nodes matched. See the [capability matrix](capability-matrix.md) for the
per-transport cell states and [agent attach](agent.md) for how the wire deadline is sized to
let those messages through.

## `waitUntil`

Use `waitUntil` for a tree condition such as a node count or a combination of states:

```kotlin
automator.waitUntil(
    description = "the lazy list has realised at least five rows",
    timeout = 5.seconds,
    pollInterval = 100.milliseconds,
) {
    allNodes().count { it.testTag?.startsWith("row.") == true } >= 5
}
```

The predicate receives an [`AutomatorTree`](automator.md#surfaces-and-the-semantics-tree)
snapshot, like `tree()` returns. Use `windows()`, `allNodes()`, and `roots()`
to express the condition. Each poll refreshes windows and reads a new tree.
The wait returns when the condition becomes true.

`description` is required and must contain text. The timeout error uses it to
describe the condition. State the expected UI state in that description.

Keep the condition quick and free of side effects.
`timeout` bounds the polling loop. An individual poll can run past the deadline.
A blocked condition can therefore exceed the timeout before the next deadline check.

```
waitUntil timed out after 5000ms: condition "the lazy list has realised at least five rows" never held in tracked windows
```

Use it for conditions that involve several nodes or the wider tree:

```kotlin
// A combination — "the popup is gone AND its trigger is back" is one barrier, not two.
automator.click(dismissButton)
automator.waitUntil(description = "the popup is dismissed and its trigger is usable") {
    val tags = allNodes().mapNotNull { it.testTag }.toSet()
    "popup.body" !in tags && "popup.toggleButton" in tags
}

// The tracked-window set itself.
automator.waitUntil(description = "the secondary window is being tracked") {
    windows().size > 1
}
```

For a single node appearing or disappearing, use `waitForNode` or `waitUntilGone`.

`waitUntil` works **in-process only** because its Kotlin predicate cannot cross the
transport boundary. CLI, MCP, and agent attach use selector waits instead.
See the [capability matrix](capability-matrix.md) for each transport's operations.

### Wait for UI conditions {#it-waits-on-the-ui-not-on-your-program}

`waitUntil` is designed for conditions on its semantics tree snapshot.
Kotlin closures can still read a service flag, file, or HTTP response. Spectre
cannot describe those external conditions accurately on timeout.

Wait for external state with `withTimeout`, a test framework polling helper, or
an [`AutomatorIdlingResource`](#idling-resources) if it should gate `waitForIdle`.
Then use `waitUntil` for the following UI condition.

## `waitForIdle`

Wait for semantics and registered resources to settle:

```kotlin
automator.waitForIdle(
    timeout = 5.seconds,
    quietPeriod = 64.milliseconds,
    pollInterval = 16.milliseconds,
)
```

`waitForIdle` returns when:

- The UI semantics fingerprint has been stable for at least `quietPeriod`.
- All registered `AutomatorIdlingResource`s report idle.
- The EDT has been drained.

The fingerprint covers tracked windows, node identities, layout bounds, role, focus,
disabled/selected flags, text, content descriptions, and editable text. For an
animation that only changes pixels (e.g., an indeterminate spinner that doesn't tick the
semantics tree), the fingerprint reports idle while the animation continues. Use
`waitForVisualIdle` for that case.

### Idling resources

Use `AutomatorIdlingResource` to teach `waitForIdle` about background work the
fingerprint can't see:

```kotlin
import dev.sebastiano.spectre.core.AutomatorIdlingResource

class NetworkIdlingResource(private val client: MyClient) : AutomatorIdlingResource {
    override val isIdleNow: Boolean
        get() = client.inflightRequests == 0

    override fun diagnosticMessage(): String? =
        "${client.inflightRequests} request(s) in flight"
}

val networkIdling = NetworkIdlingResource(client)
automator.registerIdlingResource(networkIdling)
try {
    // ...test body
} finally {
    automator.unregisterIdlingResource(networkIdling)
}
```

`waitForIdle` will keep waiting until every registered resource reports `isIdleNow ==
true` alongside its own checks. The optional `diagnosticMessage()` shows up in
`IdleTimeoutException`, so use it to describe what was still in flight when the wait
ran out of time. Register and unregister the same instance; `unregister` is
identity-based.

## `waitForVisualIdle`

Wait until tracked surface pixels stop changing:

```kotlin
automator.waitForVisualIdle(
    timeout = 5.seconds,
    stableFrames = 3,
    pollInterval = 16.milliseconds,
)
```

Hashes each tracked Compose surface independently and waits for `stableFrames`
consecutive identical hashes per surface.

Sampling follows these rules:

- **Spectre hashes each tracked surface.** It combines the resulting hashes.
  Notifications and pointer movement outside the app do not reset the stable-frame count.
- **No usable surfaces means timeout.** If Spectre tracks no surfaces or all bounds are
  empty, each poll produces a different value. The wait cannot report false stability.
- **`pollInterval` sets a minimum gap.** Each poll captures and hashes every tracked
  Compose surface. With a working `spectre-recording` helper, Spectre uses the same
  **window-scoped** native still path as `screenshot(windowIndex)`.
  True window capture can exclude occluding windows. On Linux, the helper needs
  `gst-launch-1.0`. If GStreamer is missing, Spectre uses `java.awt.Robot` region
  capture. It also uses region capture when no recording backend is present.
  A capture often takes tens or hundreds of milliseconds per surface.
  First helper startup, Wayland, large displays, and software VMs can take longer.
  The default `16.milliseconds` targets 60 Hz. Capture cost can reduce the actual poll rate.
- **Sampling has a time limit.** The first frame hash can use the wait's remaining
  timeout to cover native helper startup. Later hashes run on a worker with a 2s limit.
  A capture or hash over that limit resets the stable-frame count.
  Missing Compose surfaces have the same result. `IdleTimeoutException` reports how many
  samples could not be used because of capture limits or missing surfaces.
- **Large captures cost more.** Multiple surfaces, 4K or Retina windows, and software
  GPUs increase CPU use per poll. For slow sampling, raise `pollInterval` to
  `100.milliseconds` or `250.milliseconds`, or reduce `stableFrames` to 2.

Reach for `waitForVisualIdle` after:

- An animation that doesn't change semantics (a fade, an indeterminate spinner stopping).
- A long-running Compose operation whose completion is visible through stable pixels.
- Anything where `waitForIdle` returns too early because the semantics tree is already
  stable while the GPU is still flushing frames.

## Combining the helpers

A common pattern after a state-changing interaction:

```kotlin
automator.click(submit)
automator.waitForIdle()        // semantics + idling resources
automator.waitForVisualIdle()  // pixels actually settled too
val result = automator.findOneByTestTag("Result")
```

For slow or flaky tests, lengthen `quietPeriod` (semantics) and `stableFrames`
(visual) before adding sleeps. These settings provide an alternative to `Thread.sleep`.

## Default wait settings {#defaults-at-a-glance}

| Parameter                 | Default               |
| ------------------------- | --------------------- |
| `waitForIdle.timeout`     | 5 s                   |
| `waitForIdle.quietPeriod` | 64 ms                 |
| `waitForIdle.pollInterval`| 16 ms (~60 FPS)       |
| `waitForVisualIdle.timeout` | 5 s                |
| `waitForVisualIdle.stableFrames` | 3              |
| `waitForVisualIdle.pollInterval` | 16 ms (~60 FPS)|
| `waitForNode.timeout`     | 5 s                   |
| `waitForNode.pollInterval`| 100 ms                |
| `waitUntilGone.timeout`   | 5 s                   |
| `waitUntilGone.pollInterval` | 100 ms             |
| `waitUntil.timeout`       | 5 s                   |
| `waitUntil.pollInterval`  | 100 ms                |

Increase these values when the application needs more time.
