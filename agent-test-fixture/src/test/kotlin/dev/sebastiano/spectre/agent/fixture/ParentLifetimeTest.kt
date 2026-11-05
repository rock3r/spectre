package dev.sebastiano.spectre.agent.fixture

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParentLifetimeTest {

    @Test
    fun `exits when the parent process exits`() {
        val parent = startShortLivedProcess()
        val exited = CountDownLatch(1)

        exitWhenParentExits(parent = parent.toHandle(), exit = exited::countDown)

        assertTrue(
            exited.await(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "the fixture must exit once its parent process is gone",
        )
    }

    @Test
    fun `keeps running while the parent process is alive`() {
        val exited = CountDownLatch(1)

        // The test JVM's own parent (the Gradle daemon or IDE) outlives this test.
        val parent = ProcessHandle.current().parent().orElseThrow()

        exitWhenParentExits(parent = parent, exit = exited::countDown)

        assertFalse(
            exited.await(ALIVE_CHECK_MILLIS, TimeUnit.MILLISECONDS),
            "the fixture must not exit while its parent is alive",
        )
    }

    @Test
    fun `keeps running when there is no parent to watch`() {
        val exited = CountDownLatch(1)

        exitWhenParentExits(parent = null, exit = exited::countDown)

        assertFalse(exited.await(ALIVE_CHECK_MILLIS, TimeUnit.MILLISECONDS))
    }

    private fun startShortLivedProcess(): Process {
        val java = File(System.getProperty("java.home"), "bin/java").path
        return ProcessBuilder(java, "-version").redirectErrorStream(true).start()
    }

    private companion object {
        const val EXIT_TIMEOUT_SECONDS: Long = 30
        const val ALIVE_CHECK_MILLIS: Long = 300
    }
}
