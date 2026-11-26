package dev.sebastiano.spectre.recording.portal

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir

/** Session helper start-up: consent memo (#634) and runtime-dir environment (#635). */
class WaylandSessionClientTest {

    @TempDir lateinit var tempDir: Path

    @Test
    fun `screenshot sends embedded cursor mode for a reused legacy session`() {
        var command: Command.Screenshot? = null
        val client =
            WaylandSessionClient(
                helperExtractor = extractor(),
                envLookup = sessionDirEnv(),
                processFactory = RecordingProcessFactory(exitCode = 0),
                waitForSocket = { _, _ -> false },
                screenshotSender = {
                    command = it as Command.Screenshot
                    ImageIO.write(
                        BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB),
                        "png",
                        Path.of(it.output).toFile(),
                    )
                    Event.ScreenshotSaved(
                        outputSizeBytes = Files.size(Path.of(it.output)),
                        captureRegion = listOf(0, 0, 2, 3),
                    )
                },
            )

        val image = client.screenshot(Rectangle(-10, 20, 2, 3))

        assertEquals(2, image.width)
        assertEquals(3, image.height)
        assertEquals(CursorMode.EMBEDDED, command?.cursorMode)
        val payload = Json.encodeToString<Command>(requireNotNull(command))
        val fields = Json.parseToJsonElement(payload).jsonObject.toMutableMap()
        fields.remove("screen_size")
        fields.remove("output")
        fields["screen_size"] = JsonArray(listOf(-100, 0, 100, 100).map(::JsonPrimitive))
        fields["output"] = JsonPrimitive("OUTPUT")
        assertEquals(
            javaClass.getResource("/protocol/v0.9/session-screenshot.json")!!.readText().trim(),
            JsonObject(fields).toString(),
        )
    }

    @Test
    fun `nonresponsive identity helper is forcibly reaped and all streams close`() {
        val process = IdentityProcess(interruptWait = false)
        val client = identityClient(process)

        val failure = assertFailsWith<IllegalStateException> { client.locateWindow(IDENTITY) }

        assertTrue(failure.message.orEmpty().contains("timed out"))
        assertTrue(process.destroyed)
        assertTrue(process.forciblyDestroyed)
        assertFalse(process.isAlive)
        assertTrue(process.allStreamsClosed)
    }

    @Test
    fun `interrupted identity wait preserves interruption while reaping the helper`() {
        val process = IdentityProcess(interruptWait = true)
        val client = identityClient(process)

        assertFailsWith<InterruptedException> { client.locateWindow(IDENTITY) }

        assertTrue(Thread.interrupted(), "the caller's interrupted status must be restored")
        assertTrue(process.forciblyDestroyed)
        assertFalse(process.isAlive)
        assertTrue(process.allStreamsClosed)
    }

    @Test
    fun `identity helper cannot return a successful token with a nonzero exit`() {
        val client =
            identityClient(
                EventProcess(
                    """{"event":"window_located","binding_token":"stale-token"}""",
                    exitCode = 17,
                )
            )

        val error = assertFailsWith<IllegalStateException> { client.locateWindow(IDENTITY) }

        assertTrue(error.message.orEmpty().contains("exit 17"), error.message)
    }

    @Test
    fun `identity helper error event preserves actionable detail despite a nonzero exit`() {
        val client =
            identityClient(
                EventProcess(
                    """{"event":"error","kind":"Helper","message":"no matching XID for stale PID"}""",
                    exitCode = 19,
                )
            )

        val error = assertFailsWith<WaylandHelperErrorException> { client.locateWindow(IDENTITY) }

        assertEquals("Helper", error.kind)
        assertTrue(error.message.orEmpty().contains("no matching XID for stale PID"), error.message)
    }

    @Test
    fun `legacy screenshot response explains how to replace the held helper`() {
        val client = screenshotClient(Event.ScreenshotSaved(outputSizeBytes = 1))

        val error =
            assertFailsWith<IllegalStateException> { client.screenshot(Rectangle(10, 20, 2, 3)) }

        val message = error.message.orEmpty()
        assertTrue(message.contains("capture_region"), message)
        assertTrue(message.contains("upgrade"), message)
        assertTrue(message.contains("restart all Spectre"), message)
        assertTrue(message.contains("spectre permissions request"), message)
        assertTrue(message.contains("Share picker"), message)
    }

    @Test
    fun `verified crop whose size differs from PNG fails closed`() {
        val client =
            screenshotClient(
                Event.ScreenshotSaved(outputSizeBytes = 1, captureRegion = listOf(5, 6, 3, 3))
            )

        val error =
            assertFailsWith<IllegalStateException> { client.screenshot(Rectangle(10, 20, 2, 3)) }

        assertTrue(error.message.orEmpty().contains("refusing a clipped capture"), error.message)
    }

    private fun screenshotClient(event: Event.ScreenshotSaved): WaylandSessionClient =
        WaylandSessionClient(
            helperExtractor = extractor(),
            envLookup = sessionDirEnv(),
            processFactory = RecordingProcessFactory(exitCode = 0),
            waitForSocket = { _, _ -> false },
            screenshotSender = {
                val command = it as Command.Screenshot
                ImageIO.write(
                    BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB),
                    "png",
                    Path.of(command.output).toFile(),
                )
                event
            },
        )

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
                WAYLAND_CONSENT_PROMPT_ENV to "allow",
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
        // Explicit, so an inherited SPECTRE_WAYLAND_CONSENT_PROMPT=never cannot block the request.
        assertEquals("allow", environment[WAYLAND_CONSENT_PROMPT_ENV])
    }

    @Test
    fun `an attended request starts a new dialog after a pending one timed out`() {
        val pending = PendingProcess()
        val processes = ArrayDeque<Process>(listOf(pending, ExitedProcess(0)))
        val factory = SupplyingProcessFactory { processes.removeFirst() }
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
                    throw InterruptedException("stop waiting for the socket")
                },
                consentPrompt = { WaylandConsentPrompt.ALLOW },
            )
        assertFailsWith<InterruptedException> { client.send(PING) }
        pending.finish(WAYLAND_SESSION_CONSENT_EXIT)

        assertFailsWith<InterruptedException> { client.openSessionForRequest(300) }

        assertEquals(2, factory.starts, "the attended request must open a new dialog")
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

    private fun identityClient(process: Process): WaylandSessionClient =
        WaylandSessionClient(
            helperExtractor = extractor(),
            envLookup = { null },
            processFactory = SupplyingProcessFactory { ExitedProcess(0) },
            identityProcessFactory =
                fun(helperPath, environment): Process {
                    assertTrue(helperPath.isAbsolute)
                    assertTrue(environment.isEmpty())
                    return process
                },
            identityTimeoutSeconds = 0,
            identityDestroyTimeoutMs = 0,
        )

    private class CloseTrackingOutputStream : OutputStream() {
        var closed = false

        override fun write(value: Int) = Unit

        override fun close() {
            closed = true
        }
    }

    private class CloseTrackingInputStream : InputStream() {
        var closed = false

        override fun read(): Int = -1

        override fun close() {
            closed = true
        }
    }

    private class IdentityProcess(private val interruptWait: Boolean) : Process() {
        private val output = CloseTrackingOutputStream()
        private val input = CloseTrackingInputStream()
        private val error = CloseTrackingInputStream()
        private var alive = true
        var destroyed = false
        var forciblyDestroyed = false
        val allStreamsClosed: Boolean
            get() = output.closed && input.closed && error.closed

        override fun getOutputStream(): OutputStream = output

        override fun getInputStream(): InputStream = input

        override fun getErrorStream(): InputStream = error

        override fun waitFor(): Int = error("not used")

        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean {
            if (interruptWait && !destroyed) throw InterruptedException("test interruption")
            return !alive
        }

        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0

        override fun isAlive(): Boolean = alive

        override fun destroy() {
            destroyed = true
        }

        override fun destroyForcibly(): Process {
            forciblyDestroyed = true
            alive = false
            return this
        }
    }

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

    private class EventProcess(event: String, private val exitCode: Int) : Process() {
        private val input = ByteArrayInputStream("$event\n".toByteArray())

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = input

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = exitCode

        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = true

        override fun exitValue(): Int = exitCode

        override fun isAlive(): Boolean = false

        override fun destroy() = Unit
    }

    private companion object {
        val PING: Command = Command.PointerMove(1, 1)
        val IDENTITY = MutterWindowIdentity(7720, Region(10, 20, 300, 200))
    }
}
