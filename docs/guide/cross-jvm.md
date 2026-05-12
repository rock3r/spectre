# Cross-JVM access

When the UI you want to drive lives in a different JVM than the test process — most
commonly a Compose Desktop app you've launched as a separate process — Spectre's
`server` module gives you an HTTP transport. The hosting JVM mounts a Ktor route on
top of an in-process `ComposeAutomator`; the test JVM talks to it through
`HttpComposeAutomator`.

!!! tip "IntelliJ-hosted Compose has its own page"
    For driving Jewel-on-IntelliJ tool windows or any Compose surface hosted inside
    an IntelliJ plugin, the in-process pattern with `intellij-ide-starter` for the
    test side is the recommended path — it sidesteps the IDE's classloader isolation
    and uses JetBrains' own IPC for the test ↔ IDE bridge. See
    [IntelliJ-hosted Compose](intellij.md). The HTTP transport on this page is
    aimed at the standalone-app case.

!!! note "HTTP transport scope"
    The HTTP transport is a deliberate subset of the in-process automator: windows,
    nodes by tag, click, type-text, and screenshot. Advanced features that need live
    JVM objects (idling resources, `withTracing`) or stateful long-poll semantics
    (`waitForVisualIdle`) are in-process only. If you need them, run the test JVM in
    the same process as the UI.

!!! warning "Trust boundary"
    The HTTP transport is **experimental** and intended for **trusted local / test
    environments only**.

    - Every route is **unauthenticated**. Anything that can reach the bound port
      can click, type, and capture screenshots.
    - Communication is **plaintext HTTP**. There is no TLS support.
    - `click` and `typeText` drive **real OS input**; `screenshot` captures
      whatever pixels the host JVM can see, including content from other windows.
    - **Bind to `127.0.0.1`.** Do not expose this server on a network-reachable
      interface. The examples below pin the loopback bind explicitly.
    - Authentication, authorization, and TLS are tracked for a separately
      reviewed future design (#96).

    See [Security notes](../SECURITY.md) for the full risk register and the
    accepted-risk list.

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

// `host = "127.0.0.1"` is intentional: the HTTP transport is unauthenticated, so the
// server must not be reachable from outside the local machine. See the "Trust boundary"
// warning above.
embeddedServer(Netty, host = "127.0.0.1", port = 9274) {
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

// Connect to the loopback server mounted above.
ComposeAutomator.http(host = "127.0.0.1", port = 9274).use { remote ->
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

Node keys travel as the canonical string form `surfaceId:ownerIndex:nodeId` — the
stable string form used by the transport contract and pinned by `NodeKeyContractTest`
in the testing module.

## Use cases

The server module pays for itself when:

- The UI under test is **an IntelliJ IDE** or a third-party application whose run-loop
  you can't modify — install the routes inside a plugin and drive from a separate test
  process.
- You're running **multiple test JVMs in parallel** against a long-lived UI host (e.g.,
  a sample app launched once, hit by many tests). Combine with `RobotDriver.synthetic`
  on the server side so each test doesn't fight for global focus.
- You want to **separate test orchestration from rendering** for performance reasons,
  e.g., to run the test JVM with aggressive coroutine debugging while the UI runs lean.

If your test owns the UI and runs in the same JVM, stick with `ComposeAutomator.inProcess()`
— it's cheaper and exposes the full automator surface.
