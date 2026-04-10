package dev.sebastiano.spectre.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

suspend fun <T : Any> waitUntil(
    timeout: Duration = 5.seconds,
    pollInterval: Duration = 100.milliseconds,
    predicate: () -> T?,
): T =
    withTimeout(timeout) {
        while (true) {
            predicate()?.let {
                return@withTimeout it
            }
            delay(pollInterval)
        }

        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
