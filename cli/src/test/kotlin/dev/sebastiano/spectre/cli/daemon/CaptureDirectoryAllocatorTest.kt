package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureDirectoryAllocatorTest {
    @Test
    fun `allocates sequential NNNN-timestamp directories under the root`() {
        val root = Files.createTempDirectory("spectre-capture-root")
        val first = CaptureDirectoryAllocator.allocate(root)
        val second = CaptureDirectoryAllocator.allocate(root)

        assertTrue(first.fileName.toString().startsWith("0001-"))
        assertTrue(second.fileName.toString().startsWith("0002-"))
        assertTrue(Files.isDirectory(first))
        assertTrue(Files.isDirectory(second))
        assertEquals(root, first.parent)
        assertEquals(root, second.parent)
    }

    @Test
    fun `continues sequence after existing directories`() {
        val root = Files.createTempDirectory("spectre-capture-root")
        Files.createDirectory(root.resolve("0007-already-there"))
        Files.createDirectory(root.resolve("not-a-capture"))

        val next = CaptureDirectoryAllocator.allocate(root)
        assertTrue(next.fileName.toString().startsWith("0008-"))
    }

    @Test
    fun `concurrent allocators never collide on the same root`() {
        val root = Files.createTempDirectory("spectre-capture-root")
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures =
                (1..40).map {
                    pool.submit(
                        Callable { CaptureDirectoryAllocator.allocate(root).fileName.toString() }
                    )
                }
            val names = futures.map { it.get(10, TimeUnit.SECONDS) }.toSet()
            assertEquals(40, names.size)
            assertEquals(40, Files.list(root).use { it.count() })
        } finally {
            pool.shutdownNow()
        }
    }
}
