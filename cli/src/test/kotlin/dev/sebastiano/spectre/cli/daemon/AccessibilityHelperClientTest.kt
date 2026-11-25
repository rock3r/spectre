package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import org.junit.jupiter.api.io.TempDir

class AccessibilityHelperClientTest {
    @Test
    fun `decodes a recorded successful helper response without a desktop`() {
        val response = AccessibilityHelperClient { _, _, _ ->
            javaClass.getResource("/accessibility/success.json")!!.readText()
        }
            .snapshot(
                AccessibilitySnapshotRequest(Target.ProcessId(42), 20, 2_000, 750),
                Path.of("recorded-helper"),
            )

        val success = assertIs<AccessibilitySnapshotResponse.Success>(response)
        assertEquals("w0/app/0", success.snapshot.nodes.single().key)
        assertEquals(BoundsSpace.Screen, success.snapshot.windows.single().boundsSpace)
        assertNull(success.snapshot.nodes.single().bounds)
        assertNull(success.snapshot.nodes.single().onScreen)
    }

    @Test
    fun `decodes coherent limit error partial snapshot`() {
        val response = AccessibilityHelperClient { _, _, _ ->
            javaClass.getResource("/accessibility/limit-reached.json")!!.readText()
        }
            .snapshot(
                AccessibilitySnapshotRequest(Target.ProcessId(42), 20, 1, 750),
                Path.of("recorded-helper"),
            )
        val snapshot =
            assertIs<AccessibilitySnapshotResponse.Failure>(response).error.partialSnapshot
        assertEquals(2, snapshot?.schemaVersion)
        assertEquals("os-accessibility", snapshot?.source)
        assertEquals("w0/root", snapshot?.nodes?.single()?.key)
        assertNull(snapshot?.nodes?.single()?.bounds)
        assertNull(snapshot?.nodes?.single()?.onScreen)
    }

    @Test
    fun `rejects limit error without coherent partial snapshot`() {
        val request = AccessibilitySnapshotRequest(Target.ProcessId(1), 1, 1, 100)
        val helper = Path.of("recorded-helper")
        assertFailsWith<IllegalArgumentException> {
            AccessibilityHelperClient { _, _, _ ->
                    """{"ok":false,"error":{"code":"limit-reached","message":"limit"}}"""
                }
                .snapshot(request, helper)
        }
        val invalid =
            javaClass
                .getResource("/accessibility/limit-reached.json")!!
                .readText()
                .replace("\"source\": \"os-accessibility\"", "\"source\": \"compose-semantics\"")
        assertFailsWith<IllegalArgumentException> {
            AccessibilityHelperClient { _, _, _ -> invalid }.snapshot(request, helper)
        }
    }

    @Test
    fun `passes command and bounded request to helper`() {
        var stdin = ""
        val helper = Files.createTempFile("spectre-helper", "")
        val client = AccessibilityHelperClient { argv, input, _ ->
            assertEquals(listOf(helper.toString(), "accessibility-snapshot"), argv)
            stdin = input
            """{"ok":false,"error":{"code":"target-not-found","message":"gone"}}"""
        }

        val response =
            client.snapshot(AccessibilitySnapshotRequest(Target.WindowId("99"), 4, 5, 100), helper)

        assertIs<AccessibilitySnapshotResponse.Failure>(response)
        assertTrue(stdin.contains("\"maximumNodeCount\":5"))
    }

    @Test
    fun `rejects incoherent envelope and invalid snapshot identity`() {
        val request = AccessibilitySnapshotRequest(Target.ProcessId(1), 1, 1, 100)
        val helper = Path.of("recorded-helper")
        assertFailsWith<IllegalArgumentException> {
            AccessibilityHelperClient { _, _, _ ->
                    """{"ok":true,"snapshot":null,"error":{"code":"timeout","message":"late"}}"""
                }
                .snapshot(request, helper)
        }
        val invalid =
            javaClass
                .getResource("/accessibility/success.json")!!
                .readText()
                .replace("\"schemaVersion\": 2", "\"schemaVersion\": 1")
        assertFailsWith<IllegalArgumentException> {
            AccessibilityHelperClient { _, _, _ -> invalid }.snapshot(request, helper)
        }
    }

