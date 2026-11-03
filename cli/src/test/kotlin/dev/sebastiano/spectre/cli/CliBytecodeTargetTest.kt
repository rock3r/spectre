package dev.sebastiano.spectre.cli

import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The CLI and its daemon run on JDK 21+ (JDK 25 only adds the Leyden AOT cache), so the CLI's own
 * classes must stay loadable by a JDK 21 JVM even though the module builds and tests on the JDK 25
 * toolchain.
 */
class CliBytecodeTargetTest {
    @Test
    fun `CLI entry points compile to Java 21 bytecode`() {
        for (className in ENTRY_POINT_CLASSES) {
            val resource = className.replace('.', '/') + ".class"
            val stream = javaClass.classLoader.getResourceAsStream(resource)
            assertNotNull(stream, "$resource is not on the test classpath")
            val majorVersion =
                DataInputStream(stream).use { input ->
                    input.readInt() // magic
                    input.readUnsignedShort() // minor
                    input.readUnsignedShort()
                }
            assertEquals(JAVA_21_CLASS_FILE_MAJOR, majorVersion, "class file version of $resource")
        }
    }

    private companion object {
        const val JAVA_21_CLASS_FILE_MAJOR = 65

        val ENTRY_POINT_CLASSES =
            listOf(
                "dev.sebastiano.spectre.cli.bootstrap.SpectreBootstrap",
                "dev.sebastiano.spectre.cli.bootstrap.AotCache",
                "dev.sebastiano.spectre.cli.SpectreCliKt",
                "dev.sebastiano.spectre.cli.daemon.DaemonMainKt",
            )
    }
}
