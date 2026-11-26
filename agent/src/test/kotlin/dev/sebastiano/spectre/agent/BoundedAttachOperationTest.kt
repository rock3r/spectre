@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
        val marker = fresh("timeout")
        try {
            assertFailsWith<SpectreAttachException> {
                BoundedAttachOperation.run(command("block", marker.toString()), 2_000, { true })
            }
            val pid = awaitPid(marker)
            assertFalse(ProcessHandle.of(pid).isPresent)
            assertTrue(target.isAlive, "cleanup must not kill the target")
        } finally {
            target.destroyForcibly()
            Files.deleteIfExists(marker)
        }
    }

    @Test
    fun `interrupt is structured restored and leaves no reader thread`() {
        val marker = fresh("interrupt")
        val thrown = AtomicReference<Throwable>()
        val restored = AtomicBoolean()
        val worker = thread {
            try {
                BoundedAttachOperation.run(command("block", marker.toString()), 30_000, { true })
            } catch (error: Throwable) {
                thrown.set(error)
                restored.set(Thread.currentThread().isInterrupted)
            }
        }
        val pid = awaitPid(marker)
        worker.interrupt()
        worker.join(10_000)
        assertFalse(worker.isAlive)
        val error = assertIs<SpectreAttachException>(thrown.get())
        assertIs<InterruptedException>(error.cause)
        assertTrue(restored.get())
        assertFalse(ProcessHandle.of(pid).isPresent)
        assertFalse(
            Thread.getAllStackTraces().keys.any {
                !it.isDaemon && it.name.startsWith("spectre-attach-helper-")
            }
        )
        Files.deleteIfExists(marker)
    }

    private fun run(mode: String, value: String) =
        BoundedAttachOperation.run(command(mode, value), 5_000, { true })

    private fun fresh(label: String): Path =
        Files.createTempFile("spectre-$label-", ".marker").also(Files::deleteIfExists)

    private fun awaitPid(marker: Path): Long {
        repeat(200) {
            if (Files.exists(marker)) {
                Files.readString(marker).trim().toLongOrNull()?.let { pid ->
                    return pid
                }
            }
            Thread.sleep(25)
        }
        error("helper did not publish pid")
    }

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
                Files.writeString(Path.of(args[1]), ProcessHandle.current().pid().toString())
                Thread.sleep(60_000)
            }
            "sleep" -> Thread.sleep(args[1].toLong())
        }
    }
}
