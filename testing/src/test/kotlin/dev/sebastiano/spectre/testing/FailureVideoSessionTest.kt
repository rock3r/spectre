package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.recording.RecordingHandle
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Lifecycle for failure-video sessions: start when policy ≠ off, always stop+finalize before
 * keep/delete, no orphaned handle after finalize or abandon.
 */
class FailureVideoSessionTest {

    @Test
    fun `Off does not start the recorder`(@TempDir temp: Path) {
        val starts = AtomicInteger(0)
        val session =
            FailureVideoSession(
                config = FailureVideoConfig(policy = FailureVideoPolicy.Off, reportsRoot = temp),
                starter = { _, _ ->
                    starts.incrementAndGet()
                    error("must not start")
                },
            )
        session.start(
            automator = newHeadlessAutomator(),
            testClassName = "com.example.T",
            testMethodName = "m",
        )
        assertEquals(0, starts.get())
        assertNull(session.activeOutput)
        session.finalizeAndApply(FailureVideoOutcome.Failed)
        assertFalse(Files.walk(temp).use { s -> s.anyMatch { Files.isRegularFile(it) } })
    }

    @Test
    fun `OnFailureKeep finalizes before delete on pass`(@TempDir temp: Path) {
        val events = mutableListOf<String>()
        val session =
            FailureVideoSession(
                config =
                    FailureVideoConfig(
                        policy = FailureVideoPolicy.OnFailureKeep,
                        reportsRoot = temp,
                        invocationId = "inv-1",
                    ),
                starter = { path, _ ->
                    events += "start:${path.fileName}"
                    Files.createDirectories(path.parent)
                    Files.writeString(path, "partial")
                    object : RecordingHandle {
                        override val output: Path = path
                        override var isStopped: Boolean = false
                            private set

                        override fun stop() {
                            events += "stop"
                            isStopped = true
                            Files.writeString(path, "finalized")
                        }
                    }
                },
            )
        session.start(
            automator = newHeadlessAutomator(),
            testClassName = "com.example.T",
            testMethodName = "passes",
        )
        assertTrue(events.first().startsWith("start:"))
        val active = session.activeOutput
        checkNotNull(active)
        assertTrue(Files.exists(active))

        session.finalizeAndApply(FailureVideoOutcome.Passed)
        assertEquals(listOf(events[0], "stop"), events)
        assertFalse(Files.exists(active), "pass must delete finalized video under onFailureKeep")
        assertNull(session.activeOutput)
        assertFalse(session.hasActiveRecorder)
    }

    @Test
    fun `OnFailureKeep keeps finalized video on fail`(@TempDir temp: Path) {
        val reports = mutableListOf<Pair<String, String>>()
        val session =
            sessionWithFakeFile(
                temp = temp,
                policy = FailureVideoPolicy.OnFailureKeep,
                contentAfterStop = "playable",
            )
        session.start(
            automator = newHeadlessAutomator(),
            testClassName = "com.example.T",
            testMethodName = "fails",
        )
        val active = checkNotNull(session.activeOutput)
        session.finalizeAndApply(FailureVideoOutcome.Failed) { k, v -> reports += k to v }
        assertTrue(Files.exists(active))
        assertEquals("playable", Files.readString(active))
        assertEquals(FailureVideoSession.REPORT_ENTRY_KEY, reports.single().first)
        assertTrue(reports.single().second.contains("failure-video.mp4"))
        assertNull(session.activeOutput)
    }

    @Test
    fun `Always keeps on pass`(@TempDir temp: Path) {
        val session =
            sessionWithFakeFile(
                temp = temp,
                policy = FailureVideoPolicy.Always,
                contentAfterStop = "always",
            )
        session.start(
            automator = newHeadlessAutomator(),
            testClassName = "com.example.T",
            testMethodName = "ok",
        )
        val active = checkNotNull(session.activeOutput)
        session.finalizeAndApply(FailureVideoOutcome.Passed)
        assertTrue(Files.exists(active))
        assertEquals("always", Files.readString(active))
    }

    @Test
    fun `abort deletes video even under Always`(@TempDir temp: Path) {
        val session =
            sessionWithFakeFile(
                temp = temp,
                policy = FailureVideoPolicy.Always,
                contentAfterStop = "abort",
            )
        session.start(
            automator = newHeadlessAutomator(),
            testClassName = "com.example.T",
            testMethodName = "assumed",
        )
        val active = checkNotNull(session.activeOutput)
        session.finalizeAndApply(FailureVideoOutcome.Aborted)
        assertFalse(Files.exists(active))
    }

