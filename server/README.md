# Server

The optional HTTP transport lets another JVM control a `ComposeAutomator`.

The HTTP API is experimental. Callers must opt in with `@ExperimentalSpectreHttpApi`.
Spectre does not guarantee binary compatibility for this API. The
[security notes](../docs/SECURITY.md) describe its trust model, and the
[stability policy](../docs/STABILITY.md) defines the API tiers.

## Public surface

- `SpectreHttpSecurity(bearerToken, allowedOrigins, allowInsecureLoopback)` sets the required
  deployment bearer and exposure policy. CORS is disabled by default, and HTTPS is required.
- `Application.installSpectreRoutes(automator, security, basePath = "/spectre")` mounts routes
  on a Ktor `Application`, including CIO, Netty, or an embedded test server. The caller
  configures TLS and manages the `Application` lifecycle.
- `ComposeAutomator.http(security, host, port, basePath)` returns an `HttpComposeAutomator`.
  The client owns its `HttpClient`, sends the bearer, and uses HTTPS by default.
  Call `close()` when the client is no longer needed.
- `HttpComposeAutomator` supports `windows`,
  `allNodes`, selectors (`findBy*` / `findOneBy*` including structured `TextQuery`), input
  verbs (`click`, `doubleClick`, `longClick`, `swipe`, `scrollWheel`, `pressKey`, `typeText`,
  `clearAndTypeText`), `tree` / `printTree`, and screenshot (full-frame or node-targeted).
- DTOs in `dev.sebastiano.spectre.server.dto` define the kotlinx-serialization wire contract.
  `DtoSerializationTest` verifies that each DTO survives serialisation and deserialisation.

## Current scope

The endpoints expose data queries and actions. `registerIdlingResource`, `waitForIdle`,
`waitForVisualIdle`, and `withTracing` require an in-process automator:

- Idling resources are JVM objects without a serialisable form. Remote polling would require
  a driver design that this transport does not provide.
- `withTracing` needs a `Tracer` instance that cannot cross the process boundary.
- The current transport has no protocol for stateful long polling.

`DtoSerializationTest` and `SpectreServerRoundTripTest` check the wire contract.
The validation issues track runtime parity against a live Compose UI.
