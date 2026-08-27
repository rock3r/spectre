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

Losing the coordinator session while a lease is held does not prove that native input has stopped.
The coordinator fences that holder and keeps later work queued until the original lease cleanup
acknowledges release. If the process died or cleanup cannot acknowledge, inspect the `revoking`
holder and use exact-ID forced recovery only after accepting the overlap risk described below.

If recovery-state persistence temporarily prevents a lease from being released, `close()` throws
`InputCoordinatorException` with `RECOVERY_PERSISTENCE_FAILED`. The client keeps the coordinator
session live and retries that exact release request in the background; closing the client waits for
that acknowledgement instead of discarding the only safe cleanup path. Repair the persistence
problem rather than forcing recovery while the owner can still acknowledge cleanup safely.

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

## When the coordinator cannot be reached {#coordinator-unreachable}

`Required` never degrades — that is what it is for. Every capability that touches shared OS state
(`focusWindow`, `click`, `typeText`, `pasteText`, …) fails when no coordinator answers, and
[agent attach](agent.md) always uses `Required`, so on that path a broken coordinator takes down
the whole input surface at once.

The failure names what it measured and what you can do about it:

```text
Input coordinator connection failed: Input coordinator did not become ready at …. Spectre could
not reach the desktop input coordinator (COORDINATOR_IO), and this driver runs with
InputLeasePolicy.Required, which fails rather than continuing uncoordinated. …
```

First **fix the coordinator**, because that keeps the guarantee:

- Check `spectre-input-coordinator-server` and its dependencies are on `java.class.path` —
  a `COORDINATOR_PROVIDER_MISSING` code means the artifact is simply absent.
- Run `spectre input-lock status --json` to see whether a coordinator is up and who holds the
  desktop, and recover a stuck lease as described below.
- Run the command from `CoordinatorProcessLauncher.command()` by hand to keep the child JVM's
  stderr, which automatic launches discard.

If the coordinator is genuinely unusable and you need to make progress anyway, opt out
**explicitly**. In-process, construct the driver as `RobotDriver(InputLeasePolicy.Off)`. On the
attach path, either set the system property on the **attaching** JVM:

```text
-Ddev.sebastiano.spectre.agent.inputCoordination=disabled
```

or say it in code:

```kotlin
AgentAttach.attach(pid, AttachOptions(inputCoordination = AttachInputCoordination.Disabled))
```

The property is the channel that reaches the CLI, which attaches without `AttachOptions`. Only the
exact word `disabled` opts out — unset, blank, `true`, and a misspelling all keep coordination on,
so this cannot be tripped by accident. Both the attacher and the target print a line to stderr for
as long as it is in force.

!!! important "A running daemon keeps the mode it booted with"
    `spectre` forwards the property to a daemon it *starts*, but a daemon is long-lived and shared,
    and it resolves the mode once — every target it injects inherits that. Since you only reach for
    this switch after an attach has already failed, there is almost always a daemon already running
    without it. Kill it first:

    ```text
    spectre daemon kill
    ```

    You do not have to remember: a mode that cannot take effect is refused at the handshake with a
    message naming that command. If you launch the daemon yourself, pass the property through
    `JAVA_TOOL_OPTIONS`.

    **Taking the opt-out back off needs the same restart**, and Spectre insists on it. Removing the
    property does not re-coordinate a daemon that is already running disabled; it would go on
    attaching every new target uncoordinated, and its own warning goes to a startup log that is
    deleted once it is up, so nothing would tell you. Commands are refused until you restart it —
    running without the property means asking for the default, and Spectre will not quietly give
    you something else.

!!! danger "Opting out removes the mutual exclusion, it does not repair it"
    Coordination is what stops two Spectre processes driving the same mouse and keyboard at the
    same time. With it off nothing does, and two runs interleaving real input produce failures that
    look like anything but their cause. Use this only when you know your coordinator is broken
    **and** you know nothing else is automating this desktop, and take it back out afterwards.

`Auto` is deliberately not the answer here. It degrades for exactly two error codes —
`COORDINATOR_PROVIDER_MISSING` and `COORDINATOR_SESSION_UNAVAILABLE` — and a coordinator that
cannot be reached reports `COORDINATOR_IO`, so `Auto` would fail this case anyway while weakening
every other one.

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
