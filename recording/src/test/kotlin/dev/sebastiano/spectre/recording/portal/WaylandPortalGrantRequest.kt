package dev.sebastiano.spectre.recording.portal

import kotlin.system.exitProcess

/**
 * Entry point of `:recording:requestWaylandPortalGrant`: [WaylandPortalAccess.request], the same
 * operation as `spectre permissions request`. The first argument is the wait for each dialog step,
 * in seconds.
 */
fun main(args: Array<String>) {
    val timeoutSeconds =
        args.firstOrNull()?.toLongOrNull() ?: WaylandPortalAccess.DEFAULT_REQUEST_TIMEOUT_SECONDS
    val result = WaylandPortalAccess.request(timeoutSeconds)
    println(result.relayMessage())
    if (!result.granted) exitProcess(1)
}
