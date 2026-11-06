@file:OptIn(ExperimentalComposeUiApi::class, ComposeToolingApi::class)

package dev.sebastiano.spectre.core

import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsOwner
import java.awt.Window

/**
 * True when [this] is one of Compose Desktop's own top-level hosts: a `ComposeWindow` (a `JFrame`,
 * behind `Window { }`) or a `ComposeDialog` (a `JDialog`, behind `DialogWindow { }`, #547).
 *
 * Both hold their content in a private `ComposeWindowPanel` field named `composePanel`, which is
 * not a `ComposePanel`, so a component walk for `ComposePanel`s never finds their content.
 *
 * This matches the concrete classes rather than `ComposeDesktopEntryPoint`: that interface only
 * exists since Compose Multiplatform 1.12.0, and an injected Spectre runs against the target app's
 * Compose version.
 */
internal fun Window.isComposeHostWindow(): Boolean = this is ComposeWindow || this is ComposeDialog

/**
 * The semantics owners of a Compose host window (see [isComposeHostWindow]), or `null` when [this]
 * is not one.
 */
internal fun Window.composeHostSemanticsOwners(): Collection<SemanticsOwner>? =
    when (this) {
        is ComposeWindow -> semanticsOwners
        is ComposeDialog -> composeDialogSemanticsOwners(this)
        else -> null
    }

private fun composeDialogSemanticsOwners(dialog: ComposeDialog): Collection<SemanticsOwner> =
    try {
        dialog.semanticsOwners
    } catch (_: NoSuchMethodError) {
        // `ComposeDialog.semanticsOwners` is public only since Compose Multiplatform 1.12.0. An
        // injected Spectre can run against an older Compose, so read the private host panel.
        OverlayLayerInspector.readHostWindowSemanticsOwners(dialog)
    }
