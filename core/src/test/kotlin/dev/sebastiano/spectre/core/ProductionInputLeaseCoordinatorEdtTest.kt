@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.core

import dev.sebastiano.spectre.input.CoordinatorEndpoint
import dev.sebastiano.spectre.input.DesktopResourceKey
import dev.sebastiano.spectre.input.InputCoordinatorException
import dev.sebastiano.spectre.input.server.LocalCoordinatorServer
import java.net.StandardProtocolFamily
import java.net.URLClassLoader
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking

class ProductionInputLeaseCoordinatorEdtTest {
    @Test
    fun `cold production coordinator resolves no endpoint while it is constructed`() {
        var endpointLookups = 0
        var resourceLookups = 0
        val coordinator =
            ProductionInputLeaseCoordinator(
                endpoint = { endpointLookups++.let { error("endpoint should stay lazy") } },
                resourceKey = { resourceLookups++.let { error("resource should stay lazy") } },
            )

        coordinator.close()
        assertEquals(0, endpointLookups)
        assertEquals(0, resourceLookups)
    }

    @Test
    fun `auto falls back cold on EDT when provider and standalone are genuinely absent`() {
        withoutCoordinatorProvider {
            withCoordinatorFixture("ca") { endpoint, resource ->
                val coordinator = productionCoordinator(endpoint, resource)
                val driver = realDriver(coordinator, policy = InputLeasePolicy.Auto)
                var failure: Throwable? = null
                SwingUtilities.invokeAndWait {
                    withoutCoordinatorProvider {
                        failure =
                            runCatching { runBlocking { driver.click(1, 2) } }.exceptionOrNull()
                    }
                }
                coordinator.close()
                assertEquals(null, failure)
            }
        }
    }

    @Test
    fun `required fails cold on EDT when provider and standalone are absent`() {
        withoutCoordinatorProvider {
            withCoordinatorFixture("cr") { endpoint, resource ->
                val coordinator = productionCoordinator(endpoint, resource)
                val driver = realDriver(coordinator, policy = InputLeasePolicy.Required)
                var failure: Throwable? = null
                SwingUtilities.invokeAndWait {
                    withoutCoordinatorProvider {
                        failure =
                            runCatching { runBlocking { driver.click(1, 2) } }.exceptionOrNull()
                    }
                }
                coordinator.close()
                assertTrue(failure is ContendedEdtInputLeaseException, "was $failure")
                assertEquals(
                    "COORDINATOR_PROVIDER_MISSING",
                    (failure?.cause as InputCoordinatorException).errorCode,
                )
            }
        }
    }

    @Test
    fun `auto fails closed cold on EDT when an independent socket is listening`() {
        withoutCoordinatorProvider {
            withCoordinatorFixture("cl") { endpoint, resource ->
                ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { listener ->
                    listener.bind(UnixDomainSocketAddress.of(endpoint.socketPath))
                    val coordinator = productionCoordinator(endpoint, resource)
                    val driver = realDriver(coordinator, policy = InputLeasePolicy.Auto)
                    var failure: Throwable? = null
                    SwingUtilities.invokeAndWait {
                        withoutCoordinatorProvider {
                            failure =
                                runCatching { runBlocking { driver.click(1, 2) } }.exceptionOrNull()
                        }
                    }
                    coordinator.close()
                    assertTrue(failure is ContendedEdtInputLeaseException, "was $failure")
                    assertEquals(
                        "COORDINATOR_SESSION_UNAVAILABLE",
                        (failure?.cause as InputCoordinatorException).errorCode,
                    )
                }
            }
        }
    }

    @Test
    fun `auto fails closed on EDT when a live session owner differs`() {
        withCoordinatorFixture("om") { endpoint, resource ->
            val server =
                LocalCoordinatorServer(endpoint, idleTimeout = Duration.ofMinutes(1)).also {
                    it.start()
                }
            val coordinator = productionCoordinator(endpoint, resource)
            try {
                runBlocking {
                    coordinator
                        .acquire(InputLeaseOptions(ownerLabel = "first"), "warm", immediate = false)
                        .close()
                }
                var failure: Throwable? = null
                SwingUtilities.invokeAndWait {
                    failure =
                        runCatching {
                            runBlocking {
                                coordinator.acquire(
                                    InputLeaseOptions(ownerLabel = "second"),
                                    "click",
                                    immediate = true,
                                )
                            }
                        }
                            .exceptionOrNull()
                }
                assertEquals(
                    "COORDINATOR_SESSION_UNAVAILABLE",
                    (failure as InputCoordinatorException).errorCode,
                )
            } finally {
                coordinator.close()
                server.close()
            }
        }
    }

