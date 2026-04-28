package dev.sebastiano.spectre.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import org.jetbrains.jewel.bridge.addComposeTab

/**
 * Wires the "Spectre Sample" tool window content as a Jewel-hosted Compose tab.
 *
 * Jewel's `ToolWindow.addComposeTab` is the highest-level integration point — it transparently sets
 * up a `JewelComposePanel`, applies `SwingBridgeTheme` (so the IDE colours / fonts flow into the
 * Compose tree), and registers the resulting `ComponentContent` with the tool window. This is the
 * surface Spectre's `WindowTracker` must walk into when it discovers an IDE-hosted `ComposePanel` —
 * see #13.
 */
class SpectreSampleToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.addComposeTab(tabDisplayName = "Spectre") { SpectreSampleToolWindowContent() }
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
