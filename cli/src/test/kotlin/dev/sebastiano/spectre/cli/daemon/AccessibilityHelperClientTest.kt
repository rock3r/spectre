package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AccessibilityHelperClientTest {
    @Test
    fun `decodes a recorded successful helper response without a desktop`() {
        val response = AccessibilityHelperClient { _, _, _ ->
            javaClass.getResource("/accessibility/success.json")!!.readText()
        }
            .snapshot(
                AccessibilitySnapshotRequest(Target.ProcessId(42), 20, 2_000, 750),
                java.nio.file.Path.of("recorded-helper"),
            )

        val success = assertIs<AccessibilitySnapshotResponse.Success>(response)
        assertEquals("w0/app/0", success.snapshot.nodes.single().key)
        assertEquals(BoundsSpace.Screen, success.snapshot.windows.single().boundsSpace)
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
        assertEquals(true, stdin.contains("\"maximumNodeCount\":5"))
    }
}
