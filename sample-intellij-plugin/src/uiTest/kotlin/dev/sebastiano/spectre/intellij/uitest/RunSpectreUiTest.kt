package dev.sebastiano.spectre.intellij.uitest

import com.intellij.driver.sdk.invokeGlobalBackendAction
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.waitForProjectOpen
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.junit5.hyphenateWithClass
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.CurrentTestMethod
import com.intellij.ide.starter.runner.IDERunContext
import com.intellij.ide.starter.runner.Starter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * IDE-hosted validation for #42: boots a real IntelliJ IDEA via `intellij-ide-starter`, installs
 * the locally-built `:sample-intellij-plugin` zip, invokes `RunSpectreAction`, and asserts the
 * action's `[Spectre]` log lines mention every tagged node from `SpectreSampleToolWindowContent`.
 * As of the 253.x (2026.1.x) line, JetBrains stopped shipping a distinct IntelliJ Community Edition
 * — IDEA Ultimate is now the unified product under a freemium licence, so this test naturally
 * targets IU.
 *
 * Counterpart to the manual `./gradlew :sample-intellij-plugin:runIde` smoke that landed with #43 —
 * same assertions, no human in the loop. CI runs this only when the plugin or recording sources
 * change (path filter in `.github/workflows/ide-uitest.yml`); locally it's `./gradlew
 * :sample-intellij-plugin:uiTest`.
 *
 * The bridge between the test JVM and the IDE process is the IDE's own `idea.log`. The test JVM has
 * direct disk access to the sandbox system dir, so after the action's `thisLogger().info("[Spectre]
 * ...")` calls flush we can grep the file for the expected node tags. No remote-robot HTTP server,
 * no XPath UI driving — just the action's declarative log surface.
 */
class RunSpectreUiTest {

    // Camel-case method name (rather than the Spectre convention of backtick prose names) keeps
    // detekt's `FunctionNaming` rule happy: its built-in excludes cover `**/test/**` etc. but
    // `**/uiTest/**` isn't on that list, and we don't want to widen the project-wide config just
    // for one method. The `@DisplayName` annotation surfaces the readable name in test reports.
    @Test
    @DisplayName("RunSpectreAction discovers every tagged node from the sample tool window")
    fun runSpectreActionDiscoversEveryTaggedNodeFromTheSampleToolWindow() {
        val pluginPath = pluginZipPath()
        val tempProject = createEmptyProject()

        val testContext =
            Starter.newContext(
                    CurrentTestMethod.hyphenateWithClass(),
                    // The plugin compiles against IntelliJ IDEA 2026.1.1 (build
                    // 261.23567.138 = IU). As of the 253.x line JetBrains stopped shipping a
                    // distinct Community Edition — IU is the unified IDEA product under a
                    // freemium licence — so `IdeProductProvider.IU` is the only thing
                    // ide-starter has for 2026.1.x and this test targets it directly.
                    //
                    // We do NOT call `setLicense(...)` — invokeAction-only flows against
                    // an empty project don't need a paid licence. If the IDE later refuses
                    // to start because of licence validation, that's the signal to wire
                    // `LICENSE_KEY` from a CI secret.
                    TestCase(
                        IdeProductProvider.IU.copy(
                            buildType = "release",
                            buildNumber = IDE_BUILD_NUMBER,
                        ),
                        LocalProjectInfo(tempProject),
                    ),
                )
                .apply { PluginConfigurator(this).installPluginFromPath(pluginPath) }
                .applyVMOptionsPatch {
                    // Disable the JetBrains Daemon (`jetbrainsd.exe`) discovery + URI
                    // handling on IDE startup. The daemon is a host-side helper for Toolbox
                    // sync / AI Assistant integration / `jetbrains://` URI handlers — none
                    // of which our automation test needs. On the GitHub-hosted Windows
                    // runner the daemon's de-elevation step also fails repeatedly because
                    // `runneradmin` is elevated, which used to add ~50s of retry noise to
                    // project open. Both keys are documented registry keys in the bundled
                    // `JetBrains OS Integration` plugin (`com.intellij.platform.daemon`).
                    addSystemProperty("jetbrainsd.discovery.enabled", false)
                    addSystemProperty("jetbrainsd.uri.handling.enabled", false)
                }
                // Skip stub-index initialization on project open. With the daemon disabled
                // the dominant remaining cost on a cold Windows runner is ~3 min of stub /
                // file index initialization. Our action reads the running Compose tool
                // window's semantics tree via Spectre's in-process automator and dumps tags
                // to `idea.log` — it doesn't touch the indices, so skipping them shaves
                // most of the project-open time without affecting the assertions.
                .skipIndicesInitialization()

        // ide-starter writes the IDE's `idea.log` to `IDERunContext.logsDir`, which is
        // `<testHome>/<launchName>/log` — a SIBLING of `<testHome>/system`, not a child of it.
        // (Earlier iterations resolved this from `paths.systemDir.resolve("log")` and ended up
        // pointing at an empty `<testHome>/system/log/` that the IDE never writes to — confirmed
        // by inspecting the JVM options inside the actual log: `-Didea.log.path=<launchDir>/log`
        // and `-Didea.system.path=<launchDir>/system` are siblings.)
        //
        // The Driver-lambda receiver doesn't expose run-context paths, so we capture the
        // `IDERunContext` from `runIdeWithDriver`'s `configure` block (which fires before the
        // IDE process is spawned) and read its `logsDir` from inside the driver block.
        val capturedRunContext = AtomicReference<IDERunContext>()

        testContext
            .runIdeWithDriver(configure = { capturedRunContext.set(this) })
            .useDriverAndCloseIde {
                val ideLog =
                    requireNotNull(capturedRunContext.get()) {
                            "IDERunContext was never captured — runIdeWithDriver's configure " +
                                "block didn't fire before the driver block."
                        }
                        .logsDir
                        .resolve("idea.log")

                // Wait for the empty project we passed via `LocalProjectInfo` to finish
                // opening before firing the action. Without this the action races
                // `ProjectActivity` execution and `e.project` is unreliable.
                //
                // The default `waitForProjectOpen()` timeout (~60s) is enough on macOS but
                // not always on the GitHub-hosted Windows runner: `runneradmin` is elevated
                // there, which causes the IDE to repeatedly try (and fail) to spawn a
                // de-elevated daemon — the IDE log shows ~50s of mostly-silent retries
                // before the project finishes opening. Use a Windows-tolerant value across
                // all hosts (no harm on macOS, where it'll resolve in seconds anyway).
                waitForProjectOpen(timeout = PROJECT_OPEN_TIMEOUT)

                // Wait for indexing / Resolving / Analyzing-project indicators to settle.
                // First-open of an empty project still triggers a brief indexing burst that
                // dominates the EDT; the action's popup-discovery poll is bounded at ~3s and
                // will time out if Compose recomposition is starved by indexing during the
                // toggle → popup transition. (We saw exactly this fail mode locally: initial
                // counter / toggle nodes appeared, but `ide.popup.body` never did within
                // budget.) Waiting for indicators here moves the action invocation to a
                // quiescent IDE.
                waitForIndicators(timeout = INDICATOR_QUIESCENCE_TIMEOUT)

                // `invokeGlobalBackendAction(...)` plumbs `singleProject()` through to the
                // action's data context, so `RunSpectreAction.actionPerformed` sees a
                // non-null `e.project`. The simpler `invokeAction(...)` route does NOT
                // populate the project key (no focused IDE frame in driver-only flows), so
                // the action would early-return on its `e.project ?: return` guard — which
                // is the right behaviour for the interactive Tools-menu path but useless to
                // us here.
                //
                // `now = false`: the action self-defers to a pooled background thread for
                // the polling loop (PR #43 fix), so blocking the EDT here would deadlock
                // the very recomposition the action waits for.
                invokeGlobalBackendAction(SPECTRE_ACTION_ID, singleProject(), now = false)

                // Wait for the action's `[Spectre] (with popup)` lines to appear. Bounded so a
                // regression doesn't hang CI; covers the action's full poll budget (~3s) plus a
                // generous margin for IDE first-paint + ToolWindow activation.
                val captured = waitForSpectreLog(ideLog, deadlineMs = LOG_POLL_DEADLINE_MS)

                assertTrue(captured.isNotEmpty()) {
                    "Expected `[Spectre]` log lines in $ideLog within ${LOG_POLL_DEADLINE_MS}ms; " +
                        "RunSpectreAction either didn't fire or didn't reach its dump phase."
                }
                EXPECTED_TAGS.forEach { tag ->
                    assertTrue(captured.any { it.contains(tag) }) {
                        "Expected tagged node `$tag` in [Spectre] log lines but didn't find it. " +
                            "Captured (${captured.size} lines):\n" +
                            captured.joinToString("\n")
                    }
                }
            }
    }

    /** Reads the path the Gradle `uiTest` task wires via `-Dpath.to.build.plugin`. */
    private fun pluginZipPath(): Path {
        val raw =
            requireNotNull(System.getProperty("path.to.build.plugin")) {
                "System property `path.to.build.plugin` is not set — run via " +
                    "`./gradlew :sample-intellij-plugin:uiTest` so the Gradle task points at " +
                    "the locally-built plugin zip."
            }
        val path = Path.of(raw)
        require(path.exists()) {
            "Plugin zip $path does not exist. Run `:sample-intellij-plugin:buildPlugin` first."
        }
        return path
    }

    /**
     * Creates a throwaway project directory under the JVM's tmpdir. ide-starter `LocalProjectInfo`
     * requires a project root to open; the IDE doesn't care that there's no `.idea/` — it'll create
     * one. We just need a stable on-disk anchor so the IDE has SOMETHING to open (avoids the
     * welcome screen, which doesn't host our tool window).
     */
    private fun createEmptyProject(): Path {
        val base = Path.of(System.getProperty("java.io.tmpdir"), "spectre-uitest-project")
        base.createDirectories()
        return Files.createTempDirectory(base, "project-").also { dir ->
            dir.toFile().deleteOnExit()
        }
    }

    /**
     * Polls [logPath] until at least one `[Spectre] (with popup)` line appears (proves the action
     * ran the popup-discovery path) or [deadlineMs] elapses. Returns the matching lines (or empty
     * if the deadline was hit). Uses small file reads so the IDE's still-open log handle isn't a
     * problem on Windows.
     */
    private fun waitForSpectreLog(logPath: Path, deadlineMs: Long): List<String> {
        val deadline = System.currentTimeMillis() + deadlineMs
        var captured: List<String> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            captured =
                if (logPath.exists()) {
                    logPath.readText().lineSequence().filter { it.contains("[Spectre]") }.toList()
                } else {
                    emptyList()
                }
            if (captured.any { it.contains("[Spectre] (with popup)") }) return captured
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return captured
    }

    private companion object {
        // IntelliJ IDEA 2026.1.1 build number (resolves to IU here — see comment on the
        // `IdeProductProvider.IU` use above). Must stay in lockstep with `intellijIdea` in
        // `gradle/libs.versions.toml` — bumping that version means updating this constant to
        // the matching build (find it under
        // https://www.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/idea/).
        const val IDE_BUILD_NUMBER = "261.23567.138"
        const val SPECTRE_ACTION_ID = "dev.sebastiano.spectre.sample.RunSpectre"
        // Matches every test-tagged node the manual smoke proved discoverable in PR #43.
        // Keep this list aligned with `SpectreSampleToolWindowContent`'s `Modifier.testTag`
        // values — drifting one without the other means the test silently passes against an
        // outdated tool window.
        val EXPECTED_TAGS =
            listOf(
                "ide.counter.button",
                "ide.counter.text",
                "ide.popup.toggleButton",
                "ide.popup.body",
                "ide.popup.text",
                "ide.popup.dismissButton",
            )
        const val LOG_POLL_DEADLINE_MS: Long = 30_000
        const val POLL_INTERVAL_MS: Long = 250

        // First-open of an empty project triggers an indexing burst (sass.scss /
        // WorkspaceFileIndex iterators / JDK cataloging). On a local cached run this takes
        // 10–20s; on the GH macOS runner with a fresh ide-starter cache it has come in over
        // 90s (the whole `:uiTest` job clocks ~8 min cold), so 60s was below the runner's
        // p99 and timed out in CI even though it was fine locally. 3 min gives CI enough
        // headroom while still bounding the wait so a stuck indexer doesn't hang the job.
        val INDICATOR_QUIESCENCE_TIMEOUT = 3.minutes

        // ide-starter's `waitForProjectOpen` defaults to ~60s. That's plenty on macOS but
        // not enough on the GitHub-hosted Windows runner. The original 3-minute bump
        // wasn't enough either — index initialization alone took just over 3 min on a cold
        // cache. Combined with the daemon-disable + skipIndicesInitialization() above,
        // 5 min is comfortably above the observed worst-case open time without bounding
        // a hung indexer too loosely.
        val PROJECT_OPEN_TIMEOUT = 5.minutes
    }
}
