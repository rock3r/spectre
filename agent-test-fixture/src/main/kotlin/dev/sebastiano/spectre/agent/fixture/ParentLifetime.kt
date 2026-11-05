package dev.sebastiano.spectre.agent.fixture

import kotlin.system.exitProcess

/**
 * Ends this fixture JVM when the process that launched it exits.
 *
 * A fixture idles until the test that spawned it destroys it. If the test JVM dies first (a
 * cancelled run, a killed Gradle daemon), nothing destroys the fixture, and its window stays on the
 * desktop. Watching the parent closes that gap on every OS.
 *
 * [parent] is null when the JVM has no parent to watch; the fixture then keeps the old behaviour.
 */
internal fun exitWhenParentExits(
    parent: ProcessHandle? = ProcessHandle.current().parent().orElse(null),
    exit: () -> Unit = { exitProcess(PARENT_EXITED_STATUS) },
) {
    if (parent == null) return
    parent.onExit().thenRun {
        System.err.println("[fixture] parent process ${parent.pid()} exited; exiting")
        System.err.flush()
        exit()
    }
}

/** Exit status a fixture uses when it ends because its parent process is gone. */
internal const val PARENT_EXITED_STATUS: Int = 3
