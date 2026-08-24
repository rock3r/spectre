# Experimental desktop input coordination

Read this reference when a user has multiple Spectre processes or Gradle forks that need real OS
mouse, keyboard, focus, or clipboard access; asks about `InputLeasePolicy`,
`InputIsolationConfig`, `withExclusiveInput`, or `spectre input-lock`; or reports that real-input
tests steal focus from each other.

## Source of truth

- Published guide: <https://spectre.sebastiano.dev/guide/input-coordination/>
- JUnit guide: <https://spectre.sebastiano.dev/guide/junit/>
- CI guide: <https://spectre.sebastiano.dev/guide/ci/>
- Repo docs: `docs/guide/input-coordination.md`, `docs/guide/junit.md`, and
  `docs/guide/ci.md`

The feature is experimental and opt-in. Include:

```kotlin
@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

import dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi
```

Do not describe the coordinator as an OS mutex or input grab. It serialises participating Spectre
clients only; people, older versions, and other automation tools remain outside the boundary.

## Decide whether coordination is appropriate

1. If the test does not need real OS behaviour, prefer
   `RobotDriver.synthetic(rootWindow = topLevelWindow)`. It avoids shared focus entirely.
2. If multiple processes genuinely need real input, add
   `spectre-input-coordinator-server` at runtime and use
   `RobotDriver(InputLeasePolicy.Required)` so missing coordination fails closed.
3. Use `InputLeasePolicy.Auto` only when opportunistic availability is intended. Use `Off` only
   when an external harness already serialises input or the caller deliberately accepts races.
4. Keep the no-argument `RobotDriver()` uncoordinated while the feature is experimental; do not
   imply that the next release changes its default.

For JUnit 4/5 in-process real-input suites, recommend `InputIsolationConfig.perTest()` when setup,
failure capture, or teardown can touch focus. It spans factory creation, test execution, evidence,
and teardown. `PerInteraction` is narrower. `LaunchAndAttachExtension`/Rule rely on target-JVM
operation leases and do not offer a test-JVM `PerTest` mode because two leases would self-deadlock.

For several related operations that must not interleave, use `automator.withExclusiveInput { ... }`.
The scope is reentrant. Screenshots and recording are not automatically leased; place a capture in
the scope only when its visible state must be protected from other Spectre input.

Agent attach starts the coordinator best-effort so semantics inspection is still usable when the
runtime cannot launch. A current target core uses `Required` for real input and therefore fails
closed in that state. A target with an older pre-coordination core falls back to its legacy
uncoordinated `RobotDriver`; call out that compatibility boundary when version skew is involved.

## Recovery guidance

Inspect first:

```text
spectre input-lock status --json
```

Normal recovery uses the exact current ID and a meaningful reason:

```text
spectre input-lock revoke --lease <observed-id> --reason <text>
```

Only suggest `--force` when normal cleanup cannot complete and the user accepts possible overlap
with an in-flight native call. A forced result reports `unsafeTakeover=true`; it does not kill the
holder or prove input stopped. Never suggest parsing a PID and killing it as the coordinator's
normal escape hatch.

## Release-status honesty

The coordinator ships as Experimental on macOS, Windows with native `AF_UNIX`, and Linux
Xorg/Xvfb. Linux Wayland real Robot input is not claimed. Do not call the API stable or default-on
until cross-platform headed smoke and a later graduation decision say so.
