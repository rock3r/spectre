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
        assertTrue(message.contains("spectre permissions request"), message)
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

    @Test
    fun `a capture whose own budget runs out leaves the open dialog to the next capture`() {
        val helper = PendingProcess()
        val factory = SupplyingProcessFactory { helper }
        val client =
            WaylandSessionClient(
                helperExtractor = extractor(),
                envLookup = sessionDirEnv(),
                processFactory = factory,
                // A visual-idle sample's bounded worker is interrupted while it waits.
                waitForSocket = { _, _ -> throw InterruptedException("sample budget exceeded") },
                consentPrompt = { WaylandConsentPrompt.ALLOW },
            )

        assertFailsWith<InterruptedException> { client.send(PING) }
        assertFailsWith<InterruptedException> { client.send(PING) }

        assertEquals(1, factory.starts, "the next capture must wait on the same dialog")
        assertTrue(helper.isAlive, "the pending helper and its dialog must stay up")
    }

    @Test
    fun `a dialog that times out after its capture gave up is still remembered`() {
        val helper = PendingProcess()
        val factory = SupplyingProcessFactory { helper }
        var interrupt = true
        val client =
            WaylandSessionClient(
                helperExtractor = extractor(),
                envLookup = sessionDirEnv(),
                processFactory = factory,
                waitForSocket = { _, _ ->
                    if (interrupt) {
                        interrupt = false
                        throw InterruptedException("sample budget exceeded")
                    }
                    false
                },
                consentPrompt = { WaylandConsentPrompt.ALLOW },
            )

        assertFailsWith<InterruptedException> { client.send(PING) }
        helper.finish(WAYLAND_SESSION_CONSENT_EXIT)

        val first = assertFailsWith<WaylandConsentNotGrantedException> { client.send(PING) }
        assertFailsWith<WaylandConsentNotGrantedException> { client.send(PING) }
        assertEquals(1, factory.starts, "no new helper, so no new dialog")
        assertTrue(first.message.orEmpty().contains("spectre permissions request"), first.message)
    }

    @Test
    fun `a run that may not prompt fails before starting a helper when no grant is stored`() {
        val factory = RecordingProcessFactory(exitCode = 0)
        val tokens = Files.createDirectories(tempDir.resolve("tokens"))
        val client =
            client(
                factory,
                env =
                    sessionDirEnv(extra = mapOf("SPECTRE_WAYLAND_RESTORE_TOKEN_DIR" to "$tokens")),
                prompt = WaylandConsentPrompt.NEVER,
            )

        val error = assertFailsWith<WaylandConsentNotGrantedException> { client.send(PING) }

        assertEquals(0, factory.starts)
        val message = error.message.orEmpty()
        assertTrue(message.contains("spectre permissions request"), message)
        assertTrue(message.contains(tokens.toString()), message)
    }

    @Test
    fun `a run that may not prompt tells the helper not to prompt either`() {
        val factory = RecordingProcessFactory(exitCode = WAYLAND_SESSION_CONSENT_EXIT)
        val tokens = Files.createDirectories(tempDir.resolve("tokens"))
        Files.writeString(tokens.resolve(remoteDesktopTokenFileName(RD_SESSION_TOKEN_KEY)), "tok")
        val client =
            client(
                factory,
                env =
                    sessionDirEnv(extra = mapOf("SPECTRE_WAYLAND_RESTORE_TOKEN_DIR" to "$tokens")),
                prompt = WaylandConsentPrompt.NEVER,
            )

        val error = assertFailsWith<WaylandConsentNotGrantedException> { client.send(PING) }

        assertEquals("never", factory.environments.single()[WAYLAND_CONSENT_PROMPT_ENV])
        assertTrue(error.message.orEmpty().contains("spectre permissions request"), error.message)
    }

    @Test
    fun `an attended request may prompt and waits longer for each dialog step`() {
        val factory = RecordingProcessFactory(exitCode = WAYLAND_SESSION_CONSENT_EXIT)
        val client = client(factory, sessionDirEnv(), prompt = WaylandConsentPrompt.NEVER)

        assertFailsWith<WaylandConsentNotGrantedException> {
            client.openSessionForRequest(consentTimeoutSeconds = 300)
        }

        val environment = factory.environments.single()
        assertEquals("300", environment[WAYLAND_CONSENT_TIMEOUT_ENV])
        assertTrue(WAYLAND_CONSENT_PROMPT_ENV !in environment, environment.toString())
    }

    @Test
    fun `an attended request ignores an earlier consent failure in this JVM`() {
        val factory = RecordingProcessFactory(exitCode = WAYLAND_SESSION_CONSENT_EXIT)
        val client = client(factory, sessionDirEnv())

        assertFailsWith<WaylandConsentNotGrantedException> { client.send(PING) }
        assertFailsWith<WaylandConsentNotGrantedException> { client.openSessionForRequest(300) }

        assertEquals(2, factory.starts)
    }

    private fun sessionDirEnv(extra: Map<String, String> = emptyMap()): (String) -> String? {
        val values =
            extra + ("SPECTRE_WAYLAND_SESSION_DIR" to tempDir.resolve("session").toString())
        return values::get
    }

    private fun extractor(): WaylandHelperBinaryExtractor =
        WaylandHelperBinaryExtractor(
            envLookup = { null },
            resourceLocator = { ByteArrayInputStream(ByteArray(0)) },
            targetDirProvider = { tempDir.resolve("helper") },
            archProvider = { "x86_64" },
        )

    private fun client(
        factory: WaylandSessionClient.SessionProcessFactory,
        env: (String) -> String?,
        prompt: WaylandConsentPrompt = WaylandConsentPrompt.ALLOW,
    ): WaylandSessionClient =
        WaylandSessionClient(
            helperExtractor = extractor(),
            envLookup = env,
            processFactory = factory,
            waitForSocket = { _, _ -> false },
            consentPrompt = { prompt },
        )

    private class SupplyingProcessFactory(private val supply: () -> Process) :
        WaylandSessionClient.SessionProcessFactory {
        var starts = 0

        override fun startSession(
            helperPath: Path,
            extraEnvironment: Map<String, String>,
        ): Process {
            starts += 1
            return supply()
        }
    }

    /** A helper still showing its dialog until [finish]. */
    private class PendingProcess : Process() {
        @Volatile private var code: Int? = null

        fun finish(exitCode: Int) {
            code = exitCode
        }

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = code ?: error("still running")

        override fun exitValue(): Int = code ?: throw IllegalThreadStateException("running")

        override fun isAlive(): Boolean = code == null

        override fun destroy() = Unit
    }

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