    @Test
    fun `abandon stops and deletes without leaving an active recorder`(@TempDir temp: Path) {
        val stopped = AtomicInteger(0)
        val session =
            FailureVideoSession(
                config =
                    FailureVideoConfig(
                        policy = FailureVideoPolicy.OnFailureKeep,
                        reportsRoot = temp,
                        invocationId = "inv",
                    ),
                starter = { path, _ ->
                    Files.createDirectories(path.parent)
                    Files.writeString(path, "partial")
                    object : RecordingHandle {
                        override val output: Path = path
                        override var isStopped: Boolean = false
                            private set

                        override fun stop() {
                            stopped.incrementAndGet()
                            isStopped = true
                            Files.writeString(path, "finalized")
                        }
                    }
                },
            )
        session.start(
            automator = newHeadlessAutomator(),
            testClassName = "com.example.T",
            testMethodName = "crash",
        )
        val active = checkNotNull(session.activeOutput)
        session.abandon()
        assertEquals(1, stopped.get())
        assertFalse(Files.exists(active))
        assertFalse(session.hasActiveRecorder)
        assertNull(session.activeOutput)
    }

    @Test
    fun `paths nest attempt and invocation like stills`(@TempDir temp: Path) {
        var startedAt: Path? = null
        val session =
            FailureVideoSession(
                config =
                    FailureVideoConfig(
                        policy = FailureVideoPolicy.Always,
                        reportsRoot = temp,
                        attemptIndex = 2,
                        invocationId = "inv-xyz",
                    ),
                starter = { path, _ ->
                    startedAt = path
                    Files.createDirectories(path.parent)
                    Files.writeString(path, "x")
                    fakeHandle(path, "x")
                },
            )
        session.start(
            automator = newHeadlessAutomator(),
            testClassName = "com.example.Retry",
            testMethodName = "flaky",
        )
        val expectedDir =
            FailureArtifactPaths.methodDirectory(
                testClassName = "com.example.Retry",
                testMethodName = "flaky",
                config =
                    FailureArtifactsConfig(
                        reportsRoot = temp,
                        attemptIndex = 2,
                        invocationId = "inv-xyz",
                    ),
            )
        assertEquals(expectedDir.resolve(FailureVideoSession.VIDEO_FILE_NAME), startedAt)
        session.finalizeAndApply(FailureVideoOutcome.Passed)
    }

    @Test
    fun `stop failure deletes video even under Always`(@TempDir temp: Path) {
        val session =
            FailureVideoSession(
                config =
                    FailureVideoConfig(
                        policy = FailureVideoPolicy.Always,
                        reportsRoot = temp,
                        invocationId = "stop-fail",
                    ),
                starter = { path, _ ->
                    Files.createDirectories(path.parent)
                    Files.writeString(path, "partial")
                    object : RecordingHandle {
                        override val output: Path = path
                        override var isStopped: Boolean = false
                            private set

                        override fun stop() {
                            error("helper crashed during stop")
                        }
                    }
                },
            )
        session.start(
            automator = newHeadlessAutomator(),
            testClassName = "com.example.T",
            testMethodName = "m",
        )
        val active = checkNotNull(session.activeOutput)
        session.finalizeAndApply(FailureVideoOutcome.Failed)
        assertFalse(Files.exists(active), "failed stop must not keep partial video")
        assertFalse(session.hasActiveRecorder)
    }

    @Test
    fun `start failure is best-effort and leaves no active recorder`(@TempDir temp: Path) {
        val session =
            FailureVideoSession(
                config =
                    FailureVideoConfig(
                        policy = FailureVideoPolicy.OnFailureKeep,
                        reportsRoot = temp,
                        invocationId = "start-fail",
                    ),
                starter = { path, _ ->
                    // Simulate a helper that creates a partial file then fails.
                    Files.createDirectories(path.parent)
                    Files.writeString(path, "partial-orphan")
                    error("helper missing")
                },
            )
        session.start(
            automator = newHeadlessAutomator(),
            testClassName = "com.example.T",
            testMethodName = "m",
        )
        assertFalse(session.hasActiveRecorder)
        assertFalse(
            Files.walk(temp).use { s ->
                s.anyMatch { it.fileName.toString() == FailureVideoSession.VIDEO_FILE_NAME }
            },
            "failed start must delete partial video",
        )
        session.finalizeAndApply(FailureVideoOutcome.Failed)
        assertFalse(session.hasActiveRecorder)
    }

    private fun sessionWithFakeFile(
        temp: Path,
        policy: FailureVideoPolicy,
        contentAfterStop: String,
    ): FailureVideoSession =
        FailureVideoSession(
            config = FailureVideoConfig(policy = policy, reportsRoot = temp, invocationId = "inv"),
            starter = { path, _ ->
                Files.createDirectories(path.parent)
                Files.writeString(path, "partial")
                fakeHandle(path, contentAfterStop)
            },
        )

    private fun fakeHandle(path: Path, contentAfterStop: String): RecordingHandle =
        object : RecordingHandle {
            override val output: Path = path
            override var isStopped: Boolean = false
                private set

            override fun stop() {
                isStopped = true
                Files.writeString(path, contentAfterStop)
            }
        }
}
