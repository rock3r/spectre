package dev.sebastiano.spectre.recording.portal

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Session helper start-up: consent memo (#634) and runtime-dir environment (#635). */
class WaylandSessionClientTest {

    @TempDir lateinit var tempDir: Path

    @Test
    fun `a consent timeout or denial is not prompted again in the same client`() {
        val factory = RecordingProcessFactory(exitCode = WAYLAND_SESSION_CONSENT_EXIT)
        val client = client(factory, sessionDirEnv())

        val first = assertFailsWith<WaylandConsentNotGrantedException> { client.send(PING) }
        val second = assertFailsWith<WaylandConsentNotGrantedException> { client.send(PING) }

        assertEquals(1, factory.starts, "the second capture must not start a new helper")
        assertEquals(first.message, second.message)
        assertSame(first, second.cause)
        val message = first.message.orEmpty()
        assertTrue(message.contains("Remember"), message)
        assertTrue(message.contains("Share"), message)
        assertTrue(message.contains("new JVM"), message)
    }

    @Test
    fun `a helper crash is reported again on the next capture`() {
        val factory = RecordingProcessFactory(exitCode = 1)
        val client = client(factory, sessionDirEnv())

        repeat(2) {
            val error = assertFailsWith<IllegalStateException> { client.send(PING) }
            assertTrue(error !is WaylandConsentNotGrantedException, error.toString())
            assertTrue(error.message.orEmpty().contains("exit 1"), error.message)
        }
        assertEquals(2, factory.starts)
    }

    @Test
    fun `a missing helper is reported again on the next capture`() {
        var extractions = 0
        val factory = RecordingProcessFactory(exitCode = 0)
        val client =
            WaylandSessionClient(
                helperExtractor =
                    WaylandHelperBinaryExtractor(
                        envLookup = { null },
                        resourceLocator = {
                            extractions += 1
                            null
                        },
                        targetDirProvider = { tempDir.resolve("helper") },
                        archProvider = { "x86_64" },
                    ),
                envLookup = sessionDirEnv(),
                processFactory = factory,
                waitForSocket = { _, _ -> false },
            )

        repeat(2) { assertFailsWith<IllegalStateException> { client.send(PING) } }
        assertEquals(2, extractions)
        assertEquals(0, factory.starts)
    }

    @Test
    fun `helper gets the resolved runtime dir and D-Bus address`() {
        val runtimeDir = Files.createDirectory(tempDir.resolve("runtime"))
        val factory = RecordingProcessFactory(exitCode = 1)
        val client =
            client(
                factory,
                env =
                    mapOf(
                        "XDG_RUNTIME_DIR" to runtimeDir.toString(),
                        "DBUS_SESSION_BUS_ADDRESS" to "unix:path=$runtimeDir/bus",
                    )::get,
            )

        assertFailsWith<IllegalStateException> { client.send(PING) }

        assertEquals(
            mapOf(
                "XDG_RUNTIME_DIR" to runtimeDir.toString(),
                "DBUS_SESSION_BUS_ADDRESS" to "unix:path=$runtimeDir/bus",
            ),
            factory.environments.single(),
        )
        assertTrue(Files.isDirectory(runtimeDir), "session dir parent must stay intact")
    }

    @Test
    fun `missing runtime dir names every way to provide one`() {
        val factory = RecordingProcessFactory(exitCode = 0)
        val client = client(factory, env = { null })

        val error = assertFailsWith<IllegalStateException> { client.send(PING) }

        val message = error.message.orEmpty()
        assertTrue(message.contains("XDG_RUNTIME_DIR"), message)
        assertTrue(message.contains("SPECTRE_WAYLAND_SESSION_DIR"), message)
        assertTrue(message.contains("/run/user/"), message)
        assertEquals(0, factory.starts)
    }

    @Test
    fun `consent memo is cleared when another process brings the session socket up`() {
        val memo = WaylandSessionConsentMemo()
        val socket = tempDir.resolve("wayland-session.sock")
        val denied = WaylandConsentNotGrantedException("denied")
        var starts = 0
        val start: () -> Path = {
            starts += 1
            throw denied
        }

        assertFailsWith<WaylandConsentNotGrantedException> {
            memo.resolve(socket, socketIsLive = { false }, start = start)
        }
        assertFailsWith<WaylandConsentNotGrantedException> {
            memo.resolve(socket, socketIsLive = { false }, start = start)
        }
        assertEquals(1, starts)

        Files.createFile(socket)
        assertEquals(socket, memo.resolve(socket, socketIsLive = { true }, start = start))
        assertEquals(1, starts, "a live socket must be used without starting a helper")

        Files.delete(socket)
        assertFailsWith<WaylandConsentNotGrantedException> {
            memo.resolve(socket, socketIsLive = { false }, start = start)
        }
        assertEquals(2, starts, "after a live session the memo must not replay the old failure")
    }

    @Test
    fun `consent memo is keyed by session socket`() {
        val memo = WaylandSessionConsentMemo()
        var starts = 0
        val start: () -> Path = {
            starts += 1
            throw WaylandConsentNotGrantedException("denied")
        }
        assertFailsWith<WaylandConsentNotGrantedException> {
            memo.resolve(tempDir.resolve("a.sock"), socketIsLive = { false }, start = start)
        }
        assertFailsWith<WaylandConsentNotGrantedException> {
            memo.resolve(tempDir.resolve("b.sock"), socketIsLive = { false }, start = start)
        }
        assertEquals(2, starts)
    }

    private fun sessionDirEnv(): (String) -> String? {
        val dir = tempDir.resolve("session").toString()
        return { if (it == "SPECTRE_WAYLAND_SESSION_DIR") dir else null }
    }

    private fun client(
        factory: RecordingProcessFactory,
        env: (String) -> String?,
    ): WaylandSessionClient =
        WaylandSessionClient(
            helperExtractor =
                WaylandHelperBinaryExtractor(
                    envLookup = { null },
                    resourceLocator = { ByteArrayInputStream(ByteArray(0)) },
                    targetDirProvider = { tempDir.resolve("helper") },
                    archProvider = { "x86_64" },
                ),
            envLookup = env,
            processFactory = factory,
            waitForSocket = { _, _ -> false },
        )

    private class RecordingProcessFactory(private val exitCode: Int) :
        WaylandSessionClient.SessionProcessFactory {
        var starts = 0
        val environments = mutableListOf<Map<String, String>>()

        override fun startSession(
            helperPath: Path,
            extraEnvironment: Map<String, String>,
        ): Process {
            starts += 1
            environments += extraEnvironment
            return ExitedProcess(exitCode)
        }
    }

    private class ExitedProcess(private val code: Int) : Process() {
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = code

        override fun exitValue(): Int = code

        override fun isAlive(): Boolean = false

        override fun destroy() = Unit
    }

    private companion object {
        val PING: Command = Command.PointerMove(1, 1)
    }
}
