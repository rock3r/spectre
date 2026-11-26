@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
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
    /** Returns whether a process provider is visible without instantiating it. */
    public fun isProviderInstalled(): Boolean = providers().stream().findFirst().isPresent

    /**
     * Conservatively reports whether any canonical or fallback socket candidate exists.
     *
     * Only a definite missing-file result counts as absence. Metadata and access failures are
     * propagated so callers cannot silently opt out of coordination on ambiguous evidence.
     */
    @Throws(IOException::class)
    public fun hasExistingSocketCandidate(endpoint: CoordinatorEndpoint): Boolean =
        CoordinatorSocketCandidates.candidates(endpoint.socketPath).any { candidate ->
            try {
                Files.readAttributes(candidate, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                true
            } catch (_: NoSuchFileException) {
                false
            }
        }

    /** Connects through the installed process provider or fails closed when it is absent. */
    public fun connectOrStart(
        endpoint: CoordinatorEndpoint = LocalCoordinatorEnvironment.defaultEndpoint(),
        resourceKey: DesktopResourceKey = LocalCoordinatorEnvironment.defaultDesktopResourceKey(),
        ownerLabel: String? = null,
    ): LocalInputCoordinatorClient {
        val provider =
            providers().firstOrNull()
                ?: throw InputCoordinatorException(
                    errorCode = "COORDINATOR_PROVIDER_MISSING",
                    message =
                        "No Spectre input coordinator process provider is installed; add " +
                            "spectre-input-coordinator-server to the runtime classpath",
                )
        return provider.connect(endpoint, resourceKey, ownerLabel)
    }

    private fun providers(): ServiceLoader<InputCoordinatorClientProvider> =
        ServiceLoader.load(InputCoordinatorClientProvider::class.java)
}
