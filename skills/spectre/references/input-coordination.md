# Experimental desktop input coordination

Use this reference when parallel Spectre processes must share real OS mouse, keyboard, focus, or clipboard state via
`InputLeasePolicy`, `InputIsolationConfig`, `withExclusiveInput`, or `spectre input-lock`. Published guides:
<https://spectre.sebastiano.dev/guide/input-coordination/>, <https://spectre.sebastiano.dev/guide/junit/>, and
<https://spectre.sebastiano.dev/guide/ci/> (`docs/guide/` in the Spectre repository).

## Opt-in and cooperative boundary

Desktop input coordination is experimental (`macOS`, `Windows` with native `AF_UNIX`, and `Linux` Xorg/Xvfb; Linux
Wayland real `Robot` coordination is not claimed) and strictly cooperative across participating Spectre clients — it
never blocks humans, older Spectre versions, or non-Spectre tools:

```kotlin
@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

import dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi
```

## Choosing a policy

1. **Prefer synthetic input first:** if real OS input is unnecessary, use
   `RobotDriver.synthetic(rootWindow = topLevelWindow)` to eliminate shared focus completely.
2. **Fail-closed real input across processes:** add `spectre-input-coordinator-server` at runtime (`spectre-testing`
   already brings it in) and pass `RobotDriver(InputLeasePolicy.Required)`. Keep no-arg `RobotDriver()` uncoordinated;
   do not imply its default is changing.
3. **Other policies:** use `InputLeasePolicy.Auto` only for opportunistic coordination, and `InputLeasePolicy.Off` only
   when an external runner already serialises desktop access.
4. **JUnit whole-test leases:** pass `InputIsolationConfig.perTest()` when setup, failure capture, or teardown can alter
   focus (covers factory creation, test body, failure evidence, and teardown; default `perInteraction()` covers single
   calls). Never add a test-JVM `perTest()` lease around `LaunchAndAttachExtension`/`LaunchAndAttachRule` (the target
   JVM already coordinates operations, so an outer lease self-deadlocks).
5. **Multi-step critical sections:** wrap coupled actions (including screenshots/recordings, which do not acquire leases
   automatically) in reentrant `automator.withExclusiveInput { ... }`.
6. **Agent attach skew:** attach starts the coordinator on a best-effort basis so read-only tree inspection still works
   if the coordinator cannot launch; current target cores use `Required` for real input and fail closed, whereas legacy
   pre-coordination target cores fall back to uncoordinated `RobotDriver`.

## Inspecting and revoking stuck leases

Always inspect active leases first, then revoke by exact observed lease ID and reason (never kill by parsed PID as
routine recovery):

```shell
spectre input-lock status --json
spectre input-lock revoke --lease <observed-id> --reason "ci job timed out"
```

Pass `--force` only when normal cleanup cannot finish: it reports `unsafeTakeover=true`, does not terminate the holder,
and can overlap an in-flight native OS input call.
