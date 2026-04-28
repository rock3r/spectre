package dev.sebastiano.spectre.intellij

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Smoke test for the Spectre sample plugin descriptor.
 *
 * `BasePlatformTestCase` boots a headless IntelliJ Platform with the plugin loaded, so the
 * extensions registered in `META-INF/plugin.xml` actually go through the platform's parsing /
 * registration paths. The assertion below is deliberately narrow — we don't try to render the tool
 * window's Compose content (`BasePlatformTestCase` runs without a display) — but proving the
 * descriptor is well-formed and the action class resolves catches the most common regressions: a
 * typo'd FQN in `plugin.xml`, a missing dependency in the plugin module, etc.
 *
 * The `<toolWindow>` extension is structurally validated by `verifyPluginStructure`; we don't
 * resolve a `ToolWindowManager` here because `BasePlatformTestCase`'s project setup runs the
 * indexing pipeline first and that hangs in a CI-style environment. The full UI-driven validation
 * happens manually via `./gradlew :sample-intellij-plugin:runIde` and the in-IDE "Tools → Run
 * Spectre Against the Sample Tool Window" action; an automated remoteRobot harness is the deferred
 * follow-up tracked alongside this PR.
 */
class SpectreSamplePluginTest : BasePlatformTestCase() {

    fun testRunSpectreActionRegistered() {
        val action =
            ActionManager.getInstance().getAction("dev.sebastiano.spectre.sample.RunSpectre")
        assertNotNull(
            "RunSpectreAction should be registered from plugin.xml under its declared id",
            action,
        )
    }
}
