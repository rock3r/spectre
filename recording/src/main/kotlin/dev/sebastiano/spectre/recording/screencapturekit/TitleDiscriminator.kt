package dev.sebastiano.spectre.recording.screencapturekit

import java.util.UUID

/**
 * Stamps a unique `Spectre/<id>` suffix on a [TitledWindow]'s title for the duration of a
 * recording, so the Swift helper's window-discovery filter can find the right window even when the
 * host process owns multiple windows with the same name.
 *
 * Lifecycle:
 * 1. Construct against the target window. The discriminator [value] is computed once at this point
 *    and reused — multiple `apply()`/`restore()` cycles on a single instance use the same suffix.
 * 2. Call [apply] before starting the helper. The window's title becomes `"<original>
 *    <discriminator>"` (or just `<discriminator>` if the original was null/blank).
 * 3. Call [restore] after the helper exits. The original title is replaced verbatim, including
 *    restoring a null title to null rather than to empty string.
 *
 * Idempotency: both [apply] and [restore] are safe to call repeatedly. A second `apply` does not
 * double-suffix; a second `restore` is a no-op; a `restore` without a prior `apply` is a no-op.
 *
 * Threading: this class is **not** thread-safe. Callers must invoke `apply`/`restore` on the same
 * thread that owns the window's title (the EDT for AWT/Compose windows).
 */
internal class TitleDiscriminator(private val window: TitledWindow) {

    /**
     * The suffix the helper will match on via `--title-contains`. Stable for the lifetime of this
     * instance.
     */
    val value: String = "Spectre/" + UUID.randomUUID().toString().substring(0, ID_LENGTH)

    private var originalTitle: String? = null
    private var applied: Boolean = false

    /**
     * Mutates [window]'s title to embed [value]. Returns [value] for the convenience of the caller
     * that needs to pass it as the helper's `--title-contains` argument.
     */
    fun apply(): String {
        if (applied) return value
        originalTitle = window.title
        window.title = composeTitle(window.title, value)
        applied = true
        return value
    }

    /** Restores [window]'s title to whatever it was at the moment [apply] first ran. */
    fun restore() {
        if (!applied) return
        window.title = originalTitle
        applied = false
        originalTitle = null
    }

    private fun composeTitle(original: String?, suffix: String): String =
        if (original.isNullOrBlank()) suffix else "$original $suffix"

    private companion object {
        // 8 hex chars from a UUID = ~32 bits of entropy. More than enough to avoid collisions
        // between concurrent recordings on the same window; short enough that the title-bar
        // flicker stays barely-perceptible.
        const val ID_LENGTH: Int = 8
    }
}
