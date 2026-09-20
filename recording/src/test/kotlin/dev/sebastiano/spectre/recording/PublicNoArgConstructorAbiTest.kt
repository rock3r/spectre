package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.portal.WaylandPortalRecorder
import dev.sebastiano.spectre.recording.portal.WaylandPortalWindowRecorder
import dev.sebastiano.spectre.recording.screencapturekit.ScreenCaptureKitRecorder
import dev.sebastiano.spectre.recording.screencapturekit.ScreenCaptureKitScreenshotter
import dev.sebastiano.spectre.recording.windows.WindowsGraphicsCaptureRecorder
import dev.sebastiano.spectre.recording.windows.WindowsWindowScreenshotter
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Kotlin 2.4's `abiValidation` dump omits some explicit public no-arg secondary constructors (they
 * still exist in bytecode). This reflection check is the tripwire so deleting a backend no-arg ctor
 * still fails `check`.
 */
class PublicNoArgConstructorAbiTest {

    @Test
    fun `recording backends keep a public no-arg constructor`() {
        val backends =
            listOf(
                LinuxNativeScreenshotter::class.java,
                LinuxX11Recorder::class.java,
                WaylandPortalRecorder::class.java,
                WaylandPortalWindowRecorder::class.java,
                ScreenCaptureKitRecorder::class.java,
                ScreenCaptureKitScreenshotter::class.java,
                WindowsGraphicsCaptureRecorder::class.java,
                WindowsWindowScreenshotter::class.java,
            )
        for (type in backends) {
            assertPublicNoArgConstructor(type)
        }
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
