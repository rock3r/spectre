@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.CoordinatorEndpoint
import dev.sebastiano.spectre.input.DesktopResourceKey
import dev.sebastiano.spectre.input.LocalInputCoordinatorClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Child-JVM half of [TwoClientJvmContentionTest].
 *
 * Runs in its own process so the contention proof involves two genuinely independent client JVMs,
 * not two client sessions sharing one. Acquires the desktop lease, records the wall-clock interval
 * it held it, and releases.
 *
 * Wall-clock (`currentTimeMillis`) is deliberate: `nanoTime` is only comparable within one JVM, and
 * the whole point here is comparing intervals recorded by two different processes.
 *
 * Usage: `<socketPath> <resourceKey> <ownerLabel> <holdMillis> <outputFile>`
 */
public fun main(arguments: Array<String>) {
    require(arguments.size == EXPECTED_ARGUMENT_COUNT) {
        "Usage: <socketPath> <resourceKey> <ownerLabel> <holdMillis> <outputFile>"
    }
    val socketPath = Path.of(arguments[0])
    val resourceKey = DesktopResourceKey(arguments[1])
    val ownerLabel = arguments[2]
    val holdMillis = arguments[3].toLong()
    val outputFile = Path.of(arguments[4])

    val endpoint = CoordinatorEndpoint(socketPath.parent, socketPath)
    LocalInputCoordinatorClient.connect(endpoint, resourceKey, ownerLabel).use { client ->
        client.acquire(Duration.ofSeconds(ACQUIRE_TIMEOUT_SECONDS), "click").use {
            val acquiredAt = System.currentTimeMillis()
            Thread.sleep(holdMillis)
            // Written before the lease closes: a reader can then prove the two processes' held
            // intervals never overlapped.
            val releasedAt = System.currentTimeMillis()
            Files.writeString(outputFile, "ACQUIRED $acquiredAt\nRELEASED $releasedAt\n")
        }
    }
}

private const val EXPECTED_ARGUMENT_COUNT: Int = 5
private const val ACQUIRE_TIMEOUT_SECONDS: Long = 30
