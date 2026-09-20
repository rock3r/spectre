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
    nodes (selectors including text / content-description / role, structured
    `TextQuery`, and `findOneBy*`), click and richer input verbs (`doubleClick` /
    `longClick` / `swipe` / `scrollWheel` / `pressKey` / `typeText` /
    `clearAndTypeText`), `tree()` / `tree(windowIndex)` / `printTree()`, and
    screenshot (full-frame or node-targeted). Advanced features that need live JVM
    objects (idling resources, `withTracing`) remain in-process only. If you need
    them, run the test JVM in the same process as the UI. The full ops × transports
    × platforms picture — with multi-state cells and fail-closed CI evidence — lives
    in the [capability matrix](capability-matrix.md).

!!! warning "Trust boundary"
    The HTTP transport is **experimental** and exposes privileged desktop automation.

    - Every non-preflight route requires a deployment-scoped bearer. Treat it as a
      secret: load it from the deployment environment or a secret manager; never put
      it in source, command-line arguments, URLs, or logs.
    - Communication is **HTTPS by default**. The only plaintext escape hatch is an
      explicit opt-in for loopback tests; both server and client reject its use with
      a non-loopback peer.
    - Browser cross-origin access is disabled unless the server is given an exact
      origin allowlist. Wildcard origins are not supported.
    - `click` and `typeText` drive the host automator's input driver (synthetic AWT
      events by default; real OS input if that host opted into `RobotDriver()`);
      `screenshot` captures whatever pixels the host JVM can see, including content
      from other windows.

    See [Security notes](../SECURITY.md) for deployment and proxy guidance.

    All entry points in this guide require `@OptIn(ExperimentalSpectreHttpApi::class)`
    — see [Stability policy](../STABILITY.md) for the API tier definitions.

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
@file:OptIn(ExperimentalSpectreHttpApi::class)

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.server.ExperimentalSpectreHttpApi
import dev.sebastiano.spectre.server.SpectreHttpSecurity
import dev.sebastiano.spectre.server.installSpectreRoutes
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

val automator = ComposeAutomator.inProcess()
val security = SpectreHttpSecurity(
    bearerToken = checkNotNull(System.getenv("SPECTRE_HTTP_TOKEN")),
    // Local test escape hatch only. Omit this for an HTTPS deployment.
    allowInsecureLoopback = true,
)

// This example deliberately uses the loopback-only plaintext test mode.
embeddedServer(Netty, host = "127.0.0.1", port = 9274) {
    installSpectreRoutes(automator, security)
}.start(wait = false)
```

`9274` is the default port the **client** uses
(`HttpComposeAutomator.DEFAULT_PORT`) — `installSpectreRoutes` itself only mounts routes
on whatever Ktor `Application` you give it, so the hosting engine picks the listener.
Using `9274` on both sides keeps the defaults aligned; otherwise pass a matching port
to both `embeddedServer(...)` and `ComposeAutomator.http(...)`.

`installSpectreRoutes` mounts everything under `/spectre` by default; pass `basePath =
"/foo"` if you need it elsewhere.

For a network deployment, configure an HTTPS connector on the Ktor engine and leave
`allowInsecureLoopback` at its default `false`. If TLS terminates at a reverse proxy,
install Ktor's forwarded-header support only when the direct peer is a trusted proxy;
Spectre uses Ktor's resolved request origin to enforce HTTPS. Do not blindly trust
client-supplied `Forwarded` or `X-Forwarded-Proto` headers.

### Bearer and CORS configuration

Use one high-entropy bearer per deployment and give the same value to the hosting and
test JVMs. Bearers must contain at least 32 RFC 6750 characters;
`openssl rand -base64 32` produces a suitable value. Rotate it through your normal
secret-management mechanism; Spectre never prints or returns it.

`allowedOrigins` is empty by default, so browser CORS requests fail closed. If a browser
runner is intentional, list exact origins:

```kotlin
val security = SpectreHttpSecurity(
    bearerToken = checkNotNull(System.getenv("SPECTRE_HTTP_TOKEN")),
    allowedOrigins = setOf("https://tests.example.internal"),
)
```

Preflight (`OPTIONS`) requests do not carry the bearer, but still require HTTPS and an
allowed origin. Every other request requires the bearer, including requests without an
`Origin` header.

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
@file:OptIn(ExperimentalSpectreHttpApi::class)

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.server.ExperimentalSpectreHttpApi
import dev.sebastiano.spectre.server.SpectreHttpSecurity
import dev.sebastiano.spectre.server.http
import kotlinx.coroutines.runBlocking

val security = SpectreHttpSecurity(
    bearerToken = checkNotNull(System.getenv("SPECTRE_HTTP_TOKEN")),
    allowInsecureLoopback = true, // Match the loopback-only server example above.
)

// Connect to the loopback server mounted above.
ComposeAutomator.http(security, host = "127.0.0.1", port = 9274).use { remote ->
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

| DTO                       | Role                                                        |
| ------------------------- | ----------------------------------------------------------- |
| `WindowSummaryDto`        | Per-window summary (index, surface id, bounds, popup flag). |
| `NodeSnapshotDto`         | Read-only projection of an `AutomatorNode`.                 |
| `NodesResponse`           | List wrapper around `NodeSnapshotDto`.                      |
| `NodeResponse`            | Singular `findOneBy*` wrapper; `node` is JSON `null` if unmatched. |
| `WindowsResponse`         | List wrapper around `WindowSummaryDto`.                     |
| `TreeResponse`            | Nested `tree()` snapshot (`WindowTreeDto` + `TreeNodeDto`). |
| `PrintTreeResponse`       | `{ "dump": "..." }` from `printTree()`.                     |
| `TextQueryDto`            | Query-param form of structured text matching (`matchType` + `ignoreCase`); not a JSON request body. |
| `ClickRequest`            | `{ "nodeKey": "surfaceId:ownerIndex:nodeId" }`.             |
| `ClearAndTypeTextRequest` | `{ "nodeKey": "...", "text": "..." }`.                      |
| `TypeTextRequest`         | `{ "text": "..." }`. Types into whatever has focus.         |
| `ScreenshotResponse`      | Base64-encoded PNG bytes.                                   |

Node keys travel as the canonical string form `surfaceId:ownerIndex:nodeId` — the
stable string form used by the transport contract and pinned by `NodeKeyContractTest`
in the testing module.

Structured text matching uses query parameters on `GET /nodes` and `GET /node`:

```text
/spectre/nodes?text=Submit&matchType=Substring&ignoreCase=true
```

`matchType` is `Exact` or `Substring`. Do not combine `matchType` / `ignoreCase` with
the older `exact` shorthand. Node-targeted screenshots use
`GET /spectre/screenshot?nodeKey=…`.

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
