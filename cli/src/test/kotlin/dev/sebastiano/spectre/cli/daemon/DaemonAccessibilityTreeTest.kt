package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.transport.RectDto
import dev.sebastiano.spectre.agent.transport.WindowSummaryDto
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DaemonAccessibilityTreeTest {
    @Test
    fun `non JVM pid is sent directly to packaged helper`() {
        val fixture = fixture()
        val response = fixture.registry.handle(DaemonRequest.AccessibilityTree(processId = 8123))
        assertIs<DaemonResponse.AccessibilitySnapshotResult>(response)
        assertTrue(fixture.input.contains("\"type\":\"process-id\""))
        assertTrue(fixture.input.contains("\"value\":8123"))
    }

    @Test
    fun `title lookup is delegated as an exact unique native target`() {
        val fixture = fixture()
        fixture.registry.handle(DaemonRequest.AccessibilityTree(windowTitle = "Exact title"))
        assertTrue(fixture.input.contains("\"type\":\"window-title\""))
        assertTrue(fixture.input.contains("\"value\":\"Exact title\""))
    }

    @Test
    fun `attached session selects requested window and scopes title to owner pid`() {
        val fixture = fixture(windows = listOf(window(0, "First"), window(1, "Second")))
        assertIs<DaemonResponse.Attached>(fixture.registry.handle(DaemonRequest.Attach(44)))
        fixture.registry.handle(
            DaemonRequest.AccessibilityTree(sessionId = "pid-44", windowIndex = 1)
        )
        assertTrue(fixture.input.contains("\"value\":\"Second\""))
        assertTrue(fixture.input.contains("\"processId\":44"))
    }

    @Test
    fun `native ambiguity failure remains typed and never becomes success`() {
        val fixture =
            fixture(
                output =
                    """{"ok":false,"error":{"code":"target-not-found","message":"title matched multiple windows"}}"""
            )
        val response =
            fixture.registry.handle(DaemonRequest.AccessibilityTree(windowTitle = "Duplicate"))
        val failure = assertIs<DaemonResponse.AccessibilityErrorResult>(response)
        assertEquals(AccessibilityErrorCode.TargetNotFound, failure.error.code)
    }

    private fun fixture(
        output: String =
            """{"ok":true,"snapshot":{"schemaVersion":2,"source":"os-accessibility",
                "capturedAt":"2026-11-25T12:34:56Z","windows":[],"nodes":[],"notes":[]}}"""
                .replace("\n", ""),
        windows: List<WindowSummaryDto> = emptyList(),
    ): Fixture {
        var input = ""
        val helper =
            AccessibilityHelperClient(
                helperExecutable = { Path.of("fake-helper") },
                runner =
                    AccessibilityHelperRunner { _, stdin, _ ->
                        input = stdin
                        output
                    },
            )
        val registry =
            DaemonSessionRegistry(
                hotReloadSessionFactory = { null },
                accessibilityHelperClient = helper,
                attachAutomator = { TestDaemonSessionAutomator(windowsResult = { windows }) },
            )
        return Fixture(registry, { input })
    }

    private fun window(index: Int, title: String) =
        WindowSummaryDto(index, "surface-$index", title, false, RectDto(0, 0, 10, 10))

    private class Fixture(
        val registry: DaemonSessionRegistry,
        private val readInput: () -> String,
    ) {
        val input: String
            get() = readInput()
    }
}
