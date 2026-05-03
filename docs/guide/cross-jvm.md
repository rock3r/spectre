# Cross-JVM access

When the UI you want to drive lives in a different JVM than the test process — most
commonly an IntelliJ IDE under test, or a Compose Desktop app you've launched as a
separate process — Spectre's `server` module gives you an HTTP transport. The hosting
JVM mounts a Ktor route on top of an in-process `ComposeAutomator`; the test JVM talks
to it through `HttpComposeAutomator`.

!!! note "v1 surface"
    The HTTP transport is a deliberate v1 subset of the in-process automator: windows,
    nodes by tag, click, type-text, and screenshot. Advanced features that need live
    JVM objects (idling resources, `withTracing`) or stateful long-poll semantics
    (`waitForVisualIdle`) are in-process only. If you need them, run the test JVM in
    the same process as the UI.

## Server side: mount the routes

In the hosting JVM, build an in-process automator and install Spectre's routes on a
Ktor application. `installSpectreRoutes` is engine-agnostic — Spectre intentionally
doesn't bundle a Ktor server engine, so add one yourself:

```kotlin
dependencies {
    // ...your existing Spectre + ktor-server-core comes via the server module
    implementation("io.ktor:ktor-server-netty:2.3.12") // or :ktor-server-cio, :ktor-server-jetty
}
```

```kotlin
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.server.installSpectreRoutes
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

val automator = ComposeAutomator.inProcess()

embeddedServer(Netty, port = 9274) {
    installSpectreRoutes(automator)
}.start(wait = false)
```

`9274` is the default port the **client** uses
(`HttpComposeAutomator.DEFAULT_PORT`) — `installSpectreRoutes` itself only mounts routes
on whatever Ktor `Application` you give it, so the hosting engine picks the listener.
Using `9274` on both sides keeps the defaults aligned; otherwise pass a matching port
to both `embeddedServer(...)` and `ComposeAutomator.http(...)`.

`installSpectreRoutes` mounts everything under `/spectre` by default; pass `basePath =
"/foo"` if you need it elsewhere.

### ContentNegotiation

The routes exchange JSON. If `ContentNegotiation` isn't already installed on the
application, `installSpectreRoutes` installs it with the kotlinx JSON converter. If you
have already installed it for your own routes, **make sure your configuration includes
a JSON converter** — Ktor doesn't let plugins merge converters into an existing
installation, so Spectre leaves yours alone.

### Engine choice

Spectre doesn't pick an engine for you — you bring your own (`Netty`, `CIO`, `Jetty`,
etc.) and configure it however you need. The example above uses Netty; a lighter test
embedded server might use `CIO`.

## Client side: drive it

In the test JVM, the canonical entry point is the `ComposeAutomator.http(...)`
companion extension:

```kotlin
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.server.http
import kotlinx.coroutines.runBlocking

ComposeAutomator.http(host = "localhost", port = 9274).use { remote ->
    runBlocking {
        val nodes = remote.findByTestTag("Submit")
        if (nodes.isNotEmpty()) {
            remote.click(nodes.first().key)
        }
    }
}
```

`HttpComposeAutomator` is `AutoCloseable` and owns its underlying Ktor `HttpClient`;
use `use { ... }` (or call `close()` yourself) so the connection pool and selector
threads are released.

## What's on the wire

Everything is JSON, modelled by DTOs in `dev.sebastiano.spectre.server.dto`. Notable
shapes:

| DTO                  | Role                                                       |
| -------------------- | ---------------------------------------------------------- |
| `WindowSummaryDto`   | Per-window summary (index, surface id, bounds, popup flag).|
| `NodeSnapshotDto`    | Read-only projection of an `AutomatorNode`.                |
| `NodesResponse`      | List wrapper around `NodeSnapshotDto`.                     |
| `WindowsResponse`    | List wrapper around `WindowSummaryDto`.                    |
| `ClickRequest`       | `{ "nodeKey": "surfaceId:ownerIndex:nodeId" }`.            |
| `TypeTextRequest`    | `{ "text": "..." }`. Types into whatever has focus.        |
| `ScreenshotResponse` | Base64-encoded PNG bytes.                                  |

Node keys travel as the canonical string form `surfaceId:ownerIndex:nodeId` — the same
form pinned by `NodeKeyContractTest` in the testing module, and the form future
recording integrations will rely on.

## Use cases

The server module pays for itself when:

- The UI under test is **an IntelliJ IDE** or a third-party application you can't
  modify the run-loop of — install the routes inside a plugin and drive from a separate
  test process.
- You're running **multiple test JVMs in parallel** against a long-lived UI host (e.g.
  a sample app launched once, hit by many tests). Combine with `RobotDriver.synthetic`
  on the server side so each test doesn't fight for global focus.
- You want to **separate test orchestration from rendering** for performance reasons —
  e.g. running the test JVM with aggressive coroutine debugging while the UI runs lean.

If your test owns the UI and runs in the same JVM, stick with `ComposeAutomator.inProcess()`
— it's cheaper and exposes the full automator surface.
