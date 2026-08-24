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
    private val current = mutableMapOf<String, LedgerRecord>()

    @Synchronized
    fun load(): RecoveryRecord? {
        if (!Files.exists(path)) return null
        val record = runCatching(::read).getOrElse { corruptRecord() }
        current.clear()
        current[record.leaseId] = record
        val remainingMillis =
            (record.heartbeatExpiryEpochMillis - System.currentTimeMillis()).coerceAtLeast(0)
        return RecoveryRecord(
            resourceKey = DesktopResourceKey(record.resourceKey),
            predecessorEpoch = record.epoch,
            leaseId = record.leaseId,
            owner = LeaseOwner(record.clientId, record.processId, record.ownerLabel),
            heartbeatExpiryMillis = monotonicMillis() + remainingMillis,
            blocksAllResources = record.blocksAllResources,
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
                blocksAllResources = false,
            )
        val previous = current.put(record.leaseId, record)
        try {
            persist()
        } catch (failure: IOException) {
            if (previous == null) current.remove(record.leaseId)
            else current[record.leaseId] = previous
            throw failure
        }
    }

    @Synchronized
    fun heartbeat(token: LeaseToken) {
        val record = current[token.leaseId] ?: return
        val refreshed =
            record.copy(
                heartbeatExpiryEpochMillis =
                    System.currentTimeMillis() + heartbeatTimeout.toMillis()
            )
        current[token.leaseId] = refreshed
        persist()
    }

    @Synchronized
    fun clear(leaseId: String) {
        val removed = current.remove(leaseId) ?: return
        try {
            persist()
        } catch (failure: IOException) {
            current[leaseId] = removed
            throw failure
        }
    }

    /** Forgets a proven-unobserved grant after persistence already failed to clear it. */
    @Synchronized
    fun discardUnobserved(leaseId: String) {
        current.remove(leaseId)
    }

    @Synchronized
    fun clearClient(clientId: String): Boolean {
        val removedLeaseIds =
            current.values.filter { it.clientId == clientId }.mapTo(mutableSetOf()) { it.leaseId }
        if (removedLeaseIds.isEmpty()) return true
        current.keys.removeAll(removedLeaseIds)
        return try {
            persist()
            true
        } catch (_: IOException) {
            false
        }
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
            blocksAllResources = properties.getProperty(BLOCKS_ALL)?.toBooleanStrict() ?: false,
        )
    }

    private fun persist() {
        when (current.size) {
            0 -> Files.deleteIfExists(path)
            1 -> write(current.values.single())
            else -> write(conservativeRecord(current.values))
        }
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
                setProperty(BLOCKS_ALL, record.blocksAllResources.toString())
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
            blocksAllResources = true,
        )
    }

    private fun conservativeRecord(records: Collection<LedgerRecord>): LedgerRecord {
        val leaseIds = records.map(LedgerRecord::leaseId).sorted().joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(leaseIds.encodeToByteArray())
        val observedId = "multiple-${HexFormat.of().formatHex(digest, 0, CORRUPT_HASH_BYTES)}"
        return LedgerRecord(
            epoch = "multiple",
            leaseId = observedId,
            resourceKey = MULTIPLE_RESOURCE_KEY,
            clientId = "multiple-recovery-owners",
            processId = 0,
            ownerLabel = "multiple active desktop resources",
            heartbeatExpiryEpochMillis = records.maxOf(LedgerRecord::heartbeatExpiryEpochMillis),
            blocksAllResources = true,
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
        val blocksAllResources: Boolean,
    )

    private companion object {
        const val EPOCH: String = "epoch"
        const val LEASE_ID: String = "leaseId"
        const val RESOURCE_KEY: String = "resourceKey"
        const val CLIENT_ID: String = "clientId"
        const val PROCESS_ID: String = "processId"
        const val OWNER_LABEL: String = "ownerLabel"
        const val HEARTBEAT_EXPIRY: String = "heartbeatExpiryEpochMillis"
        const val BLOCKS_ALL: String = "blocksAllResources"
        const val CORRUPT_RESOURCE_KEY: String = "user:unknown/recovery-corrupt"
        const val MULTIPLE_RESOURCE_KEY: String = "user:unknown/recovery-multiple"
        const val CORRUPT_HASH_BYTES: Int = 8

        fun monotonicMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
    }
}
