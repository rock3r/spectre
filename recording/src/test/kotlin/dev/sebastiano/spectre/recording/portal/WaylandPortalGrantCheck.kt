package dev.sebastiano.spectre.recording.portal

import kotlin.system.exitProcess

/** QA-only, noninteractive entry point for checking the session created by the attended warmup. */
fun main() {
    val result = WaylandPortalAccess.preflight()
    println(result.relayMessage())
    if (!result.granted || !result.sessionActive) exitProcess(1)
}
