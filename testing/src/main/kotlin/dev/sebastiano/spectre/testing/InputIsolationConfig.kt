@file:OptIn(
    dev.sebastiano.spectre.core.InternalSpectreApi::class,
    dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class,
)

package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.AutomatorInputLease
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.DesktopInputIsolation
import dev.sebastiano.spectre.core.InputLeaseOptions
import dev.sebastiano.spectre.core.InputLeasePolicy
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.extension.ExtensionContext

/** Whole-test desktop-input isolation mode for in-process JUnit wrappers. */
@ExperimentalSpectreInputCoordinationApi
public enum class InputIsolationMode {
    /** Chooses per-test isolation only when the created automator touches shared OS input state. */
    Auto,

    /** Holds one desktop lease across factory setup, test execution, evidence, and teardown. */
    PerTest,

    /** Uses the core operation and explicit-scope leases without a whole-test lease. */
    PerInteraction,

    /** Disables wrapper-owned isolation because the caller coordinates externally. */
    Off,
}

/** Configuration for in-process JUnit desktop-input isolation. */
@ExperimentalSpectreInputCoordinationApi
public data class InputIsolationConfig(
    public val mode: InputIsolationMode = InputIsolationMode.PerInteraction,
    public val acquireTimeout: Duration = 30.seconds,
) {
    public companion object {
        public fun auto(acquireTimeout: Duration = 30.seconds): InputIsolationConfig =
            InputIsolationConfig(InputIsolationMode.Auto, acquireTimeout)

        public fun perTest(acquireTimeout: Duration = 30.seconds): InputIsolationConfig =
            InputIsolationConfig(InputIsolationMode.PerTest, acquireTimeout)

        public fun perInteraction(): InputIsolationConfig =
            InputIsolationConfig(InputIsolationMode.PerInteraction)

        public fun off(): InputIsolationConfig = InputIsolationConfig(InputIsolationMode.Off)
    }
}

internal fun interface InputTestLeaseFactory {
    fun acquire(options: InputLeaseOptions, ownerLabel: String): AutomatorInputLease
}

internal object ProductionInputTestLeaseFactory : InputTestLeaseFactory {
    override fun acquire(options: InputLeaseOptions, ownerLabel: String): AutomatorInputLease =
        DesktopInputIsolation.acquire(options.copy(ownerLabel = ownerLabel))
}

internal class ManagedAutomator(
    internal val automator: ComposeAutomator,
    internal val resource: AutoCloseable,
)

internal fun interface ManagedAutomatorFactory {
    fun create(): ManagedAutomator
}

internal fun defaultManagedAutomatorFactory(
    inputIsolation: InputIsolationConfig,
    coordinatedFactory: ManagedAutomatorFactory = ManagedAutomatorFactory {
        val driver = RobotDriver.synthetic(InputLeasePolicy.Required)
        runCatching { ManagedAutomator(ComposeAutomator.inProcess(driver), driver) }
            .onFailure { driver.close() }
            .getOrThrow()
    },
): ManagedAutomatorFactory? = coordinatedFactory.takeIf {
    inputIsolation.mode == InputIsolationMode.PerInteraction
}

internal class InputIsolationSession(
    private val config: InputIsolationConfig,
    private val acquireBeforeAutoFactory: Boolean,
    private val ownerLabel: String,
    private val leaseFactory: InputTestLeaseFactory,
) : ExtensionContext.Store.CloseableResource {
    private var lease: AutomatorInputLease? = null
    private var binding: AutoCloseable? = null
    private var managedResource: AutoCloseable? = null

    fun acquireBeforeFactory() {
        if (
            config.mode == InputIsolationMode.PerTest ||
                (config.mode == InputIsolationMode.Auto && acquireBeforeAutoFactory)
        ) {
            acquire()
        }
    }

    fun bindAfterFactory(automator: ComposeAutomator) {
        if (
            config.mode == InputIsolationMode.Auto &&
                !acquireBeforeAutoFactory &&
                automator.inputCapabilities.requiresDesktopCoordination
        ) {
            acquire()
        }
        lease?.let { acquired -> binding = acquired.bind(automator) }
    }

    fun createAutomator(
        factory: () -> ComposeAutomator,
        managedFactory: ManagedAutomatorFactory? = null,
    ): ComposeAutomator {
        val create = {
            if (managedFactory == null) {
                factory()
            } else {
                check(managedResource == null) { "Managed automator resource is already active" }
                managedFactory
                    .create()
                    .also { managed -> managedResource = managed.resource }
                    .automator
            }
        }
        return lease?.withLease(create) ?: create()
    }

    override fun close() {
        try {
            binding?.close()
        } finally {
            binding = null
            try {
                lease?.close()
            } finally {
                lease = null
                val resource = managedResource
                managedResource = null
                resource?.close()
            }
        }
    }

    private fun acquire() {
        check(lease == null) { "Input isolation lease has already been acquired" }
        lease =
            leaseFactory.acquire(
                InputLeaseOptions(acquireTimeout = config.acquireTimeout),
                ownerLabel,
            )
    }
}
