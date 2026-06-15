package dev.sebastiano.spectre.recording.screencapturekit

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val BINARY_NAME: String = "spectre-screencapture"
private const val OVERRIDE_ENV: String = "SPECTRE_SCREENCAPTURE_HELPER"
private const val HELPER_DIR_PROPERTY: String = "spectre.recording.screencapturekit.helperDir"

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class HelperBinaryExtractorTest {

    private val tempRoot: Path = Files.createTempDirectory("spectre-helper-extract-test-")

    @AfterTest
    fun tearDown() {
        if (tempRoot.exists()) tempRoot.deleteRecursively()
    }

    // ── baseline extraction ──────────────────────────────────────────────────

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

    // ── SPECTRE_SCREENCAPTURE_HELPER env-var override ────────────────────────

    @Test
    fun `env-var override returns the target path without extracting from the classpath`() {
        val fakeHelper = tempRoot.resolve("pre-extracted-spectre-screencapture")
        fakeHelper.toFile().writeBytes(byteArrayOf(0x01, 0x02))
        Files.setPosixFilePermissions(fakeHelper, PosixFilePermissions.fromString("rwxr-xr-x"))

        var locatorCalled = false
        val extractor =
            HelperBinaryExtractor(
                envLookup = { key -> if (key == OVERRIDE_ENV) fakeHelper.toString() else null },
                resourceLocator = {
                    locatorCalled = true
                    ByteArrayInputStream(byteArrayOf())
                },
                targetDirProvider = { tempRoot.resolve("should-not-be-used") },
            )

        val path = extractor.extract()

        assertEquals(fakeHelper, path, "Env-var override must return the pointed-to path")
        assertTrue(!locatorCalled, "Classpath resource must not be opened when env-var is set")
    }

    @Test
    fun `env-var override is cached — subsequent calls return the same path`() {
        val fakeHelper = tempRoot.resolve("pre-extracted-spectre-screencapture")
        fakeHelper.toFile().writeBytes(byteArrayOf(0x01))
        Files.setPosixFilePermissions(fakeHelper, PosixFilePermissions.fromString("rwxr-xr-x"))

        val extractor =
            HelperBinaryExtractor(
                envLookup = { key -> if (key == OVERRIDE_ENV) fakeHelper.toString() else null }
            )

        val first = extractor.extract()
        val second = extractor.extract()
        assertEquals(first, second, "Cached override path must be returned on repeat calls")
    }

    @Test
    fun `env-var override with non-executable path throws a clear error`() {
        val notExecutable = tempRoot.resolve("not-executable")
        notExecutable.toFile().writeBytes(byteArrayOf(0x01))
        Files.setPosixFilePermissions(notExecutable, PosixFilePermissions.fromString("rw-r--r--"))

        val extractor =
            HelperBinaryExtractor(
                envLookup = { key -> if (key == OVERRIDE_ENV) notExecutable.toString() else null }
            )

        val ex = assertFailsWith<IllegalStateException> { extractor.extract() }
        assertNotNull(ex.message)
        assertTrue(
            ex.message!!.contains(OVERRIDE_ENV),
            "Error must name the env var so the user knows which setting to fix; got: ${ex.message}",
        )
    }

    @Test
    fun `blank env-var value falls through to normal extraction`() {
        val payload = byteArrayOf(0x7F, 0x01)
        var locatorCalled = false
        val extractor =
            HelperBinaryExtractor(
                envLookup = { key -> if (key == OVERRIDE_ENV) "   " else null },
                resourceLocator = {
                    locatorCalled = true
                    ByteArrayInputStream(payload)
                },
                targetDirProvider = { tempRoot },
            )

        extractor.extract()

        assertTrue(locatorCalled, "Blank env-var value must not suppress normal extraction")
    }

    // ── spectre.recording.screencapturekit.helperDir system property ─────────

    @Test
    fun `helperDir system property extracts binary into the given directory`() {
        val stableDir = tempRoot.resolve("stable")
        val payload = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)

        var targetDirCalled = false
        val extractor =
            HelperBinaryExtractor(
                sysPropLookup = { key ->
                    if (key == HELPER_DIR_PROPERTY) stableDir.toString() else null
                },
                resourceLocator = { ByteArrayInputStream(payload) },
                targetDirProvider = {
                    targetDirCalled = true
                    tempRoot.resolve("default-temp")
                },
            )

        val path = extractor.extract()

        assertEquals(
            stableDir.resolve(BINARY_NAME),
            path,
            "Binary must land inside the directory specified by the system property",
        )
        assertContentEquals(payload, path.readBytes())
        assertTrue(Files.isExecutable(path))
        assertTrue(
            !targetDirCalled,
            "Default targetDirProvider must not be called when the system property is set",
        )
    }

    @Test
    fun `helperDir system property creates the directory if it does not exist`() {
        val nonExistentDir = tempRoot.resolve("a").resolve("b").resolve("c")
        val extractor =
            HelperBinaryExtractor(
                sysPropLookup = { key ->
                    if (key == HELPER_DIR_PROPERTY) nonExistentDir.toString() else null
                },
                resourceLocator = { ByteArrayInputStream(byteArrayOf(0x01)) },
            )

        val path = extractor.extract()

        assertTrue(path.exists(), "Extracted binary must exist even when the dir was created")
    }

    @Test
    fun `blank helperDir system property falls through to targetDirProvider`() {
        var targetDirCalled = false
        val extractor =
            HelperBinaryExtractor(
                sysPropLookup = { key -> if (key == HELPER_DIR_PROPERTY) "  " else null },
                resourceLocator = { ByteArrayInputStream(byteArrayOf(0x01)) },
                targetDirProvider = {
                    targetDirCalled = true
                    tempRoot
                },
            )

        extractor.extract()

        assertTrue(
            targetDirCalled,
            "Blank helperDir system property must fall through to targetDirProvider",
        )
    }

    @Test
    fun `env-var override takes priority over helperDir system property`() {
        val fakeHelper = tempRoot.resolve("env-override-binary")
        fakeHelper.toFile().writeBytes(byteArrayOf(0x01))
        Files.setPosixFilePermissions(fakeHelper, PosixFilePermissions.fromString("rwxr-xr-x"))

        var locatorCalled = false
        val extractor =
            HelperBinaryExtractor(
                envLookup = { key -> if (key == OVERRIDE_ENV) fakeHelper.toString() else null },
                sysPropLookup = { key ->
                    if (key == HELPER_DIR_PROPERTY) tempRoot.resolve("stable").toString() else null
                },
                resourceLocator = {
                    locatorCalled = true
                    ByteArrayInputStream(byteArrayOf())
                },
            )

        val path = extractor.extract()

        assertEquals(fakeHelper, path, "Env-var override must win over helperDir property")
        assertTrue(!locatorCalled, "Classpath resource must not be read when env-var wins")
    }
}

private fun assertContentEquals(expected: ByteArray, actual: ByteArray) {
    assertEquals(expected.size, actual.size, "Byte arrays differ in length")
    for (index in expected.indices) {
        assertEquals(expected[index], actual[index], "Byte mismatch at index $index")
    }
}

@Suppress("unused") private fun stubLocator(stream: InputStream?): () -> InputStream? = { stream }
