package dev.sebastiano.spectre.cli.hotreload

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HotReloadPortDiscoveryTest {
    @Test
    fun `pid file properties yield orchestration port`() {
        val content =
            """
            #pid file
            pid=12345
            orchestration.port=45678
            """
                .trimIndent()
        assertEquals(45678, HotReloadPortDiscovery.parsePortFromPidFileProperties(content))
    }

    @Test
    fun `pid file without port yields null`() {
        assertNull(HotReloadPortDiscovery.parsePortFromPidFileProperties("pid=1\n"))
    }

    @Test
    fun `pid file rejects invalid orchestration ports`() {
        assertNull(HotReloadPortDiscovery.parsePortFromPidFileProperties("orchestration.port=0\n"))
        assertNull(
            HotReloadPortDiscovery.parsePortFromPidFileProperties("orchestration.port=70000\n")
        )
    }

    @Test
    fun `jvm args dashD port property is discovered`() {
        val found =
            HotReloadPortDiscovery.parsePortFromJvmArgs(
                listOf("-Xmx1g", "-Dcompose.reload.orchestration.port=19191", "Main")
            )
        assertEquals(19191, found?.port)
        assertIs<HotReloadPortSource.SystemProperty>(found?.source)
    }

    @Test
    fun `jvm args bare port property is discovered`() {
        val found =
            HotReloadPortDiscovery.parsePortFromJvmArgs(
                listOf("compose.reload.orchestration.port=2222")
            )
        assertEquals(2222, found?.port)
    }

    @Test
    fun `invalid port values are rejected`() {
        assertNull(
            HotReloadPortDiscovery.parsePortFromJvmArgs(
                listOf("-Dcompose.reload.orchestration.port=0")
            )
        )
        assertNull(
            HotReloadPortDiscovery.parsePortFromJvmArgs(
                listOf("-Dcompose.reload.orchestration.port=70000")
            )
        )
        assertNull(
            HotReloadPortDiscovery.parsePortFromJvmArgs(
                listOf("-Dcompose.reload.orchestration.port=nope")
            )
        )
    }

    @Test
    fun `pid file path is parsed from jvm args`() {
        val expected = java.nio.file.Path.of("tmp", "app.pid")
        val path =
            HotReloadPortDiscovery.parsePidFilePathFromJvmArgs(
                listOf("-Dcompose.reload.pidFile=${expected}")
            )
        assertEquals(expected, path)
    }

    @Test
    fun `discover reads explicit pid file`() {
        val dir = Files.createTempDirectory("spectre-hr-pid")
        val pidFile = dir.resolve("app.pid")
        Files.writeString(pidFile, "pid=99\norchestration.port=33333\n")
        val found =
            HotReloadPortDiscovery.discover(
                targetPid = 99L,
                processArguments = emptyList(),
                explicitPidFile = pidFile,
            )
        assertEquals(33333, found?.port)
        val source = assertIs<HotReloadPortSource.PidFile>(found?.source)
        assertTrue(source.path.endsWith("app.pid"))
    }

    @Test
    fun `discover falls back from args port before pid file`() {
        val found =
            HotReloadPortDiscovery.discover(
                targetPid = 1L,
                processArguments = listOf("-Dcompose.reload.orchestration.port=4444"),
            )
        assertEquals(4444, found?.port)
        assertIs<HotReloadPortSource.SystemProperty>(found?.source)
    }

    @Test
    fun `discover returns null when nothing is present`() {
        assertNull(
            HotReloadPortDiscovery.discover(targetPid = 1L, processArguments = listOf("MainKt"))
        )
    }
}
