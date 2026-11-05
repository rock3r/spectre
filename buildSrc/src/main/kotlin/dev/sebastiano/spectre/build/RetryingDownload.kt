package dev.sebastiano.spectre.build

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

/**
 * One download attempt, bounded by [deadline] from start to the last body byte.
 *
 * `HttpRequest.timeout` alone is not enough: the JDK client stops that timer once the headers
 * arrive, so a body that stalls would block forever and never reach the retry loop. The whole
 * exchange runs asynchronously and is cancelled when [deadline] passes.
 */
internal fun fetchOverHttp(url: String, target: File, deadline: Duration = ATTEMPT_DEADLINE) {
    val client =
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    val request = HttpRequest.newBuilder(URI.create(url)).timeout(deadline).GET().build()
    val exchange = client.sendAsync(request, HttpResponse.BodyHandlers.ofFile(target.toPath()))
    val response =
        try {
            exchange.get(deadline.toMillis(), TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            exchange.cancel(true)
            throw IOException("Download of $url did not finish within $deadline", error)
        } catch (error: ExecutionException) {
            throw error.cause as? IOException ?: IOException("Download of $url failed", error.cause)
        } catch (error: InterruptedException) {
            exchange.cancel(true)
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
// About 140 MB per file; 5 minutes allows for a slow runner without letting a stall eat the job.
private val ATTEMPT_DEADLINE: Duration = Duration.ofMinutes(5)
