# Server

Opt-in HTTP transport so a `ComposeAutomator` can be driven from a different JVM.

## Public surface

- `Application.installSpectreRoutes(automator, basePath = "/spectre")` — mount the routes on
  any Ktor `Application` (CIO, Netty, embedded test server, etc.). The caller owns the
  `Application` lifecycle.
- `ComposeAutomator.http(host, port, basePath)` — companion extension that returns an
  `HttpComposeAutomator` connected to a remote `installSpectreRoutes` host. The instance owns
  its `HttpClient` and must be `close()`d.
- `HttpComposeAutomator` — client class with the v1 transport surface: `windows`, `allNodes`,
  `findByTestTag`, `click`, `typeText`, `screenshot`.
- DTOs in `dev.sebastiano.spectre.server.dto` — kotlinx-serialization wire shapes that pin the
  request/response contract. `DtoSerializationTest` round-trips every one.

## v1 scope

Endpoints land the most-used queries and actions. Advanced features — `registerIdlingResource`,
`waitForIdle` / `waitForVisualIdle`, `withTracing`, `printTree` — are intentionally
in-process-only:

- Idling resources are JVM objects without a serializable shape; HTTP-side polling would need a
  pluggable driver design out of scope for v1.
- `withTracing` requires a `Tracer` instance the server can't accept across processes.
- The wait helpers are stateful long-poll semantics that the v1 transport doesn't model.

The contract test suite (`DtoSerializationTest`, `SpectreServerRoundTripTest`) covers the wire
boundary; runtime parity against a live Compose UI is part of the validation issues.
