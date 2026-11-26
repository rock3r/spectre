package dev.sebastiano.spectre.core

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertFalse
import org.junit.jupiter.api.Assumptions.assumeFalse

class AutomatorNodeVisibilityTest {
    @Test
    fun `real semantics nodes report both supported hidden properties`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "ComposeWindow requires a display")
        lateinit var window: ComposeWindow
        SwingUtilities.invokeAndWait { window = ComposeWindow() }
        val automator = ComposeAutomator.inProcess()
        try {
            SwingUtilities.invokeAndWait {
                window.setContent {
                    Box(
                        Modifier.semantics {
                            testTag = "hidden-current"
                            hideFromAccessibility()
                        }
                    )
                    @Suppress("DEPRECATION")
                    Box(
                        Modifier.semantics {
                            testTag = "hidden-legacy"
                            invisibleToUser()
                        }
                    )
                }
                window.setSize(200, 100)
                window.isVisible = true
            }
            val deadline = System.nanoTime() + 5_000_000_000
            var nodes = emptyList<AutomatorNode>()
            while (System.nanoTime() < deadline && nodes.size < 2) {
                automator.refreshWindows()
                nodes = automator.allNodes().filter { it.testTag?.startsWith("hidden-") == true }
                Thread.sleep(10)
            }
            assertFalse(nodes.single { it.testTag == "hidden-current" }.isVisible)
            assertFalse(nodes.single { it.testTag == "hidden-legacy" }.isVisible)
        } finally {
            SwingUtilities.invokeAndWait { window.dispose() }
        }
    }
}
