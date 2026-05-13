# Testing

Test ergonomics for Spectre.

## Public API

- `ComposeAutomatorRule` — JUnit 4 rule that owns a per-test `ComposeAutomator` instance.
- `ComposeAutomatorExtension` — JUnit 5 extension with the same lifecycle plus parameter
  resolution for `ComposeAutomator` test method parameters.
- `AutomatorFactory` — typealias for the `() -> ComposeAutomator` lambda the rule and extension
  use to build their per-test instances.

Both wrappers default to `ComposeAutomator.inProcess()`. Tests that need a stub for headless CI
or focused unit testing can pass a custom factory built around `RobotDriver.headless()` (see the
`newHeadlessAutomator()` fixture in this module's tests for the recipe).

## JUnit dependency model

`junit:junit` and `org.junit.jupiter:junit-jupiter-api` are both `compileOnly`. Consumers pick
whichever JUnit they're already using and pull in the matching test dependency themselves; the
testing module never forces both onto the test classpath.

## Cross-boundary contracts

Contract tests pin the wire-level guarantees that cross module boundaries:

- `NodeKeyContractTest` — round-trip and parse safety for the `surfaceId:ownerIndex:nodeId`
  string form the HTTP transport's `ClickRequest.nodeKey` and the recording module's
  `TitleDiscriminator` both depend on.

Server DTO and HTTP transport contracts live with the `server` module, while recording
backend contracts live with `recording`. Add new cross-module contract tests to the module
that owns the public boundary they pin.
