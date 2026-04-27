package dev.sebastiano.spectre.sample.scenarios

import androidx.compose.runtime.Composable

/**
 * One picker entry in the sample's scenario list.
 *
 * Each scenario exercises a specific Spectre v1 capability: popup discovery, focus traversal,
 * scrollable trees, HiDPI coordinate mapping, multi-window tracking, and so on. The [testTag] is
 * the stable identifier the validation passes (#7, #8, #12, #14) navigate by.
 */
data class Scenario(
    /** Display name shown in the picker. */
    val title: String,
    /** Stable testTag used by automation — must not change between releases. */
    val testTag: String,
    /** Human-readable note pointing at the issues this scenario unblocks. */
    val unblocks: String,
    /** Composable rendering the scenario's UI. */
    val content: @Composable () -> Unit,
)
