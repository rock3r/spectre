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
 * not two client sessions sharing one. Connects, announces readiness, waits on a parent-controlled
 * barrier, then acquires the desktop lease and records the wall-clock interval it held it.
 *
 * The barrier matters: JVM startup easily outlasts the hold, so simply launching the two probes
 * back to back could let the first release before the second even asks. Their intervals would then
 * be disjoint whether or not the coordinator enforces anything, and the test would pass vacuously.
 * Both probes connect first and block until the parent opens the gate, so demand genuinely
 * overlaps.
 *
 * Wall-clock (`currentTimeMillis`) is deliberate: `nanoTime` is only comparable within one JVM, and
 * the whole point here is comparing intervals recorded by two different processes.
 *
 * Usage: `<socketPath> <resourceKey> <ownerLabel> <holdMillis> <outputFile> <readyFile> <goFile>`
 */
public fun main(arguments: Array<String>) {
    require(arguments.size == EXPECTED_ARGUMENT_COUNT) {
        "Usage: <socketPath> <resourceKey> <ownerLabel> <holdMillis> <outputFile> " +
            "<readyFile> <goFile>"
    }
    val socketPath = Path.of(arguments[0])
    val resourceKey = DesktopResourceKey(arguments[1])
    val ownerLabel = arguments[2]
    val holdMillis = arguments[3].toLong()
    val outputFile = Path.of(arguments[4])
    val readyFile = Path.of(arguments[5])
    val goFile = Path.of(arguments[6])

    val endpoint = CoordinatorEndpoint(socketPath.parent, socketPath)
    LocalInputCoordinatorClient.connect(endpoint, resourceKey, ownerLabel).use { client ->
        // Connected and about to contend: tell the parent, then wait for both probes to be here.
        Files.writeString(readyFile, "ready\n")
        awaitGate(goFile)
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

private fun awaitGate(goFile: Path) {
    val deadline = System.nanoTime() + Duration.ofSeconds(GATE_TIMEOUT_SECONDS).toNanos()
    while (System.nanoTime() < deadline) {
        if (Files.exists(goFile)) return
        Thread.sleep(GATE_POLL_MILLIS)
    }
    error("parent never opened the contention gate at $goFile")
}

private const val EXPECTED_ARGUMENT_COUNT: Int = 7
private const val ACQUIRE_TIMEOUT_SECONDS: Long = 30
private const val GATE_TIMEOUT_SECONDS: Long = 60
private const val GATE_POLL_MILLIS: Long = 5
