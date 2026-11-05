package dev.sebastiano.spectre.build

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    @Test
    fun `a body that stalls after the headers fails the attempt at its deadline`() {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val release = CountDownLatch(1)
        server.createContext("/stall") { exchange ->
            exchange.sendResponseHeaders(200, 1_000)
            exchange.responseBody.write(ByteArray(10))
            exchange.responseBody.flush()
            release.await(30, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/stall"
            val started = System.nanoTime()

            assertThrows(IOException::class.java) {
                fetchOverHttp(url, dir.resolve("stall.bin").toFile(), Duration.ofMillis(500))
            }

            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            assertTrue(
                elapsed < Duration.ofSeconds(10),
                "the attempt must end at its deadline: $elapsed",
            )
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    private companion object {
        const val URL = "https://example.invalid/OpenJDK25U-jdk_x64_windows_hotspot.zip"
    }
}
