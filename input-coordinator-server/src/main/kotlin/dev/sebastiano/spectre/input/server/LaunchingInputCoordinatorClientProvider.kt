@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.CoordinatorEndpoint
import dev.sebastiano.spectre.input.DesktopResourceKey
import dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi
import dev.sebastiano.spectre.input.InputCoordinatorClientProvider
import dev.sebastiano.spectre.input.LocalInputCoordinatorClient
import java.io.IOException
import java.time.Duration
import java.util.concurrent.locks.LockSupport

/** Service-loaded provider that launches the small external coordinator JVM when necessary. */
@ExperimentalSpectreInputCoordinationApi
public class LaunchingInputCoordinatorClientProvider : InputCoordinatorClientProvider {
    override fun connect(
        endpoint: CoordinatorEndpoint,
        resourceKey: DesktopResourceKey,
        ownerLabel: String?,
    ): LocalInputCoordinatorClient {
        tryConnect(endpoint, resourceKey, ownerLabel)?.let {
            return it
        }
        CoordinatorProcessLauncher(endpoint).start()
        val deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos()
        var lastFailure: IOException? = null
        while (System.nanoTime() < deadline) {
            try {
                return LocalInputCoordinatorClient.connect(endpoint, resourceKey, ownerLabel)
            } catch (failure: IOException) {
                lastFailure = failure
                LockSupport.parkNanos(STARTUP_RETRY_DELAY.toNanos())
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
        } catch (_: IOException) {
            null
        }

    private companion object {
        val STARTUP_TIMEOUT: Duration = Duration.ofSeconds(5)
        val STARTUP_RETRY_DELAY: Duration = Duration.ofMillis(10)
    }
}
