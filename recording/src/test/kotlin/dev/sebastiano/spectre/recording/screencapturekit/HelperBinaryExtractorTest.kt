package dev.sebastiano.spectre.recording.screencapturekit

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class HelperBinaryExtractorTest {

    private val tempRoot: Path = Files.createTempDirectory("spectre-helper-extract-test-")

    @AfterTest
    fun tearDown() {
        if (tempRoot.exists()) tempRoot.deleteRecursively()
    }

    @Test
    fun `extract writes the resource bytes to disk and marks the file executable`() {
        val payload =
            byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01, 0x01, 0x00) // ELF header bytes
        val extractor =
            HelperBinaryExtractor(
                resourceLocator = { ByteArrayInputStream(payload) },
                targetDirProvider = { tempRoot },
            )

        val path = extractor.extract()

        assertTrue(path.exists(), "Extracted path must exist")
        assertContentEquals(payload, path.readBytes())
        assertTrue(Files.isExecutable(path), "Extracted helper must be marked executable")
    }

    @Test
    fun `extract is idempotent — second call returns the same path without re-reading the resource`() {
        // Caching matters because every Recorder instance asks for the helper, and re-extracting
        // means another temp file + another stream copy + another chmod for no value.
        var locatorCalls = 0
        val extractor =
            HelperBinaryExtractor(
                resourceLocator = {
                    locatorCalls += 1
                    ByteArrayInputStream(byteArrayOf(0x01, 0x02, 0x03))
                },
                targetDirProvider = { tempRoot },
            )

        val first = extractor.extract()
        val second = extractor.extract()

        assertEquals(first, second, "Cached path must be returned on repeat calls")
        assertEquals(1, locatorCalls, "Resource must be opened exactly once across calls")
    }

    @Test
    fun `extract throws a meaningful error when the resource is missing`() {
        val extractor =
            HelperBinaryExtractor(resourceLocator = { null }, targetDirProvider = { tempRoot })

        val ex = assertFailsWith<IllegalStateException> { extractor.extract() }
        assertNotNull(ex.message)
        assertTrue(
            ex.message!!.contains("spectre-screencapture") ||
                ex.message!!.contains("helper", ignoreCase = true),
            "Error must name the missing resource so callers know what to look for; got: ${ex.message}",
        )
    }

    @Test
    fun `default classpath resource lookup finds the bundled helper on macOS builds`() {
        // Smoke test for the default-constructed extractor — the Gradle build stages the
        // Swift helper into the test classpath when running on macOS. On non-macOS hosts we
        // skip rather than fail, since the helper isn't built there.
        val isMacOs = System.getProperty("os.name").lowercase().contains("mac")
        if (!isMacOs) return

        val extractor = HelperBinaryExtractor(targetDirProvider = { tempRoot })
        val path = extractor.extract()
        assertTrue(path.exists())
        assertTrue(Files.isExecutable(path))
        assertTrue(Files.size(path) > 0, "Bundled helper must not be a zero-byte file")
    }
}

private fun assertContentEquals(expected: ByteArray, actual: ByteArray) {
    assertEquals(expected.size, actual.size, "Byte arrays differ in length")
    for (index in expected.indices) {
        assertEquals(expected[index], actual[index], "Byte mismatch at index $index")
    }
}

@Suppress("unused") private fun stubLocator(stream: InputStream?): () -> InputStream? = { stream }
