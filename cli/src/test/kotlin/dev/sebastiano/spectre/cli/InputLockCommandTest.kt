@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import dev.sebastiano.spectre.input.CoordinatorEndpoint
import dev.sebastiano.spectre.input.DesktopResourceKey
import dev.sebastiano.spectre.input.LocalInputCoordinatorClient
import dev.sebastiano.spectre.input.server.LocalCoordinatorServer
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class InputLockCommandTest {

    private val temporaryDirectory = Files.createTempDirectory("spc-cli-")
    private val endpoint =
        CoordinatorEndpoint(temporaryDirectory, temporaryDirectory.resolve("coordinator.sock"))
    private val resource = DesktopResourceKey("user:501/macos-console")
    private val resources = mutableListOf<AutoCloseable>()

    @AfterTest
    fun cleanUp() {
        resources.asReversed().forEach { runCatching { it.close() } }
        Files.deleteIfExists(endpoint.socketPath)
        Files.deleteIfExists(temporaryDirectory.resolve("coordinator.lock"))
        Files.deleteIfExists(temporaryDirectory.resolve("recovery.properties.tmp"))
        Files.deleteIfExists(temporaryDirectory.resolve("recovery.properties"))
        Files.deleteIfExists(temporaryDirectory)
    }

    @Test
    fun `status json reports no active coordinator without launching one`() {
        val output = StringBuilder()

        InputLockCommand(output, endpoint, resource).parse(listOf("status", "--json"))

        val json = Json.parseToJsonElement(output.toString()).jsonObject
        assertTrue(json.getValue("noActiveCoordinator").jsonPrimitive.boolean)
        assertFalse(Files.exists(endpoint.socketPath))
    }

    @Test
    fun `status json identifies holder and force revoke is visibly unsafe`() {
        val server =
            LocalCoordinatorServer(endpoint, revokeGrace = Duration.ZERO).also {
                it.start()
                resources += it
            }
        val client =
            LocalInputCoordinatorClient.connect(endpoint, resource, "LoginTest#submits").also {
                resources += it
            }
        val lease = client.acquire(Duration.ofSeconds(1), "typeText").also { resources += it }
        val statusOutput = StringBuilder()

        InputLockCommand(statusOutput, endpoint, resource).parse(listOf("status", "--json"))

        val status = Json.parseToJsonElement(statusOutput.toString()).jsonObject
        assertEquals(
            lease.token.leaseId,
            status.getValue("holder").jsonObject.getValue("leaseId").jsonPrimitive.content,
        )
        val revokeOutput = StringBuilder()
        InputLockCommand(revokeOutput, endpoint, resource)
            .parse(
                listOf(
                    "revoke",
                    "--lease",
                    lease.token.leaseId,
                    "--reason",
                    "test JVM is unresponsive",
                    "--force",
                )
            )
        assertTrue(revokeOutput.toString().contains("unsafeTakeover=true"))
        assertFalse(lease.isValid() && runCatching { lease.checkpoint() }.isSuccess)

        server.close()
        server.awaitTermination()
    }

    @Test
    fun `stale revoke reports a stable CLI error without a stack trace`() {
        LocalCoordinatorServer(endpoint).also {
            it.start()
            resources += it
        }
        val output = StringBuilder()

        val failure =
            kotlin.test.assertFailsWith<ProgramResult> {
                InputLockCommand(output, endpoint, resource)
                    .parse(
                        listOf(
                            "revoke",
                            "--lease",
                            "stale-id",
                            "--reason",
                            "operator inspected the holder",
                        )
                    )
            }

        assertEquals(1, failure.statusCode)
        assertTrue(output.contains("STALE_LEASE"))
        assertFalse(output.contains("Exception"))
    }

    @Test
    fun `revoke endpoint IO failure reports a stable CLI error`() {
        val output = StringBuilder()

        val failure =
            kotlin.test.assertFailsWith<ProgramResult> {
                InputLockCommand(
                        output,
                        endpoint = { throw IOException("socket path is unavailable") },
                        resourceKey = { resource },
                    )
                    .parse(
                        listOf(
                            "revoke",
                            "--lease",
                            "observed-id",
                            "--reason",
                            "operator inspected the holder",
                        )
                    )
            }

        assertEquals(1, failure.statusCode)
        assertTrue(output.contains("Input lock I/O error: socket path is unavailable"))
        assertFalse(output.contains("Exception"))
    }
}
