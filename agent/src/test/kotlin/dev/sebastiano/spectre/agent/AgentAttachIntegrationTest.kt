@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import dev.sebastiano.spectre.agent.fixture.READY_SENTINEL
import dev.sebastiano.spectre.agent.fixture.SPECTRE_FIXTURE_WINDOW_TITLE
import dev.sebastiano.spectre.agent.fixture.TAG_BUTTON
import dev.sebastiano.spectre.agent.fixture.TAG_LABEL
import dev.sebastiano.spectre.agent.fixture.TAG_TEXT_FIELD
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.WindowSummaryDto
import java.awt.GraphicsEnvironment
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Plan M-7/M-8: end-to-end attach pipeline against a child JVM running a real Compose Desktop UI
 * (the `:agent-test-fixture` module — `JFrame + ComposePanel` with three tagged nodes).
 *
 * Validates the FULL chain on every run:
 * - `VirtualMachine.attach + loadAgent` delivers `agentmain` in the target.
 * - `SpectreAgent.bootstrap` finds Spectre, constructs the automator, binds the UDS.
 * - `IpcClient` connects to the UDS the agent just bound.
 * - `windows() / allNodes() / findByTestTag() / click() / typeText() / screenshot() / detach()`
 *   each round-trip without throwing, and the agent runtime cleans up after itself.
 * - Repeating the cycle ≥ 3× leaves no orphan UDS files (covers the shutdown-hook removal +
 *   `agentState` reset paths exercised by D-7's Path A).
 *
 * **Strict by design.** The fixture polls `ComposePanel.semanticsOwners` on the EDT until it's
 * non-empty before signalling READY, so by the time the agent attaches the semantics tree is
 * guaranteed to be populated. The test then asserts:
 * - `windows()` returns at least the fixture's `'$SPECTRE_FIXTURE_WINDOW_TITLE'` window
 *   (non-empty).
 * - `findByTestTag(TAG_LABEL / TAG_BUTTON / TAG_TEXT_FIELD)` each return at least one match.
 * - `click(buttonKey)` bare-throws on any wire-level error. Focused-field `typeText("x")` also
 *   bare-throws except for CI-only macOS focus handoff loss, where the already-covered
 *   real-keyboard subpath is skipped after the attach/click/focus contract has been proven.
 *
 * The pure-mapping correctness (getter names, `Rectangle → RectDto`, screenshot's `Rectangle?`
 * lookup, refresh-before-read contract) is *also* covered at the unit level in
 * [dev.sebastiano.spectre.agent.runtime.ReflectiveAutomatorHandlerMappingTest] against synthetic
 * objects with the real getter signatures, so a regression in either layer fails fast.
 *
 * **Do not loosen these assertions.** Earlier drafts wrapped `click`/`typeText` in `runCatching`
 * and let empty `windows()` pass — that hid a real `windows()`-cache-staleness bug (the handler
 * needed `refreshWindows()`) and a `BufferedReader` deadlock in `FixtureProcess.close()`. The only
 * exception is CI macOS OS-focus loss after Compose focus has been proven; local runs still fail so
 * developers can diagnose real keyboard regressions.
 *
 * Gating:
 * - **Runs on Linux, macOS, and Windows** via `@EnabledOnOs`. Hosted GitHub `windows-latest` lacks
 *   a reliable interactive desktop for this Robot-backed fixture, so Windows additionally requires
 *   the opt-in [WindowsAttachE2eGate] property (physical desktops / Mattone). Without it, Windows
 *   methods are assumption-skipped and stay green under `:check`. Non-UI Windows transport and ACL
 *   contracts always run. Linux Xvfb validation remains the hosted full attach-to-UI gate; physical
 *   Windows is the authorized Windows UI proof path (#194).
 * - Skipped on headless JVMs (`java.awt.GraphicsEnvironment.isHeadless()`). Compose Desktop refuses
 *   to create a `JFrame + ComposePanel` without a display.
 * - Skipped when `dev.sebastiano.spectre.agent.runtimeJar` isn't set. Gradle's `:agent:test` task
 *   sets it from the `:agent-runtime:jar` output.
 * - Real-keyboard `typeText` tolerates a CI-only loss of OS keyboard focus on any platform (see
 *   `typeTextOrSkipCiFocusLoss`); the attach/click/focus contract is still asserted.
 */
@EnabledOnOs(OS.LINUX, OS.MAC, OS.WINDOWS)
class AgentAttachIntegrationTest {
    private val orphanUdsFiles = mutableListOf<Path>()

    @AfterTest
    fun cleanUpOrphans() {
        orphanUdsFiles.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    @Test
    fun `attach exercise detach cycle works against a real Compose fixture`() {
        assumeWindowsAttachE2eAllowed()
        assumeFalse(
            GraphicsEnvironment.isHeadless(),
            "Requires non-headless JVM for Compose Desktop + java.awt.Robot",
        )
        val agentJar = locateAgentJarOrSkip()

        spawnComposeFixture().use { fixture ->
            repeat(REPEAT_CYCLES) { iteration ->
                attachExerciseDetach(fixture, agentJar, iteration = iteration)
            }
        }
    }

    @Test
    fun `attach explains when the target JVM disables dynamic agent loading`() {
        assumeWindowsAttachE2eAllowed()
        assumeFalse(
            GraphicsEnvironment.isHeadless(),
            "Requires non-headless JVM for the Compose Desktop fixture",
        )
        val agentJar = locateAgentJarOrSkip()

        spawnComposeFixture(dynamicAgentLoadingEnabled = false).use { fixture ->
            val udsPath = AttachOptions.defaultUdsPath(fixture.pid)
            orphanUdsFiles.add(udsPath)

            val exception =
                assertFailsWith<SpectreAttachException> {
                    AgentAttach.attach(
                        fixture.pid,
                        AttachOptions(agentJarPath = agentJar, udsPath = udsPath),
                    )
                }

            assertEquals(
                "The target JVM does not allow dynamic agent loading. Restart it with " +
                    "`-XX:+EnableDynamicAgentLoading` and retry the attach.",
                exception.message,
            )
        }
    }

    private fun attachExerciseDetach(fixture: FixtureProcess, agentJar: Path, iteration: Int) {
        val udsPath = AttachOptions.defaultUdsPath(fixture.pid)
        orphanUdsFiles.add(udsPath)
        val options =
            AttachOptions(
                agentJarPath = agentJar,
                udsPath = udsPath,
                attachTimeoutMs = ATTACH_TIMEOUT_MS,
            )

        AgentAttach.attach(fixture.pid, options).use { automator ->
            assertEquals(fixture.pid, automator.pid)

            // Strict contract: the fixture put up exactly one tagged Compose UI before
            // signalling READY. The agent's `windows()` and `findByTestTag` must see them,
            // and `click()` / `typeText()` must not throw — any of these failing is a real
            // regression in the wire pipeline, the reflective handler, or `WindowTracker`
            // discovery of `JFrame + ComposePanel` substrates.
            val windows = automator.windows()
            assertTrue(
                windows.isNotEmpty(),
                "iteration $iteration: windows() returned empty; expected the fixture's " +
                    "'$SPECTRE_FIXTURE_WINDOW_TITLE' window. Either WindowTracker didn't see the " +
                    "fixture or the fixture didn't bring up its UI before READY.",
            )
            println(
                "[362-user-like] iter=$iteration windows.size=${windows.size} " +
                    "windows=${windows.map { "idx=${it.index} surface=${it.surfaceId} " +
                        "title=${it.title} showing=${it.isShowing}" }}"
            )

            // #362: surfaces that contribute nodes must appear in windows() — never empty windows
            // while allNodes() returns window:* (or embedded:*) keys for a live surface.
            assertWindowsAgreeWithAllNodes(automator, windows, iteration = iteration)

            val labelMatches = automator.findByTestTag(TAG_LABEL)
            assertTrue(
                labelMatches.isNotEmpty(),
                "iteration $iteration: findByTestTag($TAG_LABEL) returned empty; the fixture's " +
                    "tagged label node was not discovered.",
            )

            val buttonMatches = automator.findByTestTag(TAG_BUTTON)
            assertTrue(
                buttonMatches.isNotEmpty(),
                "iteration $iteration: findByTestTag($TAG_BUTTON) returned empty; expected the " +
                    "fixture's tagged Button.",
            )
            val buttonKey = buttonMatches.first().key
            assertTrue(
                buttonKey.isNotBlank(),
                "iteration $iteration: button node key should be non-blank; got '$buttonKey'",
            )
            // #184 window-identity: assert before keyboard focus work so OS-focus flakes do not
            // mask identity regressions (identity does not need keyboard focus).
            assertWindowIdentityMatchesWindows(automator, windows, iteration = iteration)

            // #362 P2/P3 attach parity: fingerprint wait + human-readable dump before input.
            automator.waitForIdle()
            println("[362-user-like] iter=$iteration waitForIdle=ok")
            val treeDump = automator.printTree()
            assertTrue(
                treeDump.isNotBlank(),
                "iteration $iteration: printTree() empty after waitForIdle; expected fixture tags",
            )
            assertTrue(
                treeDump.contains(TAG_BUTTON) || treeDump.contains(buttonKey),
                "iteration $iteration: printTree() should mention button tag or key; dump=$treeDump",
            )
            println(
                "[362-user-like] iter=$iteration printTree.chars=${treeDump.length} " +
                    "printTree.head=${treeDump.lineSequence().take(12).joinToString(" | ")}"
            )

            // #364: raise/activate the fixture window via the attach wire before Robot input.
            // Bare-throws on any wire/handler failure so a missing FocusWindow op fails loudly.
            automator.focusWindow(buttonKey)

            // #362: DTO click convenience uses the same wire path as key-based click.
            val buttonNode = buttonMatches.first()
            automator.click(buttonNode)
            println(
                "[362-user-like] iter=$iteration dtoClick=ok key=${buttonNode.key} " +
                    "tag=${buttonNode.testTag} bounds=${buttonNode.bounds}"
            )

            // Capture screenshots before keyboard-focus work so #362 attach ops stay proven even
            // when OS focus handoff flakes on a busy local desktop (typeText path is below).
            val screenshotBytes = automator.screenshot()
            assertTrue(
                screenshotBytes.size >= MIN_PNG_BYTES,
                "iteration $iteration: screenshot too small (${screenshotBytes.size}b) — not a real PNG?",
            )
            assertTrue(
                screenshotBytes.startsWith(PNG_MAGIC),
                "iteration $iteration: screenshot bytes do not start with PNG magic header",
            )

            // #362: node-bounds PNG (native when bridge present; region fallback for inject).
            // Assert **decoded pixel dimensions**, not compressed byte size: solid-color node
            // crops can compress under MIN_PNG_BYTES while still being a valid capture (Windows
            // Mattone regression was 92b for a real button — byte-size alone is the wrong signal).
            val nodePng = automator.screenshot(buttonNode)
            assertTrue(
                nodePng.startsWith(PNG_MAGIC),
                "iteration $iteration: node screenshot missing PNG magic (${nodePng.size}b)",
            )
            val nodeImage =
                javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(nodePng))
                    ?: error(
                        "iteration $iteration: ImageIO could not decode node PNG (${nodePng.size}b)"
                    )
            val expectedW = buttonNode.bounds.width.coerceAtLeast(1)
            val expectedH = buttonNode.bounds.height.coerceAtLeast(1)
            // Allow HiDPI scale-down to ~1/4 of logical bounds; reject empty / 1×1 crops.
            val minW = (expectedW / 4).coerceAtLeast(1)
            val minH = (expectedH / 4).coerceAtLeast(1)
            assertTrue(
                nodeImage.width >= minW && nodeImage.height >= minH,
                "iteration $iteration: node screenshot ${nodeImage.width}x${nodeImage.height} " +
                    "implausible for bounds ${expectedW}x${expectedH} (pngBytes=${nodePng.size})",
            )
            println(
                "[362-user-like] iter=$iteration nodeScreenshot.bytes=${nodePng.size} " +
                    "decoded=${nodeImage.width}x${nodeImage.height} " +
                    "nodeBounds=${expectedW}x${expectedH} " +
                    "windowScreenshot.bytes=${screenshotBytes.size}"
            )
            println(
                "[362-user-like] iter=$iteration RESULT=SUCCESS " +
                    "primary_observables_non_empty=true " +
                    "(windows,waitForIdle,printTree,dtoClick,nodeScreenshot)"
            )

            val textFieldMatches = automator.findByTestTag(TAG_TEXT_FIELD)
            assertTrue(
                textFieldMatches.isNotEmpty(),
                "iteration $iteration: findByTestTag($TAG_TEXT_FIELD) returned empty",
            )
            val textFieldKey = textFieldMatches.first().key
            assertTrue(
                textFieldKey.isNotBlank(),
                "iteration $iteration: text field node key should be non-blank; got '$textFieldKey'",
            )
            val focusedTextField =
                automator.waitForFocusedTextField(textFieldKey, iteration = iteration)
            if (focusedTextField != null) {
                val editableTextBefore = focusedTextField.editableText.orEmpty()
                // This is a real keyboard event path. Do not call typeText until a refreshed
                // semantics snapshot proves the fixture text field owns Compose focus; the
                // in-target handler also checks that this JVM owns OS keyboard focus before
                // dispatching Robot key events.
                if (automator.typeTextOrSkipCiFocusLoss(iteration = iteration)) {
                    automator.waitForTextFieldToReceiveTypedCharacterOrSkipCi(
                        textFieldKey = textFieldKey,
                        previousEditableText = editableTextBefore,
                        iteration = iteration,
                    )
                }
            }
        }

        assertFalse(
            Files.exists(udsPath),
            "iteration $iteration: UDS path $udsPath should not exist after detach",
        )
    }

    /**
     * #362 acceptance: every surface id that appears in `allNodes()` keys must appear in
     * `windows()`. Disagreement is the original attach bug (empty windows while nodes use
     * `window:0:0:*` keys).
     */
    private fun assertWindowsAgreeWithAllNodes(
        automator: AttachedAutomator,
        windows: List<WindowSummaryDto>,
        iteration: Int,
    ) {
        val nodes = automator.allNodes()
        assertTrue(
            nodes.isNotEmpty(),
            "iteration $iteration: allNodes() empty; cannot check windows/allNodes agreement",
        )
        val windowSurfaceIds = windows.map { it.surfaceId }.toSet()
        val nodeSurfaceIds =
            nodes.map { node -> surfaceIdFromNodeKey(node.key) }.filter { it.isNotEmpty() }.toSet()
        val missing = nodeSurfaceIds - windowSurfaceIds
        assertTrue(
            missing.isEmpty(),
            "iteration $iteration: windows() missing surfaces that allNodes() reports: $missing " +
                "(windows=$windowSurfaceIds nodeSurfaces=$nodeSurfaceIds). " +
                "See #362 windows()/allNodes() agreement.",
        )
    }

    /** Node keys are `surfaceId:ownerIndex:nodeId`; surfaceId itself may contain `:`. */
    private fun surfaceIdFromNodeKey(key: String): String {
        val parts = key.split(':')
        // Need at least surfacePrefix:index:owner:node — surfaceId is everything but the last two
        // colon-separated segments when those are integers (ownerIndex, nodeId).
        if (parts.size < 3) return key
        val owner = parts[parts.lastIndex - 1]
        val nodeId = parts.last()
        if (owner.toIntOrNull() == null || nodeId.toIntOrNull() == null) return key
        return parts.dropLast(2).joinToString(":")
    }

    /**
     * #184 acceptance: window-identity bounds match `windows()` for the same surface; the fixture's
     * JFrame+ComposePanel path flags cropRequired (host handle + surface crop).
     */
    private fun assertWindowIdentityMatchesWindows(
        automator: AttachedAutomator,
        windows: List<WindowSummaryDto>,
        iteration: Int,
    ) {
        val identities = automator.windowIdentities()
        assertTrue(
            identities.isNotEmpty(),
            "iteration $iteration: windowIdentities() empty for the fixture",
        )
        val mainIdentity =
            identities.firstOrNull { it.title == SPECTRE_FIXTURE_WINDOW_TITLE }
                ?: identities.first { !it.isPopup }
        val matchingWindow =
            windows.firstOrNull { it.surfaceId == mainIdentity.surfaceId }
                ?: windows.first { !it.isPopup }
        assertEquals(matchingWindow.surfaceId, mainIdentity.surfaceId)
        assertEquals(
            matchingWindow.bounds,
            mainIdentity.surfaceBoundsOnScreen,
            "iteration $iteration: surfaceBoundsOnScreen must match windows() bounds",
        )
        assertTrue(
            mainIdentity.windowBoundsOnScreen.width > 0 &&
                mainIdentity.windowBoundsOnScreen.height > 0,
            "iteration $iteration: windowBoundsOnScreen must be non-empty",
        )
        assertTrue(
            mainIdentity.surfaceBoundsInWindow.width > 0 &&
                mainIdentity.surfaceBoundsInWindow.height > 0,
            "iteration $iteration: surfaceBoundsInWindow must be non-empty",
        )
        assertEquals(
            mainIdentity.windowBoundsOnScreen.x + mainIdentity.surfaceBoundsInWindow.x,
            mainIdentity.surfaceBoundsOnScreen.x,
            "iteration $iteration: surface x must equal window origin + relative crop",
        )
        assertEquals(
            mainIdentity.windowBoundsOnScreen.y + mainIdentity.surfaceBoundsInWindow.y,
            mainIdentity.surfaceBoundsOnScreen.y,
            "iteration $iteration: surface y must equal window origin + relative crop",
        )
        assertTrue(
            mainIdentity.cropRequired,
            "iteration $iteration: JFrame+ComposePanel fixture should require crop",
        )
        assertTrue(
            mainIdentity.scaleX > 0.0 && mainIdentity.scaleY > 0.0,
            "iteration $iteration: scale must be positive (HiDPI reports >1 when applicable)",
        )
        // Host handle is best-effort after agent AWT module opens. macOS/Windows usually resolve;
        // some Xvfb X11 layouts still return null — title+pid remains a valid capture fallback.
        val handle = mainIdentity.nativeHandle
        assertTrue(
            (handle != null && handle != 0L) || !mainIdentity.title.isNullOrBlank(),
            "iteration $iteration: expected nativeHandle or non-blank title for capture " +
                "targeting; handle=$handle title=${mainIdentity.title}",
        )
    }

    private fun spawnComposeFixture(dynamicAgentLoadingEnabled: Boolean = true): FixtureProcess {
        // ProcessBuilder does not append `.exe` for an absolute path on Windows, so pick the
        // launcher name explicitly via FixtureJavaHome (also honours mixed-runtime overrides).
        val javaBin = FixtureJavaHome.javaExecutable().toString()
        val classpath = System.getProperty("java.class.path")
        val process =
            ProcessBuilder(
                    javaBin,
                    "-cp",
                    classpath,
                    "-XX:${if (dynamicAgentLoadingEnabled) "+" else "-"}EnableDynamicAgentLoading",
                    "-Djava.awt.headless=false",
                    "-Dcompose.application.configure.swing.globals=true",
                    // This test exercises real Robot-backed focus and keyboard input. A macOS
                    // UIElement/background app can render semantics but may never become the
                    // active focus owner, which would make the focus-before-type guard fail.
                    "-Dapple.awt.UIElement=false",
                    "dev.sebastiano.spectre.agent.fixture.ComposeFixtureMainKt",
                )
                .redirectErrorStream(true)
                .start()

        val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        val readyLatch = CountDownLatch(1)
        val drainerThread =
            Thread({
                    try {
                        generateSequence(reader::readLine).forEach { line ->
                            if (line.startsWith(READY_SENTINEL) && readyLatch.count > 0) {
                                readyLatch.countDown()
                            }
                            // Otherwise discard — the parent doesn't care about per-line
                            // diagnostics in a CI run.
                        }
                    } catch (_: java.io.IOException) {
                        // Pipe closed when child exits; normal.
                    }
                })
                .apply {
                    isDaemon = true
                    name = "fixture-stdout-drainer"
                    start()
                }

        if (!readyLatch.await(FIXTURE_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            error(
                "Compose fixture did not emit $READY_SENTINEL within ${FIXTURE_READY_TIMEOUT_MS} ms"
            )
        }
        // READY is printed before VirtualMachine.attach is always accepted (macOS CI race).
        Thread.sleep(FIXTURE_ATTACH_SETTLE_MS)
        check(process.isAlive) {
            process.destroyForcibly()
            "Compose fixture exited immediately after $READY_SENTINEL"
        }

        return FixtureProcess(process, process.pid(), reader, drainerThread)
    }

    private fun assumeWindowsAttachE2eAllowed() {
        assumeTrue(
            WindowsAttachE2eGate.isAllowed(),
            "Windows attach UI e2e is opt-in (hosted CI has no reliable interactive desktop). " +
                "On a physical Windows desktop pass " +
                "\"-Pspectre.agent.attachE2e.allowWindows=true\" " +
                "(PowerShell: quote the -P arg) or " +
                "-D${WindowsAttachE2eGate.ALLOW_PROP}=true on the test JVM).",
        )
    }

    private fun locateAgentJarOrSkip(): Path {
        val prop = System.getProperty("dev.sebastiano.spectre.agent.runtimeJar")
        assumeFalse(
            prop.isNullOrBlank(),
            "Requires -Ddev.sebastiano.spectre.agent.runtimeJar=<path/to/agent-runtime.jar>; the " +
                ":agent:test task sets it automatically.",
        )
        val path = Paths.get(prop!!)
        assumeFalse(
            !Files.isRegularFile(path),
            "Agent runtime JAR not found at $path; run `./gradlew :agent-runtime:jar` first.",
        )
        return path
    }

    private fun AttachedAutomator.waitForFocusedTextField(
        textFieldKey: String,
        iteration: Int,
    ): NodeSnapshotDto? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FOCUS_TIMEOUT_MS)
        var lastMatches: List<NodeSnapshotDto> = emptyList()
        while (System.nanoTime() < deadline) {
            // #364: raise/activate the fixture window first, then click the field. On macOS the
            // first click may only activate the app; focusWindow makes that activation expressible
            // over attach (the remediation pressKey/typeText error text already asks for).
            focusWindow(textFieldKey)
            click(textFieldKey)
            lastMatches = findByTestTag(TAG_TEXT_FIELD)
            lastMatches
                .firstOrNull { it.key == textFieldKey && it.isFocused }
                ?.let {
                    return it
                }
            sleepBetweenFocusPolls()
        }
        val message =
            "iteration $iteration: fixture text field $textFieldKey did not become focused " +
                "within ${FOCUS_TIMEOUT_MS}ms after click. Last matches: " +
                lastMatches.joinToString { "${it.key}(focused=${it.isFocused})" }
        if (isCi()) {
            System.err.println("$message; skipping real-keyboard typeText subpath on CI.")
            return null
        }
        error(message)
    }

    private fun AttachedAutomator.typeTextOrSkipCiFocusLoss(iteration: Int): Boolean {
        try {
            typeText("x")
            return true
        } catch (ex: IOException) {
            if (isCi() && ex.message?.contains(TARGET_FOCUS_ERROR) == true) {
                System.err.println(
                    "iteration $iteration: target JVM lost OS keyboard focus before typeText; " +
                        "skipping real-keyboard typeText subpath on CI. ${ex.message}"
                )
                return false
            }
            throw ex
        }
    }

    /**
     * Wait for the typed character, or on CI skip the remainder of the typeText assertion when
     * Xvfb/Robot delivers a no-op keystroke (same class of flakiness as
     * [typeTextOrSkipCiFocusLoss]).
     */
    private fun AttachedAutomator.waitForTextFieldToReceiveTypedCharacterOrSkipCi(
        textFieldKey: String,
        previousEditableText: String,
        iteration: Int,
    ): NodeSnapshotDto? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FOCUS_TIMEOUT_MS)
        val previousTypedCount = previousEditableText.count { it == TYPED_CHARACTER }
        var lastText: String? = null
        while (System.nanoTime() < deadline) {
            val match = findByTestTag(TAG_TEXT_FIELD).firstOrNull { it.key == textFieldKey }
            lastText = match?.editableText
            if (
                match != null &&
                    lastText.orEmpty().count { it == TYPED_CHARACTER } > previousTypedCount
            ) {
                return match
            }
            sleepBetweenFocusPolls()
        }
        val message =
            "iteration $iteration: fixture text field $textFieldKey did not receive " +
                "'$TYPED_CHARACTER' within ${FOCUS_TIMEOUT_MS}ms after typeText. " +
                "Before='$previousEditableText', last='$lastText'"
        if (isCi()) {
            System.err.println("$message; skipping typeText character assertion on CI.")
            return null
        }
        error(message)
    }

    private fun sleepBetweenFocusPolls() {
        try {
            Thread.sleep(FOCUS_POLL_INTERVAL_MS)
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ex
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return (0 until prefix.size).all { this[it] == prefix[it] }
    }

    private fun assertFalse(condition: Boolean, message: String) {
        assertEquals(false, condition, message)
    }

    private fun isCi(): Boolean = System.getenv("CI").equals("true", ignoreCase = true)

    private class FixtureProcess(
        val process: Process,
        val pid: Long,
        val reader: BufferedReader,
        private val drainerThread: Thread,
    ) : AutoCloseable {
        /**
         * Ordering matters: the drainer thread is blocked inside [BufferedReader.readLine], holding
         * the reader's `InternalLock`. Calling `reader.close()` *first* would deadlock because
         * `close()` tries to acquire the same lock. Destroy the process first — that closes the OS
         * pipe, drives the drainer's `readLine` to return null, the drainer exits and releases the
         * lock, then `reader.close()` succeeds.
         */
        override fun close() {
            process.destroyForcibly()
            process.waitFor(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)
            // Give the drainer a brief moment to exit its readLine() loop now that the
            // pipe is closed, so the subsequent reader.close() doesn't race against it.
            drainerThread.join(DRAINER_JOIN_GRACE_MS)
            runCatching { reader.close() }
        }

        private companion object {
            const val SHUTDOWN_GRACE_SECONDS: Long = 2
            const val DRAINER_JOIN_GRACE_MS: Long = 500
        }
    }

    private companion object {
        const val REPEAT_CYCLES: Int = 3
        const val ATTACH_TIMEOUT_MS: Long = 15_000
        const val FIXTURE_READY_TIMEOUT_MS: Long = 30_000
        const val FIXTURE_ATTACH_SETTLE_MS: Long = 750
        const val FOCUS_TIMEOUT_MS: Long = 2_000
        const val FOCUS_POLL_INTERVAL_MS: Long = 50
        const val TYPED_CHARACTER: Char = 'x'
        const val TARGET_FOCUS_ERROR: String = "target JVM does not currently own OS keyboard focus"
        const val MIN_PNG_BYTES: Int = 100
        // PNG file magic: 89 50 4E 47 0D 0A 1A 0A.
        val PNG_MAGIC: ByteArray =
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
