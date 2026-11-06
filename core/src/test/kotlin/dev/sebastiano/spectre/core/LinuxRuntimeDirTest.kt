package dev.sebastiano.spectre.core

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Mirror of recording's runtime-dir fallback for Bazel-style scrubbed environments (#635). */
class LinuxRuntimeDirTest {

    @Test
    fun `an exported XDG_RUNTIME_DIR wins over the per-user fallback`() {
        assertEquals(
            Path.of("/custom/runtime"),
            resolveXdgRuntimeDir(
                getenv = env("XDG_RUNTIME_DIR" to "/custom/runtime"),
                currentUid = { UID },
                isOwnedDirectory = { _: Path, _: Long -> true },
            ),
        )
    }

    @Test
    fun `a missing or blank XDG_RUNTIME_DIR falls back to the owned per-user runtime dir`() {
        for (getenv in listOf(env(), env("XDG_RUNTIME_DIR" to ""))) {
            assertEquals(
                RUN_USER,
                resolveXdgRuntimeDir(
                    getenv = getenv,
                    currentUid = { UID },
                    isOwnedDirectory = { path: Path, uid: Long -> path == RUN_USER && uid == UID },
                ),
            )
        }
    }

    @Test
    fun `no fallback when the per-user runtime dir is not owned or the uid is unknown`() {
        assertNull(
            resolveXdgRuntimeDir(
                getenv = env(),
                currentUid = { UID },
                isOwnedDirectory = { _: Path, _: Long -> false },
            )
        )
        assertNull(
            resolveXdgRuntimeDir(
                getenv = env(),
                currentUid = { null },
                isOwnedDirectory = { _: Path, _: Long -> true },
            )
        )
    }

    @Test
    fun `runtime-dir-aware getenv fills only XDG_RUNTIME_DIR`() {
        val getenv =
            xdgRuntimeDirAwareGetenv(
                getenv = env("DISPLAY" to ":0"),
                currentUid = { UID },
                isOwnedDirectory = { _: Path, _: Long -> true },
            )
        assertEquals("/run/user/1000", getenv("XDG_RUNTIME_DIR"))
        assertEquals(":0", getenv("DISPLAY"))
        assertNull(getenv("WAYLAND_DISPLAY"))
    }

    @Test
    fun `session socket lookup uses the per-user runtime dir when XDG_RUNTIME_DIR is unset`() {
        val getenv =
            xdgRuntimeDirAwareGetenv(
                getenv = env(),
                currentUid = { UID },
                isOwnedDirectory = { _: Path, _: Long -> true },
            )
        assertEquals(
            RUN_USER.resolve("spectre").resolve("wayland-session.sock"),
            waylandSessionSocketFromEnv(getenv),
        )
    }

    @Test
    fun `ownership check accepts a directory owned by the given uid only`(@TempDir dir: Path) {
        val owner = ownerOf(dir)
        assertTrue(isDirectoryOwnedBy(dir, owner))
        assertFalse(isDirectoryOwnedBy(dir, owner + 1))
        assertFalse(isDirectoryOwnedBy(dir.resolve("missing"), owner))
        val link = Files.createSymbolicLink(dir.resolve("link"), dir)
        assertFalse(isDirectoryOwnedBy(link, owner))
    }

    private fun ownerOf(path: Path): Long =
        (Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Int).toLong()

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    private companion object {
        const val UID: Long = 1000
        val RUN_USER: Path = Path.of("/run/user/1000")
    }
}
