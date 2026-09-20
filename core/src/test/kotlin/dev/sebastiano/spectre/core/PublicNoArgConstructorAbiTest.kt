@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Kotlin 2.4's `abiValidation` dump omits some explicit public no-arg secondary constructors (they
 * still exist in bytecode). This reflection check is the tripwire so deleting `RobotDriver()` /
 * `WindowTracker()` still fails `check` — see `docs/STABILITY.md`.
 */
class PublicNoArgConstructorAbiTest {

    @Test
    fun `RobotDriver and WindowTracker keep a public no-arg constructor`() {
        assertPublicNoArgConstructor(RobotDriver::class.java)
        assertPublicNoArgConstructor(WindowTracker::class.java)
    }

    private fun assertPublicNoArgConstructor(type: Class<*>) {
        val ctor = type.constructors.singleOrNull { it.parameterCount == 0 }
        assertNotNull(ctor, "${type.name} lost its public no-arg constructor")
        assertTrue(
            Modifier.isPublic(ctor.modifiers),
            "${type.name} no-arg constructor is not public",
        )
    }
}
