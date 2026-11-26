@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.CoordinatorEndpoint
import dev.sebastiano.spectre.input.DesktopResourceKey
import dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi
import dev.sebastiano.spectre.input.InputCoordinatorClientProvider
import dev.sebastiano.spectre.input.LocalInputCoordinatorClient
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.time.Duration
import java.util.concurrent.locks.LockSupport

/** Service-loaded provider that launches the small external coordinator JVM when necessary. */
@ExperimentalSpectreInputCoordinationApi
public class LaunchingInputCoordinatorClientProvider public constructor() :
    InputCoordinatorClientProvider {
    private var launch: (CoordinatorEndpoint) -> Unit = { endpoint ->
        CoordinatorProcessLauncher(endpoint).start()
    }

    internal constructor(launch: (CoordinatorEndpoint) -> Unit) : this() {
        this.launch = launch
    }

    override fun connect(
        endpoint: CoordinatorEndpoint,
        resourceKey: DesktopResourceKey,
        ownerLabel: String?,
    ): LocalInputCoordinatorClient {
        tryConnect(endpoint, resourceKey, ownerLabel)?.let {
            return it
        }
        launch(endpoint)
        val deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos()
        var lastFailure: IOException? = null
        while (System.nanoTime() < deadline) {
            try {
                return LocalInputCoordinatorClient.connect(endpoint, resourceKey, ownerLabel)
            } catch (failure: ConnectException) {
                lastFailure = failure
                LockSupport.parkNanos(STARTUP_RETRY_DELAY.toNanos())
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedIOException("Input coordinator startup was interrupted")
                }
            }
        }
        throw IOException(
            "Input coordinator did not become ready at ${endpoint.socketPath}",
            lastFailure,
        )
    }

    private fun tryConnect(
        endpoint: CoordinatorEndpoint,
        resourceKey: DesktopResourceKey,
        ownerLabel: String?,
    ): LocalInputCoordinatorClient? =
        try {
            LocalInputCoordinatorClient.connect(endpoint, resourceKey, ownerLabel)
        } catch (_: ConnectException) {
            null
        }

    private companion object {
        val STARTUP_TIMEOUT: Duration = Duration.ofSeconds(5)
        val STARTUP_RETRY_DELAY: Duration = Duration.ofMillis(10)
    }
}
