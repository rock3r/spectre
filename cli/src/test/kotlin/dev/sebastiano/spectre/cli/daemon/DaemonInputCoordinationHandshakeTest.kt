package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.AttachInputCoordination
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A running daemon keeps the coordination mode it booted with, exactly as it keeps its frame
 * budget, so `-Ddev.sebastiano.spectre.agent.inputCoordination=disabled` on a later invocation
 * cannot reach it.
 *
 * This matters more than it sounds, in both directions.
 *
 * Going *into* the hatch: it is something you reach for **after** an attach has already failed,
 * which means a daemon is already running with the mode it was started under. Forwarding the
 * property only when this invocation happens to launch the daemon would make the documented
 * recovery silently do nothing on the one journey it exists for.
 *
 * Coming *out* of it is the sharper case, because nothing else catches it: a user who removes the
 * temporary property still has the disabled daemon, and it goes on attaching every new target
 * uncoordinated. The daemon's own "coordination is DISABLED" line goes to a startup log that
 * `DaemonProcessLauncher` deletes once it is up, so that user is told nothing at all — the precise
 * silent degradation #472 exists to remove. Hence "asked for nothing" means `Required` here, unlike
 * the frame budget.
 *
 * Both were caught by Codex reviewing #474.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
class DaemonInputCoordinationHandshakeTest {

    @Test
    fun `no explicit request means the documented default, not whatever is running`() {
        // NOT the frame-budget rule, and this is the one place the analogy breaks. A budget is a
        // capacity setting, so "asked for nothing" genuinely means "any daemon will do".
        // Coordination is a safety property: asking for nothing means asking for the documented
        // default, which is Required. Accepting a still-disabled daemon here would leave every
        // later attach uncoordinated after the user had already taken the opt-out back off.
        assertNull(inputCoordinationMismatchFailure(requested = null, daemonMode = "required"))
        assertNotNull(inputCoordinationMismatchFailure(requested = null, daemonMode = "disabled"))
    }

    @Test
    fun `removing the opt-out is refused while the disabled daemon is still up`() {
        // The #472 recovery journey in reverse, and the failure mode that makes it matter: the
        // daemon's stderr is redirected to a startup log that is deleted once it starts, so the
        // "coordination is DISABLED" announcement never reaches this user. Without this check the
        // daemon would go on attaching every new target uncoordinated, silently -- which is the
        // exact thing #472 exists to not do.
        val failure =
            assertNotNull(
                inputCoordinationMismatchFailure(requested = null, daemonMode = "disabled")
            )

        assertTrue(
            failure.contains("spectre daemon kill"),
            "should say how to get the default back: $failure",
        )
        assertTrue(
            failure.contains("${AttachInputCoordination.PROPERTY}=disabled"),
            "the keep-it-off switch must carry its value or it cannot be followed: $failure",
        )
    }

    @Test
    fun `every switch the message suggests is one that would actually work`() {
        // A bare `-Dproperty` with no value sets it to the empty string, which is blank, which is
        // "no request", which resolves to Required -- the same mismatch again. Advice that loops
        // back to the error it is attached to is worse than no advice.
        for (daemonMode in listOf("disabled", "required")) {
            val failure =
                inputCoordinationMismatchFailure(
                    requested =
                        if (daemonMode == "disabled") AttachInputCoordination.Required
                        else AttachInputCoordination.Disabled,
                    daemonMode = daemonMode,
                )
            val message = assertNotNull(failure)
            val switches =
                message.split(' ', '\n').filter {
                    it.startsWith("-D${AttachInputCoordination.PROPERTY}")
                }
            assertTrue(switches.isNotEmpty(), "no switch suggested at all: $message")
            switches.forEach { suggestion ->
                assertTrue(
                    suggestion.trimEnd('.', ',').contains('='),
                    "suggested a valueless switch that resolves to no request: $suggestion",
                )
            }
        }
    }

