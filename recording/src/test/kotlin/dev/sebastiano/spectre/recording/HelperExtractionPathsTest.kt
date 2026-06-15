package dev.sebastiano.spectre.recording

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class HelperExtractionPathsTest {

    @Test
    fun `macOS helpers default under Application Support`() {
        val dir =
            HelperExtractionPaths.defaultHelperDir(
                helperName = "spectre-screencapture",
                osName = "Mac OS X",
                userHome = "/Users/alice",
                getenv = { null },
            )

        assertEquals(
            Path.of(
                "/Users/alice",
                "Library",
                "Application Support",
                "spectre",
                "helpers",
                "spectre-screencapture",
            ),
            dir,
        )
    }

    @Test
    fun `Windows helpers default under LOCALAPPDATA`() {
        val dir =
            HelperExtractionPaths.defaultHelperDir(
                helperName = "spectre-window-capture",
                osName = "Windows 11",
                userHome = "C:/Users/Alice",
                getenv = { key ->
                    if (key == "LOCALAPPDATA") "C:/Users/Alice/AppData/Local" else null
                },
            )

        assertEquals(
            Path.of("C:/Users/Alice/AppData/Local", "spectre", "helpers", "spectre-window-capture"),
            dir,
        )
    }

    @Test
    fun `Windows helpers fall back to user profile local app data`() {
        val dir =
            HelperExtractionPaths.defaultHelperDir(
                helperName = "spectre-window-capture",
                osName = "Windows 11",
                userHome = "C:/Users/Alice",
                getenv = { null },
            )

        assertEquals(
            Path.of(
                "C:/Users/Alice",
                "AppData",
                "Local",
                "spectre",
                "helpers",
                "spectre-window-capture",
            ),
            dir,
        )
    }

    @Test
    fun `Linux helpers default under XDG cache home`() {
        val dir =
            HelperExtractionPaths.defaultHelperDir(
                helperName = "spectre-wayland-helper",
                osName = "Linux",
                userHome = "/home/alice",
                getenv = { key -> if (key == "XDG_CACHE_HOME") "/var/cache/alice" else null },
            )

        assertEquals(
            Path.of("/var/cache/alice", "spectre", "helpers", "spectre-wayland-helper"),
            dir,
        )
    }

    @Test
    fun `Linux helpers fall back to dot cache`() {
        val dir =
            HelperExtractionPaths.defaultHelperDir(
                helperName = "spectre-wayland-helper",
                osName = "Linux",
                userHome = "/home/alice",
                getenv = { null },
            )

        assertEquals(
            Path.of("/home/alice", ".cache", "spectre", "helpers", "spectre-wayland-helper"),
            dir,
        )
    }
}
