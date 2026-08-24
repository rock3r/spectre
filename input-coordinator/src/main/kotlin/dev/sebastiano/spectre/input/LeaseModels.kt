@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

/** Identifies one conservatively normalized desktop input resource. */
@ExperimentalSpectreInputCoordinationApi
public data class DesktopResourceKey(public val value: String) {
    init {
        require(value.isNotBlank()) { "Desktop resource key must not be blank" }
    }
}

/** Redacted metadata describing a cooperative lease owner. */
@ExperimentalSpectreInputCoordinationApi
public data class LeaseOwner(
    public val clientId: String,
    public val processId: Long,
    public val label: String? = null,
) {
    init {
        require(clientId.isNotBlank()) { "Client ID must not be blank" }
        require(processId >= 0) { "Process ID must not be negative" }
    }
}

/** Epoch- and generation-fenced proof that a client currently owns a desktop resource. */
@ExperimentalSpectreInputCoordinationApi
public data class LeaseToken(
    public val coordinatorEpoch: String,
    public val leaseId: String,
    public val resourceKey: DesktopResourceKey,
    public val fence: Long,
)

/** Stable failure taxonomy shared by coordinator clients and servers. */
@ExperimentalSpectreInputCoordinationApi
public enum class LeaseErrorCode {
    QUEUE_FULL,
    STALE_LEASE,
    STALE_EPOCH,
    FENCED,
    REVOKE_GRACE_ACTIVE,
}
