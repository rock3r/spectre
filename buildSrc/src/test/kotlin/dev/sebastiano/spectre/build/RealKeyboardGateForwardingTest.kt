package dev.sebastiano.spectre.build

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit coverage for the real-keyboard opt-in forwarding used by the `:agent`, `:server`, and
 * `:testing` test tasks (#444, #449).
 *
 * Gradle's CLI `-P` form never reaches a forked Test worker on its own, so the build has to
 * translate it into a `-D` on the worker command line. If the two spellings drift apart the
 * translation silently stops working: the gate falls back to the `CI` environment variable, so CI
 * keeps passing while `-Pspectre.agent.realKeyboard=true` quietly does nothing on a developer
 * machine and in the release smoke scripts.
 */
class RealKeyboardGateForwardingTest {

    @Test
    fun `a blank property forwards nothing`() {
        // No `-P` and no daemon `-D`: the worker sees no property and RealKeyboardGate falls back
        // to the CI environment variable, which is the documented default.
        assertEquals(emptyList<String>(), realKeyboardJvmArgs(""))
        assertEquals(emptyList<String>(), realKeyboardJvmArgs("   "))
    }

    @Test
    fun `a set property is forwarded to the worker as a system property`() {
        assertEquals(
            listOf("-D$REAL_KEYBOARD_SYSTEM_PROPERTY=true"),
            realKeyboardJvmArgs("true"),
        )
        // `false` must forward too — that is how CI turns the keyboard paths off.
        assertEquals(
            listOf("-D$REAL_KEYBOARD_SYSTEM_PROPERTY=false"),
            realKeyboardJvmArgs("false"),
        )
    }

    @Test
    fun `the forwarded names match the gate the test JVM actually reads`() {
        // RealKeyboardGate lives in `:testing`, which buildSrc cannot depend on. Read its source
        // so a rename on either side fails here instead of silently disabling the opt-in.
        val gateSource =
            repoRoot()
                .resolve(
                    "testing/src/main/kotlin/dev/sebastiano/spectre/testing/contract/" +
                        "RealKeyboardGate.kt"
                )
                .readText()
        assertTrue(
            gateSource.contains("ENABLE_PROP: String = \"$REAL_KEYBOARD_SYSTEM_PROPERTY\""),
            "RealKeyboardGate.ENABLE_PROP no longer matches REAL_KEYBOARD_SYSTEM_PROPERTY " +
                "($REAL_KEYBOARD_SYSTEM_PROPERTY)",
        )
        assertTrue(
            gateSource.contains("GRADLE_PROPERTY: String = \"$REAL_KEYBOARD_GRADLE_PROPERTY\""),
            "RealKeyboardGate.GRADLE_PROPERTY no longer matches REAL_KEYBOARD_GRADLE_PROPERTY " +
                "($REAL_KEYBOARD_GRADLE_PROPERTY)",
        )
    }

    private fun repoRoot(): File {
        var candidate: File? = File("").absoluteFile
        while (candidate != null) {
            if (File(candidate, "settings.gradle.kts").isFile && File(candidate, "testing").isDirectory) {
                return candidate
            }
            candidate = candidate.parentFile
        }
        error("Could not locate the Spectre repo root from ${File("").absolutePath}")
    }
}
