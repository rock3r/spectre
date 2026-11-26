package dev.sebastiano.spectre.recording

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RecordingOutputValidationTest {
    @Test
    fun `missing recording output is rejected`() {
        val output = Files.createTempDirectory("spectre-output-test").resolve("missing.mp4")

        assertFailsWith<IllegalStateException> { validateRecordingOutput(output) }
    }

    @Test
    fun `empty recording output is rejected`() {
        val output = Files.createTempFile("spectre-output-test", ".mp4")
        try {
            assertFailsWith<IllegalStateException> { validateRecordingOutput(output) }
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `zero helper-reported size is rejected even when a file has bytes`() {
        val output = Files.createTempFile("spectre-output-test", ".mp4")
        try {
            Files.write(output, byteArrayOf(1))

            assertFailsWith<IllegalStateException> {
                validateRecordingOutput(output, reportedSizeBytes = 0)
            }
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `non-empty output with a positive helper-reported size is accepted`() {
        val output = Files.createTempFile("spectre-output-test", ".mp4")
        try {
            Files.write(output, byteArrayOf(1))

            validateRecordingOutput(output, reportedSizeBytes = 1)
            assertFailsWith<IllegalStateException> {
                validateRecordingOutput(output, reportedSizeBytes = 2)
            }
        } finally {
            output.deleteIfExists()
        }
    }
}
