# Experimental desktop input coordination

Use this reference when multiple Spectre processes share real OS mouse, keyboard,
focus, or clipboard access. It also covers `InputLeasePolicy`, `InputIsolationConfig`,
`withExclusiveInput`, and `spectre input-lock`.

## Source of truth

- Published guide: <https://spectre.sebastiano.dev/guide/input-coordination/>
- JUnit guide: <https://spectre.sebastiano.dev/guide/junit/>
- CI guide: <https://spectre.sebastiano.dev/guide/ci/>
- Repo docs: `docs/guide/input-coordination.md`, `docs/guide/junit.md`, and
  `docs/guide/ci.md`

Input coordination is experimental and opt-in. Add:

```kotlin
@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

import dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi
```

The coordinator serialises participating Spectre clients.
It does not control people, older versions, or other automation tools.

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

For JUnit 4 or 5 real-input suites, use `InputIsolationConfig.perTest()` when setup,
failure capture, or teardown can change focus. It covers factory creation, test execution,
evidence, and teardown. `PerInteraction` covers individual operations.
`LaunchAndAttachExtension` and `LaunchAndAttachRule` use target-JVM operation leases;
a second test-JVM `PerTest` lease would self-deadlock.

Use `automator.withExclusiveInput { ... }` for operations that must stay together.
The scope is reentrant. Screenshots and recording do not acquire a lease automatically.
Put capture inside the scope when other Spectre input could change its visible state.

Agent attach starts the coordinator best-effort so semantics inspection is still usable when the
runtime cannot launch. A current target core uses `Required` for real input and therefore fails
closed in that state. A target with an older pre-coordination core falls back to its legacy
uncoordinated `RobotDriver`; call out that compatibility boundary when version skew is involved.

## Recovery guidance

Inspect the current lease:

```text
spectre input-lock status --json
```

Normal recovery uses the exact current ID and a meaningful reason:

```text
spectre input-lock revoke --lease <observed-id> --reason <text>
```

Use `--force` only when normal cleanup cannot complete and native input may overlap.
The result reports `unsafeTakeover=true`. It does not kill the holder or prove input stopped.
Do not use a parsed PID as the normal recovery method.

## Release status

The coordinator ships as Experimental on macOS, Windows with native `AF_UNIX`, and Linux
Xorg/Xvfb. Linux Wayland real Robot input is not claimed. Do not call the API stable or default-on
until cross-platform headed smoke and a later graduation decision say so.
