package dev.sebastiano.spectre.cli.mcp

import dev.sebastiano.spectre.cli.daemon.DaemonErrorCode
import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SpectreMcpServerInputValidationTest {
    @Test
    fun `MCP rejects wrong primitive kinds and unknown names before forwarding`() = runBlocking {
        val cases =
            listOf(
                "find_text" to
                    buildJsonObject {
                        put("session_id", "session-1")
                        put("text", "needle")
                        put("exact", "false")
                    },
                "find" to
                    buildJsonObject {
                        put("session_id", "session-1")
                        put("test_tag", 7)
                    },
                "windows" to
                    buildJsonObject {
                        put("session_id", "session-1")
                        put("unknown", true)
                    },
            )
        cases.forEach { (tool, arguments) ->
            var forwarded = false
            val server = SpectreMcpServer.create {
                forwarded = true
                error("invalid input reached daemon")
            }
            val result = invokeTool(server, tool, arguments)
            assertTrue(result.isError == true, "$tool accepted $arguments")
            assertTrue(!forwarded, "$tool forwarded $arguments")
        }
    }

    @Test
    fun `detach tool fails closed for unknown session`() = runBlocking {
        val server = SpectreMcpServer.create { request ->
            when (request) {
                is DaemonRequest.Detach ->
                    DaemonResponse.Error(
                        code = DaemonErrorCode.SessionNotFound,
                        message = "session not found: ${request.sessionId}",
                    )
                else -> error("unexpected daemon request: $request")
            }
        }

        val result =
            invokeTool(server, "detach", buildJsonObject { put("session_id", "missing-session") })

        assertTrue(result.isError == true, "unknown session must set isError")
        assertEquals(
            "session not found: missing-session",
            (result.content.single() as TextContent).text,
        )
    }

    @Test
    fun `detach tool requires session_id`() = runBlocking {
        val server = SpectreMcpServer.create {
            error("detach must not reach daemon without session_id")
        }

        val result = invokeTool(server, "detach", buildJsonObject {})

        assertTrue(result.isError == true, "missing session_id must fail closed: $result")
        val message = (result.content.single() as TextContent).text
        assertTrue(
            message.contains("session_id", ignoreCase = true),
            "error should mention session_id, was: $message",
        )
    }

    @Test
    fun `detach tool fails closed for empty session_id without reaching daemon`() = runBlocking {
        var daemonReached = false
        val server = SpectreMcpServer.create {
            daemonReached = true
            error("detach must not reach daemon with empty session_id")
        }

        val result = invokeTool(server, "detach", buildJsonObject { put("session_id", "") })

        assertTrue(result.isError == true, "empty session_id must fail closed: $result")
        assertTrue(!daemonReached, "empty session_id must fail at the tool boundary: $result")
        val message = (result.content.single() as TextContent).text
        assertTrue(
            message.contains("session_id", ignoreCase = true),
            "error should mention session_id, was: $message",
        )
    }

    @Test
    fun `detach tool fails closed for whitespace-only session_id without reaching daemon`() =
        runBlocking {
            var daemonReached = false
            val server = SpectreMcpServer.create {
                daemonReached = true
                error("detach must not reach daemon with blank session_id")
            }

            val result =
                invokeTool(server, "detach", buildJsonObject { put("session_id", "   \t") })

            assertTrue(result.isError == true, "blank session_id must fail closed: $result")
            assertTrue(!daemonReached, "blank session_id must fail at the tool boundary: $result")
            val message = (result.content.single() as TextContent).text
            assertTrue(
                message.contains("session_id", ignoreCase = true),
                "error should mention session_id, was: $message",
            )
        }

    @Test
    fun `detach tool fails closed for non-string session_id without reaching daemon`() =
        runBlocking {
            var daemonReached = false
            val server = SpectreMcpServer.create {
                daemonReached = true
                error("detach must not reach daemon with non-string session_id")
            }

            val result = invokeTool(server, "detach", buildJsonObject { put("session_id", 42) })

            assertTrue(result.isError == true, "numeric session_id must fail closed: $result")
            assertTrue(
                !daemonReached,
                "non-string session_id must fail at the tool boundary: $result",
            )
            val message = (result.content.single() as TextContent).text
            assertTrue(
                message.contains("session_id", ignoreCase = true),
                "error should mention session_id, was: $message",
            )
        }

    @Test
    fun `detach tool fails closed for object session_id without reaching daemon`() = runBlocking {
        var daemonReached = false
        val server = SpectreMcpServer.create {
            daemonReached = true
            error("detach must not reach daemon with object session_id")
        }

        val result =
            invokeTool(
                server,
                "detach",
                buildJsonObject { put("session_id", buildJsonObject { put("nested", "value") }) },
            )

        assertTrue(result.isError == true, "object session_id must fail closed: $result")
        assertTrue(!daemonReached, "object session_id must fail at the tool boundary: $result")
        val message = (result.content.single() as TextContent).text
        assertTrue(
            message.contains("session_id", ignoreCase = true) ||
                message.contains("string", ignoreCase = true) ||
                message.contains("Error executing tool", ignoreCase = true),
            "error should identify bad session_id input, was: $message",
        )
    }
}
