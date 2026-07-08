@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

class AttachOptionsTest {
    // ---- base-dir selection (pure, platform-agnostic) ----

    @Test
    fun `udsBaseDir uses the JVM temp dir on Windows`() {
        assertEquals(
            "C:\\Users\\x\\AppData\\Local\\Temp",
            AttachOptions.udsBaseDir("Windows 11", "C:\\Users\\x\\AppData\\Local\\Temp"),
        )
    }

    @Test
    fun `udsBaseDir uses slash tmp on POSIX`() {
        assertEquals("/tmp", AttachOptions.udsBaseDir("Linux", "/var/folders/ignored"))
        assertEquals("/tmp", AttachOptions.udsBaseDir("Mac OS X", "/var/folders/ignored"))
    }

    // ---- default path structure (platform-agnostic) ----

    @Test
    fun `defaultUdsPath ends in the per-attach dir plus agent socket`() {
        val p = AttachOptions.defaultUdsPath(1234L)
        assertEquals("agent.sock", p.fileName.toString())
        assertTrue(
            p.parent.fileName.toString().startsWith("sp-a-1234-"),
            "per-attach dir should be sp-a-<pid>-<uuid>, got ${p.parent.fileName}",
        )
    }

    // ---- real path per platform ----

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `defaultUdsPath is absolute and under the JVM temp dir on Windows`() {
        val p = AttachOptions.defaultUdsPath(1234L)
        assertTrue(p.isAbsolute, "UDS path must be absolute on Windows, got $p")
        assertTrue(
            p.startsWith(Path.of(System.getProperty("java.io.tmpdir"))),
            "UDS path must live under java.io.tmpdir (%TEMP%) on Windows, got $p",
        )
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `defaultUdsPath is under slash tmp on POSIX`() {
        val p = AttachOptions.defaultUdsPath(1234L)
        assertTrue(p.startsWith(Path.of("/tmp")), "UDS path must live under /tmp on POSIX, got $p")
    }
}
