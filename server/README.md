# Server

Opt-in HTTP transport so a `ComposeAutomator` can be driven from a different JVM.

**Experimental.** The transport's entire public surface is gated by the
`@ExperimentalSpectreHttpApi` opt-in marker and is not covered by Spectre's binary-compatibility
guarantees. See [`docs/SECURITY.md`](../docs/SECURITY.md) for the trust model and
[`docs/STABILITY.md`](../docs/STABILITY.md) for the API-tier definitions.

## Public surface

- `SpectreHttpSecurity(bearerToken, allowedOrigins, allowInsecureLoopback)` — required,
  deployment-scoped bearer and exposure policy. CORS is disabled and HTTPS is required by default.
- `Application.installSpectreRoutes(automator, security, basePath = "/spectre")` — mount the routes
  on any Ktor `Application` (CIO, Netty, embedded test server, etc.). The caller owns TLS connector
  configuration and the `Application` lifecycle.
- `ComposeAutomator.http(security, host, port, basePath)` — companion extension that returns an
  `HttpComposeAutomator` connected to a remote `installSpectreRoutes` host. The instance owns
  its `HttpClient`, sends the bearer automatically, uses HTTPS by default, and must be `close()`d.
- `HttpComposeAutomator` — client class with the current transport surface: `windows`,
  `allNodes`, selectors (`findBy*` / `findOneBy*` including structured `TextQuery`), input
  verbs (`click`, `doubleClick`, `longClick`, `swipe`, `scrollWheel`, `pressKey`, `typeText`,
  `clearAndTypeText`), `tree` / `printTree`, and screenshot (full-frame or node-targeted).
- DTOs in `dev.sebastiano.spectre.server.dto` — kotlinx-serialization wire shapes that pin the
  request/response contract. `DtoSerializationTest` round-trips every one.

## Current scope

Endpoints land the data-only queries and actions. Advanced features — `registerIdlingResource`,
`waitForIdle` / `waitForVisualIdle`, `withTracing` — are intentionally in-process-only:

- Idling resources are JVM objects without a serializable shape; HTTP-side polling would need a
  pluggable driver design out of scope for the current transport.
- `withTracing` requires a `Tracer` instance the server can't accept across processes.
- The wait helpers are stateful long-poll semantics that the current transport doesn't model.

The contract test suite (`DtoSerializationTest`, `SpectreServerRoundTripTest`) covers the wire
boundary; runtime parity against a live Compose UI is part of the validation issues.
