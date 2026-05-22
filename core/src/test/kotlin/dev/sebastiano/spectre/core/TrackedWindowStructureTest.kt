@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Structural assertions on [TrackedWindow]. These run in any environment (no AWT initialisation
 * required), unlike the equality tests in [TrackedWindowTest] which depend on live `JFrame`
 * instances and are gated by `liveAwtAvailable` guards.
 */
class TrackedWindowStructureTest {

    @Test
    fun `TrackedWindow constructors contain no function-typed parameters`() {
        // Function-typed parameters (e.g. a semantics-owners accessor lambda) break data class
        // equality because Kotlin generates equals/hashCode over every primary-constructor
        // parameter and lambdas use reference equality. That would cause StateFlow's
        // distinctUntilChanged to re-emit on every WindowTracker refresh for any session that
        // hosts an overlay popup, since rediscovery produces a fresh lambda each time. Keep
        // TrackedWindow a pure description; any per-surface accessors live elsewhere.
        val offenders =
            TrackedWindow::class.java.declaredConstructors.flatMap { ctor ->
                ctor.parameterTypes
                    .withIndex()
                    .filter { (_, type) -> kotlin.Function::class.java.isAssignableFrom(type) }
                    .map { (index, type) -> "constructor ${ctor.name}#param$index: ${type.name}" }
            }
        assertTrue(
            offenders.isEmpty(),
            "TrackedWindow constructors must not declare function-typed parameters, " +
                "but found: $offenders",
        )
    }
}