    @Test
    fun `genuine absence learned off EDT remains available to immediate acquisition`() {
        withoutCoordinatorProvider {
            withCoordinatorFixture("wa") { endpoint, resource ->
                val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
                val coordinator =
                    ProductionInputLeaseCoordinator(
                        ioDispatcher = dispatcher,
                        endpoint = { endpoint },
                        resourceKey = { resource },
                    )
                try {
                    val blockingFailure =
                        assertFailsWith<InputCoordinatorException> {
                            runBlocking {
                                coordinator.acquire(InputLeaseOptions(), "first", immediate = false)
                            }
                        }
                    val immediateFailure =
                        assertFailsWith<InputCoordinatorException> {
                            runBlocking {
                                coordinator.acquire(InputLeaseOptions(), "second", immediate = true)
                            }
                        }
                    assertEquals("COORDINATOR_PROVIDER_MISSING", blockingFailure.errorCode)
                    assertEquals("COORDINATOR_PROVIDER_MISSING", immediateFailure.errorCode)
                } finally {
                    coordinator.close()
                    dispatcher.close()
                }
            }
        }
    }

    @Test
    fun `provider visibility is reevaluated for each immediate EDT call`() {
        withCoordinatorFixture("tc") { endpoint, resource ->
            val coordinator = productionCoordinator(endpoint, resource)
            val driver = realDriver(coordinator, policy = InputLeasePolicy.Auto)
            val providerLoader = Thread.currentThread().contextClassLoader
            var firstFailure: Throwable? = null
            var secondFailure: Throwable? = null

            SwingUtilities.invokeAndWait {
                withoutCoordinatorProvider {
                    firstFailure =
                        runCatching { runBlocking { driver.click(1, 2) } }.exceptionOrNull()
                }
                val thread = Thread.currentThread()
                val previous = thread.contextClassLoader
                thread.contextClassLoader = providerLoader
                try {
                    secondFailure =
                        runCatching { runBlocking { driver.click(3, 4) } }.exceptionOrNull()
                } finally {
                    thread.contextClassLoader = previous
                }
            }

            coordinator.close()
            assertEquals(null, firstFailure)
            assertTrue(secondFailure is ContendedEdtInputLeaseException, "was $secondFailure")
            assertEquals(
                "COORDINATOR_SESSION_UNAVAILABLE",
                (secondFailure?.cause as InputCoordinatorException).errorCode,
            )
        }
    }

    @Test
    fun `installed provider fails closed without starting it on cold immediate acquisition`() {
        val coordinator = ProductionInputLeaseCoordinator()
        val failure =
            assertFailsWith<InputCoordinatorException> {
                runBlocking { coordinator.acquire(InputLeaseOptions(), "click", immediate = true) }
            }
        coordinator.close()
        assertEquals("COORDINATOR_SESSION_UNAVAILABLE", failure.errorCode)
    }
}

private fun productionCoordinator(
    endpoint: CoordinatorEndpoint,
    resource: DesktopResourceKey,
): ProductionInputLeaseCoordinator =
    ProductionInputLeaseCoordinator(endpoint = { endpoint }, resourceKey = { resource })

private fun <T> withCoordinatorFixture(
    name: String,
    block: (CoordinatorEndpoint, DesktopResourceKey) -> T,
): T {
    val directory = compactTemporaryDirectory("spc-$name-")
    val endpoint = CoordinatorEndpoint(directory, directory.resolve("coordinator.sock"))
    return try {
        block(endpoint, DesktopResourceKey("test/$name"))
    } finally {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

private fun compactTemporaryDirectory(prefix: String): Path {
    val compactRoot = Path.of("/tmp")
    return if (
        !System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
            Files.isDirectory(compactRoot)
    ) {
        Files.createTempDirectory(compactRoot, prefix)
    } else {
        Files.createTempDirectory(prefix)
    }
}

private fun <T> withoutCoordinatorProvider(block: () -> T): T {
    val thread = Thread.currentThread()
    val previous = thread.contextClassLoader
    return URLClassLoader(emptyArray(), null).use { emptyClassLoader ->
        thread.contextClassLoader = emptyClassLoader
        try {
            block()
        } finally {
            thread.contextClassLoader = previous
        }
    }
}
