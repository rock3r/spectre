package dev.sebastiano.spectre.inject

/**
 * Marker type packaged into the inject-runtime jar so tooling can detect the inject payload
 * independently of `:core` classes (which live in the same jar after shadowing).
 *
 * The class has no behaviour — bootstrap loads `dev.sebastiano.spectre.core.ComposeAutomator` from
 * this jar via [dev.sebastiano.spectre.agent.runtime.SpectreInjectClassLoader].
 */
public object InjectRuntimeMarker
