# Testing

## Expectations

Always add tests for behavior changes.

1. Pure logic in `core` should get unit tests.
2. Serialization, DTO mapping, and remote-call behavior in `server` should get contract tests.
3. Recording code should separate testable pure logic from OS-specific integration and test the
   pure parts directly.
4. UI-facing spike work in `sample-desktop` may require manual validation, but any reusable logic
   extracted from it should still receive automated tests.
5. Run targeted tests while iterating, then finish with the broader relevant verification pass.
6. Before pushing, ensure the CI-shaped path is green locally when practical: `./gradlew check`.

## TDD Red-Green Cycle

Follow this strictly:

1. Write the test first.
2. Run it and confirm it fails for the missing behavior.
3. Write the minimum implementation.
4. Re-run the targeted test to green.
5. Run the broader relevant suite.

Do not treat “I wrote the test after the code and it passes” as evidence. If a test was never red,
recreate the failure before considering the work done.

## Test Completeness Check

Before marking testing work complete:

- review every planned scenario and edge case
- confirm each one has a corresponding test or a deliberate manual-validation note
- fill the gaps before moving on

## Cross-Boundary Contract Tests

When a feature spans a boundary, add at least one test that exercises the real boundary shape.

Examples for Spectre:

- HTTP request/response payloads in `server`
- compound node identity formatting and parsing
- coordinate conversion behavior across Compose/AWT/Robot units
- native helper invocation contracts for recording

These tests should use the real payload or coordinate format rather than a hand-crafted idealized
version. Unit tests for internal math are necessary, but boundary tests catch drift between layers.

## Manual Spike Validation

Some concerns still need live manual verification even with good automated tests:

- Retina / HiDPI coordinate accuracy
- popup discovery across different layer modes
- Robot focus behavior and click targeting
- recording permission and capture behavior on macOS

Use `sample-desktop` to make those checks reproducible. If a manual validation step is required
for a change, note it explicitly in the final report.

## Coroutine Testing

- Prefer `runTest` for coroutine-based logic.
- Avoid real sleeps when a deterministic scheduler or fake clock will do.
- Cancel/close long-lived scopes created in tests.
- If asynchronous behavior cannot be made deterministic, isolate the nondeterminism behind a small
  interface and test the decision logic separately.
