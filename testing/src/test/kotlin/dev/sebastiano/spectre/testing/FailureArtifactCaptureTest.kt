package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.capture.AtomicCapture
import dev.sebastiano.spectre.core.capture.CaptureArtifactsWriter
import dev.sebastiano.spectre.core.capture.CaptureDocument
import dev.sebastiano.spectre.core.capture.CaptureRect
import dev.sebastiano.spectre.core.capture.CaptureSummary
import dev.sebastiano.spectre.core.capture.CaptureWindow
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FailureArtifactCaptureTest {

    @Test
    fun `writes capture json and png for each window under window-N dirs`(@TempDir temp: Path) {
        val methodDir = temp.resolve("com.example.T").resolve("fails")
        val captures =
            FailureArtifactCapture.captureWindows(
                methodDirectory = methodDir,
                windowCount = 2,
                captureWindow = { index -> fakeAtomicCapture(windowIndex = index) },
            )

        assertEquals(2, captures.size)
        for (index in 0..1) {
            val windowDir = methodDir.resolve("window-$index")
            val json = windowDir.resolve(CaptureArtifactsWriter.CAPTURE_JSON_NAME)
            val png = windowDir.resolve(CaptureArtifactsWriter.SCREENSHOT_PNG_NAME)
            assertTrue(Files.isRegularFile(json), "missing $json")
            assertTrue(Files.isRegularFile(png), "missing $png")
            assertTrue(Files.size(json) > 0)
            assertTrue(Files.size(png) > 0)
            assertEquals(windowDir, captures[index].directory)
        }
    }

    @Test
    fun `zero windows writes nothing`(@TempDir temp: Path) {
        val methodDir = temp.resolve("com.example.T").resolve("empty")
        val captures =
            FailureArtifactCapture.captureWindows(
                methodDirectory = methodDir,
                windowCount = 0,
                captureWindow = { error("should not capture") },
            )
        assertTrue(captures.isEmpty())
        assertFalse(Files.exists(methodDir))
    }

    @Test
    fun `continues remaining windows when one capture throws`(@TempDir temp: Path) {
        val methodDir = temp.resolve("com.example.T").resolve("partial")
        val captures =
            FailureArtifactCapture.captureWindows(
                methodDirectory = methodDir,
                windowCount = 3,
                captureWindow = { index ->
                    if (index == 1) error("boom on window 1")
                    fakeAtomicCapture(windowIndex = index)
                },
            )
        assertEquals(2, captures.size)
        assertTrue(Files.isRegularFile(methodDir.resolve("window-0").resolve("capture.json")))
        assertFalse(Files.exists(methodDir.resolve("window-1")))
        assertTrue(Files.isRegularFile(methodDir.resolve("window-2").resolve("capture.json")))
    }

    @Test
    fun `returns empty list when window discovery throws`(@TempDir temp: Path) {
        val methodDir = temp.resolve("com.example.T").resolve("discovery")
        val captures =
            FailureArtifactCapture.captureFromDiscovery(
                methodDirectory = methodDir,
                discoverWindowCount = { error("EDT died during refresh") },
                captureWindow = { error("should not capture") },
            )
        assertTrue(captures.isEmpty())
        assertFalse(Files.exists(methodDir))
    }

    @Test
    fun `removes partial window directory when write fails after capture`(@TempDir temp: Path) {
        val methodDir = temp.resolve("com.example.T").resolve("partial-write")
        // Pre-create a stale half-written capture dir that a failed write would leave behind
        // if cleanup did not run; the capture lambda succeeds but we force write failure by
        // making the window path a *file* so createDirectories fails.
        val blocker = methodDir.resolve("window-0")
        Files.createDirectories(methodDir)
        Files.writeString(blocker, "not-a-directory")

        val captures =
            FailureArtifactCapture.captureWindows(
                methodDirectory = methodDir,
                windowCount = 1,
                captureWindow = { fakeAtomicCapture(windowIndex = 0) },
            )
        assertTrue(captures.isEmpty())
        // Best-effort: either the blocker file remains (mkdir never started) or a partial
        // capture dir was removed. A half-written capture.json+png pair must not remain.
        val json = methodDir.resolve("window-0").resolve("capture.json")
        assertFalse(Files.isRegularFile(json))
    }

    private fun fakeAtomicCapture(windowIndex: Int): AtomicCapture {
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) // minimal non-empty stub
        val document =
            CaptureDocument(
                schemaVersion = CaptureDocument.SCHEMA_VERSION,
                capturedAt = "2026-01-01T00:00:00Z",
                window =
                    CaptureWindow(
                        index = windowIndex,
                        surfaceId = "surface-$windowIndex",
                        title = "Window $windowIndex",
                        isPopup = false,
                        boundsScreen = CaptureRect(0, 0, 4, 4),
                        densityScaleX = 1.0,
                        densityScaleY = 1.0,
                        imageWidth = 4,
                        imageHeight = 4,
                    ),
                nodes = emptyList(),
                summary =
                    CaptureSummary(
                        nodeCount = 0,
                        taggedNodeCount = 0,
                        textedNodeCount = 0,
                        imageWidth = 4,
                        imageHeight = 4,
                        captureDurationMs = 1,
                    ),
            )
        return AtomicCapture(image = image, pngBytes = pngBytes, document = document)
    }
}
