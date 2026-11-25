package dev.sebastiano.spectre.agent.launch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LaunchOptionCompatibilityTest {
    @Test
    fun `extra JVM args fail for a non-JVM launcher`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                LaunchAndAttach.launch(
                    LaunchSpec(
                        command = listOf("definitely-not-a-command"),
                        extraJvmArgs = listOf("-Xmx1g"),
                    )
                )
            }
        assertTrue(error.message!!.contains("extraJvmArgs"))
    }

    @Test
    fun `classpath requirement fails when command form hides its classpath`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                LaunchAndAttach.launch(
                    LaunchSpec(
                        command = listOf("java", "-jar", "app.jar"),
                        requireSpectreCoreOnClasspath = true,
                    )
                )
            }
        assertTrue(error.message!!.contains("requireSpectreCoreOnClasspath"))
    }

    @Test
    fun `classpath requirement inspects classpath supplied by extra JVM args`() {
        val error =
            assertFailsWith<SpectreCoreMissingOnClasspathException> {
                LaunchAndAttach.launch(
                    LaunchSpec(
                        command = listOf("java", "example.Main"),
                        extraJvmArgs = listOf("-cp", "application.jar"),
                        requireSpectreCoreOnClasspath = true,
                    )
                )
            }

        assertEquals("application.jar", error.classpath)
    }
}
