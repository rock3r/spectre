package dev.sebastiano.spectre.recording

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

/** Runtime-directory and D-Bus fallbacks for environments that scrub them, such as Bazel (#635). */
@DisabledOnOs(
    OS.WINDOWS,
    disabledReason = "Linux runtime-dir fallback: needs unix:uid ownership and Unix path strings",
)
class LinuxSessionEnvironmentTest {

    @Test
    fun `an exported XDG_RUNTIME_DIR wins over the per-user fallback`() {
        var uidLookups = 0
        val dir =
            resolveXdgRuntimeDir(
                getenv = env("XDG_RUNTIME_DIR" to "/custom/runtime"),
                currentUid = {
                    uidLookups += 1
                    UID
                },
                isOwnedDirectory = { _, _ -> true },
            )
        assertEquals(Path.of("/custom/runtime"), dir)
        assertEquals(0, uidLookups)
    }

    @Test
    fun `a missing XDG_RUNTIME_DIR falls back to the owned per-user runtime dir`() {
        val dir =
            resolveXdgRuntimeDir(
                getenv = env(),
                currentUid = { UID },
                isOwnedDirectory = { path, uid -> path == RUN_USER && uid == UID },
            )
        assertEquals(RUN_USER, dir)
    }

    @Test
    fun `a blank XDG_RUNTIME_DIR is treated as missing`() {
        val dir =
            resolveXdgRuntimeDir(
                getenv = env("XDG_RUNTIME_DIR" to " "),
                currentUid = { UID },
                isOwnedDirectory = { _, _ -> true },
            )
        assertEquals(RUN_USER, dir)
    }

    @Test
    fun `no fallback when the per-user runtime dir is missing or owned by someone else`() {
        assertNull(
            resolveXdgRuntimeDir(
                getenv = env(),
                currentUid = { UID },
                isOwnedDirectory = { _, _ -> false },
            )
        )
    }

    @Test
    fun `no fallback when the current uid is unknown`() {
        assertNull(
            resolveXdgRuntimeDir(
                getenv = env(),
                currentUid = { null },
                isOwnedDirectory = { _, _ -> true },
            )
        )
    }

    @Test
    fun `session env lookup fills XDG_RUNTIME_DIR and passes other keys through`() {
        val lookup =
            linuxSessionEnvLookup(
                getenv = env("DISPLAY" to ":0"),
                currentUid = { UID },
                isOwnedDirectory = { _, _ -> true },
                fileExists = { false },
            )
        assertEquals(RUN_USER.toString(), lookup("XDG_RUNTIME_DIR"))
        assertEquals(":0", lookup("DISPLAY"))
        assertNull(lookup("WAYLAND_DISPLAY"))
    }

    @Test
    fun `session env lookup derives the D-Bus address from the runtime dir bus socket`() {
        val lookup =
            linuxSessionEnvLookup(
                getenv = env(),
                currentUid = { UID },
                isOwnedDirectory = { _, _ -> true },
                fileExists = { it == RUN_USER.resolve("bus") },
            )
        assertEquals("unix:path=/run/user/1000/bus", lookup("DBUS_SESSION_BUS_ADDRESS"))
    }

    @Test
    fun `session env lookup keeps an exported D-Bus address`() {
        val lookup =
            linuxSessionEnvLookup(
                getenv = env("DBUS_SESSION_BUS_ADDRESS" to "unix:path=/elsewhere/bus"),
                currentUid = { UID },
                isOwnedDirectory = { _, _ -> true },
                fileExists = { true },
            )
        assertEquals("unix:path=/elsewhere/bus", lookup("DBUS_SESSION_BUS_ADDRESS"))
    }

    @Test
    fun `session env lookup leaves the D-Bus address unset without a bus socket`() {
        val lookup =
            linuxSessionEnvLookup(
                getenv = env(),
                currentUid = { UID },
                isOwnedDirectory = { _, _ -> true },
                fileExists = { false },
            )
        assertNull(lookup("DBUS_SESSION_BUS_ADDRESS"))
    }

    @Test
    fun `helper environment carries the resolved runtime dir and D-Bus address`() {
        val lookup =
            linuxSessionEnvLookup(
                getenv = env(),
                currentUid = { UID },
                isOwnedDirectory = { _, _ -> true },
                fileExists = { true },
            )
        assertEquals(
            mapOf(
                "XDG_RUNTIME_DIR" to "/run/user/1000",
                "DBUS_SESSION_BUS_ADDRESS" to "unix:path=/run/user/1000/bus",
            ),
            waylandHelperEnvironment(lookup),
        )
    }

    @Test
    fun `helper environment is empty when nothing can be resolved`() {
        val lookup =
            linuxSessionEnvLookup(
                getenv = env(),
                currentUid = { null },
                isOwnedDirectory = { _, _ -> true },
                fileExists = { true },
            )
        assertEquals(emptyMap(), waylandHelperEnvironment(lookup))
    }

    @Test
    fun `ownership check accepts a directory owned by the given uid only`(@TempDir dir: Path) {
        val owner = (Files.getAttribute(dir, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Int).toLong()
        assertTrue(isDirectoryOwnedBy(dir, owner))
        assertFalse(isDirectoryOwnedBy(dir, owner + 1))
        assertFalse(isDirectoryOwnedBy(dir.resolve("missing"), owner))
    }

    @Test
    fun `ownership check rejects a symlink to an owned directory`(@TempDir dir: Path) {
        val target = Files.createDirectory(dir.resolve("target"))
        val link = Files.createSymbolicLink(dir.resolve("link"), target)
        val owner = (Files.getAttribute(dir, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Int).toLong()
        assertFalse(isDirectoryOwnedBy(link, owner))
    }

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    private companion object {
        const val UID: Long = 1000
        val RUN_USER: Path = Path.of("/run/user/1000")
    }
}
