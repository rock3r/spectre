package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.RectDto
import dev.sebastiano.spectre.agent.transport.WindowSummaryDto
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DaemonAccessibilityTreeTest {
    @Test
    fun `standalone accessibility accepts timeout above sixty seconds`() {
        val response =
            fixture()
                .registry
                .handle(DaemonRequest.AccessibilityTree(processId = 8123, timeoutMs = 60_001))

        assertIs<DaemonResponse.AccessibilitySnapshotResult>(response)
    }

    @Test
    fun `attached accessibility accepts timeout above sixty seconds`() {
        val fixture = fixture(windows = listOf(window(0, "Fixture")))
        fixture.registry.handle(DaemonRequest.Attach(44))

        val response =
            fixture.registry.handle(
                DaemonRequest.AccessibilityTree(sessionId = "pid-44", timeoutMs = 60_001)
            )

        assertIs<DaemonResponse.AccessibilitySnapshotResult>(response)
    }

    @Test
    fun `attached accessibility request owns exact session until helper completes`() {
        val windowsEntered = CountDownLatch(1)
        val releaseWindows = CountDownLatch(1)
        val helperEntered = CountDownLatch(1)
        val releaseHelper = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val fixture =
            fixture(
                automator =
                    TestDaemonSessionAutomator(
                        windowsResult = {
                            windowsEntered.countDown()
                            check(releaseWindows.await(5, TimeUnit.SECONDS))
                            listOf(window(0, "Fixture"))
                        },
                        closeAction = { closed.countDown() },
                    ),
                helperAction = {
                    helperEntered.countDown()
                    check(releaseHelper.await(5, TimeUnit.SECONDS))
                },
            )
        fixture.registry.handle(DaemonRequest.Attach(44))

        val pool = Executors.newFixedThreadPool(2)
        try {
            val request =
                pool.submit<DaemonResponse> {
                    fixture.registry.handle(DaemonRequest.AccessibilityTree(sessionId = "pid-44"))
                }
            check(windowsEntered.await(5, TimeUnit.SECONDS))
            val detach =
                pool.submit<DaemonResponse> {
                    fixture.registry.handle(DaemonRequest.Detach("pid-44"))
                }
            assertTrue(!closed.await(200, TimeUnit.MILLISECONDS), "detach raced target lookup")
            releaseWindows.countDown()
            check(helperEntered.await(5, TimeUnit.SECONDS))
            assertTrue(!closed.await(200, TimeUnit.MILLISECONDS), "detach raced native helper")
            releaseHelper.countDown()

            assertIs<DaemonResponse.AccessibilitySnapshotResult>(request.get(5, TimeUnit.SECONDS))
            assertIs<DaemonResponse.Detached>(detach.get(5, TimeUnit.SECONDS))
        } finally {
            releaseWindows.countDown()
            releaseHelper.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `non JVM pid is sent directly to packaged helper`() {
        val fixture = fixture()
        val response = fixture.registry.handle(DaemonRequest.AccessibilityTree(processId = 8123))
        assertIs<DaemonResponse.AccessibilitySnapshotResult>(response)
        assertTrue(fixture.input.contains("\"type\":\"process-id\""))
        assertTrue(fixture.input.contains("\"value\":8123"))
    }

    @Test
    fun `title lookup is delegated as an exact unique native target`() {
        val fixture = fixture()
        fixture.registry.handle(DaemonRequest.AccessibilityTree(windowTitle = "Exact title"))
        assertTrue(fixture.input.contains("\"type\":\"window-title\""))
        assertTrue(fixture.input.contains("\"value\":\"Exact title\""))
    }

    @Test
    fun `attached session selects requested window and scopes title to owner pid`() {
        val fixture = fixture(windows = listOf(window(0, "First"), window(1, "Second")))
        assertIs<DaemonResponse.Attached>(fixture.registry.handle(DaemonRequest.Attach(44)))
        fixture.registry.handle(
            DaemonRequest.AccessibilityTree(sessionId = "pid-44", windowIndex = 1)
        )
        assertTrue(fixture.input.contains("\"value\":\"Second\""))
        assertTrue(fixture.input.contains("\"processId\":44"))
    }

    @OptIn(ExperimentalSpectreAgentApi::class)
    @Test
    fun `bound accessibility capture uses atomic window pixels and native JSON`(
        @TempDir out: Path
    ) {
        val png =
            ByteArrayOutputStream()
                .apply {
                    ImageIO.write(BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", this)
                }
                .toByteArray()
        val windows = listOf(window(0, "First"), window(1, "Second"))
        val fixture =
            fixture(
                automator =
                    TestDaemonSessionAutomator(
                        windowsResult = { windows },
                        screenshotResult = { _, _, _ ->
                            throw UnsupportedOperationException(
                                "window screenshots are unsupported on attach"
                            )
                        },
                        captureResult = { index ->
                            assertEquals(1, index)
                            TestDaemonSessionAutomator().capture(index).copy(pngBytes = png)
                        },
                    )
            )
        fixture.registry.handle(DaemonRequest.Attach(44))
        val capture =
            assertIs<DaemonResponse.Capture>(
                fixture.registry.handle(
                    DaemonRequest.Capture(
                        "pid-44",
                        windowIndex = 1,
                        outDir = out.toString(),
                        source = DaemonCaptureSource.Accessibility,
                    )
                )
            )
        assertTrue(fixture.input.contains("\"value\":\"Second\""))
        assertTrue(fixture.input.contains("\"processId\":44"))
        assertContentEquals(png, Files.readAllBytes(Path.of(capture.screenshotPngPath)))
        assertEquals(2, capture.schemaVersion)
        assertEquals(3, capture.imageWidth)
        assertTrue(
            Files.readString(Path.of(capture.captureJsonPath))
                .contains("\"source\":\"os-accessibility\"")
        )
    }

    @Test
    fun `bound native reader failure keeps typed error and creates no capture`(@TempDir out: Path) {
        val fixture =
            fixture(
                windows = listOf(window(0, "Fixture")),
                output =
                    """{"ok":false,"error":{"code":"target-not-found","message":"window disappeared"}}""",
            )
        fixture.registry.handle(DaemonRequest.Attach(44))
        val failure =
            assertIs<DaemonResponse.AccessibilityErrorResult>(
                fixture.registry.handle(
                    DaemonRequest.Capture(
                        "pid-44",
                        outDir = out.toString(),
                        source = DaemonCaptureSource.Accessibility,
                    )
                )
            )
        assertEquals(AccessibilityErrorCode.TargetNotFound, failure.error.code)
        assertTrue(Files.list(out).use { it.findAny().isEmpty })
    }

    @Test
    fun `dropped attached target returns operation failure instead of escaping`() {
        val fixture =
            fixture(
                automator =
                    TestDaemonSessionAutomator(
                        windowsResult = { throw java.io.IOException("agent connection dropped") }
                    )
            )
        assertIs<DaemonResponse.Attached>(fixture.registry.handle(DaemonRequest.Attach(44)))

        val response =
            assertIs<DaemonResponse.Error>(
                fixture.registry.handle(DaemonRequest.AccessibilityTree(sessionId = "pid-44"))
            )

        assertEquals(DaemonErrorCode.OperationFailed, response.code)
        assertEquals("agent connection dropped", response.message)
    }

    @Test
    fun `native ambiguity failure remains typed and never becomes success`() {
        val fixture =
            fixture(
                output =
                    """{"ok":false,"error":{"code":"target-not-found","message":"title matched multiple windows"}}"""
            )
        val response =
            fixture.registry.handle(DaemonRequest.AccessibilityTree(windowTitle = "Duplicate"))
        val failure = assertIs<DaemonResponse.AccessibilityErrorResult>(response)
        assertEquals(AccessibilityErrorCode.TargetNotFound, failure.error.code)
    }

    @Test
    fun `mismatched partial error is rejected before reaching daemon output`() {
        val partial =
            """{"schemaVersion":2,"source":"os-accessibility","capturedAt":"now","windows":[],"nodes":[],"notes":[]}"""
        val fixture =
            fixture(
                output =
                    """{"ok":false,"error":{"code":"permission-missing","message":"denied","partialSnapshot":$partial}}"""
            )

        val response = fixture.registry.handle(DaemonRequest.AccessibilityTree(processId = 42))

        val failure = assertIs<DaemonResponse.Error>(response)
        assertEquals(DaemonErrorCode.OperationFailed, failure.code)
    }

    private fun fixture(
        output: String =
            """{"ok":true,"snapshot":{"schemaVersion":2,"source":"os-accessibility",
                "capturedAt":"2026-11-25T12:34:56Z","windows":[],"nodes":[],"notes":[]}}"""
                .replace("\n", ""),
        windows: List<WindowSummaryDto> = emptyList(),
        automator: TestDaemonSessionAutomator =
            TestDaemonSessionAutomator(windowsResult = { windows }),
        helperAction: () -> Unit = {},
    ): Fixture {
        var input = ""
        val helper =
            AccessibilityHelperClient(
                helperExecutable = { Path.of("fake-helper") },
                runner =
                    AccessibilityHelperRunner { _, stdin, _ ->
                        input = stdin
                        helperAction()
                        output
                    },
            )
        val registry =
            DaemonSessionRegistry(
                hotReloadSessionFactory = { null },
                accessibilityHelperClient = helper,
                attachAutomator = { automator },
            )
        return Fixture(registry, { input })
    }

    private fun window(index: Int, title: String) =
        WindowSummaryDto(index, "surface-$index", title, false, RectDto(0, 0, 10, 10))

    private class Fixture(
        val registry: DaemonSessionRegistry,
        private val readInput: () -> String,
    ) {
        val input: String
            get() = readInput()
    }
}
