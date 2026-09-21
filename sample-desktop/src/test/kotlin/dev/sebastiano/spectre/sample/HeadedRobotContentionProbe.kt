@file:JvmName("HeadedRobotContentionProbe")
@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.sample

import dev.sebastiano.spectre.core.InputLeasePolicy
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.core.clickAndTypeText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
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
 * 2. **Signal readiness**, then park on the parent's gate file. While parked, a nudge file holding
 *    `x y` is one more click at those screen coordinates. The parent writes it when the shared
 *    field is still unfocused; the click is outside the measured `typeText` lease.
 * 3. **Click the latest aim point and type one block under one lease.** [clickAndTypeText] holds
 *    the desktop lease across the click, a short focus settle, End (so the caret is not left in the
 *    middle of text the other probe already typed), and the characters. One lease is deliberate:
 *    splitting the click and the typing would let the other JVM steal the foreground between them.
 *    The block stays one `typeText`, which is the unit the coordinator serialises.
 * 4. **Record wall-clock timestamps** so the parent can prove demand actually overlapped rather
 *    than assuming it. `currentTimeMillis`, not `nanoTime`: the two are only comparable across
 *    processes as wall clock.
 *
 * Usage: `<screenX> <screenY> <character> <blockLength> <readyFile> <goFile> <nudgeFile>
 * <outputFile>`
 */
public fun main(arguments: Array<String>) {
    require(arguments.size == EXPECTED_ARGUMENT_COUNT) {
        "Usage: <screenX> <screenY> <character> <blockLength> <readyFile> <goFile> " +
            "<nudgeFile> <outputFile>"
    }
    val screenX = arguments[0].toInt()
    val screenY = arguments[1].toInt()
    val character = arguments[2].single()
    val blockLength = arguments[3].toInt()
    val readyFile = Path.of(arguments[4])
    val goFile = Path.of(arguments[5])
    val nudgeFile = Path.of(arguments[6])
    val outputFile = Path.of(arguments[7])

    try {
        runBlocking {
            typeOneBlock(
                screenX,
                screenY,
                character,
                blockLength,
                readyFile,
                goFile,
                nudgeFile,
                outputFile,
            )
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
    nudgeFile: Path,
    outputFile: Path,
) {
    RobotDriver(InputLeasePolicy.Required).use { driver ->
        // Warm-up: pays for coordinator launch, client session setup, and focusing the field while
        // nothing is racing. The field is still empty here, so the caret lands at offset 0 for
        // both probes and neither block can be typed into the middle of the other's text.
        driver.click(screenX, screenY)

        Files.writeString(readyFile, "ready\n")
        awaitGate(goFile, nudgeFile, driver)
        val aim = readAim(readyFile.parent.resolve("aim")) ?: (screenX to screenY)

        val requestedAt = System.currentTimeMillis()
        driver.clickAndTypeText(aim.first, aim.second, character.toString().repeat(blockLength))
        val typedTo = System.currentTimeMillis()
        // `requestedAt` is before the lease is acquired, so it includes the wait while the other
        // probe still holds the keyboard. `typedTo` is when this probe stopped using it.
        Files.writeString(outputFile, "REQUESTED $requestedAt\nTYPED_TO $typedTo\n")
    }
}

private suspend fun awaitGate(goFile: Path, nudgeFile: Path, driver: RobotDriver) {
    val deadline = System.nanoTime() + Duration.ofSeconds(GATE_TIMEOUT_SECONDS).toNanos()
    while (System.nanoTime() < deadline) {
        applyNudge(nudgeFile, driver)
        if (Files.exists(goFile)) return
        delay(GATE_POLL_MILLIS)
    }
    error("parent never opened the contention gate at $goFile")
}

private suspend fun applyNudge(nudgeFile: Path, driver: RobotDriver) {
    if (!Files.isRegularFile(nudgeFile)) return
    val text = runCatching { Files.readString(nudgeFile) }.getOrNull() ?: return
    val target = parseNudgeTarget(text) ?: return
    // Delete before the click so a later parent write is a new nudge, not a spin on this one.
    runCatching { Files.deleteIfExists(nudgeFile) }
    driver.click(target.first, target.second)
}

private fun readAim(aimFile: Path): Pair<Int, Int>? {
    if (!Files.isRegularFile(aimFile)) return null
    val text = runCatching { Files.readString(aimFile) }.getOrNull() ?: return null
    return parseNudgeTarget(text)
}

private const val EXPECTED_ARGUMENT_COUNT: Int = 8
private const val GATE_TIMEOUT_SECONDS: Long = 120
private const val GATE_POLL_MILLIS: Long = 5
