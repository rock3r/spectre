package dev.sebastiano.spectre.build

import java.io.File
import java.io.IOException
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Covers the retry policy for the CLI packaging downloads (target JDKs and Roast). Construo's own
 * download task uses a default HTTP client with 10 s timeouts and no retry, and large GitHub
 * release downloads stall past that on CI runners.
 */
class RetryingDownloadTest {

    @TempDir lateinit var dir: Path

    @Test
    fun `a transient failure is retried until the download succeeds`() {
        val dest = dir.resolve("jdk.zip").toFile()
        var calls = 0
        val sleeps = mutableListOf<Long>()

        downloadWithRetries(
            url = URL,
            dest = dest,
            attempts = 3,
            fetch = { _, target ->
                calls++
                if (calls < 3) throw IOException("timeout")
                target.writeText("payload")
            },
            sleep = { sleeps += it },
        )

        assertEquals(3, calls)
        assertEquals("payload", dest.readText())
        assertEquals(2, sleeps.size, "waits between attempts, not after the last one")
        assertTrue(sleeps[1] > sleeps[0], "the wait grows between attempts: $sleeps")
    }

    @Test
    fun `the last failure is rethrown once every attempt fails`() {
        val dest = dir.resolve("roast.zip").toFile()
        var calls = 0

        val error =
            assertThrows(IOException::class.java) {
                downloadWithRetries(
                    url = URL,
                    dest = dest,
                    attempts = 2,
                    fetch = { _, _ ->
                        calls++
                        throw IOException("timeout $calls")
                    },
                    sleep = {},
                )
            }

        assertEquals(2, calls)
        assertTrue(error.message!!.contains(URL), "the error names the URL: ${error.message}")
        assertEquals("timeout 2", error.cause?.message)
    }

    @Test
    fun `a partial file from a failed attempt is not left behind`() {
        val dest = dir.resolve("partial.zip").toFile()

        assertThrows(IOException::class.java) {
            downloadWithRetries(
                url = URL,
                dest = dest,
                attempts = 1,
                fetch = { _, target ->
                    target.writeText("half")
                    throw IOException("connection reset")
                },
                sleep = {},
            )
        }

        assertFalse(dest.exists(), "a failed download must not leave a truncated file")
        assertFalse(File(dest.path + ".part").exists())
    }

    private companion object {
        const val URL = "https://example.invalid/OpenJDK25U-jdk_x64_windows_hotspot.zip"
    }
}
