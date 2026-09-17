package dev.sebastiano.spectre.recording.portal

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WaylandSessionOwnershipTest {
    @Test
    fun `rejected restore token is retried once then fails closed`() {
        val cleared = mutableListOf<String>()
        val error =
            assertFailsWith<IllegalStateException> {
                restoreRemoteDesktopGrant(
                    tokenKey = "rd-monitor-embedded",
                    loadToken = { "stale-token" },
                    clearToken = { cleared += it },
                    startWithToken = { token ->
                        if (token != null) {
                            Result.failure(
                                IllegalStateException(
                                    "SelectDevices rejected (response code 2): restore_token"
                                )
                            )
                        } else {
                            Result.failure(IllegalStateException("interactive also failed"))
                        }
                    },
                )
            }
        assertEquals(listOf("rd-monitor-embedded"), cleared)
        assertTrue(error.message.orEmpty().contains("interactive"))
        assertTrue(error.message.orEmpty().contains("restore_token"))
    }

    @Test
    fun `rejected restore token retries interactively once and keeps the new grant`() {
        var used: String? = "stale"
        val result =
            restoreRemoteDesktopGrant(
                tokenKey = "rd-monitor-embedded",
                loadToken = { used },
                clearToken = { used = null },
                startWithToken = { token ->
                    if (token != null) {
                        Result.failure(
                            IllegalStateException(
                                "SelectDevices rejected (response code 2): restore_token"
                            )
                        )
                    } else {
                        Result.success("new-grant")
                    }
                },
            )
        assertEquals("new-grant", result)
        assertEquals(null, used)
    }

    @Test
    fun `session directory is spectre-owned not the AWT robot store`() {
        val dir = Files.createTempDirectory("spectre-wayland-session-test-")
        try {
            val paths = waylandSessionPaths(dir)
            assertEquals(dir.resolve("wayland-session.lock"), paths.lock)
            assertEquals(dir.resolve("wayland-session.sock"), paths.socket)
            assertFalsePathContainsJavaRobot(paths.lock.toString())
            assertFalsePathContainsJavaRobot(paths.socket.toString())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `stale session socket is unlinked so a new helper can bind`() {
        val dir = Files.createTempDirectory("spectre-wayland-stale-socket-")
        try {
            val paths = waylandSessionPaths(dir)
            Files.createFile(paths.socket)
            var started = 0
            val resolved =
                resolveWaylandSessionSocket(
                    paths = paths,
                    socketIsLive = { false },
                    startHelper = {
                        started += 1
                        Files.deleteIfExists(paths.socket)
                        Files.createFile(paths.socket)
                    },
                    waitForSocket = { path, _ -> Files.exists(path) },
                    timeoutMs = 1_000,
                )
            assertEquals(1, started)
            assertEquals(paths.socket, resolved)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `helper that exits before binding fails closed instead of waiting out the timeout`() {
        val dir = Files.createTempDirectory("spectre-wayland-helper-exit-")
        try {
            val paths = waylandSessionPaths(dir)
            val error =
                assertFailsWith<IllegalStateException> {
                    resolveWaylandSessionSocket(
                        paths = paths,
                        socketIsLive = { false },
                        startHelper = {},
                        waitForSocket = { _, _ -> false },
                        timeoutMs = 5_000,
                        helperExited = { true },
                        helperExitDetail = { "exited with 1" },
                    )
                }
            assertTrue(error.message.orEmpty().contains("exited with 1"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun assertFalsePathContainsJavaRobot(path: String) {
        assertTrue(!path.contains(".java/robot"), path)
        assertTrue(!path.contains("robot.properties"), path)
    }
}
