@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.sebastiano.spectre.input.CoordinatorControlResult
import dev.sebastiano.spectre.input.CoordinatorEndpoint
import dev.sebastiano.spectre.input.CoordinatorHolderStatus
import dev.sebastiano.spectre.input.CoordinatorStatus
import dev.sebastiano.spectre.input.DesktopResourceKey
import dev.sebastiano.spectre.input.InputCoordinatorException
import dev.sebastiano.spectre.input.LocalCoordinatorEnvironment
import dev.sebastiano.spectre.input.LocalInputCoordinatorControl
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class InputLockCommand
internal constructor(
    output: Appendable,
    endpoint: () -> CoordinatorEndpoint,
    resourceKey: () -> DesktopResourceKey,
) : CliktCommand(name = "input-lock") {
    constructor(
        output: Appendable,
        endpoint: CoordinatorEndpoint,
        resourceKey: DesktopResourceKey,
    ) : this(output, { endpoint }, { resourceKey })

    init {
        subcommands(
            InputLockStatusCommand(output, endpoint, resourceKey),
            InputLockRevokeCommand(output, endpoint, resourceKey),
        )
    }

    override fun run(): Unit = Unit

    companion object {
        fun default(output: Appendable): InputLockCommand =
            InputLockCommand(
                output,
                LocalCoordinatorEnvironment::defaultEndpoint,
                LocalCoordinatorEnvironment::defaultDesktopResourceKey,
            )
    }
}

private class InputLockStatusCommand(
    private val output: Appendable,
    private val endpoint: () -> CoordinatorEndpoint,
    private val resourceKey: () -> DesktopResourceKey,
) : CliktCommand(name = "status") {
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        try {
            when (val result = LocalInputCoordinatorControl(endpoint()).status(resourceKey())) {
                CoordinatorControlResult.NoActiveCoordinator ->
                    if (json) {
                        output.appendLine(INPUT_LOCK_JSON.encodeToString(InputLockStatusJson()))
                    } else {
                        output.appendLine("No active input coordinator.")
                    }
                is CoordinatorControlResult.Active -> renderActive(result.status)
            }
        } catch (failure: InputCoordinatorException) {
            failInputLock(output, failure)
        } catch (failure: IOException) {
            failInputLockIo(output, failure)
        }
    }

    private fun renderActive(status: CoordinatorStatus) {
        if (json) {
            output.appendLine(
                INPUT_LOCK_JSON.encodeToString(
                    InputLockStatusJson(
                        noActiveCoordinator = false,
                        resourceKey = status.resourceKey.value,
                        holder = status.holder?.let(::InputLockHolderJson),
                        waiterCount = status.waiters.size,
                        quarantineLeaseId = status.quarantine?.predecessorLeaseId,
                        quarantine = status.quarantine?.let(::InputLockQuarantineJson),
                    )
                )
            )
            return
        }
        status.holder?.let { holder ->
            output.appendLine(
                "Input lock ${holder.leaseId}: ${holder.state}, owner=${holder.owner.label}, " +
                    "pid=${holder.owner.processId}, age=${holder.acquisitionAgeMillis}ms, " +
                    "heartbeatAge=${holder.heartbeatAgeMillis}ms, " +
                    "operation=${holder.currentOperation}, waiters=${status.waiters.size}"
            )
        } ?: output.appendLine("Input lock is free; waiters=${status.waiters.size}.")
        status.quarantine?.let { quarantine ->
            output.appendLine(
                "Recovery quarantine for predecessor ${quarantine.predecessorLeaseId}; " +
                    "owner=${quarantine.owner.label}, pid=${quarantine.owner.processId}, " +
                    "releaseEligibleAt=${quarantine.releaseEligibleAtMillis}; an " +
                    "unacknowledged live owner requires exact-ID --force."
            )
        }
    }
}

private class InputLockRevokeCommand(
    private val output: Appendable,
    private val endpoint: () -> CoordinatorEndpoint,
    private val resourceKey: () -> DesktopResourceKey,
) : CliktCommand(name = "revoke") {
    private val leaseId: String by option("--lease").required()
    private val reason: String by option("--reason").required()
    private val force: Boolean by option("--force").flag(default = false)

    override fun run() {
        try {
            val resource = resourceKey()
            val control = LocalInputCoordinatorControl(endpoint())
            if (control.status(resource) == CoordinatorControlResult.NoActiveCoordinator) {
                output.appendLine("No active input coordinator.")
                return
            }
            val result =
                control.revoke(
                    resourceKey = resource,
                    observedLeaseId = leaseId,
                    requesterLabel = "spectre-cli:${ProcessHandle.current().pid()}",
                    reason = reason,
                    force = force,
                )
            if (result.unsafeTakeover) {
                output.appendLine(
                    "warning: lease was fenced without owner acknowledgement; unsafeTakeover=true"
                )
            } else {
                output.appendLine("Lease $leaseId is revoking; unsafeTakeover=false")
            }
        } catch (failure: InputCoordinatorException) {
            if (failure.errorCode == "REVOKE_GRACE_ACTIVE") {
                output.appendLine(
                    "The lease is fenced; inspect status, wait for the grace period, then " +
                        "retry the exact lease ID with --force if recovery is still required."
                )
            }
            failInputLock(output, failure)
        } catch (failure: IOException) {
            failInputLockIo(output, failure)
        }
    }
}

private fun failInputLock(output: Appendable, failure: InputCoordinatorException): Nothing {
    output.appendLine("Input lock error (${failure.errorCode}): ${failure.message}")
    throw ProgramResult(1)
}

private fun failInputLockIo(output: Appendable, failure: IOException): Nothing {
    output.appendLine("Input lock I/O error: ${failure.message}")
    throw ProgramResult(1)
}

@Serializable
private data class InputLockStatusJson(
    val version: Int = 1,
    val noActiveCoordinator: Boolean = true,
    val resourceKey: String? = null,
    val holder: InputLockHolderJson? = null,
    val waiterCount: Int = 0,
    val quarantineLeaseId: String? = null,
    val quarantine: InputLockQuarantineJson? = null,
)

@Serializable
private data class InputLockQuarantineJson(
    val predecessorLeaseId: String,
    val predecessorEpoch: String,
    val owner: String?,
    val processId: Long,
    val releaseEligibleAtMillis: Long,
) {
    constructor(
        quarantine: dev.sebastiano.spectre.input.CoordinatorQuarantineStatus
    ) : this(
        predecessorLeaseId = quarantine.predecessorLeaseId,
        predecessorEpoch = quarantine.predecessorEpoch,
        owner = quarantine.owner.label,
        processId = quarantine.owner.processId,
        releaseEligibleAtMillis = quarantine.releaseEligibleAtMillis,
    )
}

@Serializable
private data class InputLockHolderJson(
    val leaseId: String,
    val owner: String?,
    val processId: Long,
    val state: String,
    val fence: Long,
    val acquisitionAgeMillis: Long,
    val heartbeatAgeMillis: Long,
    val currentOperation: String?,
) {
    constructor(
        holder: CoordinatorHolderStatus
    ) : this(
        leaseId = holder.leaseId,
        owner = holder.owner.label,
        processId = holder.owner.processId,
        state = holder.state,
        fence = holder.fence,
        acquisitionAgeMillis = holder.acquisitionAgeMillis,
        heartbeatAgeMillis = holder.heartbeatAgeMillis,
        currentOperation = holder.currentOperation,
    )
}

private val INPUT_LOCK_JSON: Json = Json { encodeDefaults = true }
