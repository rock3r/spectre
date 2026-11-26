@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BoundedAttachOperationTest {
    @Test
    fun `output larger than pipe capacity cannot deadlock`() {
        val result = run("large", "131072")
        assertEquals(131_072, result.length)
    }

    @Test
    fun `successful output is returned`() =
        assertEquals("ordinary-result", run("success", "ordinary-result"))

    @Test
    fun `primary failure retains helper diagnostics`() {
        val error = assertFailsWith<SpectreAttachException> { run("failure", "diagnostic") }
        assertTrue(error.message.orEmpty().contains("diagnostic"))
    }

    @Test
    fun `string launch error is preserved`() {
        val error =
            assertFailsWith<java.io.IOException> {
                BoundedAttachOperation.run(listOf(fresh("missing").toString()), 1_000, { true })
            }
        assertTrue(error.message.orEmpty().isNotBlank())
    }

    @Test
    fun `timeout reaps helper without killing target`() {
        val target = ProcessBuilder(command("sleep", "10000")).start()
        val helper = AtomicReference<Process>()
        try {
            val error =
                assertFailsWith<SpectreAttachException> {
                    BoundedAttachOperation.run(
                        command("block"),
                        2_000,
                        { true },
                        startProcess = { command ->
                            ProcessBuilder(command).start().also(helper::set)
                        },
                    )
                }
            assertTrue(error.message.orEmpty().contains("did not complete within 2000ms"))
            val ownedHelper = checkNotNull(helper.get())
            assertNotEquals(target.pid(), ownedHelper.pid())
            assertFalse(ownedHelper.isAlive)
            assertTrue(target.isAlive, "cleanup must not kill the target")
        } finally {
            target.destroyForcibly()
        }
    }

    @Test
    fun `interrupt is structured restored and leaves no reader thread`() {
        val helper = AtomicReference<Process>()
        val helperStarted = CountDownLatch(1)
        val thrown = AtomicReference<Throwable>()
        val restored = AtomicBoolean()
        val worker = thread {
            try {
                BoundedAttachOperation.run(
                    command("block"),
                    30_000,
                    { true },
                    startProcess = { command ->
                        ProcessBuilder(command).start().also {
                            helper.set(it)
                            helperStarted.countDown()
                        }
                    },
                )
            } catch (error: Throwable) {
                thrown.set(error)
                restored.set(Thread.currentThread().isInterrupted)
            }
        }
        assertTrue(helperStarted.await(10, TimeUnit.SECONDS), "helper did not start")
        worker.interrupt()
        worker.join(10_000)
        assertFalse(worker.isAlive)
        val error = assertIs<SpectreAttachException>(thrown.get())
        assertIs<InterruptedException>(error.cause)
        assertTrue(restored.get())
        assertFalse(checkNotNull(helper.get()).isAlive)
        assertFalse(
            Thread.getAllStackTraces().keys.any {
                !it.isDaemon && it.name.startsWith("spectre-attach-helper-")
            }
        )
    }

    private fun run(mode: String, value: String) =
        BoundedAttachOperation.run(command(mode, value), 5_000, { true })

    private fun fresh(label: String): Path =
        Files.createTempFile("spectre-$label-", ".marker").also(Files::deleteIfExists)

    private fun command(vararg args: String) =
        listOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("java.class.path"),
            BoundedAttachOperationFixture::class.java.name,
            *args,
        )
}

object BoundedAttachOperationFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args[0]) {
            "success" -> println(args[1])
            "large" -> print("x".repeat(args[1].toInt()))
            "failure" -> {
                System.err.println(args[1])
                kotlin.system.exitProcess(7)
            }
            "block" -> {
                Thread.sleep(60_000)
            }
            "sleep" -> Thread.sleep(args[1].toLong())
        }
    }
}
