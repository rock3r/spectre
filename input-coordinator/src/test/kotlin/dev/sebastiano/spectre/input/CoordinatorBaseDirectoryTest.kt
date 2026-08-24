@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * #462: on Windows the coordinator socket used to live directly under `%LOCALAPPDATA%`. On hosts
 * with a filesystem filter over the AppData tree, an AF_UNIX socket file created there survives
 * both a clean close and process exit, and afterwards can neither be deleted nor re-bound — one
 * coordinator run permanently bricked the machine's attach input path. The per-user temporary
 * directory does not have that problem, and it is what every other platform already uses.
 */
class CoordinatorBaseDirectoryTest {

    private val windowsEnvironment =
        mapOf(
            "TEMP" to "C:\\Users\\someone\\AppData\\Local\\Temp",
            "TMP" to "C:\\Users\\someone\\AppData\\Local\\Temp",
            "LOCALAPPDATA" to "C:\\Users\\someone\\AppData\\Local",
        )

    @Test
    fun `Windows puts the coordinator under the temporary directory`() {
        val base =
            LocalCoordinatorEnvironment.baseDirectory(
                platform = DesktopPlatform.WINDOWS,
                environment = windowsEnvironment,
                javaTemporaryDirectory = "C:\\ignored",
            )

        assertEquals(Path.of("C:\\Users\\someone\\AppData\\Local\\Temp"), base)
    }

    @Test
    fun `Windows never places the coordinator directly in the AppData tree`() {
        // Regression guard for #462: reintroducing an AppData-root base would re-brick the affected
        // hosts, and the failure only shows up after the first coordinator shutdown.
        val base =
            LocalCoordinatorEnvironment.baseDirectory(
                platform = DesktopPlatform.WINDOWS,
                environment = windowsEnvironment,
                javaTemporaryDirectory = "C:\\ignored",
            )

        val normalized = base.toString().replace('\\', '/').trimEnd('/')
        assertFalse(
            normalized.endsWith("/AppData/Local") || normalized.endsWith("/AppData/Roaming"),
            "Windows coordinator base must not be an AppData root, was $base",
        )
    }

    @Test
    fun `the environment wins over a per-JVM java io tmpdir override`() {
        // Every process on one desktop must derive the same directory, or two of them would
        // coordinate against different coordinators and both believe they own the desktop.
        // `java.io.tmpdir` is settable with -D per JVM (Gradle does this for test workers), so it
        // cannot be the primary source.
        val base =
            LocalCoordinatorEnvironment.baseDirectory(
                platform = DesktopPlatform.WINDOWS,
                environment = windowsEnvironment,
                javaTemporaryDirectory = "C:\\some\\gradle\\worker\\tmp",
            )

        assertEquals(Path.of("C:\\Users\\someone\\AppData\\Local\\Temp"), base)
    }

    @Test
    fun `Windows falls back to the LOCALAPPDATA temp directory when TEMP is unset`() {
        val base =
            LocalCoordinatorEnvironment.baseDirectory(
                platform = DesktopPlatform.WINDOWS,
                environment = mapOf("LOCALAPPDATA" to "C:\\Users\\someone\\AppData\\Local"),
                javaTemporaryDirectory = "C:\\ignored",
            )

        assertEquals(Path.of("C:\\Users\\someone\\AppData\\Local\\Temp"), base)
    }

    @Test
    fun `Windows falls back to java io tmpdir only when the environment says nothing`() {
        val base =
            LocalCoordinatorEnvironment.baseDirectory(
                platform = DesktopPlatform.WINDOWS,
                environment = emptyMap(),
                javaTemporaryDirectory = "C:\\fallback\\tmp",
            )

        assertEquals(Path.of("C:\\fallback\\tmp"), base)
    }

    @Test
    fun `an unresolvable Windows temporary directory is rejected loudly`() {
        assertFailsWith<IllegalArgumentException> {
            LocalCoordinatorEnvironment.baseDirectory(
                platform = DesktopPlatform.WINDOWS,
                environment = emptyMap(),
                javaTemporaryDirectory = "",
            )
        }
    }

    @Test
    fun `POSIX platforms keep using tmp`() {
        assertEquals(
            Path.of("/tmp"),
            LocalCoordinatorEnvironment.baseDirectory(
                DesktopPlatform.LINUX,
                windowsEnvironment,
                "/ignored",
            ),
        )
        assertEquals(
            Path.of("/tmp"),
            LocalCoordinatorEnvironment.baseDirectory(
                DesktopPlatform.MACOS,
                windowsEnvironment,
                "/ignored",
            ),
        )
    }

    @Test
    fun `the default Windows endpoint does not resolve into an AppData root`() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return

        val endpoint = LocalCoordinatorEnvironment.defaultEndpoint()

        val parent = endpoint.directory.parent.toString().replace('\\', '/').trimEnd('/')
        assertFalse(
            parent.endsWith("/AppData/Local") || parent.endsWith("/AppData/Roaming"),
            "coordinator directory must not sit in an AppData root, was ${endpoint.directory}",
        )
    }
}
