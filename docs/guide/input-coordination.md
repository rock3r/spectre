# Experimental desktop input coordination

!!! warning "Experimental, opt-in API"
    Cooperative desktop input coordination ships as an experimental preview. Its API and runtime
    protocol may change in any release. Opt in with
    `ExperimentalSpectreInputCoordinationApi`; the no-argument `RobotDriver()` remains
    uncoordinated during this rollout.

Real desktop input is shared state. Two test JVMs can move the same pointer, type into whichever
window currently owns focus, and overwrite the same system clipboard. JUnit's in-process locks do
not cover separate Gradle forks, CLI processes, or attached target JVMs.

Spectre's coordinator gives participating local processes a FIFO, fenced lease for one normalised
user/desktop identity. It is cooperative rather than an OS input grab: it does not block people,
older Spectre versions, or other automation tools.

## Install and opt in

`spectre-core` contains the client integration. A core-only application that selects `Auto` or
`Required` must also put the process-launching runtime on its runtime classpath:

```kotlin
dependencies {
    testImplementation("dev.sebastiano.spectre:spectre-core:<version>")
    testRuntimeOnly("dev.sebastiano.spectre:spectre-input-coordinator-server:<version>")
}
```

`spectre-testing`, the CLI, and the attaching side of `spectre-agent` already bring the
coordinator server runtime. Low-level integrations may compile against
`spectre-input-coordinator`, but most tests should use the core or JUnit entry points.

The experimental launcher starts a dedicated JVM with the client process's
`java.class.path`. Thin `java -jar` launchers and module-path-only applications must make the
coordinator server and its dependencies visible on that classpath. Automatic launches discard
the child process's output; if startup times out, run the command returned by
`CoordinatorProcessLauncher.command()` directly to retain its diagnostics.

The marker is warning-level: code still compiles without `@OptIn`, but every experimental call
site produces a compiler warning.

```kotlin
@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.InputLeasePolicy
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi

val driver = RobotDriver(InputLeasePolicy.Required)
try {
    val automator = ComposeAutomator.inProcess(driver)
    // Use automator.
} finally {
    driver.close()
}
```

`RobotDriver` is `AutoCloseable`. `Auto` and `Required` open their coordinator session lazily;
close the driver (prefer `use { ... }` when its lifetime is block-scoped) to stop heartbeats and let
an idle coordinator exit. JUnit isolation and attached-agent teardown close their owned sessions
automatically.

## Choose the narrowest useful mode

| Need | Recommended mode |
| --- | --- |
| No real OS input or shared clipboard | `RobotDriver.synthetic(rootWindow)`; no coordinator |
| Real input, fail if coordination cannot start | `InputLeasePolicy.Required` |
| Coordinate when the runtime is present | `InputLeasePolicy.Auto` |
| An external harness already serialises the desktop | `InputLeasePolicy.Off` |
| One JUnit lease around setup, body, evidence, and teardown | `InputIsolationConfig.perTest()` |

`Auto` and `Required` are explicit choices. The no-argument `RobotDriver()` preserves the existing
uncoordinated behaviour. Changing that default requires the full cross-platform release-smoke
evidence and a separate stability decision.

`Auto` never blocks the AWT event-dispatch thread to establish a new coordinator session. If no
session is already connected, an EDT operation proceeds uncoordinated; use `Required`, acquire an
exclusive scope off the EDT, or use JUnit per-test isolation when that fallback is unacceptable.

For a multi-step transaction, hold one reentrant lease so another process cannot run between the
steps:

```kotlin
import dev.sebastiano.spectre.core.InputLeaseOptions

automator.withExclusiveInput(InputLeaseOptions(ownerLabel = "submits checkout")) {
    focusWindow(email)
    click(email)
    typeText("alice@example.com")
    click(submit)
    automator.waitForIdle()
}
```

Automatic leases cover real pointer, keyboard, focus, and system-clipboard work. Semantics reads,
waits, screenshots, and recording do not acquire automatically. Put a visibility-sensitive
capture inside `withExclusiveInput` when it must not race another client's input.

## JUnit 4 and JUnit 5

Whole-test isolation is available on both in-process wrappers:

```kotlin
@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

import dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi
import dev.sebastiano.spectre.testing.ComposeAutomatorExtension
import dev.sebastiano.spectre.testing.InputIsolationConfig
import org.junit.jupiter.api.extension.RegisterExtension

@JvmField
@RegisterExtension
val automatorExt =
    ComposeAutomatorExtension(
        inputIsolation = InputIsolationConfig.perTest(),
    )
```

`PerTest` acquires before the default/custom factory and releases after failure evidence, video
finalisation, and teardown. JUnit 4 wraps the complete `Statement.evaluate()` lifecycle. JUnit 5
stores the lease per invocation, so parameter-injected automators remain parallel-safe.

`LaunchAndAttachExtension` and `LaunchAndAttachRule` intentionally do not expose a test-JVM
`PerTest` lease. Input is dispatched and coordinated in the attached target JVM; holding a second
lease in the test JVM would self-deadlock.

## Inspect and recover a stuck lease

Control commands observe an existing coordinator and never launch one:

```text
spectre input-lock status
spectre input-lock status --json
spectre input-lock revoke --lease <observed-id> --reason <text>
spectre input-lock revoke --lease <observed-id> --reason <text> --force
```

Revoke is compare-and-revoke. Copy the exact current lease ID from `status`; a stale ID cannot
revoke a newer holder. Normal revoke fences the owner and allows its cleanup path to finish.

!!! danger "Forced recovery can overlap native input"
    `--force` is an explicit unsafe recovery escape hatch. It records the reason and returns
    `unsafeTakeover=true`, but it cannot prove an in-flight native call has stopped and never kills
    the holder process. Use it only after inspecting the exact current lease, allowing a reasonable
    cleanup interval, and deciding that restoring progress is worth possible overlapping input.

Coordinator restart enters recovery quarantine instead of assuming the desktop is free. Corrupt
or ambiguous recovery state quarantines conservatively. Restart quarantine never expires
automatically because Spectre cannot prove that predecessor native input has stopped; it remains
closed until an operator makes an exact-ID forced recovery decision.

## Platform and trust boundaries

- macOS uses one conservative per-user console identity.
- Windows requires native `AF_UNIX` support and uses the current logon session when available.
- Linux Xorg/Xvfb normalises equivalent local `DISPLAY` spellings.
- Linux Wayland real Robot input is not claimed; use synthetic input. Socket-backed identities are
  canonicalized where possible.
- POSIX endpoints enforce mode 0700 on the directory and 0600 on the socket. On Windows, the
  coordinator uses the current user's `LOCALAPPDATA` and inherits that directory's ACL; this layer
  rejects path substitution but does not rewrite the Windows ACL. Labels are diagnostic
  attribution, not authentication; typed text, clipboard contents, selectors, credentials, and
  prompts are never recorded.

Coordination remains **Experimental** until headed two-process smoke records FIFO contention,
holder crash, exact revoke, forced recovery, and JUnit parallelism on macOS, Windows, and Linux
Xorg/Xvfb. Graduation or making `Auto` the default requires that evidence plus real-world feedback
without unresolved recovery or lifecycle defects.
