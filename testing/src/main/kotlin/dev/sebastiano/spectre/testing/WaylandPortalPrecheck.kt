package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.recording.portal.WaylandPortalAccess
import dev.sebastiano.spectre.recording.portal.WaylandPortalAccessResult
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stops a JUnit run on Linux Wayland from opening the portal's Share dialog by itself.
 *
 * Unattended, a dialog that a capture opens times out and fails that test, and the next capture
 * opens another one. So the first Spectre test in the JVM sets the consent prompt to `never`
 * (unless the build already chose a value): a missing grant then fails every capture and real input
 * at once, with the `spectre permissions request` fix in the message. It also prints that fix once
 * when the grant is missing. Tests that never capture or type are not affected.
 */
internal object WaylandPortalPrecheck {
    private val done = AtomicBoolean(false)

    fun ensureOnce() {
        if (!done.compareAndSet(false, true)) return
        run(
            isApplicable = WaylandPortalAccess::isApplicable,
            preflight = WaylandPortalAccess::preflight,
            getProperty = System::getProperty,
            setProperty = { key, value -> System.setProperty(key, value) },
            getenv = System::getenv,
            warn = System.err::println,
        )
    }

    @Suppress("LongParameterList") // Every collaborator is a seam the precheck tests substitute.
    internal fun run(
        isApplicable: () -> Boolean,
        preflight: () -> WaylandPortalAccessResult,
        getProperty: (String) -> String?,
        setProperty: (String, String) -> Unit,
        getenv: (String) -> String?,
        warn: (String) -> Unit,
    ) {
        if (!isApplicable()) return
        if (
            getProperty(CONSENT_PROMPT_PROPERTY).isNullOrBlank() &&
                getenv(CONSENT_PROMPT_ENV).isNullOrBlank()
        ) {
            setProperty(CONSENT_PROMPT_PROPERTY, "never")
        }
        val result = preflight()
        if (!result.granted) {
            warn(
                "Spectre: this Linux Wayland session has no portal grant, so screen capture and " +
                    "real input fail at once in this test run instead of opening the Share " +
                    "dialog. ${result.guidance}"
            )
        }
    }
}

/** Mirrors `WAYLAND_CONSENT_PROMPT_PROPERTY` in `:recording` (internal there). */
internal const val CONSENT_PROMPT_PROPERTY: String = "spectre.wayland.consentPrompt"

/** Mirrors `WAYLAND_CONSENT_PROMPT_ENV` in `:recording` (internal there). */
internal const val CONSENT_PROMPT_ENV: String = "SPECTRE_WAYLAND_CONSENT_PROMPT"
