# The automator

`ComposeAutomator` is the entry point to everything Spectre does. This page is the
mental model: what an automator owns, how it sees a Compose Desktop application, and
the deliberate design choices that affect how you write tests.

## What an automator owns

Every `ComposeAutomator` wraps three collaborators:

- **`WindowTracker`** — keeps a list of the running Compose surfaces (top-level windows
  and popup roots) the automator can see.
- **`SemanticsReader`** — reads Compose's semantics tree out of those surfaces and
  produces `AutomatorNode`s.
- **`RobotDriver`** — dispatches mouse, keyboard, and clipboard input, and captures
  screenshots.

Build one with the default in-process configuration:

```kotlin
val automator = ComposeAutomator.inProcess()
```

For a headless setup that exercises the semantics-reading paths without driving real OS
input, build one with `RobotDriver.headless()`. The `testing` module's
`newHeadlessAutomator()` fixture has the recipe.

## Surfaces and the semantics tree

A *surface* is a root that Compose draws into. In a typical desktop app that's a
top-level `Window`, but Compose also creates separate roots for popups, menus, and
dialogs. Spectre tracks all of them so a node inside a dropdown is just as findable as
one in the main window.

Calling `automator.refreshWindows()` rescans the live surface list. The query helpers do
this for you, so you rarely call it directly.

`automator.tree()` returns an `AutomatorTree` snapshot — a list of `AutomatorWindow`s,
each with its own root nodes:

```kotlin
val tree = automator.tree()
for (window in tree.windows()) {
    println("Window ${window.windowIndex}: ${window.surfaceId}")
    for (root in window.roots()) { /* … */ }
}
```

For ad-hoc debugging, `automator.printTree()` returns a human-readable dump of every
window with its node hierarchy, test tags, text, and roles.

## Queries vs. interactions

The API is split into two layers:

- **Queries** — `tree()`, `allNodes()`, `findByTestTag(...)`, `findByText(...)`,
  `findByContentDescription(...)`, `findByRole(...)`, and the `findOneBy…` variants.
  These do a single read against the current semantics state and return what they see.
- **Interactions** — `click`, `doubleClick`, `longClick`, `swipe`, `scrollWheel`,
  `typeText`, `clearAndTypeText`, `pressKey`, `pressEnter`, `screenshot`. These dispatch
  input via `RobotDriver` (or capture pixels).

The split matters because **queries do not auto-wait**. If you call `findOneByTestTag(...)`
on a frame where the node isn't there yet, you get `null` — there's no implicit retry.
This is a deliberate v1 contract; see the next section.

## No auto-wait

Frameworks like Espresso wrap every read and action in an idle barrier. Spectre v1 does
not. The reasoning lives in [`ComposeAutomator.kt`](https://github.com/rock3r/spectre/blob/main/core/src/main/kotlin/dev/sebastiano/spectre/core/ComposeAutomator.kt):

```kotlin
// V1 contract: queries and actions do not auto-wait. Callers must invoke waitForIdle() /
// waitForVisualIdle() / waitForNode() explicitly when synchronisation matters.
```

What this means in practice:

- After launching the UI, **wait for a known node** with `waitForNode(tag = "…")` before
  asserting on it. This is your "the UI is on screen" barrier.
- After an interaction that triggers state change or animation, call
  **`waitForVisualIdle()`** (pixels stable for N frames) or **`waitForIdle()`**
  (semantics fingerprint stable plus any registered idling resources).
- A failing assertion isn't necessarily a bug in your UI — it's often "the UI hadn't
  finished updating before I read it back".

See [Synchronization](synchronization.md) for the full toolkit.

## The EDT rule

The wait helpers refuse to run on the AWT event dispatch thread (EDT):

```
waitForIdle must not be called from the AWT event dispatch thread;
wrap the call with withContext(Dispatchers.Default) or similar.
```

This is enforced at runtime because the wait loops drain the EDT and snapshot semantics
via `invokeAndWait`. If they ran on the EDT they would either deadlock or quietly skip
the bounded worker that enforces their timeout.

JUnit test methods don't run on the EDT, but coroutines launched on `Dispatchers.Main`
or any dispatcher backed by Swing's EDT will. The standard pattern is `runBlocking
{ withContext(Dispatchers.Default) { … } }`.

## Real input vs. synthetic input

By default `ComposeAutomator.inProcess()` uses `RobotDriver()` — `java.awt.Robot`-backed
real OS-level input. That's what end users actually do, so it's the most realistic.

The trade-off is that real OS input fights for global focus. Two parallel test JVMs
clicking at the same time will collide. For that case `RobotDriver.synthetic(...)`
dispatches AWT events directly into the target window's event queue:

- No real cursor moves, no keyboard focus stolen.
- Tests in parallel processes can run without coordinating focus.
- Some interactions (e.g. system-level shortcuts) won't behave the same way as real input.

See [Driving input](interactions.md) for the per-call differences.

## Lifecycle

The JUnit wrappers create a fresh `ComposeAutomator` per test. The
[`testing`](junit.md) module gives you `ComposeAutomatorExtension` (JUnit 5) and
`ComposeAutomatorRule` (JUnit 4); both default to `ComposeAutomator.inProcess()` and
both accept a custom `AutomatorFactory` if you want to inject a stub.

Outside JUnit, build the automator yourself; there's no global state to clean up beyond
whatever your factory wires in (a recording session, idling resources, etc.).

## Idling resources

If your app has work that the semantics fingerprint can't see — a background poller, a
network call, a custom animation — register an `AutomatorIdlingResource`:

```kotlin
automator.registerIdlingResource(myResource)
// ...
automator.unregisterIdlingResource(myResource)
```

`waitForIdle()` polls every registered resource alongside its own checks; it returns
only when all of them report idle and the UI fingerprint has been stable for the
configured quiet period.
