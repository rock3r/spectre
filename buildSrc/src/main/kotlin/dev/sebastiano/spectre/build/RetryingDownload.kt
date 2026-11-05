package dev.sebastiano.spectre.build

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.StandardCopyOption
import java.time.Duration

/**
 * Downloads [url] to [dest], retrying failed attempts.
 *
 * The CLI packaging downloads (target JDKs and Roast) come from GitHub releases. Construo's own
 * download task uses a default HTTP client with 10 s timeouts and no retry, and these large files
 * stall past that, or get short bursts of HTTP 504, on CI runners. Each attempt writes to a
 * `.part` file that is moved into place only when the attempt succeeds, so a failed attempt never
 * leaves a truncated [dest] behind.
 */
fun downloadWithRetries(
    url: String,
    dest: File,
    attempts: Int = DEFAULT_ATTEMPTS,
    fetch: (url: String, target: File) -> Unit = ::fetchOverHttp,
    sleep: (millis: Long) -> Unit = Thread::sleep,
) {
    require(attempts > 0) { "attempts must be positive" }
    dest.parentFile?.mkdirs()
    val part = File(dest.path + ".part")
    var lastError: IOException? = null
    for (attempt in 1..attempts) {
        try {
            part.delete()
            fetch(url, part)
            java.nio.file.Files.move(
                part.toPath(),
                dest.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            return
        } catch (error: IOException) {
            lastError = error
            part.delete()
            if (attempt < attempts) sleep(INITIAL_BACKOFF_MILLIS * attempt)
        }
    }
    dest.delete()
    throw IOException("Download of $url failed after $attempts attempts", lastError)
}

private fun fetchOverHttp(url: String, target: File) {
    val client =
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    val request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build()
    val response =
        try {
            client.send(request, HttpResponse.BodyHandlers.ofFile(target.toPath()))
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while downloading $url", error)
        }
    if (response.statusCode() !in 200..299) {
        throw IOException("HTTP ${response.statusCode()} for $url")
    }
}

// GitHub release downloads return short bursts of HTTP 504. Five attempts with 10/20/30/40 s
// waits ride out about 100 s of them.
private const val DEFAULT_ATTEMPTS = 5
private const val INITIAL_BACKOFF_MILLIS = 10_000L
private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)
private val REQUEST_TIMEOUT: Duration = Duration.ofMinutes(10)
