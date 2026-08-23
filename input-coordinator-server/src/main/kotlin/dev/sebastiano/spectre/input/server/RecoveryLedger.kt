@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.DesktopResourceKey
import dev.sebastiano.spectre.input.LeaseOwner
import dev.sebastiano.spectre.input.LeaseToken
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.Properties
import java.util.concurrent.TimeUnit

internal class RecoveryLedger(private val path: Path, private val heartbeatTimeout: Duration) {
    private var current: LedgerRecord? = null

    @Synchronized
    fun load(): RecoveryRecord? {
        if (!Files.exists(path)) return null
        val record = runCatching(::read).getOrElse { corruptRecord() }
        current = record
        val remainingMillis =
            (record.heartbeatExpiryEpochMillis - System.currentTimeMillis()).coerceAtLeast(0)
        return RecoveryRecord(
            resourceKey = DesktopResourceKey(record.resourceKey),
            predecessorEpoch = record.epoch,
            leaseId = record.leaseId,
            owner = LeaseOwner(record.clientId, record.processId, record.ownerLabel),
            heartbeatExpiryMillis = monotonicMillis() + remainingMillis,
            blocksAllResources = record.corrupt,
        )
    }

    @Synchronized
    fun record(grant: LeaseGrant) {
        val record =
            LedgerRecord(
                epoch = grant.token.coordinatorEpoch,
                leaseId = grant.token.leaseId,
                resourceKey = grant.token.resourceKey.value,
                clientId = grant.owner.clientId,
                processId = grant.owner.processId,
                ownerLabel = grant.owner.label,
                heartbeatExpiryEpochMillis =
                    System.currentTimeMillis() + heartbeatTimeout.toMillis(),
                corrupt = false,
            )
        write(record)
        current = record
    }

    @Synchronized
    fun heartbeat(token: LeaseToken) {
        val record = current?.takeIf { it.leaseId == token.leaseId } ?: return
        val refreshed =
            record.copy(
                heartbeatExpiryEpochMillis =
                    System.currentTimeMillis() + heartbeatTimeout.toMillis()
            )
        write(refreshed)
        current = refreshed
    }

    @Synchronized
    fun clear(leaseId: String) {
        if (current?.leaseId != leaseId) return
        Files.deleteIfExists(path)
        current = null
    }

    @Synchronized
    fun clearClient(clientId: String) {
        current?.takeIf { it.clientId == clientId }?.let { clear(it.leaseId) }
    }

    @Synchronized
    fun clearExpiredRecovery(recoveryGrace: Duration) {
        val record = current ?: return
        val graceMillis = recoveryGrace.toMillis()
        val eligibleAt =
            if (Long.MAX_VALUE - record.heartbeatExpiryEpochMillis < graceMillis) {
                Long.MAX_VALUE
            } else {
                record.heartbeatExpiryEpochMillis + graceMillis
            }
        if (System.currentTimeMillis() >= eligibleAt) clear(record.leaseId)
    }

    private fun read(): LedgerRecord {
        val properties = Properties()
        Files.newInputStream(path).use(properties::load)
        return LedgerRecord(
            epoch = properties.required(EPOCH),
            leaseId = properties.required(LEASE_ID),
            resourceKey = properties.required(RESOURCE_KEY),
            clientId = properties.required(CLIENT_ID),
            processId = properties.required(PROCESS_ID).toLong(),
            ownerLabel = properties.getProperty(OWNER_LABEL),
            heartbeatExpiryEpochMillis = properties.required(HEARTBEAT_EXPIRY).toLong(),
            corrupt = false,
        )
    }

    private fun write(record: LedgerRecord) {
        val properties =
            Properties().apply {
                setProperty(EPOCH, record.epoch)
                setProperty(LEASE_ID, record.leaseId)
                setProperty(RESOURCE_KEY, record.resourceKey)
                setProperty(CLIENT_ID, record.clientId)
                setProperty(PROCESS_ID, record.processId.toString())
                record.ownerLabel?.let { setProperty(OWNER_LABEL, it) }
                setProperty(HEARTBEAT_EXPIRY, record.heartbeatExpiryEpochMillis.toString())
            }
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.newOutputStream(temporary).use { output -> properties.store(output, null) }
        if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
        }
        try {
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, path, REPLACE_EXISTING)
        }
    }

    private fun corruptRecord(): LedgerRecord {
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        val observedId = "corrupt-${HexFormat.of().formatHex(digest, 0, CORRUPT_HASH_BYTES)}"
        return LedgerRecord(
            epoch = "unknown",
            leaseId = observedId,
            resourceKey = CORRUPT_RESOURCE_KEY,
            clientId = "unknown-recovery-owner",
            processId = 0,
            ownerLabel = "corrupt recovery ledger",
            heartbeatExpiryEpochMillis = Long.MAX_VALUE,
            corrupt = true,
        )
    }

    private fun Properties.required(key: String): String =
        getProperty(key)?.takeIf(String::isNotBlank)
            ?: throw IOException("Recovery ledger is missing $key")

    private data class LedgerRecord(
        val epoch: String,
        val leaseId: String,
        val resourceKey: String,
        val clientId: String,
        val processId: Long,
        val ownerLabel: String?,
        val heartbeatExpiryEpochMillis: Long,
        val corrupt: Boolean,
    )

    private companion object {
        const val EPOCH: String = "epoch"
        const val LEASE_ID: String = "leaseId"
        const val RESOURCE_KEY: String = "resourceKey"
        const val CLIENT_ID: String = "clientId"
        const val PROCESS_ID: String = "processId"
        const val OWNER_LABEL: String = "ownerLabel"
        const val HEARTBEAT_EXPIRY: String = "heartbeatExpiryEpochMillis"
        const val CORRUPT_RESOURCE_KEY: String = "user:unknown/recovery-corrupt"
        const val CORRUPT_HASH_BYTES: Int = 8

        fun monotonicMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
    }
}
