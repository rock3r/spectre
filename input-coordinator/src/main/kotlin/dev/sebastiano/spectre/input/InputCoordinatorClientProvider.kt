@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.util.ServiceLoader

/** Runtime provider that can make the external coordinator available and open a client session. */
@ExperimentalSpectreInputCoordinationApi
public fun interface InputCoordinatorClientProvider {
    /** Connects to, or safely starts and then connects to, the coordinator for [endpoint]. */
    public fun connect(
        endpoint: CoordinatorEndpoint,
        resourceKey: DesktopResourceKey,
        ownerLabel: String?,
    ): LocalInputCoordinatorClient
}

/**
 * Discovers the process artifact without making `spectre-core` embed coordinator server classes.
 */
@ExperimentalSpectreInputCoordinationApi
public object InputCoordinatorClientFactory {
    /** Connects through the installed process provider or fails closed when it is absent. */
    public fun connectOrStart(
        endpoint: CoordinatorEndpoint = LocalCoordinatorEnvironment.defaultEndpoint(),
        resourceKey: DesktopResourceKey = LocalCoordinatorEnvironment.defaultDesktopResourceKey(),
        ownerLabel: String? = null,
    ): LocalInputCoordinatorClient {
        val provider =
            ServiceLoader.load(InputCoordinatorClientProvider::class.java).firstOrNull()
                ?: throw InputCoordinatorException(
                    errorCode = "COORDINATOR_PROVIDER_MISSING",
                    message =
                        "No Spectre input coordinator process provider is installed; add " +
                            "spectre-input-coordinator-server to the runtime classpath",
                )
        return provider.connect(endpoint, resourceKey, ownerLabel)
    }
}
