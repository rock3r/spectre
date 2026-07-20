package dev.sebastiano.spectre.core.capture

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureDocumentJsonTest {

    @Test
    fun `schema golden matches versioned capture envelope`() {
        val document = sampleDocument()
        val actual = CaptureJson.encode(document).trim()
        val expected =
            javaClass
                .getResourceAsStream("/capture/capture-schema-v1.golden.json")!!
                .bufferedReader()
                .use { it.readText().trim() }
        assertEquals(expected, actual)
    }

    @Test
    fun `writer writes capture json and screenshot png`() {
        val dir = Files.createTempDirectory("spectre-capture-writer-")
        try {
            val document = sampleDocument(minimal = true)
            val png = minimalPngBytes()
            val written = CaptureArtifactsWriter.write(dir, document, png)
            assertTrue(Files.isRegularFile(written.captureJsonPath))
            assertTrue(Files.isRegularFile(written.screenshotPngPath))
            assertEquals(
                CaptureJson.encode(document).trim(),
                Files.readString(written.captureJsonPath).trim(),
            )
            assertTrue(Files.readAllBytes(written.screenshotPngPath).contentEquals(png))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun sampleDocument(minimal: Boolean = false): CaptureDocument {
        if (minimal) {
            return CaptureDocument(
                schemaVersion = CaptureDocument.SCHEMA_VERSION,
                capturedAt = "2026-07-20T12:00:00.000Z",
                window =
                    CaptureWindow(
                        index = 0,
                        surfaceId = "s",
                        title = null,
                        isPopup = false,
                        boundsScreen = CaptureRect(0, 0, 1, 1),
                        densityScaleX = 1.0,
                        densityScaleY = 1.0,
                        imageWidth = 1,
                        imageHeight = 1,
                    ),
                nodes = emptyList(),
                summary =
                    CaptureSummary(
                        nodeCount = 0,
                        taggedNodeCount = 0,
                        textedNodeCount = 0,
                        imageWidth = 1,
                        imageHeight = 1,
                        captureDurationMs = 0,
                    ),
            )
        }
        return CaptureDocument(
            schemaVersion = CaptureDocument.SCHEMA_VERSION,
            capturedAt = "2026-07-20T12:00:00.000Z",
            window =
                CaptureWindow(
                    index = 0,
                    surfaceId = "surface-main",
                    title = "Fixture",
                    isPopup = false,
                    boundsScreen = CaptureRect(x = 10, y = 20, width = 400, height = 300),
                    densityScaleX = 2.0,
                    densityScaleY = 2.0,
                    imageWidth = 800,
                    imageHeight = 600,
                ),
            nodes =
                listOf(
                    CaptureNode(
                        key = "surface-main:0:42",
                        testTag = "submit",
                        text = "Save",
                        texts = listOf("Save"),
                        contentDescription = null,
                        role = "Button",
                        enabled = true,
                        clickable = true,
                        focused = false,
                        selected = false,
                        boundsImage = CaptureRect(x = 20, y = 40, width = 100, height = 48),
                        boundsScreen = CaptureRect(x = 20, y = 40, width = 50, height = 24),
                    )
                ),
            summary =
                CaptureSummary(
                    nodeCount = 1,
                    taggedNodeCount = 1,
                    textedNodeCount = 1,
                    imageWidth = 800,
                    imageHeight = 600,
                    captureDurationMs = 12,
                ),
        )
    }

    private fun minimalPngBytes(): ByteArray =
        byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
            0x00,
            0x00,
            0x00,
            0x0D,
            0x49,
            0x48,
            0x44,
            0x52,
            0x00,
            0x00,
            0x00,
            0x01,
            0x00,
            0x00,
            0x00,
            0x01,
            0x08,
            0x02,
            0x00,
            0x00,
            0x00,
            0x90.toByte(),
            0x77,
            0x53,
            0xDE.toByte(),
            0x00,
            0x00,
            0x00,
            0x0C,
            0x49,
            0x44,
            0x41,
            0x54,
            0x08,
            0xD7.toByte(),
            0x63,
            0xF8.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0x3F,
            0x00,
            0x05,
            0xFE.toByte(),
            0x02,
            0xFE.toByte(),
            0xDC.toByte(),
            0xCC.toByte(),
            0x59,
            0xE7.toByte(),
            0x00,
            0x00,
            0x00,
            0x00,
            0x49,
            0x45,
            0x4E,
            0x44,
            0xAE.toByte(),
            0x42,
            0x60,
            0x82.toByte(),
        )
}