    @Test
    fun `a daemon too old to report its mode is accepted when nothing was asked`() {
        // Daemons that predate the setting predate the opt-out too, so they are coordinated by
        // construction. Nothing to warn about, and failing here would break every older daemon.
        assertNull(inputCoordinationMismatchFailure(requested = null, daemonMode = null))
    }

    @Test
    fun `a daemon too old to report its mode satisfies an explicit required request`() {
        // Same construction argument, so rejecting this was inconsistent with the branch's own
        // reasoning: the daemon predates the opt-out, which is exactly what `required` asks for.
        // Only an explicit `disabled` needs a restart against one of these.
        assertNull(
            inputCoordinationMismatchFailure(
                requested = AttachInputCoordination.Required,
                daemonMode = null,
            )
        )
    }

    @Test
    fun `an explicit request the daemon already satisfies is accepted`() {
        assertNull(
            inputCoordinationMismatchFailure(
                requested = AttachInputCoordination.Disabled,
                daemonMode = "disabled",
            )
        )
        assertNull(
            inputCoordinationMismatchFailure(
                requested = AttachInputCoordination.Required,
                daemonMode = "required",
            )
        )
    }

    @Test
    fun `asking to disable coordination on a coordinated daemon is refused`() {
        val failure =
            assertNotNull(
                inputCoordinationMismatchFailure(
                    requested = AttachInputCoordination.Disabled,
                    daemonMode = "required",
                )
            )

        assertTrue(
            failure.contains(AttachInputCoordination.PROPERTY),
            "should name the switch that cannot take effect: $failure",
        )
        assertTrue(failure.contains("disabled"), "should name what was asked for: $failure")
        assertTrue(failure.contains("required"), "should name what the daemon runs: $failure")
        assertTrue(
            failure.contains("spectre daemon kill"),
            "should say how to make the mode stick: $failure",
        )
    }

    @Test
    fun `asking to restore coordination on a disabled daemon is refused`() {
        // The dangerous direction. A daemon left over from a recovery session attaches every new
        // target uncoordinated; a user who has since put the switch back must not be told nothing.
        val failure =
            assertNotNull(
                inputCoordinationMismatchFailure(
                    requested = AttachInputCoordination.Required,
                    daemonMode = "disabled",
                )
            )

        assertTrue(failure.contains("spectre daemon kill"), failure)
    }

    @Test
    fun `a daemon too old to report its mode cannot satisfy an explicit request`() {
        val failure =
            assertNotNull(
                inputCoordinationMismatchFailure(
                    requested = AttachInputCoordination.Disabled,
                    daemonMode = null,
                )
            )

        assertTrue(
            failure.contains("predates", ignoreCase = true),
            "should say the daemon is too old to answer: $failure",
        )
        assertTrue(failure.contains("spectre daemon kill"), failure)
    }

    @Test
    fun `shutdown is exempt so the documented recovery is not a dead end`() {
        // `spectre daemon kill` inherits the same -D and would hit this very check, leaving the
        // user told to run a command that cannot run. Same exemption the frame budget needed.
        assertTrue(ignoresInputCoordination(DaemonRequest.Shutdown))
        assertTrue(!ignoresInputCoordination(DaemonRequest.ListSessions))
    }

    // ---- what counts as an explicit request ----

    @Test
    fun `an unset or blank property is not a request`() {
        assertNull(AttachInputCoordination.requestedFromProperty(null))
        assertNull(AttachInputCoordination.requestedFromProperty("   "))
    }

    @Test
    fun `a set property is a request, including a typo`() {
        // A typo still resolves to Required, and saying so against a Disabled daemon is right:
        // the user meant to say something about coordination and did not get what they typed.
        assertTrue(
            AttachInputCoordination.requestedFromProperty("disabled") ==
                AttachInputCoordination.Disabled
        )
        assertTrue(
            AttachInputCoordination.requestedFromProperty("required") ==
                AttachInputCoordination.Required
        )
        assertTrue(
            AttachInputCoordination.requestedFromProperty("disabledd") ==
                AttachInputCoordination.Required
        )
    }
}
