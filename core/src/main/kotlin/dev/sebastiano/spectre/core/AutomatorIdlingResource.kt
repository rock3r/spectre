package dev.sebastiano.spectre.core

/**
 * Asynchronous work the automator should wait for before considering the UI idle.
 *
 * Implementations report `isIdleNow = false` while a relevant background activity (network call,
 * data load, animation managed outside Compose, etc.) is in flight. `waitForIdle` polls every
 * registered resource and only treats the UI as idle once they all report `true` and the UI
 * fingerprint has remained stable for the configured quiet period.
 *
 * `diagnosticMessage()` is included in [IdleTimeoutException] when a wait times out, so it should
 * describe the in-flight work in a way that helps a developer narrow down the cause.
 */
interface AutomatorIdlingResource {

    val isIdleNow: Boolean

    fun diagnosticMessage(): String? = null
}
