# Cross-JVM access

The `server` module lets a test control a Compose Desktop UI in another JVM over HTTP.
The application JVM mounts Ktor routes backed by an in-process `ComposeAutomator`.
The test JVM sends requests through `HttpComposeAutomator`.

!!! tip "IntelliJ-hosted Compose has its own page"
    For an IntelliJ plugin or Jewel tool window, run the automator inside the IDE.
    Use `intellij-ide-starter` to drive the IDE from the test JVM.
    This setup handles IDE classloader isolation through JetBrains IPC.
    See [IntelliJ-hosted Compose](intellij.md). The HTTP setup below targets standalone apps.

!!! note "HTTP transport scope"
    The HTTP transport supports windows and node queries. Selectors include text,
    content description, role, structured `TextQuery`, and `findOneBy*`. Input
    includes click, `doubleClick`, `longClick`, `swipe`, `scrollWheel`, `pressKey`,
    `typeText`, and `clearAndTypeText`. It also supports `tree()`,
    `tree(windowIndex)`, `printTree()`, and full-frame or node screenshots.
    Idling resources and `withTracing` need live JVM objects and work in-process.
    The [capability matrix](capability-matrix.md) lists transport and platform
    support with CI evidence.

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
    - `click` and `typeText` use the host automator's input driver. It sends
      synthetic AWT events by default. A host configured with `RobotDriver()`
      sends real OS input. `screenshot` captures pixels visible to the host JVM,
      which can include other windows.

    See [Security notes](../SECURITY.md) for deployment and proxy guidance.

    All entry points in this guide require `@OptIn(ExperimentalSpectreHttpApi::class)`.
    See [Stability policy](../STABILITY.md) for the API tier definitions.

## Server side: mount the routes

In the application JVM, create an in-process automator and mount Spectre routes on Ktor.
`installSpectreRoutes` works with any Ktor server engine. Add the application's engine:

```kotlin
dependencies {
    // ...your existing Spectre + ktor-server-core comes via the server module
    implementation("io.ktor:ktor-server-netty:3.4.1") // or :ktor-server-cio, :ktor-server-jetty
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

`HttpComposeAutomator.DEFAULT_PORT` is `9274`. The Ktor engine sets the server port;
`installSpectreRoutes` only mounts routes. If the server uses another port, pass that port
to `ComposeAutomator.http(...)` too.

`installSpectreRoutes` uses `/spectre` by default. Pass `basePath = "/foo"` for another path.

For network use, configure an HTTPS connector and keep `allowInsecureLoopback = false`.
If a reverse proxy terminates TLS, enable Ktor forwarded headers only for a trusted
direct proxy peer. Spectre checks Ktor's resolved origin to enforce HTTPS.
Do not trust `Forwarded` or `X-Forwarded-Proto` headers from clients.

### Bearer and CORS configuration

Create one high-entropy bearer for each deployment and give it to both JVMs.
The bearer needs at least 32 RFC 6750 characters. `openssl rand -base64 32` produces
a suitable value. Use a secret manager for rotation. Spectre does not print the bearer.

`allowedOrigins` defaults to an empty set, which rejects browser CORS requests.
List exact origins when a browser runner needs access:

```kotlin
val security = SpectreHttpSecurity(
    bearerToken = checkNotNull(System.getenv("SPECTRE_HTTP_TOKEN")),
    allowedOrigins = setOf("https://tests.example.internal"),
)
```

Preflight `OPTIONS` requests require HTTPS and an allowed origin. They do not require a bearer.
Every other request requires the bearer, including requests without an `Origin` header.

### ContentNegotiation

Spectre routes exchange JSON. `installSpectreRoutes` installs `ContentNegotiation` with
the kotlinx JSON converter when the application has no existing installation.
If the application already installed the plugin, configure a JSON converter yourself.
Ktor cannot merge converters into an existing installation.

### Engine choice

The application selects and configures its Ktor engine. The example uses Netty.
An embedded test server can use CIO or another available engine.

## Client side: drive it

In the test JVM, connect through the `ComposeAutomator.http(...)` companion extension:

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

`HttpComposeAutomator` owns a Ktor `HttpClient` and implements `AutoCloseable`.
Use `use { ... }` or call `close()` to release connections and selector threads.

## What's on the wire

The transport serialises JSON using DTOs in `dev.sebastiano.spectre.server.dto`:

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

Node keys use `surfaceId:ownerIndex:nodeId`. `NodeKeyContractTest` in `testing` pins
this transport format.

Structured text matching uses query parameters on `GET /nodes` and `GET /node`:

```text
/spectre/nodes?text=Submit&matchType=Substring&ignoreCase=true
```

`matchType` is `Exact` or `Substring`. Do not combine `matchType` / `ignoreCase` with
the older `exact` shorthand. Node-targeted screenshots use
`GET /spectre/screenshot?nodeKey=…`.

## Use cases

Use the server module for these situations:

- The UI under test is **an IntelliJ IDE** or a third-party application with an unmodifiable run loop. Install the routes inside a plugin and drive from a separate test
  process.
- **Multiple test JVMs run in parallel** against a long-lived UI host (e.g.,
  a sample app launched once, hit by many tests). Combine with `RobotDriver.synthetic`
  on the server side so each test doesn't fight for global focus.
- **Test orchestration and rendering need separate processes** for performance reasons,
  e.g., to run the test JVM with aggressive coroutine debugging while the UI runs lean.

When the test and UI share a JVM, `ComposeAutomator.inProcess()` avoids HTTP overhead
and exposes the full automator API.