    @Test
    fun `validates process and window targets`() {
        assertFailsWith<IllegalArgumentException> { Target.ProcessId(0) }
        assertFailsWith<IllegalArgumentException> { Target.WindowId("  ") }
    }

    @Test
    fun `real cross-platform helper and its descendant are forcibly bounded on timeout`(
        @TempDir directory: Path
    ) {
        withReadyHelper(directory) { helper, child ->
            val elapsed = measureTime {
                val error =
                    assertFailsWith<IllegalStateException> {
                        exchangeWithHelper(helper, "{}", 2_000)
                    }
                assertEquals("Accessibility helper exceeded its 2000ms deadline", error.message)
            }
            assertTrue(elapsed < 4.seconds, "helper took $elapsed to be reaped")
            assertFalse(helper.isAlive, "helper survived timeout")
            assertFalse(child.isAlive, "child survived timeout")
        }
    }

    @Test
    fun `interruption still reaps descendants and preserves interrupt status`(
        @TempDir directory: Path
    ) {
        withReadyHelper(directory) { helper, child ->
            val caught = AtomicReference<Throwable>()
            val interruptPreserved = AtomicBoolean()
            val worker = Thread {
                try {
                    exchangeWithHelper(helper, "{}", 30_000)
                } catch (throwable: Throwable) {
                    caught.set(throwable)
                    interruptPreserved.set(Thread.currentThread().isInterrupted)
                }
            }
            worker.start()
            try {
                awaitFixture("helper to receive request") {
                    Files.exists(directory.resolve("request-received"))
                }
            } finally {
                worker.interrupt()
            }
            worker.join(2_000)
            assertFalse(worker.isAlive, "interrupted helper cleanup was not bounded")
            assertIs<InterruptedException>(caught.get())
            assertTrue(interruptPreserved.get(), "interrupt status was not restored")
            assertFalse(helper.isAlive, "helper survived interruption")
            assertFalse(child.isAlive, "child survived interruption")
        }
    }

    @Test
    fun `deadline bounds a helper still starting up`() {
        val elapsed = measureTime {
            val error =
                assertFailsWith<IllegalStateException> {
                    AccessibilityHelperClient(command = { fixtureCommand("sleep") })
                        .snapshot(request(timeoutMs = 2_000), Path.of("fixture"))
                }
            assertEquals("Accessibility helper exceeded its 2000ms deadline", error.message)
        }
        assertTrue(elapsed < 4.seconds, "helper startup took $elapsed")
    }

    @Test
    fun `real helper startup failure is reported`() {
        val missing = Files.createTempDirectory("missing-helper").resolve("does-not-exist")
        assertFailsWith<java.io.IOException> {
            AccessibilityHelperClient().snapshot(request(timeoutMs = 50), missing)
        }
    }

    private fun withReadyHelper(directory: Path, test: (Process, ProcessHandle) -> Unit) {
        val helper = ProcessBuilder(fixtureCommand("descendant", directory.toString())).start()
        try {
            awaitFixture("descendant startup") { Files.exists(directory.resolve("child-ready")) }
            val pid = Files.readString(directory.resolve("child.pid")).toLong()
            val child =
                helper.children().use { children ->
                    children.filter { it.pid() == pid }.findFirst().orElseThrow()
                }
            assertTrue(child.isAlive, "fixture child exited before request")
            try {
                test(helper, child)
            } finally {
                if (child.isAlive) child.destroyForcibly()
            }
        } finally {
            helper.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
            helper.destroyForcibly()
        }
    }

    private fun fixtureCommand(vararg args: String) =
        listOf(
            javaExecutable(),
            "-cp",
            System.getProperty("java.class.path"),
            AccessibilityHelperFixture::class.java.name,
            *args,
        )

    private fun request(timeoutMs: Long) =
        AccessibilitySnapshotRequest(Target.ProcessId(1), 1, 1, timeoutMs)

    private fun awaitFixture(description: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!ready()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $description" }
            Thread.sleep(10)
        }
    }

    private fun javaExecutable(): String =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
            )
            .toString()
}
