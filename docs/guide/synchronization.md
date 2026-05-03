# Synchronization

Spectre deliberately doesn't wrap reads and actions in an implicit idle barrier. The
flip side is that you have a small but explicit set of wait helpers, and you decide
where to put them. This page covers all three.

## The EDT rule

`waitForIdle` and `waitForVisualIdle` refuse to run on the AWT event dispatch thread:

```
waitForIdle must not be called from the AWT event dispatch thread;
wrap the call with withContext(Dispatchers.Default) or similar.
```

This is enforced because their wait loops drain the EDT and snapshot semantics via
`invokeAndWait` — running on the EDT would either deadlock or skip the bounded worker
that enforces the timeout. `waitForNode` is exempt: it polls via `readOnEdt`, so it's
safe to call from anywhere a coroutine can suspend.

The standard pattern in tests:

```kotlin
@Test
fun mySpec() = runBlocking {
    launchApp()
    withContext(Dispatchers.Default) {
        automator.waitForNode(tag = "Root")
        // ...your test body
    }
}
```

If you call `waitForIdle`/`waitForVisualIdle` from the EDT you'll get a clear
`IllegalStateException` rather than a deadlock.

## `waitForNode`

The "the UI is on screen" barrier:

```kotlin
val node = automator.waitForNode(
    tag = "CounterValue",
    timeout = 5.seconds,
    pollInterval = 100.milliseconds,
)
```

Polls the semantics tree until a node matches every non-null criterion you pass, then
returns it; throws on timeout. You must pass at least one of `tag` or `text`. If you
pass both, the helper waits for a node whose test tag matches **and** whose text (or
editable text) matches — `tag` and `text` together describe a single node, not a choice
between two.

Use it once after launching the UI to know your test can start touching things, and
optionally after each interaction that introduces new content (a dialog opening, a list
item appearing).

## `waitForIdle`

The "everything has settled" barrier:

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
disabled/selected flags, text, content descriptions, and editable text. If your
animation only changes pixels (e.g., an indeterminate spinner that doesn't tick the
semantics tree), the fingerprint will report idle even while the spinner spins — that's
where `waitForVisualIdle` comes in.

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
ran out of time. Register and unregister the same instance — `unregister` is identity-
based.

## `waitForVisualIdle`

The "the pixels have stopped changing" barrier:

```kotlin
automator.waitForVisualIdle(
    timeout = 5.seconds,
    stableFrames = 3,
    pollInterval = 16.milliseconds,
)
```

Hashes each tracked Compose surface independently and waits for `stableFrames`
consecutive identical hashes per surface.

A few details worth knowing:

- **Per-surface, not full screen.** Each tracked Compose surface is hashed on its own,
  then the per-surface hashes are combined. Pixel churn outside the app (notifications,
  cursor movement on another monitor) doesn't reset the streak.
- **No surfaces tracked → never idle.** If no Compose surfaces are tracked, or all of
  them have empty bounds, `waitForVisualIdle` returns a different value every poll and
  times out rather than reporting fake stability.
- **Bounded sampling budget.** Each frame hash runs on a worker thread with a hard
  budget. A hung Robot or stuck EDT can't out-block the wait loop's overall timeout.

Reach for `waitForVisualIdle` after:

- An animation that doesn't change semantics (a fade, an indeterminate spinner stopping).
- A long-running compose operation where you only care that the UI has stopped twitching.
- Anything where `waitForIdle` returns too early because the semantics tree is already
  stable but the GPU is still flushing frames.

## Combining the helpers

A common pattern after a state-changing interaction:

```kotlin
automator.click(submit)
automator.waitForIdle()        // semantics + idling resources
automator.waitForVisualIdle()  // pixels actually settled too
val result = automator.findOneByTestTag("Result")
```

For tests that are slow or flaky, lengthen `quietPeriod` (semantics) and `stableFrames`
(visual) before reaching for sleeps. The wait helpers are deliberately tunable so you
don't have to fall back to `Thread.sleep`.

## Defaults at a glance

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

These are tuned for desktop; bump them up freely if your scenarios are heavier.
