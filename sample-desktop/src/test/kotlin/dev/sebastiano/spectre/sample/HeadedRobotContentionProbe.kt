@file:JvmName("HeadedRobotContentionProbe")
@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.sample

import dev.sebastiano.spectre.core.InputLeasePolicy
import dev.sebastiano.spectre.core.RobotDriver
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * Child-JVM half of [HeadedRobotContentionTest] (#491).
 *
 * Runs in its own process with its own `RobotDriver(InputLeasePolicy.Required)` so the proof
 * involves two genuinely independent sources of **real OS input**, not two drivers sharing one JVM
 * (which a plain in-process mutex would already serialise) and not two coordinator client sessions
 * that never touch the keyboard (which is what the `input-coord-*` protocol cells already cover,
 * and why they all pass headless).
 *
 * Order of operations, all of it load-bearing:
 * 1. **Warm up.** One coordinated click on the shared text field. This launches or connects the
 *    coordinator, establishes the driver's client session, and focuses the field — every slow,
 *    variable step is spent here, *before* the barrier, so what the barrier releases is two
 *    processes that are one lease acquisition away from typing.
 * 2. **Signal readiness**, then park on the parent's gate file.
 * 3. **Type one block in a single `typeText` call.** One call is deliberate: `RobotDriver` holds
 *    one lease for a whole `typeText`, so a block is the unit the coordinator is allowed to
 *    serialise. Splitting it across calls would take a fresh lease per call and interleaving
 *    between them would be entirely legitimate — the test would then be asserting something the
 *    coordinator never promised.
 * 4. **Record wall-clock timestamps** so the parent can prove demand actually overlapped rather
 *    than assuming it. `currentTimeMillis`, not `nanoTime`: the two are only comparable across
 *    processes as wall clock.
 *
 * Usage: `<screenX> <screenY> <character> <blockLength> <readyFile> <goFile> <outputFile>`
 */
public fun main(arguments: Array<String>) {
    require(arguments.size == EXPECTED_ARGUMENT_COUNT) {
        "Usage: <screenX> <screenY> <character> <blockLength> <readyFile> <goFile> <outputFile>"
    }
    val screenX = arguments[0].toInt()
    val screenY = arguments[1].toInt()
    val character = arguments[2].single()
    val blockLength = arguments[3].toInt()
    val readyFile = Path.of(arguments[4])
    val goFile = Path.of(arguments[5])
    val outputFile = Path.of(arguments[6])

    try {
        runBlocking {
            typeOneBlock(screenX, screenY, character, blockLength, readyFile, goFile, outputFile)
        }
    } catch (failure: Throwable) {
        System.err.println(
            "headed contention probe '$character' failed: ${failure.message}\n" +
                failure.stackTraceToString()
        )
        exitProcess(1)
    }
    exitProcess(0)
}

@Suppress("LongParameterList")
private suspend fun typeOneBlock(
    screenX: Int,
    screenY: Int,
    character: Char,
    blockLength: Int,
    readyFile: Path,
    goFile: Path,
    outputFile: Path,
) {
    RobotDriver(InputLeasePolicy.Required).use { driver ->
        // Warm-up: pays for coordinator launch, client session setup, and focusing the field while
        // nothing is racing. The field is still empty here, so the caret lands at offset 0 for
        // both probes and neither block can be typed into the middle of the other's text.
        driver.click(screenX, screenY)

        Files.writeString(readyFile, "ready\n")
        awaitGate(goFile)

        val requestedAt = System.currentTimeMillis()
        driver.typeText(character.toString().repeat(blockLength))
        val typedTo = System.currentTimeMillis()
        // `typedFrom` is not measurable from here: the lease is acquired inside `typeText`, so the
        // wait for the other probe is folded into the call. `requestedAt` (when this process began
        // wanting the keyboard) and `typedTo` (when it stopped using it) are what the parent needs
        // to prove the two demands overlapped.
        Files.writeString(outputFile, "REQUESTED $requestedAt\nTYPED_TO $typedTo\n")
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
private const val GATE_TIMEOUT_SECONDS: Long = 120
private const val GATE_POLL_MILLIS: Long = 5
