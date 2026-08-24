@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #462: on Windows the coordinator socket used to live directly under `%LOCALAPPDATA%`. On hosts
 * with a filesystem filter over the AppData tree, an AF_UNIX socket file created there survives
 * both a clean close and process exit, and afterwards can neither be connected to, deleted, nor
 * re-bound — one coordinator run permanently bricked the machine's attach input path.
 *
 * The endpoint therefore moves one level down into `%LOCALAPPDATA%\Temp`, which does not have that
 * problem. It deliberately does *not* move to `%TEMP%`: this directory decides coordinator
 * identity, so it has to be stable across every process on the desktop and has to stay inside the
 * user's own ACL boundary.
 */
class CoordinatorBaseDirectoryTest {

    private val localAppData = "C:\\Users\\someone\\AppData\\Local"

    /**
     * Composed the way the production code composes it. A literal `"C:\\…\\Local\\Temp"` would only
     * be equal on Windows: elsewhere `Path.of` does not treat a backslash as a separator, so the
     * literal is a single element while the composed path is two, and these tests would fail on the
     * Linux and macOS CI legs.
     */
    private val expectedBase = Path.of(localAppData, "Temp")

    /** A host whose TEMP points somewhere shared and outside the user profile. */
    private val windowsEnvironment =
        mapOf(
            "TEMP" to "C:\\Windows\\Temp",
            "TMP" to "C:\\Windows\\Temp",
            "LOCALAPPDATA" to localAppData,
        )

    private fun windowsBase(
        environment: Map<String, String> = windowsEnvironment,
        javaTemporaryDirectory: String = "C:\\ignored",
    ): Path =
        LocalCoordinatorEnvironment.baseDirectory(
            platform = DesktopPlatform.WINDOWS,
            environment = environment,
            javaTemporaryDirectory = javaTemporaryDirectory,
        )

    @Test
    fun `Windows puts the coordinator under the LOCALAPPDATA temp directory`() {
        assertEquals(expectedBase, windowsBase())
    }

    @Test
    fun `Windows never places the coordinator directly in the AppData tree`() {
        // Regression guard for #462: an AppData-root base re-bricks the affected hosts, and the
        // failure only shows up after the first coordinator shutdown.
        val normalized = windowsBase().toString().replace('\\', '/').trimEnd('/')

        assertFalse(
            normalized.endsWith("/AppData/Local") || normalized.endsWith("/AppData/Roaming"),
            "Windows coordinator base must not be an AppData root, was $normalized",
        )
    }

    @Test
    fun `TEMP and TMP are ignored so one desktop cannot grow two coordinators`() {
        // The election lock lives in this directory. If two processes on one desktop derived
        // different directories they would each elect themselves and hand out simultaneous leases
        // for the same desktop. TEMP/TMP are routinely overridden per process (build wrappers, CI
        // harnesses, service hosts), so they cannot decide coordinator identity; LOCALAPPDATA is a
        // Known Folder fixed at logon.
        assertEquals(
            expectedBase,
            windowsBase(javaTemporaryDirectory = "C:\\some\\gradle\\worker\\tmp"),
        )
    }

    @Test
    fun `a process-overridden TEMP does not move the coordinator`() {
        val fromDesktop = windowsBase()
        val fromWrapperWithItsOwnTemp =
            windowsBase(environment = windowsEnvironment + mapOf("TEMP" to "D:\\build\\scratch"))

        assertEquals(fromDesktop, fromWrapperWithItsOwnTemp)
    }

    @Test
    fun `the coordinator stays inside the per-user profile for its ACLs`() {
        // Windows uses BasicOwnerOnlyEndpointProtection, which never tightens a directory ACL, so
        // the endpoint must inherit one from a location that is already owner-only. A shared TEMP
        // such as C:\Windows\Temp would expose the socket, election lock, and recovery ledger to
        // other OS users on the machine.
        val base = windowsBase()

        assertTrue(
            base.startsWith(Path.of(localAppData)),
            "coordinator base must stay under the user's LOCALAPPDATA, was $base",
        )
    }

    @Test
    fun `Windows falls back to java io tmpdir only when LOCALAPPDATA is unset`() {
        assertEquals(
            Path.of("C:\\fallback\\tmp"),
            windowsBase(environment = emptyMap(), javaTemporaryDirectory = "C:\\fallback\\tmp"),
        )
    }

    @Test
    fun `an unresolvable Windows temporary directory is rejected loudly`() {
        assertFailsWith<IllegalArgumentException> {
            windowsBase(environment = emptyMap(), javaTemporaryDirectory = "")
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
