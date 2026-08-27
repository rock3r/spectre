package dev.sebastiano.spectre.agent

/**
 * Whether an attached target coordinates its use of the shared desktop, and the deliberate way to
 * say it should not (#472).
 *
 * The attach path drives real OS input into a process Spectre does not own. Coordination is the
 * mutual exclusion that keeps two Spectre runs from interleaving mouse and keyboard events on one
 * desktop — the failure mode behind #446, #447, #449 and #460 — so [Required] is the default and
 * stays the default. A coordinator it cannot reach fails the operation loudly rather than quietly
 * dropping the guarantee.
 *
 * [Disabled] exists because "loud" was, until #472, also "terminal": #462 wedged the coordinator on
 * a Windows host and every input verb on the attach path died with no way for the user to proceed.
 * Someone who knows their coordinator is broken and knows nothing else is driving the desktop needs
 * a way through. This is that way, and it is deliberately awkward:
 * - it is off unless [PROPERTY] holds exactly [DISABLE_VALUE], or [AttachOptions.inputCoordination]
 *   names it in code;
 * - nothing infers it — no timeout, no failure, and no "we could not reach the coordinator so we
 *   assume you meant this" ever selects it;
 * - both sides announce it on stderr while it is in force, so a session running uncoordinated says
 *   so in its log rather than looking like an ordinary one.
 *
 * **What you give up.** Nothing then stops a second Spectre process from driving the same mouse and
 * keyboard at the same time. Two runs interleaving real input produce failures that look like
 * anything but their cause, which is why this is an explicit choice and not a fallback.
 *
 * Not offered here: a best-effort middle ground. `InputLeasePolicy.Auto` degrades for exactly two
 * error codes — `COORDINATOR_PROVIDER_MISSING` and `COORDINATOR_SESSION_UNAVAILABLE` — and a wedged
 * coordinator is neither: the launching provider's startup timeout arrives as `COORDINATOR_IO`. So
 * `Auto` would hard-fail the very situation this exists for, while also weakening every case where
 * coordination is merely absent. Opting out means opting out.
 */
@ExperimentalSpectreAgentApi
public enum class AttachInputCoordination(
    internal val leasePolicyName: String,
    internal val wireValue: String,
) {
    /** Coordinate every shared-desktop capability; an unreachable coordinator is an error. */
    Required(leasePolicyName = "Required", wireValue = "required"),

    /** Drive the desktop without coordinating. Deliberate, announced, and never inferred. */
    Disabled(leasePolicyName = "Off", wireValue = "disabled");

    public companion object {
        /** System property read on the **attaching** JVM. See [fromProperty]. */
        public const val PROPERTY: String = "dev.sebastiano.spectre.agent.inputCoordination"

        /** The only value that opts out. Anything else means [Required]. */
        public const val DISABLE_VALUE: String = "disabled"

        /** The value that pins the default explicitly, for a caller who wants it in writing. */
        public const val REQUIRE_VALUE: String = "required"

        /**
         * Resolves [value] (by default [PROPERTY] on this JVM) to a coordination mode.
         *
         * Case and surrounding whitespace are forgiven; nothing else is. Unset, blank, a typo, and
         * an affirmative-looking `true` all mean [Required], because falling back *to* coordination
         * is the only safe direction: a boolean switch would put "stop policing the desktop" one
         * stray property away, and a mistyped opt-out that silently coordinated anyway would be
         * indistinguishable from a working one. A typo is not silent in practice either — the
         * failure the user still gets names the exact spelling that would have worked.
         *
         * Read on the attacher, not in the target: the target is somebody else's application, and
         * letting a property *it* happens to carry downgrade the attacher's coordination is exactly
         * the silent degradation this design refuses. The attacher's decision travels to the target
         * through `agentArgs` instead.
         */
        public fun fromProperty(
            value: String? = System.getProperty(PROPERTY)
        ): AttachInputCoordination =
            when (value?.trim()?.lowercase()) {
                DISABLE_VALUE -> Disabled
                REQUIRE_VALUE -> Required
                else -> Required
            }

        /** Reverses [AttachInputCoordination.wireValue] for the `agentArgs` field. */
        internal fun fromWireValue(value: String?): AttachInputCoordination =
            entries.firstOrNull { it.wireValue == value } ?: Required
    }
}
